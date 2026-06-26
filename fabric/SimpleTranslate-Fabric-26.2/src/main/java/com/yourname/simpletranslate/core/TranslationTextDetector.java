package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.config.ModConfig;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared Unicode-aware translatable text detector.
 *
 * <p>Rendering always keeps the original text. NFKC is used only to decide
 * whether a run should be sent to the translator and to build diagnostics/cache
 * context that can understand fullwidth Latin and compatibility glyphs.</p>
 */
public final class TranslationTextDetector {
    private static final Pattern LATIN_TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9'’\\-]*");
    private static final Pattern ROMAN_NUMERAL_PATTERN = Pattern.compile(
            "(?i)M{0,4}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})");
    private static final Set<String> PRESERVABLE_ABBREVIATIONS = Set.of(
            "AOE", "API", "ASPD", "ATK", "CD", "DEF", "DPS", "EXP", "FPS", "HP", "HPR",
            "ID", "LV", "LVL", "MP", "MPR", "NBT", "SPD", "TPS", "UI", "XP",
            "ALT", "CTRL", "DELETE", "ENTER", "ESC", "SHIFT", "SPACE", "TAB"
    );

    private TranslationTextDetector() {
    }

    public static boolean containsTranslatableText(String text) {
        return containsTranslatableText(text, 1);
    }

