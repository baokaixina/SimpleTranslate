package com.yourname.simpletranslate.chat;

import com.yourname.simpletranslate.util.TooltipTranslationHelper;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores translated messages to avoid re-translation
 * and provides utility methods for translation
 */
public class ChatMessageStore {

    // Store original text -> translated text (completed translations)
    private static final Map<String, String> translatedMessages = new ConcurrentHashMap<>();

    // Set of messages currently being translated (to avoid duplicate requests)
    private static final Set<String> pendingTranslations = ConcurrentHashMap.newKeySet();

    /**
     * Check if text contains English
     */
    public static boolean containsEnglish(String text) {
        return TooltipTranslationHelper.containsEnglish(text, 2);
    }

    /**
     * Check if a message has already been translated (completed)
     */
    public static boolean hasTranslation(String original) {
        return translatedMessages.containsKey(original);
    }

    /**
     * Check if a message is currently being translated
     */
    public static boolean isPending(String original) {
        return pendingTranslations.contains(original);
    }

    /**
     * Mark a message as completed translation
     */
    public static void markTranslated(String original, String translated) {
        translatedMessages.put(original, translated);
        pendingTranslations.remove(original);
    }

    /**
     * Mark a message as pending translation (returns false if already pending or
     * translated)
     */
    public static boolean markPending(String original) {
        // If already translated, don't mark pending
        if (translatedMessages.containsKey(original)) {
            return false;
        }
        // Try to add to pending set (returns false if already exists)
        return pendingTranslations.add(original);
    }

    /**
     * Remove a message from pending (when translation is cancelled or failed)
     */
    public static void removePending(String original) {
        pendingTranslations.remove(original);
    }

    /**
     * Get the translation for a message (null if not translated)
     */
    public static String getTranslation(String original) {
        return translatedMessages.get(original);
    }

    /**
     * Clear all stored translations and pending
     */
    public static void clear() {
        translatedMessages.clear();
        pendingTranslations.clear();
    }

    /**
     * Get the number of stored translations
     */
    public static int size() {
        return translatedMessages.size();
    }
}
