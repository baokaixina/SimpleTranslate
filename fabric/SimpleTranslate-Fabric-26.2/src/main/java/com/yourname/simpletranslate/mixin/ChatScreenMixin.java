package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.compat.ClientGuiCompat;
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

@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Unique
    private static final String SIMPLE_TRANSLATE_CLICK_PREFIX = "simple_translate:";

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

        ChatComponent chatComponent = ClientGuiCompat.chat(minecraft);
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
