package com.yourname.simpletranslate.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified numeric fidelity guard.
 *
 * <p>Numbers travel through the wire protocol as literal digits. After
 * translation, each line's numeric token multiset must match the source line;
 * otherwise that single line falls back to the original text instead of
 * showing wrong values (e.g. "every 10 Seconds" becoming "每 1 秒").</p>
 *
 * <p>Chinese numerals ({@code 一}..{@code 九}, {@code 两}) count as their
 * Arabic equivalents so phrases like "1 by 1" may become "一个一个" without
 * falsely reverting the line.</p>
 */
public final class NumberGuard {
    private static final Pattern NUMBER_TOKEN_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");

    private NumberGuard() {
    }

    public static boolean linePasses(String sourceLine, String translatedLine) {
        Map<String, Integer> expected = tokenCounts(sourceLine);
        if (expected.isEmpty()) {
            // No explicit source numbers: reject invented Arabic digits, but
            // allow ordinary Chinese numerals in natural words such as 第二章.
            return arabicTokenCounts(translatedLine).isEmpty();
        }
        Map<String, Integer> actualArabic = arabicTokenCounts(translatedLine);
        if (expected.equals(actualArabic)) {
            return true;
        }
        Map<String, Integer> actual = addChineseDigitCounts(translatedLine, actualArabic);
        return expected.equals(actual);
    }

    public static Map<String, Integer> tokenCounts(String text) {
        return addChineseDigitCounts(text, arabicTokenCounts(text));
    }

    private static Map<String, Integer> addChineseDigitCounts(String text, Map<String, Integer> baseCounts) {
        Map<String, Integer> counts = new LinkedHashMap<>(baseCounts);
        if (text == null || text.isEmpty()) {
            return counts;
        }
        for (int i = 0; i < text.length(); i++) {
            String digit = chineseDigitValue(text.charAt(i));
            if (digit != null) {
                counts.merge(digit, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, Integer> arabicTokenCounts(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return counts;
        }
        Matcher matcher = NUMBER_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            counts.merge(matcher.group(), 1, Integer::sum);
        }
        return counts;
    }

    private static String chineseDigitValue(char c) {
        return switch (c) {
            case '零' -> "0";
            case '一' -> "1";
            case '二' -> "2";
            case '两' -> "2";
            case '三' -> "3";
            case '四' -> "4";
            case '五' -> "5";
            case '六' -> "6";
            case '七' -> "7";
            case '八' -> "8";
            case '九' -> "9";
            default -> null;
        };
    }
}
