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
 * <p>Verified via {@code javap -p -s} against the exact Forge jar
 * {@code ftb-library-forge-1802.3.12-build.726.jar}. Forge production jars are reobfuscated,
 * so ScreenWrapper's vanilla overrides carry SRG names: {@code m_7933_(III)Z}
 * is Screen#keyPressed and {@code m_6305_(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V}
 * is Screen#render (1.19.2 has no GuiGraphics; class names stay Mojmap at
 * runtime, only method/field names are SRG).
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.ui.ScreenWrapper", remap = false)
public abstract class FtbScreenWrapperTranslationMixin {
    @Unique
    private boolean simple_translate$ownsTranslationFrame;

    @Inject(method = "m_7933_(III)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void simple_translate$translateGuiShortcut(
            int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (ModKeyBindings.handleTranslateGuiKey(keyCode, scanCode)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "m_6305_(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V",
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

    @Inject(method = "m_6305_(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V",
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
