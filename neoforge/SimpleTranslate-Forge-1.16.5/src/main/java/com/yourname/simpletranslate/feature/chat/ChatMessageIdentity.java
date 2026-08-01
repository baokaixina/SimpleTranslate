package com.yourname.simpletranslate.feature.chat;

import net.minecraft.util.text.ITextComponent;

public final class ChatMessageIdentity {
    public final ITextComponent originalComponent;
    public final String originalText;
    public final int messageId;
    public final int addedTime;
    public final long runtimeRevision;

    public ChatMessageIdentity(ITextComponent originalComponent, String originalText,
                               int addedTime, int messageId, long runtimeRevision) {
        this.originalComponent = originalComponent;
        this.originalText = originalText;
        this.addedTime = addedTime;
        this.messageId = messageId;
        this.runtimeRevision = runtimeRevision;
    }
}
