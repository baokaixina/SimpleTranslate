package com.yourname.simpletranslate.compat;

import com.mojang.datafixers.util.Either;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationController;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * Soft optional integration with Iceberg's tooltip gather event (Forge).
 *
 * <p>Legendary Tooltips rebuilds item tooltips through Iceberg's
 * {@code com.anthonyhilyard.iceberg.events.GatherComponentsExtEvent} before
 * wrapping lines into {@code ClientTooltipComponent}s, bypassing the vanilla
 * tooltip path our hooks observe. Registering here keeps translation on the
 * same official pipeline LT uses, without inventing LT-private mixins.
 *
 * <p>API surface verified against the exact
 * {@code Iceberg-1.19.2-forge-1.1.4.jar} (the only Forge build declared for
 * 1.19/1.19.1/1.19.2 on Modrinth/CurseForge): the Forge Iceberg build has no
 * Fabric-style {@code RenderTooltipEvents.GATHER}; instead
 * {@code GatherComponentsExtEvent} extends Forge's
 * {@link RenderTooltipEvent.GatherComponents} (constructor
 * (ItemStack, int, int, List&lt;Either&lt;FormattedText, TooltipComponent&gt;&gt;, int, int),
 * adding only {@code getIndex()}). The Forge base class (verified in
 * forge-1.19.2-43.5.2-universal.jar) exposes {@code getTooltipElements()},
 * {@code getItemStack()}, {@code getScreenWidth()}, {@code getScreenHeight()}
 * and {@code getMaxWidth()}. The listener is registered by reflection against
 * the ext class so only the Iceberg/LT pipeline is affected; the handler then
 * consumes it through the compile-time Forge base type. The actual translation
 * runs through {@link TooltipTranslationHelper#translateGatheredTooltipLines},
 * the same semantic projection pipeline as the vanilla item tooltip frame.</p>
 */
public final class IcebergTooltipGatherCompat {
    private static final String ICEBERG_MOD_ID = "iceberg";
    private static final String GATHER_EXT_EVENT_CLASS =
            "com.anthonyhilyard.iceberg.events.GatherComponentsExtEvent";

    private static boolean registered;

    private IcebergTooltipGatherCompat() {
    }

    public static void registerIfPresent() {
        if (registered) {
            return;
        }
        boolean icebergLoaded;
        try {
            icebergLoaded = ModList.get().isLoaded(ICEBERG_MOD_ID);
        } catch (Throwable ignored) {
            icebergLoaded = false;
        }
        if (!icebergLoaded) {
            return;
        }
        try {
            Class<?> extClass = Class.forName(GATHER_EXT_EVENT_CLASS);
            if (!RenderTooltipEvent.GatherComponents.class.isAssignableFrom(extClass)) {
                SimpleTranslateMod.getLogger().warn(
                        "Iceberg GatherComponentsExtEvent is not a RenderTooltipEvent.GatherComponents; gather compat skipped");
                return;
            }
            @SuppressWarnings("unchecked")
            Class<RenderTooltipEvent.GatherComponents> eventType =
                    (Class<RenderTooltipEvent.GatherComponents>) extClass;
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, eventType,
                    IcebergTooltipGatherCompat::onGather);
            registered = true;
            SimpleTranslateMod.getLogger().info(
                    "Registered Iceberg GatherComponentsExtEvent tooltip translation compat");
        } catch (Throwable error) {
            SimpleTranslateMod.getLogger().warn(
                    "Failed to register Iceberg tooltip gather compat: {}", error.toString());
        }
    }

    private static void onGather(RenderTooltipEvent.GatherComponents event) {
        if (event == null) {
            return;
        }
        translateTooltipElements(event.getTooltipElements());
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
}
