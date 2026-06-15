package com.yourname.simpletranslate.chat;

import net.minecraft.client.GuiMessage;

import java.util.List;

/**
 * Narrow view of the vanilla {@code ChatComponent} internals exposed by the
 * mixin so chat translation controllers can live outside the mixin class.
 */
public interface ChatComponentAccess {
    List<GuiMessage> simpleTranslateAllMessages();

    void simpleTranslateRescale();
}
