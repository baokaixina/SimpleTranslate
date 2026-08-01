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
import java.util.function.Consumer;

/**
 * Soft optional integration with Iceberg's tooltip gather event (Forge edition).
 *
 * <p>Legendary Tooltips (and Iceberg itself) rebuild item tooltips through
 * {@code com.anthonyhilyard.iceberg.events.GatherComponentsExtEvent} before
 * wrapping lines into {@code ClientTooltipComponent}s, bypassing the vanilla
 * GUI graphics tooltip path our hooks observe. Registering here keeps
 * translation on the same official pipeline LT uses for model padding / compact
 * rows, without inventing LT-private mixins.</p>
 *
 * <p>API surface verified against the exact installed
 * Iceberg-1.20.1-forge-1.1.25 jar: {@code GatherComponentsExtEvent} extends
 * {@link RenderTooltipEvent.GatherComponents} (Forge event bus event, javap
 * -p -s evidence) and carries the mutable
 * {@code List<Either<FormattedText, TooltipComponent>>} from
 * {@code getTooltipElements()}. Unlike the Fabric {@code RenderTooltipEvents}
 * holder class, the Forge jar ships no {@code GATHER} static field, so
 * registration goes through the Forge event bus. The actual translation runs
 * through {@link TooltipTranslationHelper#translateGatheredTooltipLines}, the
 * same semantic projection pipeline as the vanilla item tooltip frame.</p>
 */
public final class IcebergTooltipGatherCompat {
    private static final String ICEBERG_MOD_ID = "iceberg";
    private static final String GATHER_EVENT_CLASS =
            "com.anthonyhilyard.iceberg.events.GatherComponentsExtEvent";

    private static boolean registered;

    private IcebergTooltipGatherCompat() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerIfPresent() {
        if (registered || !ModList.get().isLoaded(ICEBERG_MOD_ID)) {
            return;
        }
        try {
            // Resolve the Iceberg event type reflectively so this class never
            // links an Iceberg class while Iceberg is absent.
            Class<?> eventType = Class.forName(GATHER_EVENT_CLASS);
            if (!RenderTooltipEvent.GatherComponents.class.isAssignableFrom(eventType)) {
                SimpleTranslateMod.getLogger().warn(
                        "Iceberg GatherComponentsExtEvent is not a RenderTooltipEvent.GatherComponents; "
                                + "tooltip gather compat skipped");
                return;
            }
            Consumer<RenderTooltipEvent.GatherComponents> listener =
                    IcebergTooltipGatherCompat::onGatherComponents;
            MinecraftForge.EVENT_BUS.addListener(
                    EventPriority.NORMAL, false, (Class) eventType, listener);
            registered = true;
            SimpleTranslateMod.getLogger().info(
                    "Registered Iceberg GatherComponentsExtEvent tooltip translation compat");
        } catch (Throwable error) {
            SimpleTranslateMod.getLogger().warn(
                    "Failed to register Iceberg tooltip gather compat: {}",
                    error.toString());
        }
    }

    private static void onGatherComponents(RenderTooltipEvent.GatherComponents event) {
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
