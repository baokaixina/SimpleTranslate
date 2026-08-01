package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.chat.OutgoingChatTranslator;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact 1.12.2 target: GuiChat#keyTyped(CI)V; only Ctrl+Enter is consumed. */
@Mixin(GuiChat.class)
public abstract class GuiChatOutgoingMixin {
    @Shadow protected GuiTextField inputField;

    @Inject(method = "keyTyped(CI)V", at = @At("HEAD"), cancellable = true)
    private void simpletranslate$translateOutgoingChat(char typedChar, int keyCode, CallbackInfo callback) {
        if ((keyCode == 28 || keyCode == 156) && GuiScreen.isCtrlKeyDown()
                && OutgoingChatTranslator.tryTranslate((GuiChat) (Object) this,
                inputField == null ? "" : inputField.getText())) {
            callback.cancel();
        }
    }
}
