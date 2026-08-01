package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.chat.ChatTranslationController;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.event.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact 1.12.2 target: GuiScreen#handleComponentClick(ITextComponent)Z. */
@Mixin(GuiScreen.class)
public abstract class GuiScreenChatButtonMixin {
    @Inject(method = "handleComponentClick(Lnet/minecraft/util/text/ITextComponent;)Z", at = @At("HEAD"), cancellable = true)
    private void simpletranslate$handleChatButton(ITextComponent component, CallbackInfoReturnable<Boolean> callback) {
        if (component == null || component.getStyle() == null) return;
        ClickEvent event = component.getStyle().getClickEvent();
        if (event != null && event.getAction() == ClickEvent.Action.SUGGEST_COMMAND
                && ChatTranslationController.handleButtonClick(event.getValue())) {
            callback.setReturnValue(Boolean.TRUE);
        }
    }
}
