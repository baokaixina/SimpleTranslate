package com.yourname.simpletranslate.mixin.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.core.render.GuiGraphics;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import net.minecraft.client.gui.screens.Screen;
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
 * <p>Verified against ftb-library-fabric-1903.4.0-build.183.jar bytecode (the
 * newest obtainable 1.19.x-era series; declares {@code minecraft: ">=1.19"}):
 * ScreenWrapper ships intermediary-named overrides where {@code method_25404}
 * is Screen#keyPressed and {@code method_25394} is Screen#render taking
 * PoseStack (intermediary class_4587); no GuiGraphics exists on 1.19.x.</p>
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.ui.ScreenWrapper", remap = false)
public abstract class FtbScreenWrapperTranslationMixin {
    @Unique
    private boolean simple_translate$ownsTranslationFrame;

    @Inject(method = "method_25404(III)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void simple_translate$translateGuiShortcut(
            int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (ModKeyBindings.handleTranslateGuiKey(keyCode, scanCode)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "method_25394(Lnet/minecraft/class_4587;IIF)V",
            at = @At("HEAD"), require = 1, remap = false)
    private void simple_translate$beginFtbFrame(
            PoseStack poseStack, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        boolean alreadyActive = GuiTranslationHelper.isActive();
        if (!alreadyActive) {
            GuiTranslationHelper.beginFrame((Screen) (Object) this);
        }
        simple_translate$ownsTranslationFrame = !alreadyActive && GuiTranslationHelper.isActive();
    }

    @Inject(method = "method_25394(Lnet/minecraft/class_4587;IIF)V",
            at = @At("RETURN"), require = 1, remap = false)
    private void simple_translate$endFtbFrame(
            PoseStack poseStack, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        if (simple_translate$ownsTranslationFrame) {
            simple_translate$ownsTranslationFrame = false;
            GuiTranslationHelper.endFrame(GuiGraphics.wrap(poseStack));
        }
    }
}
