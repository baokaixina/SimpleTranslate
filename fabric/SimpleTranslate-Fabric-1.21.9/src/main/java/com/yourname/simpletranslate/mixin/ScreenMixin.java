package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.gui.OcrOverlayScreen;
import com.yourname.simpletranslate.gui.OcrOverlayManager;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void simpletranslate$openOcrFromAnyScreen(KeyEvent event,
                                                     CallbackInfoReturnable<Boolean> cir) {
        Screen self = (Screen) (Object) this;
        if (self instanceof OcrOverlayScreen || !ModKeyBindings.matchesOcrToggleKey(event)) {
            return;
        }
        OcrOverlayManager.toggleForScreen(Minecraft.getInstance(), self);
        cir.setReturnValue(true);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void simpletranslate$renderOcrOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                  CallbackInfo ci) {
        OcrOverlayManager.render((Screen) (Object) this, graphics, mouseX, mouseY, partialTick);
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void simpletranslate$closeOcrOverlayWithScreen(CallbackInfo ci) {
        OcrOverlayManager.closeIfAttached((Screen) (Object) this);
    }
}
