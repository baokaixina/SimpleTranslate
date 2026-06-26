package com.yourname.simpletranslate.feature.chat;

import java.util.Locale;
import java.util.Set;

/**
 * Keeps AUTO chat translation focused on sentence-like messages. Busy servers
 * often emit short player chatter and recruitment codes; sending those to the
 * model causes empty responses and repeated queue retries.
 */
public final class ChatAutoTranslationFilter {
    private static final Set<String> COMMON_SHORT_CHAT = Set.of(
            "gg", "gj", "gl", "hf", "ty", "thx", "thanks", "omw", "brb",
            "ok", "okay", "yes", "no", "yep", "nah", "rip", "lol", "lmao",
            "wc", "lfg", "lfm", "fs", "r1", "r2", "r3", "r4", "r5",
            "t1", "t2", "t3", "t4", "t5", "key", "keys", "geode", "cc", "ccs",
            "edd", "mara", "dam", "damn", "pdia", "pdias"
    );

    private ChatAutoTranslationFilter() {
    }

    public static boolean shouldAutoTranslate(String plainText) {
        Candidate candidate = candidateFrom(plainText);
        if (candidate.body.isEmpty()) {
            return false;
        }
        if (!ChatMessageStore.containsEnglish(candidate.body)) {
            return false;
        }

        Stats stats = Stats.from(candidate.body);
        if (stats.wordCount == 0) {
            return false;
        }

        if (!candidate.playerChat) {
            return stats.letterCount >= 2;
        }

        if (isProbablyShortPlayerChatter(candidate.body, stats)) {
            return false;
        }
        return true;
    }

    public static String candidateBodyForTest(String plainText) {
        return candidateFrom(plainText).body;
    }

    private static boolean isProbablyShortPlayerChatter(String body, Stats stats) {
        String lower = body.toLowerCase(Locale.ROOT);
        String compact = lower.replaceAll("[^a-z0-9]+", "");
        if (COMMON_SHORT_CHAT.contains(compact)) {
            return true;
        }
        if (stats.wordCount <= 5 && stats.containsShortChatToken) {
            return true;
        }
        if (stats.wordCount <= 8 && stats.containsShortChatToken && stats.digitCount > 0) {
            return true;
        }
        if (stats.wordCount <= 5 && stats.digitCount > 0 && stats.symbolCount >= stats.wordCount) {
            return true;
        }
        return stats.letterCount < 3;
    }

    private static Candidate candidateFrom(String plainText) {
        String text = ChatContextHelper.stripChatButtonSuffix(stripFormatting(plainText)).trim();
        if (text.isEmpty()) {
            return new Candidate("", false);
        }

        int monumentaBody = text.lastIndexOf(" ? ");
        if (monumentaBody >= 0 && monumentaBody + 3 < text.length()) {
            return new Candidate(text.substring(monumentaBody + 3).trim(), true);
        }

        int bodyStart = ChatContextHelper.findChatBodyStart(text);
        if (bodyStart > 0 && bodyStart < text.length()) {
            return new Candidate(text.substring(bodyStart).trim(), true);
        }

        return new Candidate(text, false);
    }

    private static String stripFormatting(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\u00a7.", "");
    }

    private record Candidate(String body, boolean playerChat) {
    }

    private static final class Stats {
        final int wordCount;
        final int letterCount;
        final int digitCount;
        final int symbolCount;
        final boolean hasSentencePunctuation;
        final boolean containsShortChatToken;

        private Stats(int wordCount, int letterCount, int digitCount, int symbolCount,
                      boolean hasSentencePunctuation, boolean containsShortChatToken) {
            this.wordCount = wordCount;
            this.letterCount = letterCount;
            this.digitCount = digitCount;
            this.symbolCount = symbolCount;
            this.hasSentencePunctuation = hasSentencePunctuation;
            this.containsShortChatToken = containsShortChatToken;
        }

        static Stats from(String body) {
            int words = 0;
            int letters = 0;
            int digits = 0;
            int symbols = 0;
            boolean sentencePunctuation = false;
            boolean shortToken = false;
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (Character.isLetter(c)) {
                    letters++;
                } else if (Character.isDigit(c)) {
                    digits++;
                } else if (!Character.isWhitespace(c)) {
                    symbols++;
                    if (c == '.' || c == '!' || c == '?' || c == ',' || c == ';' || c == ':') {
                        sentencePunctuation = true;
                    }
                }
            }
            for (String token : body.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                words++;
                if (COMMON_SHORT_CHAT.contains(token)) {
                    shortToken = true;
                }
            }
            return new Stats(words, letters, digits, symbols, sentencePunctuation, shortToken);
        }
    }
}


