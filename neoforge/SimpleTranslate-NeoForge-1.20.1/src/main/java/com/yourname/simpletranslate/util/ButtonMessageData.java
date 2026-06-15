package com.yourname.simpletranslate.util;

import net.minecraft.network.chat.Component;

/**
 * Data class to store message info for button mode translation
 * Must be outside mixin package to avoid classloading issues
 */
public class ButtonMessageData {
    public enum State {
        ORIGINAL,      // Showing original text, button shows [翻译]
        TRANSLATING,   // Translation in progress, button shows [翻译中...]
        TRANSLATED     // Showing translated text, button shows [原文]
    }

    private final Component originalMessage;
    private final String originalPlainText;
    private final long runtimeRevision;
    private volatile Component translatedMessage;
    private volatile State state;

    public ButtonMessageData(Component originalMessage, String originalPlainText, long runtimeRevision) {
        this.originalMessage = originalMessage;
        this.originalPlainText = originalPlainText;
        this.runtimeRevision = runtimeRevision;
        this.translatedMessage = null;
        this.state = State.ORIGINAL;
    }

    public Component originalMessage() {
        return originalMessage;
    }

    public String originalPlainText() {
        return originalPlainText;
    }

    public long runtimeRevision() {
        return runtimeRevision;
    }

    public synchronized Component translatedMessage() {
        return translatedMessage;
    }

    public synchronized void setTranslatedMessage(Component translated) {
        this.translatedMessage = translated;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized void setState(State state) {
        this.state = state;
    }
}
