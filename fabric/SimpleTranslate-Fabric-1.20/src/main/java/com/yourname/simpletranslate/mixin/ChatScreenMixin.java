package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.util.ChatButtonClickHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Unique
    private static final String SIMPLE_TRANSLATE_CLICK_PREFIX = "simple_translate:";

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void simple_translate$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gui == null) {
            return;
        }

        ChatComponent chatComponent = minecraft.gui.getChat();
        Style style = chatComponent.getClickedComponentStyleAt(mouseX, mouseY);
        if (style == null || style.getClickEvent() == null) {
            return;
        }

        ClickEvent clickEvent = style.getClickEvent();
        if (clickEvent.getAction() != ClickEvent.Action.SUGGEST_COMMAND) {
            return;
        }

        String value = clickEvent.getValue();
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
