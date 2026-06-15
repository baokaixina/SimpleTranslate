package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.gui.OcrOverlayScreen;
import com.yourname.simpletranslate.gui.OcrOverlayManager;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import net.minecraft.client.Minecraft;
import com.yourname.simpletranslate.compat.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "m_7933_(III)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void simpletranslate$openOcrFromAnyScreen(int keyCode, int scanCode, int modifiers,
                                                     CallbackInfoReturnable<Boolean> cir) {
        Screen self = (Screen) (Object) this;
        if (self instanceof OcrOverlayScreen || !ModKeyBindings.matchesOcrToggleKey(keyCode, scanCode)) {
            return;
        }
        OcrOverlayManager.toggleForScreen(Minecraft.getInstance(), self);
        cir.setReturnValue(true);
    }

    @Inject(method = "m_6305_(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("TAIL"), remap = false)
    private void simpletranslate$renderOcrOverlay(PoseStack poseStack, int mouseX, int mouseY, float partialTick,
                                                  CallbackInfo ci) {
        GuiGraphics graphics = new GuiGraphics(poseStack);
        OcrOverlayManager.render((Screen) (Object) this, graphics, mouseX, mouseY, partialTick);
    }

    @Inject(method = "m_7861_()V", at = @At("TAIL"), remap = false)
    private void simpletranslate$closeOcrOverlayWithScreen(CallbackInfo ci) {
        OcrOverlayManager.closeIfAttached((Screen) (Object) this);
    }
}

