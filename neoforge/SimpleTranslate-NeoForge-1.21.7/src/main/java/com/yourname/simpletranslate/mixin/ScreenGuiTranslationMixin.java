package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defines the immediate-render frame that collects one visible GUI document.
 *
 * <p>Minecraft 1.21.8 ends {@code Screen.renderWithTooltip} with
 * {@code GuiGraphics.renderDeferredTooltip()} (the deferred-tooltip flush; the
 * 1.21.9 {@code renderWithTooltipAndSubtitles}/{@code renderDeferredElements}
 * names do not exist yet).</p>
 */
@Mixin(Screen.class)
public class ScreenGuiTranslationMixin {
    @Inject(method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("HEAD"), require = 1)
    private void simple_translate$beginGuiFrame(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        GuiTranslationHelper.beginFrame((Screen) (Object) this);
    }

    @Inject(method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;renderDeferredTooltip()V",
                    shift = At.Shift.BEFORE), require = 1)
    private void simple_translate$beforeDeferredElements(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        GuiTranslationHelper.beforeDeferredElements(graphics);
    }

    @Inject(method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("RETURN"), require = 1)
    private void simple_translate$endGuiFrame(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        GuiTranslationHelper.endFrame(graphics);
    }
}