    public static boolean containsTranslatableText(String text, int minLetters) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalizeForDetection(text);
        normalized = stripMinecraftFormattingCodes(normalized);
        if (normalized.isBlank()) {
            return false;
        }
        ScriptCounts counts = countScripts(normalized);
        if (counts.totalLetters() == 0) {
            return false;
        }
        String target = canonicalLanguageCode(ModConfig.TARGET_LANGUAGE.get());
        return !isAlreadyTargetOnly(counts, target);
    }

    /**
     * Stricter detector for rejecting semantically incomplete restored text.
     *
     * <p>{@link #containsTranslatableText(String)} intentionally treats any
     * non-target Latin run as eligible for translation, including short item
     * labels. Rejection is riskier: already-localized Minecraft lines often keep
     * harmless Latin markers such as enchantment roman numerals ("锋利 I") or stat
     * abbreviations ("HP"). This method only flags natural source-language
     * leftovers that should force a retry.</p>
     */
    public static boolean containsMeaningfulTranslatableText(String text) {
        if (!containsTranslatableText(text)) {
            return false;
        }
        String normalized = stripMinecraftFormattingCodes(normalizeForDetection(text));
        if (normalized.isBlank()) {
            return false;
        }

        ScriptCounts counts = countScripts(normalized);
        String target = canonicalLanguageCode(ModConfig.TARGET_LANGUAGE.get());
        if (hasForeignNonLatinScript(counts, target)) {
            return true;
        }

        Matcher matcher = LATIN_TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (isPreservableLatinMarker(token)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Detects source-language text that survived inside a translated result.
     *
     * <p>This is intentionally source-aware. A translated Chinese sentence may
     * legitimately keep a proper noun, command argument, or one-word UI token in
     * Latin letters. We reject only unchanged whole strings or repeated natural
     * source phrases, which are the cases that produce mixed-language residue in
     * tooltips, signs and legacy raw/mapping paths.</p>
     */
    public static boolean containsResidualSourceText(String source, String translated) {
        String normalizedSource = stripMinecraftFormattingCodes(normalizeForDetection(source));
        String normalizedTranslated = stripMinecraftFormattingCodes(normalizeForDetection(translated));
        if (normalizedSource.isBlank() || normalizedTranslated.isBlank()) {
            return false;
        }
        if (!containsMeaningfulTranslatableText(normalizedSource)) {
            return false;
        }
        if (normalizedSource.equalsIgnoreCase(normalizedTranslated)) {
            return true;
        }

        List<String> sourceWords = meaningfulLatinWords(normalizedSource);
        if (sourceWords.size() < 2) {
            return false;
        }
        String translatedLatin = " " + normalizeLatinPhrase(normalizedTranslated) + " ";
        for (int start = 0; start < sourceWords.size(); start++) {
            for (int length = 2; length <= 5 && start + length <= sourceWords.size(); length++) {
                String phrase = String.join(" ", sourceWords.subList(start, start + length));
                if (translatedLatin.contains(" " + phrase + " ")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String normalizeForDetection(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('\u3000', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }

    private static String stripMinecraftFormattingCodes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("(?i)\u00a7[0-9A-FK-OR]", "");
    }

    public static String canonicalLanguageCode(String code) {
        if (code == null || code.isBlank()) {
            return "auto";
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
        return switch (normalized) {
            case "auto", "detect", "auto-detect", "automatic" -> "auto";
            case "zh", "cn", "zh-cn", "zh-hans", "chinese", "simplified-chinese" -> "zh_cn";
            case "zh-tw", "zh-hant", "traditional-chinese" -> "zh_tw";
            case "en", "en-us", "en-gb", "english" -> "en";
            case "ja", "jp", "japanese" -> "ja";
            case "ko", "kr", "korean" -> "ko";
            case "es", "spanish" -> "es";
            case "fr", "french" -> "fr";
            case "de", "german" -> "de";
            case "ru", "russian" -> "ru";
            default -> normalized;
        };
    }

    public static String languagePairKey() {
        return canonicalLanguageCode(ModConfig.SOURCE_LANGUAGE.get())
                + "->"
                + canonicalLanguageCode(ModConfig.TARGET_LANGUAGE.get());
    }

    public static String displayLanguageName(String code) {
        String canonical = canonicalLanguageCode(code);
        return switch (canonical) {
            case "auto" -> "auto-detected source language";
            case "zh_cn" -> "Simplified Chinese";
            case "zh_tw" -> "Traditional Chinese";
            case "en" -> "English";
            case "ja" -> "Japanese";
            case "ko" -> "Korean";
            case "es" -> "Spanish";
            case "fr" -> "French";
            case "de" -> "German";
            case "ru" -> "Russian";
            default -> canonical;
        };
    }

    private static String normalizeStyleGuidance(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return normalizeForDetection(value).replaceAll("\\s+", " ");
    }

    private static boolean isAlreadyTargetOnly(ScriptCounts counts, String target) {
        return switch (target) {
            case "zh_cn", "zh_tw" -> counts.han > 0
                    && counts.latin == 0
                    && counts.kana == 0
                    && counts.hangul == 0
                    && counts.cyrillic == 0
                    && counts.greek == 0
                    && counts.otherNatural == 0;
            case "en" -> counts.latin > 0
                    && counts.han == 0
                    && counts.kana == 0
                    && counts.hangul == 0
                    && counts.cyrillic == 0
                    && counts.greek == 0
                    && counts.otherNatural == 0;
            case "ja" -> (counts.kana > 0 || counts.han > 0)
                    && counts.latin == 0
                    && counts.hangul == 0
                    && counts.cyrillic == 0
                    && counts.greek == 0
                    && counts.otherNatural == 0;
            case "ko" -> counts.hangul > 0
                    && counts.latin == 0
                    && counts.kana == 0
                    && counts.cyrillic == 0
                    && counts.greek == 0
                    && counts.otherNatural == 0;
            case "ru" -> counts.cyrillic > 0
                    && counts.latin == 0
                    && counts.han == 0
                    && counts.kana == 0
                    && counts.hangul == 0
                    && counts.greek == 0
                    && counts.otherNatural == 0;
            default -> false;
        };
    }

    private static boolean hasForeignNonLatinScript(ScriptCounts counts, String target) {
        return switch (target) {
            case "zh_cn", "zh_tw" -> counts.kana > 0 || counts.hangul > 0
                    || counts.cyrillic > 0 || counts.greek > 0 || counts.otherNatural > 0;
            case "ja" -> counts.hangul > 0 || counts.cyrillic > 0
                    || counts.greek > 0 || counts.otherNatural > 0;
            case "ko" -> counts.kana > 0 || counts.cyrillic > 0
                    || counts.greek > 0 || counts.otherNatural > 0;
            case "ru" -> counts.han > 0 || counts.kana > 0 || counts.hangul > 0
                    || counts.greek > 0 || counts.otherNatural > 0;
            case "en" -> counts.han > 0 || counts.kana > 0 || counts.hangul > 0
                    || counts.cyrillic > 0 || counts.greek > 0 || counts.otherNatural > 0;
            default -> counts.han > 0 || counts.kana > 0 || counts.hangul > 0
                    || counts.cyrillic > 0 || counts.greek > 0 || counts.otherNatural > 0;
        };
    }

    private static boolean isPreservableLatinMarker(String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        String normalized = token.replace('’', '\'')
                .replaceAll("^[^A-Za-z]+|[^A-Za-z0-9']+$", "");
        if (normalized.isBlank()) {
            return true;
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (isRomanNumeral(upper)) {
            return true;
        }
        return PRESERVABLE_ABBREVIATIONS.contains(upper);
    }

    private static List<String> meaningfulLatinWords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        java.util.ArrayList<String> words = new java.util.ArrayList<>();
        Matcher matcher = LATIN_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (isPreservableLatinMarker(token)) {
                continue;
            }
            words.add(token.replace('’', '\'').toLowerCase(Locale.ROOT));
        }
        return words;
    }

    private static String normalizeLatinPhrase(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        Matcher matcher = LATIN_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(token.replace('’', '\'').toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    private static boolean isRomanNumeral(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String upper = token.toUpperCase(Locale.ROOT);
        return upper.matches("[IVXLCDM]+") && ROMAN_NUMERAL_PATTERN.matcher(upper).matches();
    }

    private static ScriptCounts countScripts(String text) {
        ScriptCounts counts = new ScriptCounts();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            switch (script) {
                case LATIN -> counts.latin++;
                case HAN -> counts.han++;
                case HIRAGANA, KATAKANA -> counts.kana++;
                case HANGUL -> counts.hangul++;
                case CYRILLIC -> counts.cyrillic++;
                case GREEK -> counts.greek++;
                case ARABIC, HEBREW, THAI, DEVANAGARI, BENGALI, GEORGIAN, ARMENIAN -> counts.otherNatural++;
                default -> counts.otherNatural++;
            }
        }
        return counts;
    }

    private static final class ScriptCounts {
        int latin;
        int han;
        int kana;
        int hangul;
        int cyrillic;
        int greek;
        int otherNatural;

        int totalLetters() {
            return latin + han + kana + hangul + cyrillic + greek + otherNatural;
        }
    }
}
