package com.yourname.simpletranslate.compat;

import com.mojang.datafixers.util.Either;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationController;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.FabricLoader;
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
 * <p>API surface verified against the exact 1.20.5/1.20.6-family jars
 * Iceberg-1.20.6-fabric-1.1.22 (declares {@code minecraft >=1.20.5}) and
 * LegendaryTooltips-1.20.6-fabric-1.4.6: on this family the event class is
 * still {@code com.anthonyhilyard.iceberg.events.RenderTooltipEvents} and its
 * {@code GATHER} is a public Fabric {@code Event<Gather>}, not Iceberg 1.3.x's
 * own {@code events.client} event class. The actual translation runs through
 * {@link TooltipTranslationHelper#translateGatheredTooltipLines}, the
 * same semantic projection pipeline as the vanilla item tooltip frame.</p>
 */
public final class IcebergTooltipGatherCompat {
    private static final String ICEBERG_MOD_ID = "iceberg";
    private static final String EVENTS_CLASS =
            "com.anthonyhilyard.iceberg.events.RenderTooltipEvents";
    private static final String GATHER_INTERFACE =
            "com.anthonyhilyard.iceberg.events.RenderTooltipEvents$Gather";
    private static final String GATHER_RESULT_CLASS =
            "com.anthonyhilyard.iceberg.events.RenderTooltipEvents$GatherResult";

    private static boolean registered;

    private IcebergTooltipGatherCompat() {
    }

    public static void registerIfPresent() {
        if (registered || !FabricLoader.getInstance().isModLoaded(ICEBERG_MOD_ID)) {
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

            // GATHER is declared as Fabric's public Event<Gather>. Its runtime
            // implementation is package-private, so reflecting on
            // gatherEvent.getClass() fails with IllegalAccessException. Invoke
            // the public interface API instead.
            if (!(gatherEvent instanceof Event<?> publicEvent)) {
                SimpleTranslateMod.getLogger().warn(
                        "Iceberg RenderTooltipEvents.GATHER is not a Fabric Event; tooltip gather compat skipped");
                return;
            }
            Method register = Event.class.getMethod("register", Object.class);
            register.invoke(publicEvent, listener);
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
