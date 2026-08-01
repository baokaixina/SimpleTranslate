package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.gui.GuiTranslationController;
import com.yourname.simpletranslate.compat.FtbTextInputState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FTBLib 5.4.7.2 evidence: TextBox#draw(Theme,IIII)V renders its private
 * editable text field. Keep that render window outside whole-frame GUI
 * collection so quest search/input text is never sent for translation.
 */
@Pseudo
@Mixin(targets = "com.feed_the_beast.ftblib.lib.gui.TextBox", remap = false)
public abstract class FtbTextBoxMixin {
    @Inject(method = "setFocused(Z)V", at = @At("RETURN"), remap = false)
    private void simpletranslate$trackFocus(boolean focused, CallbackInfo callback) {
        FtbTextInputState.setFocused(this, focused);
    }

    @Inject(method = "onClosed()V", at = @At("HEAD"), remap = false)
    private void simpletranslate$clearFocus(CallbackInfo callback) {
        FtbTextInputState.setFocused(this, false);
    }

    @Inject(method = "draw(Lcom/feed_the_beast/ftblib/lib/gui/Theme;IIII)V", at = @At("HEAD"), remap = false)
    private void simpletranslate$beginTextInput(CallbackInfo callback) {
        GuiTranslationController.beginTextInput();
    }

    @Inject(method = "draw(Lcom/feed_the_beast/ftblib/lib/gui/Theme;IIII)V", at = @At("RETURN"), remap = false)
    private void simpletranslate$endTextInput(CallbackInfo callback) {
        GuiTranslationController.endTextInput();
    }
}
