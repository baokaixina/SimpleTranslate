package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.core.render.GuiGraphics;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defines the immediate-render frame that collects one visible GUI document.
 *
 * <p>Minecraft 1.19.4 has no renderWithTooltip split: the deferred tooltip is
 * flushed at the end of {@code Screen.render(PoseStack, int, int, float)}, so
 * the frame wraps that exact method.</p>
 */
@Mixin(Screen.class)
public class ScreenGuiTranslationMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V",
            at = @At("HEAD"), require = 1)
    private void simple_translate$beginGuiFrame(
            PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        GuiTranslationHelper.beginFrame((Screen) (Object) this);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V",
            at = @At("RETURN"), require = 1)
    private void simple_translate$endGuiFrame(
            PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        GuiTranslationHelper.endFrame(GuiGraphics.wrap(poseStack));
    }
}
