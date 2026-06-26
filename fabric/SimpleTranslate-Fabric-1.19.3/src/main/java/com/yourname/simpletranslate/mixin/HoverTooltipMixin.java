package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.compat.GuiGraphics;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationController;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationGlowRenderer;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
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
import java.util.function.Consumer;

@Mixin(Screen.class)
public class HoverTooltipMixin {

    @Inject(method = "renderComponentHoverEffect", at = @At("HEAD"), cancellable = true)
    private void simple_translate$onRenderComponentHoverEffect(PoseStack poseStack, Style style, int mouseX, int mouseY,
                                                               CallbackInfo ci) {
        if (style == null || HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER)) {
            return;
        }
        HoverEvent hoverEvent = style.getHoverEvent();
        if (hoverEvent == null || hoverEvent.getAction() != HoverEvent.Action.SHOW_TEXT) {
            return;
        }
        Component hoverText = hoverEvent.getValue(HoverEvent.Action.SHOW_TEXT);
        if (!TooltipTranslationController.shouldTranslateChatHover(hoverText) && !simple_translate$shouldTranslateBookHover()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        GuiGraphics graphics = new GuiGraphics(poseStack);
        TooltipTranslationController.RenderContext context = TooltipTranslationController.resolveRenderContext();
        boolean requestAllowed = TooltipTranslationController.allowRequest(context, List.of(hoverText));
        List<Component> translatedLines = TooltipTranslationHelper.translateHoverComponentLines(hoverText, requestAllowed);

        if (translatedLines.size() == 1 && translatedLines.get(0) == hoverText) {
            if (requestAllowed || TooltipTranslationHelper.isHoverTranslationPending(hoverText)) {
                TooltipTranslationController.beginRenderingTranslated();
                try {
                    TooltipTranslationController.armPendingGlowForHover(hoverText, requestAllowed);
                    graphics.renderTooltip(font,
                            TooltipTranslationHelper.splitHoverComponentLinesForRender(hoverText),
                            Optional.empty(), mouseX, mouseY);
                } finally {
                    TooltipTranslationController.endRenderingTranslated();
                }
                ci.cancel();
            }
            return;
        }
        simple_translate$renderWithGuard(graphics,
                g -> g.renderTooltip(font, translatedLines, Optional.empty(), mouseX, mouseY), ci);
    }

    @Inject(method = "renderTooltipInternal", at = @At("TAIL"))
    private void simple_translate$renderPendingTranslationGlow(PoseStack poseStack,
                                                               List<ClientTooltipComponent> components,
                                                               int mouseX, int mouseY,
                                                               ClientTooltipPositioner positioner,
                                                               CallbackInfo ci) {
        if (!TooltipTranslationController.consumePendingGlow()) {
            return;
        }
        TooltipTranslationGlowRenderer.render(new GuiGraphics(poseStack), Minecraft.getInstance().font,
                components, mouseX, mouseY, positioner);
    }

