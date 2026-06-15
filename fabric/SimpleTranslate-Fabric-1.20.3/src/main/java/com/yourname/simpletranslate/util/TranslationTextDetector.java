package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.config.ModConfig;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Shared Unicode-aware translatable text detector.
 *
 * <p>Rendering always keeps the original text. NFKC is used only to decide
 * whether a run should be sent to the translator and to build diagnostics/cache
 * context that can understand fullwidth Latin and compatibility glyphs.</p>
 */
public final class TranslationTextDetector {
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
                + canonicalLanguageCode(ModConfig.TARGET_LANGUAGE.get())
                + "|customStyle="
                + customStylePromptKey();
    }

    public static String customStylePromptKey() {
        String customStyle = normalizeStyleGuidance(ModConfig.HUD_STYLE_PROMPT.get());
        return customStyle.isEmpty() ? "none" : customStyle;
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
