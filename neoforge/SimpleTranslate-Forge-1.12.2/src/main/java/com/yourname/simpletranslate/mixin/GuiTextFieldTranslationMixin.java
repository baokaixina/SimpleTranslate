package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.gui.GuiTranslationController;
import net.minecraft.client.gui.GuiTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps editable text, including credentials and addresses, out of whole-frame GUI requests. */
@Mixin(GuiTextField.class)
public abstract class GuiTextFieldTranslationMixin {
    @Inject(method = "drawTextBox()V", at = @At("HEAD"))
    private void simpletranslate$beginTextInputDraw(CallbackInfo callback) {
        GuiTranslationController.beginTextInput();
    }

    @Inject(method = "drawTextBox()V", at = @At("RETURN"))
    private void simpletranslate$endTextInputDraw(CallbackInfo callback) {
        GuiTranslationController.endTextInput();
    }
}
