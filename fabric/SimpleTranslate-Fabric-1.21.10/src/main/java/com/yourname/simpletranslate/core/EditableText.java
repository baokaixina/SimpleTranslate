package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.util.TranslationTextDetector;
import net.minecraft.network.chat.Style;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a styled run contains translatable natural language.
 * Obfuscated runs and legacy HUD formatting noise are never editable.
 */
public final class EditableText {
    private static final Pattern ASCII_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9]+");
    private static final Set<String> TRANSLATABLE_SINGLE_WORDS = Set.of(
            "ability", "abbreviations", "activation", "allies", "ally", "amulet", "armor",
            "attack", "atk", "bleed", "blueprint", "book", "boss", "buff", "burn",
            "cast", "casting", "charm", "click", "complete", "completed", "cooldown",
            "damage", "debuff", "disabled", "duration", "enabled", "enemies", "enemy",
            "failed", "freeze", "freezing", "heal", "health", "hoe", "invulnerable",
            "loading", "loaded", "magic", "mana", "physical", "radius", "range",
            "ready", "relic", "resistance", "ring", "root", "score", "self", "shred",
            "siege", "silence", "slot", "slow", "speed", "staff", "stats", "stun",
            "summon", "target", "targets", "tooltip", "used"
    );

    private EditableText() {
    }

    public static boolean containsEnglish(String text) {
        return TranslationTextDetector.containsTranslatableText(text);
    }

    public static boolean isEditableRun(String text, Style style, String surface) {
        if (!containsEnglish(text)) {
            return false;
        }
        Style effectiveStyle = style == null ? Style.EMPTY : style;
        if (effectiveStyle.isObfuscated()) {
            return false;
        }
        return !isLegacyFormattedNoise(text, surface);
    }

    public static boolean isLegacyFormattedNoise(String text, String surface) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String effectiveSurface = surface == null ? "" : surface.toLowerCase(Locale.ROOT);
        if (!effectiveSurface.startsWith("hud.") && !effectiveSurface.startsWith("title.")
                && !effectiveSurface.startsWith("actionbar.")) {
            return false;
        }

        String normalized = TranslationTextDetector.normalizeForDetection(text);
        Matcher matcher = ASCII_TOKEN_PATTERN.matcher(normalized);
        int tokens = 0;
        int artifactTokens = 0;
        int meaningfulTokens = 0;
        while (matcher.find()) {
            String token = matcher.group();
            if (token == null || token.isBlank()) {
                continue;
            }
            tokens++;
            if (isLegacyArtifactToken(token)) {
                artifactTokens++;
                continue;
            }
            if (isMeaningfulHudToken(token)) {
                meaningfulTokens++;
            }
        }
        return tokens > 0 && artifactTokens > 0 && meaningfulTokens == 0;
    }

    private static boolean isLegacyArtifactToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.matches("[0-9a-frklmno]{2,}")) {
            return true;
        }
        if (containsDigit(token) && legacyCodeRatio(lower) >= 0.55D) {
            return true;
        }
        String stripped = stripLegacyCodePrefix(token);
        return !stripped.equals(token) && !isMeaningfulHudToken(stripped);
    }

    private static double legacyCodeRatio(String token) {
        if (token == null || token.isBlank()) {
            return 0.0D;
        }
        int codeLike = 0;
        int total = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                continue;
            }
            total++;
            if ((c >= '0' && c <= '9') || "abcdefklmnor".indexOf(c) >= 0) {
                codeLike++;
            }
        }
        return total == 0 ? 0.0D : (double) codeLike / (double) total;
    }

    private static String stripLegacyCodePrefix(String token) {
        if (token == null || token.length() < 2) {
            return token == null ? "" : token;
        }
        int index = 0;
        while (index < token.length() - 1 && isLegacyFormatCodeChar(token.charAt(index))) {
            index++;
            if (index >= 4) {
                break;
            }
        }
        if (index == 0 || index >= token.length()) {
            return token;
        }
        return token.substring(index);
    }

    private static boolean isLegacyFormatCodeChar(char c) {
        return (c >= '0' && c <= '9') || "abcdefklmnor".indexOf(c) >= 0;
    }

    private static boolean isMeaningfulHudToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String stripped = stripLegacyCodePrefix(token).replaceAll("[^A-Za-z]", "");
        if (stripped.length() < 2) {
            return false;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (TRANSLATABLE_SINGLE_WORDS.contains(lower)) {
            return true;
        }
        if (stripped.matches("[A-Z]{2,}")) {
            return true;
        }
        if (stripped.matches("[a-z]{3,}") && !stripped.matches("[a-fk-or]+") && hasAsciiVowel(stripped)) {
            return true;
        }
        return Character.isUpperCase(stripped.charAt(0)) && hasLowercase(stripped) && !stripped.matches(".*\\d.*");
    }

    private static boolean hasAsciiVowel(String token) {
        if (token == null) {
            return false;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.indexOf('a') >= 0 || lower.indexOf('e') >= 0 || lower.indexOf('i') >= 0
                || lower.indexOf('o') >= 0 || lower.indexOf('u') >= 0;
    }

    private static boolean hasLowercase(String token) {
        if (token == null) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (Character.isLowerCase(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDigit(String word) {
        if (word == null) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            if (Character.isDigit(word.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
