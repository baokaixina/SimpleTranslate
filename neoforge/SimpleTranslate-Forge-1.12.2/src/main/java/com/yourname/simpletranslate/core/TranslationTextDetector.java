package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.config.ModConfig;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
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
    private static final Set<String> PRESERVABLE_ABBREVIATIONS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
            "AOE", "API", "ASPD", "ATK", "CD", "DEF", "DPS", "EXP", "FPS", "HP", "HPR",
            "ID", "LV", "LVL", "MP", "MPR", "NBT", "SPD", "TPS", "UI", "XP",
            "ALT", "CTRL", "DELETE", "ENTER", "ESC", "SHIFT", "SPACE", "TAB"
    )));

    private TranslationTextDetector() {
    }

    public static boolean containsTranslatableText(String text) {
        return containsTranslatableText(text, 1);
    }

    public static boolean containsTranslatableText(String text, int minLetters) {
        return containsTranslatableText(text, minLetters, ModConfig.TARGET_LANGUAGE.get());
    }

    public static boolean containsTranslatableText(String text, int minLetters, String targetLanguage) {
        if (blank(text)) {
            return false;
        }
        String normalized = normalizeForDetection(text);
        normalized = stripMinecraftFormattingCodes(normalized);
        if (blank(normalized)) {
            return false;
        }
        ScriptCounts counts = countScripts(normalized);
        if (counts.totalLetters() == 0) {
            return false;
        }
        String target = canonicalLanguageCode(targetLanguage);
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
        return containsMeaningfulTranslatableText(text, ModConfig.TARGET_LANGUAGE.get());
    }

    public static boolean containsMeaningfulTranslatableText(String text, String targetLanguage) {
        if (!containsTranslatableText(text, 1, targetLanguage)) {
            return false;
        }
        String normalized = stripMinecraftFormattingCodes(normalizeForDetection(text));
        if (blank(normalized)) {
            return false;
        }

        ScriptCounts counts = countScripts(normalized);
        String target = canonicalLanguageCode(targetLanguage);
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
        if (blank(normalizedSource) || blank(normalizedTranslated)) {
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
        if (blank(code)) {
            return "auto";
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
        if (equalsAny(normalized, "auto", "detect", "auto-detect", "automatic")) return "auto";
        if (equalsAny(normalized, "zh", "cn", "zh-cn", "zh-hans", "chinese", "simplified-chinese")) return "zh_cn";
        if (equalsAny(normalized, "zh-tw", "zh-hant", "traditional-chinese")) return "zh_tw";
        if (equalsAny(normalized, "en", "en-us", "en-gb", "english")) return "en";
        if (equalsAny(normalized, "ja", "jp", "japanese")) return "ja";
        if (equalsAny(normalized, "ko", "kr", "korean")) return "ko";
        if (equalsAny(normalized, "es", "spanish")) return "es";
        if (equalsAny(normalized, "fr", "french")) return "fr";
        if (equalsAny(normalized, "de", "german")) return "de";
        if (equalsAny(normalized, "ru", "russian")) return "ru";
        return normalized;
    }

    public static String languagePairKey() {
        return languagePairKey(ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
    }

    public static String languagePairKey(String sourceLanguage, String targetLanguage) {
        return canonicalLanguageCode(sourceLanguage)
                + "->"
                + canonicalLanguageCode(targetLanguage);
    }

    public static String displayLanguageName(String code) {
        String canonical = canonicalLanguageCode(code);
        if ("auto".equals(canonical)) return "auto-detected source language";
        if ("zh_cn".equals(canonical)) return "Simplified Chinese";
        if ("zh_tw".equals(canonical)) return "Traditional Chinese";
        if ("en".equals(canonical)) return "English";
        if ("ja".equals(canonical)) return "Japanese";
        if ("ko".equals(canonical)) return "Korean";
        if ("es".equals(canonical)) return "Spanish";
        if ("fr".equals(canonical)) return "French";
        if ("de".equals(canonical)) return "German";
        if ("ru".equals(canonical)) return "Russian";
        return canonical;
    }

    public static boolean hasLanguageSignal(String text, String language) {
        if (blank(text)) {
            return false;
        }
        String normalized = normalizeForDetection(text);
        normalized = stripMinecraftFormattingCodes(normalized);
        if (blank(normalized)) {
            return false;
        }
        ScriptCounts counts = countScripts(normalized);
        String canonical = canonicalLanguageCode(language);
        if ("zh_cn".equals(canonical) || "zh_tw".equals(canonical)) return counts.han > 0;
        if ("ja".equals(canonical)) return counts.kana > 0 || counts.han > 0;
        if ("ko".equals(canonical)) return counts.hangul > 0;
        if ("ru".equals(canonical)) return counts.cyrillic > 0;
        if (equalsAny(canonical, "en", "es", "fr", "de")) return counts.latin > 0;
        return counts.totalLetters() > 0;
    }

    private static String normalizeStyleGuidance(String value) {
        if (blank(value)) {
            return "";
        }
        return normalizeForDetection(value).replaceAll("\\s+", " ");
    }

    private static boolean isAlreadyTargetOnly(ScriptCounts counts, String target) {
        if ("zh_cn".equals(target) || "zh_tw".equals(target)) return counts.han > 0
                    && counts.latin == 0
                    && counts.kana == 0
                    && counts.hangul == 0
                    && counts.cyrillic == 0
                    && counts.greek == 0
                    && counts.otherNatural == 0;
        if ("en".equals(target)) return counts.latin > 0
                    && counts.han == 0
                    && counts.kana == 0
                    && counts.hangul == 0
                    && counts.cyrillic == 0
                    && counts.greek == 0
                    && counts.otherNatural == 0;
        if ("ja".equals(target)) return (counts.kana > 0 || counts.han > 0)
                    && counts.latin == 0
                    && counts.hangul == 0
                    && counts.cyrillic == 0
                    && counts.greek == 0
                    && counts.otherNatural == 0;
        if ("ko".equals(target)) return counts.hangul > 0
                    && counts.latin == 0
                    && counts.kana == 0
                    && counts.cyrillic == 0
                    && counts.greek == 0
                    && counts.otherNatural == 0;
        if ("ru".equals(target)) return counts.cyrillic > 0
                    && counts.latin == 0
                    && counts.han == 0
                    && counts.kana == 0
                    && counts.hangul == 0
                    && counts.greek == 0
                    && counts.otherNatural == 0;
        return false;
    }

    private static boolean hasForeignNonLatinScript(ScriptCounts counts, String target) {
        if ("zh_cn".equals(target) || "zh_tw".equals(target)) return counts.kana > 0 || counts.hangul > 0
                || counts.cyrillic > 0 || counts.greek > 0 || counts.otherNatural > 0;
        if ("ja".equals(target)) return counts.hangul > 0 || counts.cyrillic > 0
                || counts.greek > 0 || counts.otherNatural > 0;
        if ("ko".equals(target)) return counts.kana > 0 || counts.cyrillic > 0
                || counts.greek > 0 || counts.otherNatural > 0;
        if ("ru".equals(target)) return counts.han > 0 || counts.kana > 0 || counts.hangul > 0
                || counts.greek > 0 || counts.otherNatural > 0;
        return counts.han > 0 || counts.kana > 0 || counts.hangul > 0
                || counts.cyrillic > 0 || counts.greek > 0 || counts.otherNatural > 0;
    }

    private static boolean isPreservableLatinMarker(String token) {
        if (blank(token)) {
            return true;
        }
        String normalized = token.replace('’', '\'')
                .replaceAll("^[^A-Za-z]+|[^A-Za-z0-9']+$", "");
        if (blank(normalized)) {
            return true;
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (isRomanNumeral(upper)) {
            return true;
        }
        return PRESERVABLE_ABBREVIATIONS.contains(upper);
    }

    private static List<String> meaningfulLatinWords(String text) {
        if (blank(text)) {
            return Collections.emptyList();
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
        if (blank(text)) {
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
        if (blank(token)) {
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
                case LATIN: counts.latin++; break;
                case HAN: counts.han++; break;
                case HIRAGANA:
                case KATAKANA: counts.kana++; break;
                case HANGUL: counts.hangul++; break;
                case CYRILLIC: counts.cyrillic++; break;
                case GREEK: counts.greek++; break;
                default: counts.otherNatural++; break;
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

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean equalsAny(String value, String... candidates) {
        if (value == null || candidates == null) return false;
        for (String candidate : candidates) if (value.equals(candidate)) return true;
        return false;
    }
}
