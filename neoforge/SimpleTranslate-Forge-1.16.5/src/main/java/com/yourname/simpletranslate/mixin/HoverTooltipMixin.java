package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.render.GuiGraphics;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationController;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationGlowRenderer;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ReadBookScreen;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraft.util.text.Style;
import net.minecraft.util.IReorderingProcessor;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Consumer;

/** Exact 1.16.5 Screen tooltip hooks. */
@Mixin(Screen.class)
public class HoverTooltipMixin {
    @Unique private boolean simple_translate$itemFrameStarted;

    @Inject(method = "renderComponentHoverEffect", at = @At("HEAD"), cancellable = true, require = 1)
    private void onRenderComponentHoverEffect(MatrixStack poseStack, Style style, int mouseX, int mouseY,
                                              CallbackInfo ci) {
        if (style == null || HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER)) {
            return;
        }
        HoverEvent hoverEvent = style.getHoverEvent();
        if (hoverEvent == null || hoverEvent.getAction() != HoverEvent.Action.SHOW_TEXT) {
            return;
        }
        ITextComponent hoverText = hoverEvent.getValue(HoverEvent.Action.SHOW_TEXT);
        if (!TooltipTranslationController.shouldTranslateChatHover(hoverText) && !shouldTranslateBookHover()) {
            return;
        }
        TooltipTranslationController.RenderContext context = TooltipTranslationController.resolveRenderContext();
        boolean requestAllowed = TooltipTranslationController.allowRequest(context, List.of(hoverText));
        List<ITextComponent> translatedLines = TooltipTranslationHelper.translateHoverComponentLines(hoverText, requestAllowed);
        Screen screen = (Screen) (Object) this;
        if (translatedLines.size() == 1 && translatedLines.get(0) == hoverText) {
            if (requestAllowed || TooltipTranslationHelper.isHoverTranslationPending(hoverText)) {
                TooltipTranslationController.beginRenderingTranslated();
                try {
                    TooltipTranslationController.armPendingGlowForHover(hoverText, requestAllowed);
                    screen.renderComponentTooltip(poseStack,
                            TooltipTranslationHelper.splitHoverComponentLinesForRender(hoverText), mouseX, mouseY);
                } finally {
                    TooltipTranslationController.endRenderingTranslated();
                }
                ci.cancel();
            }
            return;
        }
        renderTranslatedTooltip(poseStack,
                stack -> screen.renderComponentTooltip(stack, translatedLines, mouseX, mouseY), ci);
    }

    @Inject(method = "renderTooltip(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/item/ItemStack;II)V",
            at = @At("HEAD"), require = 1)
    private void simple_translate$beginItemStackSubmission(MatrixStack poseStack, ItemStack stack,
                                                            int mouseX, int mouseY, CallbackInfo ci) {
        TooltipTranslationController.beginItemTooltipSubmission();
    }

    @Inject(method = "renderTooltip(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/item/ItemStack;II)V",
            at = @At("RETURN"), require = 1)
    private void simple_translate$endItemStackSubmission(MatrixStack poseStack, ItemStack stack,
                                                          int mouseX, int mouseY, CallbackInfo ci) {
        TooltipTranslationController.endItemTooltipSubmission();
    }

    /** Opens the shared frame while vanilla turns ITextComponent rows into visual rows. */
    @Inject(method = "renderComponentTooltip(Lcom/mojang/blaze3d/matrix/MatrixStack;Ljava/util/List;II)V",
            at = @At("HEAD"), require = 1)
    private void simple_translate$beginItemFrame(MatrixStack poseStack, List<ITextComponent> rows,
                                                  int mouseX, int mouseY, CallbackInfo ci) {
        if (!TooltipTranslationController.isItemTooltipSubmission()
                || rows == null || rows.isEmpty()
                || !TooltipTranslationController.shouldCaptureItemFrame(rows)) {
            return;
        }
        String frameKey = GuiTranslationHelper.detachedFrameKey("gui.item_tooltip", rows);
        boolean request = TooltipTranslationController.allowRequest(
                TooltipTranslationController.RenderContext.ITEM, rows);
        simple_translate$itemFrameStarted = GuiTranslationHelper.beginItemTooltipFrame(
                frameKey, "Item tooltip", request, rows);
        if (simple_translate$itemFrameStarted) {
            TooltipTranslationController.armPendingGlowIf(
                    request || GuiTranslationHelper.isFrameTranslationPending(frameKey));
        }
    }

    @Inject(method = "renderComponentTooltip(Lcom/mojang/blaze3d/matrix/MatrixStack;Ljava/util/List;II)V",
            at = @At("RETURN"), require = 1)
    private void simple_translate$endItemFrame(MatrixStack poseStack, List<ITextComponent> rows,
                                                int mouseX, int mouseY, CallbackInfo ci) {
        if (simple_translate$itemFrameStarted) {
            GuiTranslationHelper.endDetachedFrame(GuiGraphics.wrap(poseStack));
            simple_translate$itemFrameStarted = false;
        }
    }

    @Inject(method = "renderTooltip(Lcom/mojang/blaze3d/matrix/MatrixStack;Ljava/util/List;II)V",
            at = @At("TAIL"), require = 1)
    private void simple_translate$renderPendingTranslationGlow(
            MatrixStack poseStack, List<? extends IReorderingProcessor> rows,
            int mouseX, int mouseY, CallbackInfo ci) {
        if (TooltipTranslationController.consumePendingGlow()) {
            TooltipTranslationGlowRenderer.render(GuiGraphics.wrap(poseStack), (Screen) (Object) this,
                    Minecraft.getInstance().font, rows, mouseX, mouseY);
        }
    }

    private void renderTranslatedTooltip(MatrixStack poseStack, Consumer<MatrixStack> renderer, CallbackInfo ci) {
        TooltipTranslationController.beginRenderingTranslated();
        try {
            renderer.accept(poseStack);
        } finally {
            TooltipTranslationController.endRenderingTranslated();
        }
        ci.cancel();
    }

    @Unique
    private static boolean shouldTranslateBookHover() {
        Screen screen = Minecraft.getInstance().screen;
        return ModConfig.GLOBAL_ENABLED.get()
                && screen instanceof ReadBookScreen
                && ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER);
    }
}
