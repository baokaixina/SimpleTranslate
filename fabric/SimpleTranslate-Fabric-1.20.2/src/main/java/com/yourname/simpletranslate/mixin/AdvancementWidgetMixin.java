package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.AdvancementTranslationHelper;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
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
 * Mixin to translate advancement widget text (title and description)
 *
 * 翻译进度节点的标题和描述（鼠标悬停时显示）
 * 使用批量翻译保持标题和描述的上下文关联
 * 保留原文的样式（颜色、格式等）
 */
@Mixin(AdvancementWidget.class)
public abstract class AdvancementWidgetMixin {

    @Shadow @Final private AdvancementNode advancementNode;
    @Shadow @Final private DisplayInfo display;
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private FormattedCharSequence title;
    @Shadow @Final private int width;
    @Shadow @Final private List<FormattedCharSequence> description;

    @Shadow
    protected abstract List<FormattedText> findOptimalLines(Component component, int maxWidth);

    @Unique
    private String simple_translate$descriptionCacheKey;

    @Unique
    private List<FormattedCharSequence> simple_translate$translatedDescription;

    @Unique
    private String simple_translate$titleCacheKey;

    @Unique
    private FormattedCharSequence simple_translate$translatedTitle;

    /**
     * Inject at the start of drawHover to trigger batch translation
     * 在drawHover开始时触发批量翻译（标题+描述一起翻译）
     */
    @Inject(method = "drawHover", at = @At("HEAD"))
    private void simple_translate$onDrawHoverStart(GuiGraphics guiGraphics, int x, int y, float fade, int width, int height, CallbackInfo ci) {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return;
        }

