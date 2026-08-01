package com.yourname.simpletranslate.feature.chat;

import net.minecraft.client.gui.ChatLine;
import net.minecraft.util.text.ITextComponent;

import java.util.List;

/**
 * Narrow view of the vanilla {@code ChatComponent} internals exposed by the
 * mixin so chat translation controllers can live outside the mixin class.
 */
public interface ChatComponentAccess {
    List<ChatLine<ITextComponent>> simpleTranslateAllMessages();

    void simpleTranslateRescale();
}
