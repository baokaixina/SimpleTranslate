package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.core.render.GuiGraphics;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.feature.gui.GuiLayoutProgramRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.19.4 direct-font text hooks for the shared GUI Component JSON frame.
 *
 * <p>On 1.19.4 the HUD (titles, actionbar, boss bar, chat, scoreboard) and
 * widgets such as EditBox draw text straight through
 * {@code Font.draw/drawShadow} instead of the {@link net.minecraft.client.gui.GuiComponent}
 * statics, so the K frame needs the same enter/leave scope and translation
 * behavior here as on the GuiComponent path.</p>
 */
@Mixin(Font.class)
public class FontDrawTranslationMixin {
    @Inject(
            method = "drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void simple_translate$replayShadowLayoutProgram(
            PoseStack poseStack, Component component, float x, float y, int color,
            CallbackInfoReturnable<Integer> cir) {
        if (!GuiTranslationHelper.isActive() || GuiLayoutProgramRenderer.isReplaying()
                || component == null || !GuiLayoutProgramRenderer.isLayoutProgram(component)) {
            return;
        }
        Component translated = GuiTranslationHelper.translateVisible(component);
        if (GuiLayoutProgramRenderer.renderText(GuiGraphics.wrap(poseStack), (Font) (Object) this,
                component, translated, x, y, color, true)) {
            GuiTranslationHelper.leaveDirectDrawIfTop("draw.component");
            cir.setReturnValue(0);
        }
    }

    @Inject(
            method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void simple_translate$replayLayoutProgram(
            PoseStack poseStack, Component component, float x, float y, int color,
            CallbackInfoReturnable<Integer> cir) {
        if (!GuiTranslationHelper.isActive() || GuiLayoutProgramRenderer.isReplaying()
                || component == null || !GuiLayoutProgramRenderer.isLayoutProgram(component)) {
            return;
        }
        Component translated = GuiTranslationHelper.translateVisible(component);
        if (GuiLayoutProgramRenderer.renderText(GuiGraphics.wrap(poseStack), (Font) (Object) this,
                component, translated, x, y, color, false)) {
            GuiTranslationHelper.leaveDirectDrawIfTop("draw.component");
            cir.setReturnValue(0);
        }
    }

    @ModifyVariable(
            method = "drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private Component simple_translate$translateShadowComponent(Component component) {
        boolean outermost = GuiTranslationHelper.enterDirectDraw("draw.component");
        return outermost && !GuiLayoutProgramRenderer.isReplaying()
                && !GuiLayoutProgramRenderer.isLayoutProgram(component)
                ? GuiTranslationHelper.translateVisible(component) : component;
    }

    @ModifyVariable(
            method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private Component simple_translate$translateComponent(Component component) {
        boolean outermost = GuiTranslationHelper.enterDirectDraw("draw.component");
        return outermost && !GuiLayoutProgramRenderer.isReplaying()
                && !GuiLayoutProgramRenderer.isLayoutProgram(component)
                ? GuiTranslationHelper.translateVisible(component) : component;
    }

    @ModifyVariable(
            method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/lang/String;FFI)I",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private String simple_translate$translateString(String text) {
        return GuiTranslationHelper.enterDirectDraw("draw.string")
                ? GuiTranslationHelper.translatePlainText(text).getString() : text;
    }

    @ModifyVariable(
            method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/util/FormattedCharSequence;FFI)I",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private FormattedCharSequence simple_translate$translateVisual(FormattedCharSequence text) {
        return GuiTranslationHelper.enterDirectDraw("draw.visual")
                ? GuiTranslationHelper.translateFormattedSequence(text) : text;
    }

    @Inject(method = {
                    "drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
                    "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
                    "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/lang/String;FFI)I",
                    "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/util/FormattedCharSequence;FFI)I"
            }, at = @At("RETURN"), require = 4)
    private void simple_translate$leaveDraw(CallbackInfoReturnable<Integer> cir) {
        GuiTranslationHelper.leaveDirectDraw();
    }
}
