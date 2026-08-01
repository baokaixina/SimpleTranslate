package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.feature.chat.ChatTranslationController;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Shadow
    protected EditBox input;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void simple_translate$onKeyPressed(
            int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        // GLFW_KEY_ENTER (257) and GLFW_KEY_KP_ENTER (335) are the chat
        // confirmation keys; KeyEvent.isConfirmation() is 1.21.x-only.
        if ((keyCode != 257 && keyCode != 335) || !Screen.hasControlDown()) {
            return;
        }

        ChatScreen screen = (ChatScreen) (Object) this;
        if (ChatTranslationController.tryTranslateOutgoingMessage(
                screen, this.input.getValue(), () -> this.input.getValue())) {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }
}
