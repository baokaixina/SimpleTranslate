package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.gui.OcrOverlayManager;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void simpletranslate$handleOcrOverlayKeys(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (OcrOverlayManager.keyboardEvent(window, action, event)) {
            ci.cancel();
        }
    }
}