        DisplayInfo display = this.display;
        if (display != null) {
            Component title = display.getTitle();
            Component description = display.getDescription();

            // Get advancement ID for caching
            String advancementId = simple_translate$advancementKey(title, description);

            // Trigger batch translation with context (title + description together)
            AdvancementTranslationHelper.ensureTranslation(advancementId, title, description);
        }
    }

    /**
     * Redirect description list access to use translated description lines
     */
    @Redirect(
            method = "drawHover",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/screens/advancements/AdvancementWidget;description:Ljava/util/List;"),
            require = 1
    )
    private List<FormattedCharSequence> simple_translate$redirectDescription(AdvancementWidget instance) {
        return simple_translate$getDescriptionLinesForRender();
    }

    /**
     * Redirect title field access because vanilla precomputes the title as a
     * FormattedCharSequence in the constructor. drawString(Component) redirects
     * never see that original title component.
     */
    @Redirect(
            method = "drawHover",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/screens/advancements/AdvancementWidget;title:Lnet/minecraft/util/FormattedCharSequence;"),
            require = 1
    )
    private FormattedCharSequence simple_translate$redirectTitle(AdvancementWidget instance) {
        return simple_translate$getTitleForRender();
    }

    /**
     * Redirect drawString for Component (5 params) - use cached translation with style preservation
     */
    @Redirect(
            method = "drawHover",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"),
            require = 0
    )
    private int simple_translate$redirectDrawString5(GuiGraphics guiGraphics, Font font, Component component, int x, int y, int color) {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return guiGraphics.drawString(font, component, x, y, color);
        }

        Component translated = simple_translate$getTranslatedComponent(component);
        return guiGraphics.drawString(font, translated, x, y, color);
    }

    /**
     * Redirect drawString for Component (6 params with shadow) - use cached translation with style preservation
     */
    @Redirect(
            method = "drawHover",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"),
            require = 0
    )
    private int simple_translate$redirectDrawString6(GuiGraphics guiGraphics, Font font, Component component, int x, int y, int color, boolean shadow) {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return guiGraphics.drawString(font, component, x, y, color, shadow);
        }

        Component translated = simple_translate$getTranslatedComponent(component);
        return guiGraphics.drawString(font, translated, x, y, color, shadow);
    }

    /**
     * Redirect drawString for FormattedCharSequence (5 params)
     */
    @Redirect(
            method = "drawHover",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"),
            require = 0
    )
    private int simple_translate$redirectDrawStringFCS5(GuiGraphics guiGraphics, Font font, FormattedCharSequence text, int x, int y, int color) {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return guiGraphics.drawString(font, text, x, y, color);
        }

        Component translated = simple_translate$translateFormattedCharSequence(text);
        if (translated != null) {
            return guiGraphics.drawString(font, translated, x, y, color);
        }
        return guiGraphics.drawString(font, text, x, y, color);
    }

    /**
     * Redirect drawString for FormattedCharSequence (6 params with shadow)
     */
    @Redirect(
            method = "drawHover",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I"),
            require = 0
    )
    private int simple_translate$redirectDrawStringFCS6(GuiGraphics guiGraphics, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return guiGraphics.drawString(font, text, x, y, color, shadow);
        }

        Component translated = simple_translate$translateFormattedCharSequence(text);
        if (translated != null) {
            return guiGraphics.drawString(font, translated, x, y, color, shadow);
        }
        return guiGraphics.drawString(font, text, x, y, color, shadow);
    }

    @Unique
    private List<FormattedCharSequence> simple_translate$getDescriptionLinesForRender() {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return this.description;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            return this.description;
        }

        if (this.display == null) {
            return this.description;
        }

        Component original = this.display.getDescription();
        if (original == null) {
            return this.description;
        }

        String originalText = original.getString();
        if (originalText.isEmpty() || !simple_translate$containsEnglish(originalText)) {
            return this.description;
        }

        String advancementId = simple_translate$advancementKey(this.display.getTitle(), original);
        AdvancementTranslationHelper.ensureTranslation(advancementId, this.display.getTitle(), original);

        Component styledOriginal = original.copy();
        Component translatedComponent = AdvancementTranslationHelper.getCachedDescriptionComponent(this.display.getTitle(), original);
        if (translatedComponent == null || translatedComponent.getString().equals(originalText)) {
            translatedComponent = AdvancementTranslationHelper.translateComponent(styledOriginal,
                    "advancement.widget.description.direct", "advancement-description");
        }
        if (translatedComponent == null || translatedComponent.getString().equals(originalText)) {
            return this.description;
        }

        String cacheKey = advancementId + "|" + originalText + "|" + translatedComponent.getString();
        if (cacheKey.equals(this.simple_translate$descriptionCacheKey) && this.simple_translate$translatedDescription != null) {
            return this.simple_translate$translatedDescription;
        }

        int maxWidth = Math.max(1, this.width - 3 - 5);
        List<FormattedText> lines = this.findOptimalLines(translatedComponent, maxWidth);
        List<FormattedCharSequence> visual = lines != null ? Language.getInstance().getVisualOrder(lines) : this.description;

        this.simple_translate$descriptionCacheKey = cacheKey;
        this.simple_translate$translatedDescription = visual;

        return visual;
    }

    @Unique
    private FormattedCharSequence simple_translate$getTitleForRender() {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return this.title;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            return this.title;
        }
        if (this.display == null) {
            return this.title;
        }

        Component original = this.display.getTitle();
        if (original == null) {
            return this.title;
        }

        String originalText = original.getString();
        if (originalText.isEmpty() || !simple_translate$containsEnglish(originalText)) {
            return this.title;
        }

        Component description = this.display.getDescription();
        String advancementId = simple_translate$advancementKey(original, description);
        AdvancementTranslationHelper.ensureTranslation(advancementId, original, description);

        Component translatedComponent = AdvancementTranslationHelper.getCachedTitleComponent(original);
        if (translatedComponent == null || translatedComponent.getString().equals(originalText)) {
            return this.title;
        }

        String cacheKey = advancementId + "|" + originalText + "|" + translatedComponent.getString();
        if (cacheKey.equals(this.simple_translate$titleCacheKey) && this.simple_translate$translatedTitle != null) {
            return this.simple_translate$translatedTitle;
        }

        FormattedText clipped = this.minecraft.font.substrByWidth(translatedComponent, 163);
        FormattedCharSequence visual = Language.getInstance().getVisualOrder(clipped);
        this.simple_translate$titleCacheKey = cacheKey;
        this.simple_translate$translatedTitle = visual;
        return visual;
    }

    /**
     * Get translated component using the batch translation helper
     */
    @Unique
    private Component simple_translate$getTranslatedComponent(Component component) {
        if (component == null) {
            return null;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            return component;
        }

        String text = component.getString();
        if (text.isEmpty() || !simple_translate$containsEnglish(text)) {
            return component;
        }

        return AdvancementTranslationHelper.translateComponent(component,
                "advancement.widget.component.direct", "advancement-widget");
    }

    /**
     * Translate FormattedCharSequence by extracting text with styles
     */
    @Unique
    private Component simple_translate$translateFormattedCharSequence(FormattedCharSequence text) {
        // FormattedCharSequence is already a visual slice produced by Minecraft's
        // wrapping code. Translating it here would enqueue word fragments such as
        // "of grand" and starve real component/document requests. Whole
        // advancement components are translated through ensureTranslation and
        // simple_translate$getDescriptionLinesForRender instead.
        return null;
    }

    @Unique
    private boolean simple_translate$containsEnglish(String text) {
        return TooltipTranslationHelper.containsEnglish(text);
    }

    @Unique
    private String simple_translate$advancementKey(Component title, Component description) {
        if (this.advancementNode != null && this.advancementNode.holder() != null
                && this.advancementNode.holder().id() != null) {
            return "advancement:" + this.advancementNode.holder().id();
        }
        String titleText = title == null ? "" : title.getString();
        String descriptionText = description == null ? "" : description.getString();
        return "advancement:document:" + titleText.hashCode() + ":" + descriptionText.hashCode();
    }
}

