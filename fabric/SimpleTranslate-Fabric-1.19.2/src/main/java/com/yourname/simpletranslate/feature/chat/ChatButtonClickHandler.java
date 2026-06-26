package com.yourname.simpletranslate.feature.chat;

/**
 * Bridge interface to route custom chat button click events from ChatScreen.
 */
public interface ChatButtonClickHandler {
    boolean simple_translate$handleButtonClickEvent(String clickValue);

    /**
     * Restore all currently visible translated button-mode messages back to original text.
     */
    boolean simple_translate$showVisibleOriginalMessages();
}


