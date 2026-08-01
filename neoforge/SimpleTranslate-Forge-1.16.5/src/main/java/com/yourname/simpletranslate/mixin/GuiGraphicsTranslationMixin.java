package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.yourname.simpletranslate.core.render.GuiGraphics;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.feature.gui.GuiLayoutProgramRenderer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.IReorderingProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.19.4 immediate-render text hooks for the shared GUI ITextComponent JSON frame.
 *
 * <p>Minecraft 1.19.4 has no GuiGraphics: screen and widget text flows through
 * the static {@link AbstractGui} draw helpers, while HUD and direct-font text
 * flows through {@code FontRenderer.draw/drawShadow} (covered by
 * {@link FontDrawTranslationMixin}). The scope contract is unchanged: enter at
 * HEAD, leave at RETURN, only the outermost call translates.</p>
 */
@Mixin(AbstractGui.class)
public class GuiGraphicsTranslationMixin {
    @Inject(
            method = "drawString(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/gui/FontRenderer;Lnet/minecraft/util/text/ITextComponent;III)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void simple_translate$replayLayoutProgram(
            MatrixStack poseStack, FontRenderer font, ITextComponent component, int x, int y, int color,
            CallbackInfo ci) {
        if (!GuiTranslationHelper.isActive() || GuiLayoutProgramRenderer.isReplaying()
                || component == null || !GuiLayoutProgramRenderer.isLayoutProgram(component)) {
            return;
        }
        ITextComponent translated = GuiTranslationHelper.translateVisible(component);
        if (GuiLayoutProgramRenderer.renderText(GuiGraphics.wrap(poseStack), font, component,
                translated, x, y, color, true)) {
            // HEAD injector ordering relative to ModifyVariable is not stable.
            // If the variable hook entered first, cancellation bypasses RETURN.
            GuiTranslationHelper.leaveDirectDrawIfTop("draw.component");
            ci.cancel();
        }
    }

    @Inject(
            method = "drawCenteredString(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/gui/FontRenderer;Lnet/minecraft/util/text/ITextComponent;III)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void simple_translate$replayCenteredLayoutProgram(
            MatrixStack poseStack, FontRenderer font, ITextComponent component, int centerX, int y, int color,
            CallbackInfo ci) {
        if (!GuiTranslationHelper.isActive() || GuiLayoutProgramRenderer.isReplaying()
                || component == null || !GuiLayoutProgramRenderer.isLayoutProgram(component)) {
            return;
        }
        ITextComponent translated = GuiTranslationHelper.translateVisible(component);
        if (GuiLayoutProgramRenderer.renderCenteredText(GuiGraphics.wrap(poseStack), font, component,
                translated, centerX, y, color)) {
            GuiTranslationHelper.leaveDirectDrawIfTop("centered.component");
            ci.cancel();
        }
    }

    @ModifyVariable(
            method = "drawString(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/gui/FontRenderer;Lnet/minecraft/util/text/ITextComponent;III)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static ITextComponent simple_translate$translateComponent(ITextComponent component) {
        boolean outermost = GuiTranslationHelper.enterDirectDraw("draw.component");
        return outermost && !GuiLayoutProgramRenderer.isReplaying()
                && !GuiLayoutProgramRenderer.isLayoutProgram(component)
                ? GuiTranslationHelper.translateVisible(component) : component;
    }

    @ModifyVariable(
            method = "drawString(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static String simple_translate$translateString(String text) {
        return GuiTranslationHelper.enterDirectDraw("draw.string")
                ? GuiTranslationHelper.translatePlainText(text).getString() : text;
    }

    @ModifyVariable(
            method = "drawCenteredString(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/gui/FontRenderer;Lnet/minecraft/util/text/ITextComponent;III)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static ITextComponent simple_translate$translateCentered(ITextComponent component) {
        boolean outermost = GuiTranslationHelper.enterDirectDraw("centered.component");
        return outermost && !GuiLayoutProgramRenderer.isReplaying()
                && !GuiLayoutProgramRenderer.isLayoutProgram(component)
                ? GuiTranslationHelper.translateVisible(component) : component;
    }

    @Inject(method = {
                    "drawString(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/gui/FontRenderer;Lnet/minecraft/util/text/ITextComponent;III)V",
                    "drawString(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;III)V"
            }, at = @At("RETURN"), require = 2)
    private static void simple_translate$leaveDraw(CallbackInfo ci) {
        GuiTranslationHelper.leaveDirectDraw();
    }

    @Inject(
            method = "drawCenteredString(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/gui/FontRenderer;Lnet/minecraft/util/text/ITextComponent;III)V",
            at = @At("RETURN"), require = 1)
    private static void simple_translate$leaveVoidDraw(CallbackInfo ci) {
        GuiTranslationHelper.leaveDirectDraw();
    }
}
