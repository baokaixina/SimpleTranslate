package com.yourname.simpletranslate.feature.chat;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.feature.chat.ChatTranslationRuntime;
import com.yourname.simpletranslate.core.TextSegmentInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Blacklist checks shared by every chat translation path. */
public final class ChatBlacklistGuard {
    private ChatBlacklistGuard() {
    }

    public static boolean isBlacklisted(String text) {
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        return blacklist != null && blacklist.isBlacklisted(text);
    }

    public static boolean containsBlacklistedText(String text) {
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        return blacklist != null && blacklist.containsBlacklistedEntry(text);
    }

    public static boolean hasBlacklistedSourceText(Component message, String plainText) {
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist == null) {
            return false;
        }

        if (blacklist.isBlacklisted(plainText)) {
            return true;
        }

        if (message != null) {
            List<TextSegmentInfo> segments = new ArrayList<>();
            ChatTranslationRuntime.extractSegments(message, segments);
            for (TextSegmentInfo segment : segments) {
                if (segment != null && blacklist.isBlacklisted(segment.text)) {
                    return true;
                }
            }
        }

        if (plainText == null || plainText.isBlank()) {
            return false;
        }

        int angleIndex = plainText.lastIndexOf('>');
        if (angleIndex >= 0 && angleIndex + 1 < plainText.length()
                && blacklist.isBlacklisted(plainText.substring(angleIndex + 1))) {
            return true;
        }

        int colonIndex = Math.max(plainText.lastIndexOf(':'), plainText.lastIndexOf('\uff1a'));
        if (colonIndex >= 0 && colonIndex + 1 < plainText.length()
                && blacklist.isBlacklisted(plainText.substring(colonIndex + 1))) {
            return true;
        }

        return false;
    }
}


