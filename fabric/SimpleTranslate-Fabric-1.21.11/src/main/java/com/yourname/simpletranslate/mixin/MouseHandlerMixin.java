package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.gui.OcrOverlayManager;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "method_22684(JIII)V", at = @At("HEAD"), cancellable = true)
    private void simpletranslate$handleOcrOverlayPress(long window, int button, int action, int modifiers,
                                                      CallbackInfo ci) {
        if (OcrOverlayManager.mousePress(window, button, action)) {
            ci.cancel();
        }
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void simpletranslate$handleOcrOverlayMove(long window, double x, double y, CallbackInfo ci) {
        if (OcrOverlayManager.mouseMove(window, x, y)) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void simpletranslate$handleOcrOverlayScroll(long window, double scrollX, double scrollY,
                                                       CallbackInfo ci) {
        if (OcrOverlayManager.mouseScroll(window, scrollY)) {
            ci.cancel();
        }
    }
}
