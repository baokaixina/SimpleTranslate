package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.gui.GuiTranslationController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact optional target: Tips 1.12.2 1.0.7, TipsAPI#renderTip()V. */
@Pseudo
@Mixin(targets = "net.darkhax.tips.TipsAPI", remap = false)
public abstract class TipsOverlayMixin {
    @Inject(method = "renderTip()V", at = @At("HEAD"), remap = false)
    private static void simpletranslate$beginTipsOverlay(CallbackInfo ci) {
        GuiTranslationController.beginTipsOverlay();
    }

    @Inject(method = "renderTip()V", at = @At("RETURN"), remap = false)
    private static void simpletranslate$endTipsOverlay(CallbackInfo ci) {
        GuiTranslationController.endTipsOverlay();
    }
}
