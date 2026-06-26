package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.feature.advancement.AdvancementTranslationHelper;
import com.yourname.simpletranslate.core.ComponentRenderSafety;
import com.yourname.simpletranslate.core.DrawStringHelper;
import com.yourname.simpletranslate.core.MixinRuntimeProbe;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(AdvancementToast.class)
public class AdvancementToastMixin {
    @Shadow @Final private AdvancementHolder advancement;

    @Unique private int simple_translate$titleLineIndex;
    @Unique private String simple_translate$titleLinesCacheKey;
    @Unique private List<FormattedCharSequence> simple_translate$translatedTitleLines;

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void simple_translate$prepareTranslation(
            GuiGraphicsExtractor graphics, Font font, long visibleTime, CallbackInfo ci) {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get() || this.advancement == null) {
            return;
        }
        DisplayInfo display = this.advancement.value().display().orElse(null);
        if (display == null) {
            return;
        }
        simple_translate$titleLineIndex = 0;
        Component title = ComponentRenderSafety.sanitize(display.getTitle());
        Component description = ComponentRenderSafety.sanitize(display.getDescription());
        AdvancementTranslationHelper.ensureTranslation(
                simple_translate$advancementKey(title, description), title, description);
    }

    @Redirect(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"),
            require = 0
    )
    private List<FormattedCharSequence> simple_translate$splitSafeTitle(
            Font font, FormattedText text, int width) {
        MixinRuntimeProbe.matched("AdvancementToastMixin#titleSplit");
        FormattedText safe = text instanceof Component component
                ? ComponentRenderSafety.sanitize(component)
                : text == null ? Component.empty() : text;
        return font.split(safe, width);
    }

    @Redirect(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"),
            require = 0
    )
    private void simple_translate$drawFrameLabel(
            GuiGraphicsExtractor graphics, Font font, Component component,
            int x, int y, int color, boolean shadow) {
        Component safe = ComponentRenderSafety.sanitize(component);
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            graphics.text(font, safe, x, y, color, shadow);
            return;
        }
        DrawStringHelper.component(graphics, font, safe, x, y, color, shadow,
                value -> ComponentRenderSafety.sanitize(
                        simple_translate$translateComponent(value), value.getString()));
    }

    @Redirect(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V"),
            require = 0
    )
    private void simple_translate$drawTitleLine(
            GuiGraphicsExtractor graphics, Font font, FormattedCharSequence text,
            int x, int y, int color, boolean shadow) {
        DrawStringHelper.sequence(
                graphics, font, text, x, y, color, shadow,
                simple_translate$getNextTranslatedTitleLine(font));
    }

    @Unique
    private FormattedCharSequence simple_translate$getNextTranslatedTitleLine(Font font) {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)
                || this.advancement == null) {
            return null;
        }
        DisplayInfo display = this.advancement.value().display().orElse(null);
        if (display == null || display.getTitle() == null) {
            return null;
        }
        Component original = ComponentRenderSafety.sanitize(display.getTitle());
        Component translated = AdvancementTranslationHelper.getCachedTitleComponent(display.getTitle());
        Component safeTranslated = ComponentRenderSafety.sanitize(translated, original.getString());
        if (translated == null || safeTranslated.getString().equals(original.getString())) {
            return null;
        }
        String cacheKey = original.getString() + "|" + safeTranslated.getString();
        if (!cacheKey.equals(simple_translate$titleLinesCacheKey)
                || simple_translate$translatedTitleLines == null) {
            simple_translate$titleLinesCacheKey = cacheKey;
            simple_translate$translatedTitleLines = font.split(safeTranslated, 125);
        }
        if (simple_translate$titleLineIndex >= simple_translate$translatedTitleLines.size()) {
            return null;
        }
        return simple_translate$translatedTitleLines.get(simple_translate$titleLineIndex++);
    }

    @Unique
    private Component simple_translate$translateComponent(Component component) {
        return SafeTranslate.guard(() -> {
            Component safe = ComponentRenderSafety.sanitize(component);
            if (HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
                return safe;
            }
            String text = safe.getString();
            if (text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
                return safe;
            }
            return AdvancementTranslationHelper.translateComponent(
                    safe, "advancement.toast.component.direct", "advancement-toast");
        }, component, "advancement.translateComponent");
    }

    @Unique
    private String simple_translate$advancementKey(Component title, Component description) {
        if (this.advancement != null && this.advancement.id() != null) {
            return "advancement:" + this.advancement.id();
        }
        String titleText = ComponentRenderSafety.sanitize(title).getString();
        String descriptionText = ComponentRenderSafety.sanitize(description).getString();
        return "advancement:document:" + titleText.hashCode() + ":" + descriptionText.hashCode();
    }
}
