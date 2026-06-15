package com.yourname.simpletranslate.network;

import com.yourname.simpletranslate.util.TranslationCacheKeys;
import net.minecraft.network.FriendlyByteBuf;

public final class SharedCacheEntry {
    public static final int MAX_KEY_CHARS = 1024;
    public static final int MAX_TRANSLATION_CHARS = 12000;
    public static final int MAX_DISPLAY_CHARS = 4000;
    public static final int MAX_SURFACE_CHARS = 128;

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

    public String key() {
        return key;
    }

    public String translation() {
        return translation;
    }

    public String sourceText() {
        return sourceText;
    }

    public String translationText() {
        return translationText;
    }

    public String surface() {
        return surface;
    }

    public long createdAt() {
        return createdAt;
    }

    public boolean editedByPlayer() {
        return editedByPlayer;
    }

    public long editedAt() {
        return editedAt;
    }

    public boolean isShareable() {
        return TranslationCacheKeys.isCurrentProtocolKey(key)
                && !"ocr".equals(TranslationCacheKeys.laneFromKey(key))
                && !translation.isBlank()
                && key.length() <= MAX_KEY_CHARS
                && translation.length() <= MAX_TRANSLATION_CHARS
                && sourceText.length() <= MAX_DISPLAY_CHARS
                && translationText.length() <= MAX_DISPLAY_CHARS
                && surface.length() <= MAX_SURFACE_CHARS;
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(key, MAX_KEY_CHARS);
        buffer.writeUtf(translation, MAX_TRANSLATION_CHARS);
        buffer.writeUtf(sourceText, MAX_DISPLAY_CHARS);
        buffer.writeUtf(translationText, MAX_DISPLAY_CHARS);
        buffer.writeUtf(surface, MAX_SURFACE_CHARS);
        buffer.writeLong(createdAt);
        buffer.writeBoolean(editedByPlayer);
        buffer.writeLong(editedAt);
    }

    public static SharedCacheEntry read(FriendlyByteBuf buffer) {
        return new SharedCacheEntry(
                buffer.readUtf(MAX_KEY_CHARS),
                buffer.readUtf(MAX_TRANSLATION_CHARS),
                buffer.readUtf(MAX_DISPLAY_CHARS),
                buffer.readUtf(MAX_DISPLAY_CHARS),
                buffer.readUtf(MAX_SURFACE_CHARS),
                buffer.readLong(),
                buffer.readBoolean(),
                buffer.readLong());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
