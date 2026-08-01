package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.core.render.GuiGraphics;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.feature.gui.GuiLayoutProgramRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.19.4 immediate-render text hooks for the shared GUI Component JSON frame.
 *
 * <p>Minecraft 1.19.4 has no GuiGraphics: screen and widget text flows through
 * the static {@link GuiComponent} draw helpers, while HUD and direct-font text
 * flows through {@code Font.draw/drawShadow} (covered by
 * {@link FontDrawTranslationMixin}). The scope contract is unchanged: enter at
 * HEAD, leave at RETURN, only the outermost call translates.</p>
 */
@Mixin(GuiComponent.class)
public class GuiGraphicsTranslationMixin {
    @Inject(
            method = "drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void simple_translate$replayLayoutProgram(
            PoseStack poseStack, Font font, Component component, int x, int y, int color,
            CallbackInfo ci) {
        if (!GuiTranslationHelper.isActive() || GuiLayoutProgramRenderer.isReplaying()
                || component == null || !GuiLayoutProgramRenderer.isLayoutProgram(component)) {
            return;
        }
        Component translated = GuiTranslationHelper.translateVisible(component);
        if (GuiLayoutProgramRenderer.renderText(GuiGraphics.wrap(poseStack), font, component,
                translated, x, y, color, true)) {
            // HEAD injector ordering relative to ModifyVariable is not stable.
            // If the variable hook entered first, cancellation bypasses RETURN.
            GuiTranslationHelper.leaveDirectDrawIfTop("draw.component");
            ci.cancel();
        }
    }

    @Inject(
            method = "drawCenteredString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void simple_translate$replayCenteredLayoutProgram(
            PoseStack poseStack, Font font, Component component, int centerX, int y, int color,
            CallbackInfo ci) {
        if (!GuiTranslationHelper.isActive() || GuiLayoutProgramRenderer.isReplaying()
                || component == null || !GuiLayoutProgramRenderer.isLayoutProgram(component)) {
            return;
        }
        Component translated = GuiTranslationHelper.translateVisible(component);
        if (GuiLayoutProgramRenderer.renderCenteredText(GuiGraphics.wrap(poseStack), font, component,
                translated, centerX, y, color)) {
            GuiTranslationHelper.leaveDirectDrawIfTop("centered.component");
            ci.cancel();
        }
    }

    @ModifyVariable(
            method = "drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static Component simple_translate$translateComponent(Component component) {
        boolean outermost = GuiTranslationHelper.enterDirectDraw("draw.component");
        return outermost && !GuiLayoutProgramRenderer.isReplaying()
                && !GuiLayoutProgramRenderer.isLayoutProgram(component)
                ? GuiTranslationHelper.translateVisible(component) : component;
    }

    @ModifyVariable(
            method = "drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static String simple_translate$translateString(String text) {
        return GuiTranslationHelper.enterDirectDraw("draw.string")
                ? GuiTranslationHelper.translatePlainText(text).getString() : text;
    }

    @ModifyVariable(
            method = "drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static FormattedCharSequence simple_translate$translateVisual(FormattedCharSequence text) {
        return GuiTranslationHelper.enterDirectDraw("draw.visual")
                ? GuiTranslationHelper.translateFormattedSequence(text) : text;
    }

    @ModifyVariable(
            method = "drawCenteredString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static Component simple_translate$translateCentered(Component component) {
        boolean outermost = GuiTranslationHelper.enterDirectDraw("centered.component");
        return outermost && !GuiLayoutProgramRenderer.isReplaying()
                && !GuiLayoutProgramRenderer.isLayoutProgram(component)
                ? GuiTranslationHelper.translateVisible(component) : component;
    }

    @Inject(method = {
                    "drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
                    "drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
                    "drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V"
            }, at = @At("RETURN"), require = 3)
    private static void simple_translate$leaveDraw(CallbackInfo ci) {
        GuiTranslationHelper.leaveDirectDraw();
    }

    @Inject(
            method = "drawCenteredString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("RETURN"), require = 1)
    private static void simple_translate$leaveVoidDraw(CallbackInfo ci) {
        GuiTranslationHelper.leaveDirectDraw();
    }
}
