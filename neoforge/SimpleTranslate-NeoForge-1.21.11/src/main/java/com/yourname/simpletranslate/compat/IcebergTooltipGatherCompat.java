package com.yourname.simpletranslate.compat;

import com.mojang.datafixers.util.Either;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationController;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.neoforged.fml.ModList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Soft optional integration with Iceberg's public tooltip gather event.
 *
 * <p>Legendary Tooltips (and Iceberg itself) rebuild item tooltips through
 * {@code com.anthonyhilyard.iceberg.events.RenderTooltipEvents#GATHER} before
 * wrapping lines into {@code ClientTooltipComponent}s, bypassing the vanilla
 * GUI graphics tooltip path our hooks observe. Registering here keeps
 * translation on the same official pipeline LT uses for model padding / compact
 * rows, without inventing LT-private mixins.</p>
 *
 * <p>API surface verified against the Iceberg 1.21.11-neoforge-1.4.0.1 jar
 * (the exact NeoForge artifact for Minecraft 1.21.11, javap-checked): the
 * event class lives at
 * {@code com.anthonyhilyard.iceberg.events.client.RenderTooltipEvents}, its
 * {@code GATHER} is Iceberg's own public {@code com.anthonyhilyard.iceberg.events.Event}
 * with {@code register(T)}, {@code Gather#onGather} takes
 * (ItemStack, int, int, List, int, int), and {@code GatherResult} has a public
 * (InteractionResult, int, List) constructor. The actual translation runs
 * through {@link TooltipTranslationHelper#translateGatheredTooltipLines}, the
 * same semantic projection pipeline as the vanilla item tooltip frame.</p>
 */
public final class IcebergTooltipGatherCompat {
    private static final String ICEBERG_MOD_ID = "iceberg";
    private static final String EVENTS_CLASS =
            "com.anthonyhilyard.iceberg.events.client.RenderTooltipEvents";
    private static final String ICEBERG_EVENT_CLASS =
            "com.anthonyhilyard.iceberg.events.Event";
    private static final String GATHER_INTERFACE =
            "com.anthonyhilyard.iceberg.events.client.RenderTooltipEvents$Gather";
    private static final String GATHER_RESULT_CLASS =
            "com.anthonyhilyard.iceberg.events.client.RenderTooltipEvents$GatherResult";

    private static boolean registered;

    private IcebergTooltipGatherCompat() {
    }

    public static void registerIfPresent() {
        if (registered || !ModList.get().isLoaded(ICEBERG_MOD_ID)) {
            return;
        }
        try {
            Class<?> eventsClass = Class.forName(EVENTS_CLASS);
            Object gatherEvent = eventsClass.getField("GATHER").get(null);
            if (gatherEvent == null) {
                SimpleTranslateMod.getLogger().warn(
                        "Iceberg RenderTooltipEvents.GATHER is null; Legendary Tooltips gather compat skipped");
                return;
            }

            Class<?> gatherInterface = Class.forName(GATHER_INTERFACE);
            Object listener = Proxy.newProxyInstance(
                    gatherInterface.getClassLoader(),
                    new Class<?>[]{gatherInterface},
                    (proxy, method, args) -> dispatch(proxy, method, args));

            // Iceberg 1.3.x declares GATHER as its own public Event class with
            // a public register(T). Invoking the method as declared on that
            // public base class also works when the runtime implementation is
            // an anonymous or package-private subclass.
            Class<?> icebergEventClass = Class.forName(ICEBERG_EVENT_CLASS);
            if (!icebergEventClass.isInstance(gatherEvent)) {
                SimpleTranslateMod.getLogger().warn(
                        "Iceberg RenderTooltipEvents.GATHER is not an Iceberg Event; tooltip gather compat skipped");
                return;
            }
            Method register = icebergEventClass.getMethod("register", Object.class);
            register.invoke(gatherEvent, listener);
            registered = true;
            SimpleTranslateMod.getLogger().info(
                    "Registered Iceberg RenderTooltipEvents.GATHER tooltip translation compat");
        } catch (Throwable error) {
            SimpleTranslateMod.getLogger().warn(
                    "Failed to register Iceberg tooltip gather compat: {}",
                    error.toString());
        }
    }

    private static Object dispatch(Object proxy, Method method, Object[] args) throws Exception {
        String name = method.getName();
        if ("onGather".equals(name) && args != null && args.length >= 6) {
            return onGather(
                    args[0] instanceof ItemStack stack ? stack : ItemStack.EMPTY,
                    args[1] instanceof Integer sw ? sw : 0,
                    args[2] instanceof Integer sh ? sh : 0,
                    castElements(args[3]),
                    args[4] instanceof Integer maxWidth ? maxWidth : 0,
                    args[5] instanceof Integer index ? index : 0);
        }
        if ("equals".equals(name) && args != null && args.length == 1) {
            return proxy == args[0];
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(name)) {
            return "SimpleTranslateIcebergTooltipGatherCompat";
        }
        if (method.getReturnType() == void.class) {
            return null;
        }
        if (method.getReturnType() == boolean.class) {
            return false;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Either<FormattedText, TooltipComponent>> castElements(Object raw) {
        if (raw instanceof List<?> list) {
            return (List<Either<FormattedText, TooltipComponent>>) list;
        }
        return List.of();
    }

    private static Object onGather(
            ItemStack itemStack,
            int screenWidth,
            int screenHeight,
            List<Either<FormattedText, TooltipComponent>> tooltipElements,
            int maxWidth,
            int index) throws Exception {
        translateTooltipElements(tooltipElements);
        return newGatherResult(InteractionResult.PASS, maxWidth, tooltipElements);
    }

    private static void translateTooltipElements(
            List<Either<FormattedText, TooltipComponent>> tooltipElements) {
        if (tooltipElements == null || tooltipElements.isEmpty()) {
            return;
        }
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return;
        }
        if (TooltipTranslationController.isRenderingTranslated()) {
            return;
        }
        TooltipTranslationController.RenderContext context =
                TooltipTranslationController.resolveRenderContext();
        if (!enabledFor(context)) {
            return;
        }

        List<Component> sourceComponents = new ArrayList<>();
        for (Either<FormattedText, TooltipComponent> either : tooltipElements) {
            if (either == null || either.left().isEmpty()) {
                continue;
            }
            Component component = asComponent(either.left().get());
            if (component == null) {
                return;
            }
            if (TooltipTranslationHelper.isMarkedTranslatedTooltip(component)) {
                return;
            }
            sourceComponents.add(component);
        }
        if (sourceComponents.isEmpty() || !TooltipTranslationHelper.anyContainsEnglish(sourceComponents)) {
            return;
        }

        boolean requestAllowed = TooltipTranslationController.allowRequest(context, sourceComponents);
        List<Component> translated = TooltipTranslationHelper.translateGatheredTooltipLines(
                sourceComponents, context, requestAllowed);
        if (translated == null || translated == sourceComponents
                || translated.size() != sourceComponents.size()) {
            return;
        }

        int cursor = 0;
        for (int i = 0; i < tooltipElements.size() && cursor < translated.size(); i++) {
            Either<FormattedText, TooltipComponent> either = tooltipElements.get(i);
            if (either == null || either.left().isEmpty()) {
                continue;
            }
            Component line = translated.get(cursor++);
            Component source = sourceComponents.get(cursor - 1);
            if (line == null || (line == source && line.getString().equals(source.getString()))) {
                continue;
            }
            tooltipElements.set(i, Either.left(line));
        }
        TooltipTranslationHelper.markTranslatedTooltip(translated);
    }

    private static boolean enabledFor(TooltipTranslationController.RenderContext context) {
        return switch (context) {
            case ITEM -> ModConfig.TOOLTIP_ITEM_ENABLED.get()
                    && !HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_ITEM);
            case BOOK -> ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get()
                    && !HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER);
            case CHAT_OVERLAY -> false;
        };
    }

    private static Component asComponent(FormattedText text) {
        if (text == null) {
            return null;
        }
        if (text instanceof Component component) {
            return component;
        }
        String plain = text.getString();
        return plain == null ? Component.empty() : Component.literal(plain);
    }

    private static Object newGatherResult(
            InteractionResult result,
            int maxWidth,
            List<Either<FormattedText, TooltipComponent>> tooltipElements) throws Exception {
        Class<?> resultClass = Class.forName(GATHER_RESULT_CLASS);
        Constructor<?> constructor = resultClass.getConstructor(
                InteractionResult.class, int.class, List.class);
        return constructor.newInstance(result, maxWidth, tooltipElements);
    }
}
