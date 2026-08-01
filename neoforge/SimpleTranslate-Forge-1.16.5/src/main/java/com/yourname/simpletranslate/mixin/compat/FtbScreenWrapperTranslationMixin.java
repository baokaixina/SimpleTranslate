package com.yourname.simpletranslate.mixin.compat;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.yourname.simpletranslate.core.render.GuiGraphics;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional terminal bridge for FTB Library's Screen method overrides.
 *
 * <p>Verified via {@code javap -p -s} against the exact Forge jar
 * {@code ftb-library-forge-1605.3.5-build.724.jar} (newest 1.16.5-series FTB
 * Library Forge build on maven.ftb.dev, archived in .analysis/optional-1.16.5).
 * Forge 1.16.5 production jars are reobfuscated to func-style SRG names, so
 * ScreenWrapper's vanilla overrides are {@code func_231046_a_(III)Z}
 * (Screen#keyPressed) and
 * {@code func_230430_a_(Lcom/mojang/blaze3d/matrix/MatrixStack;IIF)V}
 * (Screen#render), confirmed both in the FTB jar bytecode and in the
 * createMcpToSrg mapping table for this exact Forge build.
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.ui.ScreenWrapper", remap = false)
public abstract class FtbScreenWrapperTranslationMixin {
    @Unique
    private boolean simple_translate$ownsTranslationFrame;

    @Inject(method = "func_231046_a_(III)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void simple_translate$translateGuiShortcut(
            int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (ModKeyBindings.handleTranslateGuiKey(keyCode, scanCode)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "func_230430_a_(Lcom/mojang/blaze3d/matrix/MatrixStack;IIF)V",
            at = @At("HEAD"), require = 1, remap = false)
    private void simple_translate$beginFtbFrame(
            MatrixStack poseStack, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        boolean alreadyActive = GuiTranslationHelper.isActive();
        if (!alreadyActive) {
            GuiTranslationHelper.beginFrame((Screen) (Object) this);
        }
        simple_translate$ownsTranslationFrame = !alreadyActive && GuiTranslationHelper.isActive();
    }

    @Inject(method = "func_230430_a_(Lcom/mojang/blaze3d/matrix/MatrixStack;IIF)V",
            at = @At("RETURN"), require = 1, remap = false)
    private void simple_translate$endFtbFrame(
            MatrixStack poseStack, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        if (simple_translate$ownsTranslationFrame) {
            simple_translate$ownsTranslationFrame = false;
            GuiTranslationHelper.endFrame(GuiGraphics.wrap(poseStack));
        }
    }
}
