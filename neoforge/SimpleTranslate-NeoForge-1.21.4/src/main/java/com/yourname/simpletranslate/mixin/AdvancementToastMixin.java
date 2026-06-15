package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.AdvancementTranslationHelper;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.network.chat.Component;
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

/**
 * Mixin to translate advancement toast text (title and description)
 *
 * 翻译成就弹窗的标题和描述
 * 保留原文的样式和颜色
 */
@Mixin(AdvancementToast.class)
public class AdvancementToastMixin {

    @Shadow @Final private AdvancementHolder advancement;

    @Unique
    private int simple_translate$titleLineIndex;

    @Unique
    private String simple_translate$titleLinesCacheKey;

    @Unique
    private List<FormattedCharSequence> simple_translate$translatedTitleLines;

    /**
     * Inject at the start of render to log and trigger translation
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void simple_translate$onRenderStart(GuiGraphics guiGraphics, Font font, long timeSinceLastVisible, CallbackInfo ci) {
        if (ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            DisplayInfo display = simple_translate$getDisplay();
            if (display != null) {
                simple_translate$titleLineIndex = 0;
                String title = display.getTitle().getString();
                String desc = display.getDescription().getString();
                SimpleTranslateMod.getLogger().debug("AdvancementToast rendering: title='{}', desc='{}'", title, desc);
                AdvancementTranslationHelper.ensureTranslation(
                        simple_translate$advancementKey(display.getTitle(), display.getDescription()),
                        display.getTitle(), display.getDescription());
            }
        }
    }

    // ==================== Component versions ====================

    /**
     * Redirect drawString for Component (5 params) - no shadow
     */
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"),
            require = 0
    )
    private int simple_translate$redirectDrawString5(GuiGraphics guiGraphics, Font font, Component component, int x, int y, int color) {
        SimpleTranslateMod.getLogger().debug("AdvancementToast drawString(Component,5) called: '{}'", component.getString());

        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return guiGraphics.drawString(font, component, x, y, color);
        }

        Component translated = simple_translate$translateComponent(component);
        return guiGraphics.drawString(font, translated, x, y, color);
    }

    /**
     * Redirect drawString for Component (6 params with shadow boolean)
     */
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"),
            require = 0
    )
    private int simple_translate$redirectDrawString6(GuiGraphics guiGraphics, Font font, Component component, int x, int y, int color, boolean shadow) {
        SimpleTranslateMod.getLogger().debug("AdvancementToast drawString(Component,6) called: '{}'", component.getString());

        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return guiGraphics.drawString(font, component, x, y, color, shadow);
        }

        Component translated = simple_translate$translateComponent(component);
        return guiGraphics.drawString(font, translated, x, y, color, shadow);
    }

    // ==================== String versions ====================

    /**
     * Redirect drawString for String (5 params) - no shadow
     */
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"),
            require = 0
    )
    private int simple_translate$redirectDrawStringStr5(GuiGraphics guiGraphics, Font font, String text, int x, int y, int color) {
        SimpleTranslateMod.getLogger().debug("AdvancementToast drawString(String,5) called: '{}'", text);

        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return guiGraphics.drawString(font, text, x, y, color);
        }

        String translated = simple_translate$translateText(text);
        return guiGraphics.drawString(font, translated, x, y, color);
    }

    /**
     * Redirect drawString for String (6 params with shadow boolean)
     */
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I"),
            require = 0
    )
    private int simple_translate$redirectDrawStringStr6(GuiGraphics guiGraphics, Font font, String text, int x, int y, int color, boolean shadow) {
        SimpleTranslateMod.getLogger().debug("AdvancementToast drawString(String,6) called: '{}'", text);

        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return guiGraphics.drawString(font, text, x, y, color, shadow);
        }

        String translated = simple_translate$translateText(text);
        return guiGraphics.drawString(font, translated, x, y, color, shadow);
    }

    // ==================== FormattedCharSequence versions ====================

    /**
     * Redirect drawString for FormattedCharSequence (5 params)
     */
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"),
            require = 0
    )
    private int simple_translate$redirectDrawStringFCS5(GuiGraphics guiGraphics, Font font, FormattedCharSequence text, int x, int y, int color) {
        FormattedCharSequence translated = simple_translate$getNextTranslatedTitleLine(font);
        if (translated != null) {
            return guiGraphics.drawString(font, translated, x, y, color);
        }
        return guiGraphics.drawString(font, text, x, y, color);
    }

    /**
     * Redirect drawString for FormattedCharSequence (6 params with shadow)
     */
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I"),
            require = 0
    )
    private int simple_translate$redirectDrawStringFCS6(GuiGraphics guiGraphics, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        FormattedCharSequence translated = simple_translate$getNextTranslatedTitleLine(font);
        if (translated != null) {
            return guiGraphics.drawString(font, translated, x, y, color, shadow);
        }
        return guiGraphics.drawString(font, text, x, y, color, shadow);
    }

    @Unique
    private FormattedCharSequence simple_translate$getNextTranslatedTitleLine(Font font) {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)
                || this.advancement == null) {
            return null;
        }
        DisplayInfo display = simple_translate$getDisplay();
        if (display == null || display.getTitle() == null) {
            return null;
        }

        Component title = display.getTitle();
        Component translated = AdvancementTranslationHelper.getCachedTitleComponent(title);
        if (translated == null || translated.getString().equals(title.getString())) {
            return null;
        }

        String cacheKey = title.getString() + "|" + translated.getString();
        if (!cacheKey.equals(this.simple_translate$titleLinesCacheKey) || this.simple_translate$translatedTitleLines == null) {
            this.simple_translate$titleLinesCacheKey = cacheKey;
            this.simple_translate$translatedTitleLines = font.split(translated, 125);
        }
        if (this.simple_translate$translatedTitleLines == null
                || this.simple_translate$titleLineIndex >= this.simple_translate$translatedTitleLines.size()) {
            return null;
        }
        return this.simple_translate$translatedTitleLines.get(this.simple_translate$titleLineIndex++);
    }

    /**
     * Translate a Component
     */
    @Unique
    private Component simple_translate$translateComponent(Component component) {
        if (component == null) {
            return null;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            return component;
        }

        String text = component.getString();
        if (text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
            return component;
        }

        return AdvancementTranslationHelper.translateComponent(component,
                "advancement.toast.component.direct", "advancement-toast");
    }

    /**
     * Translate a String
     */
    @Unique
    private String simple_translate$translateText(String text) {
        if (text == null || text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
            return text;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            return text;
        }

        String translated = AdvancementTranslationHelper.getCachedTranslation(text);
        if (translated != null && !translated.equals(text)) {
            SimpleTranslateMod.getLogger().debug("Advancement translated: '{}' -> '{}'", text, translated);
            return translated;
        }

        return text;
    }

    @Unique
    private String simple_translate$advancementKey(Component title, Component description) {
        if (this.advancement != null && this.advancement.id() != null) {
            return "advancement:" + this.advancement.id();
        }
        String titleText = title == null ? "" : title.getString();
        String descriptionText = description == null ? "" : description.getString();
        return "advancement:document:" + titleText.hashCode() + ":" + descriptionText.hashCode();
    }

    @Unique
    private DisplayInfo simple_translate$getDisplay() {
        return this.advancement == null ? null : this.advancement.value().display().orElse(null);
    }
}
