package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.TooltipTranslationController;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

/**
 * Intercepts hover tooltips in chat/book screens and materialized tooltip lists.
 * Minecraft 1.19.4 renders through Screen/PoseStack, so this keeps the old
 * descriptor while sharing the same controller and cache-miss queue as newer
 * GuiGraphics targets.
 */
@Mixin(Screen.class)
public class HoverTooltipMixin {

    @Inject(method = "renderComponentHoverEffect", at = @At("HEAD"), cancellable = true)
    private void onRenderComponentHoverEffect(PoseStack poseStack, Style style, int mouseX, int mouseY, CallbackInfo ci) {
        if (style == null) {
            return;
        }

        HoverEvent hoverEvent = style.getHoverEvent();
        if (hoverEvent == null || hoverEvent.getAction() != HoverEvent.Action.SHOW_TEXT) {
            return;
        }

        Component hoverText = hoverEvent.getValue(HoverEvent.Action.SHOW_TEXT);
        if (!TooltipTranslationController.shouldTranslateChatHover(hoverText)
                && !simple_translate$shouldTranslateBookHover(hoverText)) {
            return;
        }

        List<Component> translatedLines = TooltipTranslationHelper.translateHoverComponentLines(hoverText);
        if (translatedLines.size() == 1 && translatedLines.get(0) == hoverText) {
            return;
        }

        Screen screen = (Screen) (Object) this;
        TooltipTranslationController.beginRenderingTranslated();
        try {
            screen.renderTooltip(poseStack, translatedLines, Optional.empty(), mouseX, mouseY);
        } finally {
            TooltipTranslationController.endRenderingTranslated();
        }
        ci.cancel();
    }

    @Inject(
            method = "renderTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void simple_translate$onRenderTooltipList(PoseStack poseStack, List<Component> components,
                                                      Optional<TooltipComponent> image, int mouseX, int mouseY,
                                                      CallbackInfo ci) {
        if (!TooltipTranslationController.shouldTranslateRenderedTooltip(components)) {
            return;
        }

        TooltipTranslationController.RenderContext context = TooltipTranslationController.resolveRenderContext();
        List<Component> translated = TooltipTranslationController.translateForRender(components, context);
        if (translated == components) {
            return;
        }

        Screen screen = (Screen) (Object) this;
        TooltipTranslationController.beginRenderingTranslated();
        try {
            screen.renderTooltip(poseStack, translated, image, mouseX, mouseY);
        } finally {
            TooltipTranslationController.endRenderingTranslated();
        }
        ci.cancel();
    }

    @Inject(
            method = "renderComponentTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;II)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void simple_translate$onRenderComponentTooltip(PoseStack poseStack, List<Component> components,
                                                           int mouseX, int mouseY, CallbackInfo ci) {
        if (!TooltipTranslationController.shouldTranslateRenderedTooltip(components)) {
            return;
        }

        TooltipTranslationController.RenderContext context = TooltipTranslationController.resolveRenderContext();
        List<Component> translated = TooltipTranslationController.translateForRender(components, context);
        if (translated == components) {
            return;
        }

        Screen screen = (Screen) (Object) this;
        TooltipTranslationController.beginRenderingTranslated();
        try {
            screen.renderComponentTooltip(poseStack, translated, mouseX, mouseY);
        } finally {
            TooltipTranslationController.endRenderingTranslated();
        }
        ci.cancel();
    }

    @Inject(
            method = "renderTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;II)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void simple_translate$onRenderTooltipComponent(PoseStack poseStack, Component component, int mouseX, int mouseY,
                                                           CallbackInfo ci) {
        if (!TooltipTranslationController.shouldTranslateRenderedTooltip(List.of(component))) {
            return;
        }

        TooltipTranslationController.RenderContext context = TooltipTranslationController.resolveRenderContext();
        List<Component> translated = TooltipTranslationController.translateForRender(List.of(component), context);
        if (translated.isEmpty() || (translated.size() == 1 && translated.get(0) == component)) {
            return;
        }

        Screen screen = (Screen) (Object) this;
        TooltipTranslationController.beginRenderingTranslated();
        try {
            if (translated.size() == 1) {
                screen.renderTooltip(poseStack, translated.get(0), mouseX, mouseY);
            } else {
                screen.renderTooltip(poseStack, translated, Optional.empty(), mouseX, mouseY);
            }
        } finally {
            TooltipTranslationController.endRenderingTranslated();
        }
        ci.cancel();
    }

    @Unique
    private static boolean simple_translate$shouldTranslateBookHover(Component hoverText) {
        if (hoverText == null || TooltipTranslationController.isRenderingTranslated()) {
            return false;
        }
        Screen screen = Minecraft.getInstance().screen;
        return screen instanceof BookViewScreen
                && ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER)
                && !TooltipTranslationHelper.isMarkedTranslatedTooltip(hoverText)
                && TooltipTranslationHelper.containsEnglish(hoverText.getString());
    }
}
