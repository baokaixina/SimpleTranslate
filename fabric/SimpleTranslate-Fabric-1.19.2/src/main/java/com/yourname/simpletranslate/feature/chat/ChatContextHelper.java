package com.yourname.simpletranslate.feature.chat;

import com.yourname.simpletranslate.feature.chat.ChatTranslationRuntime;
import com.yourname.simpletranslate.core.TextSegmentInfo;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.regex.Pattern;

/** Player-prefix detection and chat context keys used by automatic chat translation. */
public final class ChatContextHelper {
    private static final Pattern MINECRAFT_USERNAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    private ChatContextHelper() {
    }

    public static boolean hasChatBodyPrefix(String plainText) {
        return findChatBodyStart(plainText) > 0;
    }

    public static int findChatBodyStart(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return -1;
        }

        int angleIndex = plainText.indexOf('>');
        if (plainText.startsWith("<") && angleIndex > 1 && angleIndex + 1 < plainText.length()) {
            return skipWhitespace(plainText, angleIndex + 1);
        }

        int colonIndex = firstChatColonIndex(plainText);
        if (colonIndex > 0 && colonIndex < 80 && colonIndex + 1 < plainText.length()) {
            String prefix = plainText.substring(0, colonIndex);
            if (prefixContainsKnownPlayerName(prefix)) {
                return skipWhitespace(plainText, colonIndex + 1);
            }
        }

        return -1;
    }

    private static int firstChatColonIndex(String text) {
        int ascii = text.indexOf(':');
        int fullWidth = text.indexOf('\uff1a');
        if (ascii < 0) {
            return fullWidth;
        }
        if (fullWidth < 0) {
            return ascii;
        }
        return Math.min(ascii, fullWidth);
    }

    private static int skipWhitespace(String text, int index) {
        int i = Math.max(0, index);
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean prefixContainsKnownPlayerName(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        int stop = prefix.length();
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (Character.isWhitespace(c) || c == '(' || c == '[') {
                stop = i;
                break;
            }
        }
        String leading = prefix.substring(0, stop).trim();
        if (leading.isEmpty()) {
            return false;
        }
        return isKnownPlayerName(leading) || looksLikePlayerName(leading);
    }

    /** Username-shaped token; does not require the player to be online. */
    public static boolean looksLikePlayerName(String text) {
        String candidate = normalizeNameCandidate(text);
        if (candidate.isEmpty() || !MINECRAFT_USERNAME.matcher(candidate).matches()) {
            return false;
        }
        // Avoid treating long English words (e.g. "Abbreviations:") as player names.
        if (candidate.length() > 8 && candidate.chars().allMatch(ch -> ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z')) {
            return false;
        }
        return true;
    }

    public static boolean isKnownPlayerName(String text) {
        String candidate = normalizeNameCandidate(text);
        if (candidate.isEmpty()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getConnection() == null) {
            return false;
        }
        for (var playerInfo : minecraft.getConnection().getOnlinePlayers()) {
            if (playerInfo != null
                    && playerInfo.getProfile() != null
                    && candidate.equalsIgnoreCase(playerInfo.getProfile().getName())) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeNameCandidate(String text) {
        if (text == null) {
            return "";
        }
        String candidate = text.trim();
        while (candidate.length() >= 2
                && ((candidate.charAt(0) == '<' && candidate.charAt(candidate.length() - 1) == '>')
                || (candidate.charAt(0) == '[' && candidate.charAt(candidate.length() - 1) == ']')
                || (candidate.charAt(0) == '(' && candidate.charAt(candidate.length() - 1) == ')'))) {
            candidate = candidate.substring(1, candidate.length() - 1).trim();
        }
        return candidate;
    }

    public static String translatableChatSegmentText(List<TextSegmentInfo> segments, int segmentIndex,
                                                     String plainText) {
        String candidate = ChatTranslationRuntime.getTranslatableChatSegmentText(
                segments, segmentIndex, plainText, findChatBodyStart(plainText));
        if (candidate == null) {
            return null;
        }
        candidate = candidate.trim();
        if (candidate.isEmpty() || isKnownPlayerName(candidate) || ChatBlacklistGuard.isBlacklisted(candidate)) {
            return null;
        }
        return candidate;
    }

    public static String stripChatButtonSuffix(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] suffixes = {
                " [翻译中...]",
                " [翻译]",
                " [原文]",
                " [Translating...]",
                " [Translate]",
                " [Original]"
        };
        for (String suffix : suffixes) {
            if (text.endsWith(suffix)) {
                return text.substring(0, text.length() - suffix.length());
            }
        }
        return text;
    }

    public static String contextRequestKey(String plainText, List<String> contextLines, int targetIndex) {
        String context = ChatTranslationRuntime.contextText(contextLines, targetIndex);
        int contextHash = context.hashCode();
        return (plainText == null ? "" : plainText)
                + "\u001Fchat-context:v2:"
                + targetIndex
                + ":"
                + (contextLines == null ? 0 : contextLines.size())
                + ":"
                + Integer.toHexString(contextHash);
    }
}


