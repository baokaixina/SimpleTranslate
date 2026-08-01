package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.feature.gui.GuiLayoutProgramRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.21.11 immediate-render text hooks for the shared GUI Component JSON frame. */
@Mixin(GuiGraphics.class)
public class GuiGraphicsTranslationMixin {
    @Inject(
            method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void simple_translate$replayLayoutProgram(
            Font font, Component component, int x, int y, int color, boolean shadow,
            CallbackInfo ci) {
        if (!GuiTranslationHelper.isActive() || GuiLayoutProgramRenderer.isReplaying()
                || component == null || !GuiLayoutProgramRenderer.isLayoutProgram(component)) {
            return;
        }
        Component translated = GuiTranslationHelper.translateVisible(component);
        if (GuiLayoutProgramRenderer.renderText((GuiGraphics) (Object) this, font, component,
                translated, x, y, color, shadow)) {
            // HEAD injector ordering relative to ModifyVariable is not stable.
            // If the variable hook entered first, cancellation bypasses RETURN.
            GuiTranslationHelper.leaveDirectDrawIfTop("draw.component");
            ci.cancel();
        }
    }

    @Inject(
            method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void simple_translate$replayCenteredLayoutProgram(
            Font font, Component component, int centerX, int y, int color, CallbackInfo ci) {
        if (!GuiTranslationHelper.isActive() || GuiLayoutProgramRenderer.isReplaying()
                || component == null || !GuiLayoutProgramRenderer.isLayoutProgram(component)) {
            return;
        }
        Component translated = GuiTranslationHelper.translateVisible(component);
        if (GuiLayoutProgramRenderer.renderCenteredText((GuiGraphics) (Object) this, font, component,
                translated, centerX, y, color)) {
            GuiTranslationHelper.leaveDirectDrawIfTop("centered.component");
            ci.cancel();
        }
    }

    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private Component simple_translate$translateComponent(Component component) {
        boolean outermost = GuiTranslationHelper.enterDirectDraw("draw.component");
        return outermost && !GuiLayoutProgramRenderer.isReplaying()
                && !GuiLayoutProgramRenderer.isLayoutProgram(component)
                ? GuiTranslationHelper.translateVisible(component) : component;
    }

    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private String simple_translate$translateString(String text) {
        return GuiTranslationHelper.enterDirectDraw("draw.string")
                ? GuiTranslationHelper.translatePlainText(text).getString() : text;
    }

    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private FormattedCharSequence simple_translate$translateVisual(FormattedCharSequence text) {
        return GuiTranslationHelper.enterDirectDraw("draw.visual")
                ? GuiTranslationHelper.translateFormattedSequence(text) : text;
    }

    /**
     * Bookshelf (and many other mod libraries) uses this no-shadow overload
     * directly. Capture it before its forwarding call so visual-only mod text
     * always enters the same Component JSON GUI frame.
     */
    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private FormattedCharSequence simple_translate$translateVisualWithoutShadow(FormattedCharSequence text) {
        return GuiTranslationHelper.enterDirectDraw("draw.visual.no_shadow")
                ? GuiTranslationHelper.translateFormattedSequence(text) : text;
    }

    @ModifyVariable(
            method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private Component simple_translate$translateCentered(Component component) {
        boolean outermost = GuiTranslationHelper.enterDirectDraw("centered.component");
        return outermost && !GuiLayoutProgramRenderer.isReplaying()
                && !GuiLayoutProgramRenderer.isLayoutProgram(component)
                ? GuiTranslationHelper.translateVisible(component) : component;
    }

    @ModifyVariable(
            method = "drawWordWrap(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/FormattedText;IIII)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private FormattedText simple_translate$translateWrapped(FormattedText text) {
        return GuiTranslationHelper.enterDirectDraw("wordwrap.formatted")
                ? GuiTranslationHelper.translateFormattedText(text) : text;
    }

    @Inject(method = {
                    "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
                    "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
                    "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
                    "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V"
            }, at = @At("RETURN"), require = 4)
    private void simple_translate$leaveDraw(CallbackInfo ci) {
        GuiTranslationHelper.leaveDirectDraw();
    }

    @Inject(method = {
                    "drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
                    "drawWordWrap(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/FormattedText;IIII)V"
            }, at = @At("RETURN"), require = 2)
    private void simple_translate$leaveVoidDraw(CallbackInfo ci) {
        GuiTranslationHelper.leaveDirectDraw();
    }
}
