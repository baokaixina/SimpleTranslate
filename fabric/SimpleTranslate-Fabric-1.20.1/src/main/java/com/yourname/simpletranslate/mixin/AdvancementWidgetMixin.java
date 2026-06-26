package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.feature.advancement.AdvancementTranslationHelper;
import com.yourname.simpletranslate.core.ComponentRenderSafety;
import com.yourname.simpletranslate.core.MixinRuntimeProbe;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
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

    @Shadow @Final private Advancement advancement;
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

        DisplayInfo display = this.advancement.getDisplay();
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
            require = 0
    )
    private List<FormattedCharSequence> simple_translate$redirectDescription(AdvancementWidget instance) {
        MixinRuntimeProbe.matched("AdvancementWidgetMixin#description");
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
            require = 0
    )
    private FormattedCharSequence simple_translate$redirectTitle(AdvancementWidget instance) {
        MixinRuntimeProbe.matched("AdvancementWidgetMixin#title");
        return simple_translate$getTitleForRender();
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

        Component original = ComponentRenderSafety.sanitize(this.display.getDescription());
        if (original == null) {
            return this.description;
        }

        String originalText = original.getString();
        if (originalText.isEmpty() || !simple_translate$containsEnglish(originalText)) {
            return this.description;
        }

        String advancementId = simple_translate$advancementKey(this.display.getTitle(), original);
        AdvancementTranslationHelper.ensureTranslation(advancementId, this.display.getTitle(), original);

        Component styledOriginal = ComponentUtils.mergeStyles(
                original.copy(),
                Style.EMPTY.withColor(this.display.getFrame().getChatColor())
        );
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
        List<FormattedCharSequence> visual;
        if (lines == null) {
            visual = this.description;
        } else {
            visual = new java.util.ArrayList<>(Language.getInstance().getVisualOrder(lines));
            while (visual.size() < this.description.size()) {
                visual.add(FormattedCharSequence.EMPTY);
            }
            if (visual.size() > this.description.size()) {
                visual = new java.util.ArrayList<>(visual.subList(0, this.description.size()));
            }
        }

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

        Component original = ComponentRenderSafety.sanitize(this.display.getTitle());
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

    @Unique
    private boolean simple_translate$containsEnglish(String text) {
        return TooltipTranslationHelper.containsEnglish(text);
    }

    @Unique
    private String simple_translate$advancementKey(Component title, Component description) {
        if (this.advancement != null && this.advancement.getId() != null) {
            return "advancement:" + this.advancement.getId();
        }
        String titleText = ComponentRenderSafety.sanitize(title).getString();
        String descriptionText = ComponentRenderSafety.sanitize(description).getString();
        return "advancement:document:" + titleText.hashCode() + ":" + descriptionText.hashCode();
    }
}

