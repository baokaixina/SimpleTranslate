package com.yourname.simpletranslate.feature.chat;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

public final class ChatMessageIdentity {
    public final Component originalComponent;
    public final String originalText;
    public final MessageSignature signature;
    public final GuiMessageTag tag;
    public final int addedTime;
    public final long runtimeRevision;

    public ChatMessageIdentity(Component originalComponent, String originalText,
                               MessageSignature signature, GuiMessageTag tag,
                               int addedTime, long runtimeRevision) {
        this.originalComponent = originalComponent;
        this.originalText = originalText;
        this.signature = signature;
        this.tag = tag;
        this.addedTime = addedTime;
        this.runtimeRevision = runtimeRevision;
    }
}


