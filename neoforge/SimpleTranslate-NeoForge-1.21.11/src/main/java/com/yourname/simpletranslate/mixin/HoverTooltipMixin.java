package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

/**
 * Mixin to translate hover tooltips in chat and book screens
 *
 * 拦截悬浮提示的渲染，翻译SHOW_TEXT类型的HoverEvent
 */
@Mixin(GuiGraphics.class)
public class HoverTooltipMixin {

    @Unique
    private boolean simple_translate$renderingTranslatedTooltip;

    /**
     * Intercept hover effect rendering to translate tooltips with style preservation
     */
    @Inject(method = "renderComponentHoverEffect", at = @At("HEAD"), cancellable = true)
    private void onRenderComponentHoverEffect(Font font, Style style, int mouseX, int mouseY, CallbackInfo ci) {
        if (style == null) {
            return;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER)) {
            return;
        }

        HoverEvent hoverEvent = style.getHoverEvent();
        if (hoverEvent == null) {
            return;
        }

        // Check if it's a SHOW_TEXT hover event
        if (!(hoverEvent instanceof HoverEvent.ShowText showText)) {
            return;
        }

        // Check which screen we're on and if translation is enabled
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null) {
            return;
        }

        boolean shouldTranslate = false;

        if (screen instanceof ChatScreen) {
            boolean enabled = ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get();
            SimpleTranslateMod.getLogger().debug("ChatScreen hover detected, enabled={}", enabled);
            if (enabled) {
                shouldTranslate = true;
            }
        } else if (screen instanceof BookViewScreen && ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get()) {
            shouldTranslate = true;
        }

        if (!shouldTranslate) {
            return;
        }

        // Get the hover text
        Component hoverText = showText.value();
        if (hoverText == null) {
            return;
        }

        String plainText = hoverText.getString();
        SimpleTranslateMod.getLogger().debug("Hover text: {}", plainText);

        if (plainText.isEmpty() || !TooltipTranslationHelper.containsEnglish(plainText)) {
            return;
        }

        // Translate with line-template preservation. SHOW_TEXT hover payloads often
        // contain embedded newlines inside one Component, so render the translated
        // result as a component list to keep the original line slots.
        List<Component> translatedLines = TooltipTranslationHelper.translateHoverComponentLines(hoverText);

        if (!(translatedLines.size() == 1 && translatedLines.get(0) == hoverText)) {
            SimpleTranslateMod.getLogger().debug("Rendering translated tooltip with {} lines", translatedLines.size());
            // Cancel the original rendering and render translated tooltip
            GuiGraphics guiGraphics = (GuiGraphics)(Object)this;
            simple_translate$renderingTranslatedTooltip = true;
            try {
                guiGraphics.setTooltipForNextFrame(font, translatedLines, Optional.empty(), mouseX, mouseY);
            } finally {
                simple_translate$renderingTranslatedTooltip = false;
            }
            ci.cancel();
        } else {
            SimpleTranslateMod.getLogger().debug("Translation not cached yet, showing original");
        }
    }

    /**
     * Translate already-materialized tooltip component lists. Some UI mods render
     * inventory/ability tooltips directly through GuiGraphics instead of asking
     * ItemStack for tooltip lines, so ItemStackMixin never sees those texts.
     */
    @Inject(
            method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void simple_translate$onSetTooltipForNextFrame(Font font, List<Component> components,
                                                           Optional<TooltipComponent> image, int mouseX, int mouseY,
                                                           CallbackInfo ci) {
        if (!simple_translate$shouldTranslateRenderedTooltip(components)) {
            return;
        }

        List<Component> translated = TooltipTranslationHelper.translateComponentsBatch(components);
        if (translated == components) {
            return;
        }

        GuiGraphics guiGraphics = (GuiGraphics)(Object)this;
        simple_translate$renderingTranslatedTooltip = true;
        try {
            guiGraphics.setTooltipForNextFrame(font, translated, image, mouseX, mouseY);
        } finally {
            simple_translate$renderingTranslatedTooltip = false;
        }
        ci.cancel();
    }

    @Inject(method = "setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V", at = @At("HEAD"), cancellable = true)
    private void simple_translate$onSetComponentTooltipForNextFrame(Font font, List<Component> components, int mouseX, int mouseY,
                                                                    CallbackInfo ci) {
        if (!simple_translate$shouldTranslateRenderedTooltip(components)) {
            return;
        }

        List<Component> translated = TooltipTranslationHelper.translateComponentsBatch(components);
        if (translated == components) {
            return;
        }

        GuiGraphics guiGraphics = (GuiGraphics)(Object)this;
        simple_translate$renderingTranslatedTooltip = true;
        try {
            guiGraphics.setComponentTooltipForNextFrame(font, translated, mouseX, mouseY);
        } finally {
            simple_translate$renderingTranslatedTooltip = false;
        }
        ci.cancel();
    }

    @Inject(
            method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;II)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void simple_translate$onSetTooltipComponentForNextFrame(Font font, Component component, int mouseX, int mouseY,
                                                                    CallbackInfo ci) {
        if (component == null || simple_translate$renderingTranslatedTooltip) {
            return;
        }
        if (TooltipTranslationHelper.isMarkedTranslatedTooltip(component)) {
            return;
        }
        if (!ModConfig.TOOLTIP_ITEM_ENABLED.get()) {
            return;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_ITEM)) {
            return;
        }
        if (!TooltipTranslationHelper.containsEnglish(component.getString())) {
            return;
        }

        List<Component> translated = TooltipTranslationHelper.translateComponentsBatch(List.of(component));
        if (translated.isEmpty() || (translated.size() == 1 && translated.get(0) == component)) {
            return;
        }

        GuiGraphics guiGraphics = (GuiGraphics)(Object)this;
        simple_translate$renderingTranslatedTooltip = true;
        try {
            if (translated.size() == 1) {
                guiGraphics.setTooltipForNextFrame(font, translated.get(0), mouseX, mouseY);
            } else {
                guiGraphics.setTooltipForNextFrame(font, translated, Optional.empty(), mouseX, mouseY);
            }
        } finally {
            simple_translate$renderingTranslatedTooltip = false;
        }
        ci.cancel();
    }

    @Unique
    private boolean simple_translate$shouldTranslateRenderedTooltip(List<Component> components) {
        if (simple_translate$renderingTranslatedTooltip) {
            return false;
        }
        if (TooltipTranslationHelper.isMarkedTranslatedTooltip(components)) {
            return false;
        }
        if (!ModConfig.TOOLTIP_ITEM_ENABLED.get()) {
            return false;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_ITEM)) {
            return false;
        }
        return components != null
                && !components.isEmpty()
                && TooltipTranslationHelper.anyContainsEnglish(components);
    }
}
