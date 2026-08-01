package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.feature.chat.ChatTranslationController;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
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
    private void simple_translate$onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!event.isConfirmation() || !event.hasControlDown()) {
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
