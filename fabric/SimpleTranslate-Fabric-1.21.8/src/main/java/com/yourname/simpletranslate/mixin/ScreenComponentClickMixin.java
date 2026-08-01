package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.feature.chat.ChatButtonClickHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.10 adaptation of the chat click interception: this Minecraft version has
 * only the single-argument {@code Screen.handleComponentClicked(Style)} (the
 * two-argument ChatScreen override with an explicit insertion flag does not
 * exist yet). Intercepting here keeps the custom simple_translate: suggest
 * clicks out of vanilla command insertion. The prefix only appears on our own
 * translated chat lines, so other screens are unaffected.
 */
@Mixin(Screen.class)
public abstract class ScreenComponentClickMixin {

    @Unique
    private static final String SIMPLE_TRANSLATE_CLICK_PREFIX = "simple_translate:";

    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true)
    private void simple_translate$onHandleComponentClicked(
            Style style, CallbackInfoReturnable<Boolean> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gui == null || Screen.hasShiftDown()
                || style == null || style.getClickEvent() == null) {
            return;
        }

        ChatComponent chatComponent = minecraft.gui.getChat();
        if (!(style.getClickEvent() instanceof ClickEvent.SuggestCommand suggestCommand)) {
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
