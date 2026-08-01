package com.yourname.simpletranslate.cache;

import com.yourname.simpletranslate.core.TranslationCacheKeys;
import java.nio.charset.StandardCharsets;

/** Complete baseline shared-cache value, kept loader-neutral for 1.12.2. */
public final class SharedCacheEntry {
    public static final int MAX_KEY_CHARS = 1024;
    public static final int MAX_TRANSLATION_CHARS = 12000;
    public static final int MAX_DISPLAY_CHARS = 4000;
    public static final int MAX_SURFACE_CHARS = 128;
    public static final int MAX_ENTRY_WIRE_BYTES = 24000;

    private final String key;
    private final String translation;
    private final String sourceText;
    private final String translationText;
    private final String surface;
    private final long createdAt;
    private final boolean editedByPlayer;
    private final long editedAt;

    public SharedCacheEntry(String key, String translation, String sourceText, String translationText,
                            String surface, long createdAt, boolean editedByPlayer, long editedAt) {
        this.key = normalize(key);
        this.translation = normalize(translation);
        this.sourceText = normalize(sourceText);
        this.translationText = normalize(translationText);
        this.surface = normalize(surface);
        this.createdAt = Math.max(0L, createdAt);
        this.editedByPlayer = editedByPlayer;
        this.editedAt = Math.max(0L, editedAt);
    }

    public String key() { return key; }
    public String translation() { return translation; }
    public String sourceText() { return sourceText; }
    public String translationText() { return translationText; }
    public String surface() { return surface; }
    public long createdAt() { return createdAt; }
    public boolean editedByPlayer() { return editedByPlayer; }
    public long editedAt() { return editedAt; }

    public boolean isShareable() {
        return TranslationCacheKeys.isCurrentProtocolKey(key)
                && !translation.trim().isEmpty()
                && utf8Bytes(key) <= MAX_KEY_CHARS
                && utf8Bytes(translation) <= MAX_TRANSLATION_CHARS
                && utf8Bytes(sourceText) <= MAX_DISPLAY_CHARS
                && utf8Bytes(translationText) <= MAX_DISPLAY_CHARS
                && utf8Bytes(surface) <= MAX_SURFACE_CHARS
                && estimatedWireBytes() <= MAX_ENTRY_WIRE_BYTES;
    }

    public int estimatedWireBytes() {
        return utfFieldBytes(key) + utfFieldBytes(translation) + utfFieldBytes(sourceText)
                + utfFieldBytes(translationText) + utfFieldBytes(surface) + 8 + 1 + 8;
    }

    private static String normalize(String value) { return value == null ? "" : value.trim(); }
    private static int utfFieldBytes(String value) {
        int bytes = utf8Bytes(value);
        return varIntBytes(bytes) + bytes;
    }
    private static int utf8Bytes(String value) {
        return normalize(value).getBytes(StandardCharsets.UTF_8).length;
    }
    private static int varIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) { value >>>= 7; bytes++; }
        return bytes;
    }
}
