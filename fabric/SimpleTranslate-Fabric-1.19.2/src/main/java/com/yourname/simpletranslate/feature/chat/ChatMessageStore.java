package com.yourname.simpletranslate.feature.chat;

import com.yourname.simpletranslate.feature.chat.ChatMessageIdentity;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;

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

    // Runtime-only messages deliberately left untranslated for the current view.
    private static final Set<String> skippedTranslations = ConcurrentHashMap.newKeySet();

    // Messages that failed batch translation, with expiry timestamps.
    private static final Map<String, Long> failedCooldowns = new ConcurrentHashMap<>();
    private static final long FAILURE_COOLDOWN_MS = 30_000L;

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
        skippedTranslations.remove(original);
    }

    /**
     * Mark a message as pending translation (returns false if already pending or
     * translated)
     */
    public static boolean markPending(String original) {
        if (translatedMessages.containsKey(original) || skippedTranslations.contains(original)) {
            return false;
        }
        Long failTime = failedCooldowns.get(original);
        if (failTime != null && System.currentTimeMillis() < failTime) {
            return false;
        }
        failedCooldowns.remove(original);
        return pendingTranslations.add(original);
    }

    /**
     * Remove a message from pending (when translation is cancelled or failed)
     */
    public static void removePending(String original) {
        pendingTranslations.remove(original);
    }

    /**
     * Marks a message as recently failed, preventing re-translation for a
     * cooldown period. Used when batch translation fails to avoid spinning.
     */
    public static void markFailedCooldown(String original) {
        if (original == null) {
            return;
        }
        pendingTranslations.remove(original);
        failedCooldowns.put(original, System.currentTimeMillis() + FAILURE_COOLDOWN_MS);
    }

    /**
     * Mark a visible message as intentionally untranslated for this runtime.
     */
    public static void markSkipped(String original) {
        if (original == null) {
            return;
        }
        pendingTranslations.remove(original);
        skippedTranslations.add(original);
    }

    /**
     * Get the translation for a message (null if not translated)
     */
    public static String getTranslation(String original) {
        return translatedMessages.get(original);
    }

    /**
     * Per-message pending/translated key so duplicate plain text in chat does not
     * suppress later lines.
     */
    public static String messageKey(ChatMessageIdentity identity, String fallback) {
        if (identity == null || fallback == null) {
            return fallback;
        }
        if (identity.addedTime >= 0) {
            int signatureHash = identity.signature == null ? 0 : identity.signature.hashCode();
            return identity.addedTime + "\u001F" + signatureHash + "\u001F" + fallback;
        }
        return fallback;
    }

    /**
     * Clear all stored translations and pending
     */
    public static void clear() {
        translatedMessages.clear();
        pendingTranslations.clear();
        skippedTranslations.clear();
        failedCooldowns.clear();
    }

    /**
     * Get the number of stored translations
     */
    public static int size() {
        return translatedMessages.size();
    }
}