    @Inject(
            method = "renderTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), cancellable = true)
    private void simple_translate$onRenderTooltipList(PoseStack poseStack, List<Component> components,
                                                      Optional<TooltipComponent> image, int mouseX, int mouseY,
                                                      CallbackInfo ci) {
        if (!TooltipTranslationController.shouldTranslateRenderedTooltip(components)) {
            return;
        }
        TooltipTranslationController.RenderContext context = TooltipTranslationController.resolveRenderContext();
        boolean requestAllowed = TooltipTranslationController.allowRequest(context, components);
        List<Component> translated = TooltipTranslationController.translateForRender(components, context, requestAllowed);
        GuiGraphics graphics = new GuiGraphics(poseStack);

        if (translated == components) {
            if (TooltipTranslationController.shouldRenderPendingOriginal(components, context, requestAllowed)) {
                List<Component> splitOriginal = TooltipTranslationHelper.splitHoverComponentsLinesForRender(components);
                if (splitOriginal != components) {
                    simple_translate$renderWithGuard(graphics,
                            g -> g.renderTooltip(Minecraft.getInstance().font, splitOriginal, image, mouseX, mouseY), ci);
                }
            }
            return;
        }
        simple_translate$renderWithGuard(graphics,
                g -> g.renderTooltip(Minecraft.getInstance().font, translated, image, mouseX, mouseY), ci);
    }

    @Inject(method = "renderComponentTooltip", at = @At("HEAD"), cancellable = true)
    private void simple_translate$onRenderComponentTooltip(PoseStack poseStack, List<Component> components,
                                                           int mouseX, int mouseY, CallbackInfo ci) {
        if (!TooltipTranslationController.shouldTranslateRenderedTooltip(components)) {
            return;
        }
        TooltipTranslationController.RenderContext context = TooltipTranslationController.resolveRenderContext();
        boolean requestAllowed = TooltipTranslationController.allowRequest(context, components);
        List<Component> translated = TooltipTranslationController.translateForRender(components, context, requestAllowed);
        GuiGraphics graphics = new GuiGraphics(poseStack);

        if (translated == components) {
            if (TooltipTranslationController.shouldRenderPendingOriginal(components, context, requestAllowed)) {
                List<Component> splitOriginal = TooltipTranslationHelper.splitHoverComponentsLinesForRender(components);
                if (splitOriginal != components) {
                    simple_translate$renderWithGuard(graphics,
                            g -> g.renderComponentTooltip(Minecraft.getInstance().font, splitOriginal, mouseX, mouseY), ci);
                }
            }
            return;
        }
        simple_translate$renderWithGuard(graphics,
                g -> g.renderComponentTooltip(Minecraft.getInstance().font, translated, mouseX, mouseY), ci);
    }

    @Inject(
            method = "renderTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;II)V",
            at = @At("HEAD"), cancellable = true)
    private void simple_translate$onRenderTooltipComponent(PoseStack poseStack, Component component, int mouseX, int mouseY,
                                                           CallbackInfo ci) {
        if (component == null || TooltipTranslationController.isRenderingTranslated()) {
            return;
        }
        if (TooltipTranslationHelper.isMarkedTranslatedTooltip(component)) {
            return;
        }
        if (!TooltipTranslationController.shouldTranslateRenderedTooltip(List.of(component))) {
            return;
        }
        TooltipTranslationController.RenderContext context = TooltipTranslationController.resolveRenderContext();
        List<Component> source = List.of(component);
        boolean requestAllowed = TooltipTranslationController.allowRequest(context, source);
        List<Component> translated = TooltipTranslationController.translateForRender(source, context, requestAllowed);
        GuiGraphics graphics = new GuiGraphics(poseStack);

        if (translated.isEmpty() || (translated.size() == 1 && translated.get(0) == component)) {
            if (TooltipTranslationController.shouldRenderPendingOriginal(source, context, requestAllowed)) {
                List<Component> splitOriginal = TooltipTranslationHelper.splitHoverComponentLinesForRender(component);
                if (splitOriginal.size() != 1 || splitOriginal.get(0) != component) {
                    simple_translate$renderWithGuard(graphics,
                            g -> g.renderTooltip(Minecraft.getInstance().font, splitOriginal, Optional.empty(), mouseX, mouseY), ci);
                }
            }
            return;
        }
        simple_translate$renderWithGuard(graphics, g -> {
            if (translated.size() == 1) {
                g.renderTooltip(Minecraft.getInstance().font, translated.get(0), mouseX, mouseY);
            } else {
                g.renderTooltip(Minecraft.getInstance().font, translated, Optional.empty(), mouseX, mouseY);
            }
        }, ci);
    }

    @Unique
    private void simple_translate$renderWithGuard(GuiGraphics guiGraphics, Consumer<GuiGraphics> renderer, CallbackInfo ci) {
        TooltipTranslationController.beginRenderingTranslated();
        try {
            renderer.accept(guiGraphics);
        } finally {
            TooltipTranslationController.endRenderingTranslated();
        }
        ci.cancel();
    }

    @Unique
    private static boolean simple_translate$shouldTranslateBookHover() {
        Screen screen = Minecraft.getInstance().screen;
        return screen instanceof BookViewScreen
                && ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER);
    }
}
