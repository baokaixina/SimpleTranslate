package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.feature.chat.ChatButtonClickHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.yourname.simpletranslate.feature.chat.ChatTranslationController;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Unique
    private static final String SIMPLE_TRANSLATE_CLICK_PREFIX = "simple_translate:";

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

    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true)
    private void simple_translate$onHandleComponentClicked(
            Style style,
            boolean insertionClickMode,
            CallbackInfoReturnable<Boolean> cir) {
        if (insertionClickMode || style == null || style.getClickEvent() == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gui == null) {
            return;
        }

        ChatComponent chatComponent = minecraft.gui.hud.getChat();
        ClickEvent clickEvent = style.getClickEvent();
        if (!(clickEvent instanceof ClickEvent.SuggestCommand suggestCommand)) {
            return;
        }

        String value = suggestCommand.command();
        if (value == null || !value.startsWith(SIMPLE_TRANSLATE_CLICK_PREFIX)) {
            return;
        }

        if (chatComponent instanceof ChatButtonClickHandler handler
                && handler.simple_translate$handleButtonClickEvent(value)) {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }
}
