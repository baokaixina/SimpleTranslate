package com.yourname.simpletranslate.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic-number masking for Component JSON trees.
 *
 * <p>Only values with a live status/progress shape are replaced with stable
 * {@code ⟦Ni⟧} markers. Ordinary prose numbers stay inside the semantic
 * sentence so the translator can reorder articles, classifiers and quantities
 * correctly. Pure helpers used by {@link JsonPassthroughPipeline}.</p>
 */
public final class ComponentJsonNumberNormalizer {
    private static final Pattern DYNAMIC_VALUE_CANDIDATE = Pattern.compile(
            "[+-]?\\d+(?:[.,:]\\d+)*(?:\\s*/\\s*[+-]?\\d+(?:[.,:]\\d+)*)?%?"
                    + "(?i:st|nd|rd|th|ms|[xdhms])?");
    private static final Pattern COORDINATE_GROUP = Pattern.compile(
            "\\[\\s*[+-]?\\d+(?:[.,:]\\d+)*(?:\\s*,\\s*[+-]?\\d+(?:[.,:]\\d+)*){1,2}\\s*\\]");
    private static final Pattern OBJECTIVE_PROSE = Pattern.compile(
            "(?i)\\b(?:reach|complete|survive|win|play|finish|deal|earn|collect|kill|open|use|"
                    + "compare|contribute|contributed|towards|at\\s+least|more\\s+than|less\\s+than)\\b");
    private static final Pattern LIVE_COUNTDOWN_LANGUAGE = Pattern.compile(
            "(?i)\\b(?:timer|time\\s+left|remaining|cooldown|expires?|rotates?)\\b");
    private static final Pattern LIVE_STATUS_LABEL = Pattern.compile(
            "(?i)\\b(?:progress|health|mana|score|wave|round|ping|fps|tps|durability|charges?|"
                    + "streak|level|xp|hp|mp|sp)\\b");
    /** Existing ⟦…⟧ spans (HUD placeholders, mask tokens) must never be re-normalized. */
    private static final Pattern EXISTING_MARKER_PATTERN = Pattern.compile("\u27e6[^\u27e6\u27e7]*\u27e7");
    private static final Pattern DYNAMIC_MARKER_PATTERN = Pattern.compile("\u27e6N(\\d+)\u27e7");
    /**
     * Splits a matched candidate token into its numeric part and its unit
     * suffix ({@code %}, {@code st/nd/rd/th}, {@code ms}, {@code x/d/h/m/s}).
     * Only the numeric part is masked; the suffix stays as literal text so the
     * translator keeps the unit grammar ("every <masked>m" -> "every 10 minutes").
     */
    private static final Pattern NUMBER_SUFFIX_SPLIT = Pattern.compile(
            "^([+-]?\\d+(?:[.,:]\\d+)*(?:\\s*/\\s*[+-]?\\d+(?:[.,:]\\d+)*)?)(.*)$");
    static final Pattern PROMPT_DYNAMIC_NUMBER_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])[+-]?\\d+(?:[.,:]\\d+)*(?:\\s*/\\s*[+-]?\\d+(?:[.,:]\\d+)*)?%?"
                    + "(?i:st|nd|rd|th|ms|[xdhms])?(?![\\p{L}\\p{N}_])");

    private ComponentJsonNumberNormalizer() {
    }

    /**
     * Extracts classified live numbers from a text string, replacing them with
     * {@code ⟦N0⟧}, {@code ⟦N1⟧}, ... markers so that changing values (e.g.
     * "Durability: 69/80" → "68/80") produce identical cache keys.
     *
     * <p>Digits inside pre-existing {@code ⟦…⟧} placeholders (for example
     * HUD action-bar {@code ⟦1000⟧} markers) are left untouched so layout
     * tokens cannot be corrupted into {@code ⟦⟦N0⟧⟧}.</p>
     */
    public static String normalizeNumbers(String text, List<String> values) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (!text.contains("\u27e6")) {
            return normalizeNumbersInPlain(text, values);
        }
        Matcher matcher = EXISTING_MARKER_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                result.append(normalizeNumbersInPlain(text.substring(cursor, matcher.start()), values));
            }
            result.append(matcher.group());
            cursor = matcher.end();
        }
        if (cursor < text.length()) {
            result.append(normalizeNumbersInPlain(text.substring(cursor), values));
        }
        return result.toString();
    }

    private static String normalizeNumbersInPlain(String text, List<String> values) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Split on every legacy format pair before applying the candidate pattern.
        // A simple "preceded by §" check is not sufficient for §7123: the
        // matcher would consume 7123 as one number and corrupt the colour code.
        StringBuilder protectedFormats = new StringBuilder(text.length());
        int plainStart = 0;
        for (int index = 0; index < text.length(); ) {
            if (text.charAt(index) == '\u00a7' && index + 1 < text.length()
                    && isLegacyFormatCode(text.charAt(index + 1))) {
                protectedFormats.append(normalizeNumbersWithoutLegacyFormats(
                        text.substring(plainStart, index), values));
                protectedFormats.append(text, index, index + 2);
                index += 2;
                plainStart = index;
            } else {
                index += Character.charCount(text.codePointAt(index));
            }
        }
        protectedFormats.append(normalizeNumbersWithoutLegacyFormats(text.substring(plainStart), values));
        return protectedFormats.toString();
    }

    private static boolean isLegacyFormatCode(char code) {
        char normalized = Character.toLowerCase(code);
        return (normalized >= '0' && normalized <= '9')
                || (normalized >= 'a' && normalized <= 'f')
                || (normalized >= 'k' && normalized <= 'o')
                || normalized == 'r';
    }

    private static String normalizeNumbersWithoutLegacyFormats(String text, List<String> values) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = DYNAMIC_VALUE_CANDIDATE.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int cursor = 0;
        while (matcher.find()) {
            sb.append(text, cursor, matcher.start());
            if (isDynamicValue(text, matcher.start(), matcher.end())) {
                // Mask only the numeric part; keep the unit suffix (%, st/nd/rd/th,
                // ms, x/d/h/m/s) as literal text after the marker so the translator
                // can see the unit grammar and restore stays exact.
                Matcher split = NUMBER_SUFFIX_SPLIT.matcher(matcher.group());
                boolean hasSuffix = split.matches() && !split.group(2).isEmpty();
                values.add(hasSuffix ? split.group(1) : matcher.group());
                sb.append('\u27e6').append('N').append(values.size() - 1).append('\u27e7');
                if (hasSuffix) {
                    sb.append(split.group(2));
                }
            } else {
                sb.append(matcher.group());
            }
            cursor = matcher.end();
        }
        sb.append(text, cursor, text.length());
        return sb.toString();
    }

    /** Returns the end of a classified live value at {@code index}, or {@code index}. */
    static int dynamicValueEnd(String text, int index) {
        if (text == null || index < 0 || index >= text.length() || !tokenBoundaryBefore(text, index)) {
            return index;
        }
        Matcher matcher = DYNAMIC_VALUE_CANDIDATE.matcher(text);
        matcher.region(index, text.length());
        if (!matcher.lookingAt() || !tokenBoundaryAfter(text, matcher.end())
                || !isDynamicValue(text, index, matcher.end())) {
            return index;
        }
        return matcher.end();
    }

    private static boolean isDynamicValue(String text, int start, int end) {
        int lineStart = Math.max(text.lastIndexOf('\n', Math.max(0, start - 1)),
                text.lastIndexOf('\r', Math.max(0, start - 1))) + 1;
        int nextLf = text.indexOf('\n', end);
        int nextCr = text.indexOf('\r', end);
        int lineEnd = minPositive(nextLf, nextCr, text.length());
        String line = stripLegacyFormats(text.substring(lineStart, lineEnd));
        int localStart = stripLegacyFormats(text.substring(lineStart, start)).length();
        int localEnd = localStart + stripLegacyFormats(text.substring(start, end)).length();

        if (coveredBy(COORDINATE_GROUP, line, localStart, localEnd)) {
            return true;
        }
        String token = line.substring(Math.min(localStart, line.length()),
                Math.min(localEnd, line.length()));
        if (token.indexOf('/') >= 0 || token.matches("[+-]?\\d+(?::\\d+)+")) {
            return true;
        }
        if (isDataOnlyLine(line)) {
            return true;
        }

        String prefix = line.substring(0, Math.min(localStart, line.length())).strip();
        String suffix = line.substring(Math.min(localEnd, line.length())).strip();
        if (OBJECTIVE_PROSE.matcher(line).find()) {
            return false;
        }
        if (prefix.matches("(?s).*[:=]\\s*$") && isDataOnlyRemainder(suffix)) {
            return true;
        }
        if (LIVE_COUNTDOWN_LANGUAGE.matcher(line).find()) {
            return true;
        }
        boolean valueAtStatusEdge = prefix.isBlank() || isDataOnlyRemainder(suffix);
        return valueAtStatusEdge && LIVE_STATUS_LABEL.matcher(line).find();
    }

    private static boolean isDataOnlyLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String withoutValues = DYNAMIC_VALUE_CANDIDATE.matcher(line).replaceAll("");
        for (int offset = 0; offset < withoutValues.length(); ) {
            int cp = withoutValues.codePointAt(offset);
            if (Character.isLetterOrDigit(cp)) {
                return false;
            }
            offset += Character.charCount(cp);
        }
        return true;
    }

    private static boolean isDataOnlyRemainder(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return true;
        }
        return isDataOnlyLine(suffix);
    }

    private static boolean coveredBy(Pattern pattern, String text, int start, int end) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (matcher.start() <= start && matcher.end() >= end) {
                return true;
            }
        }
        return false;
    }

    private static int minPositive(int first, int second, int fallback) {
        int result = fallback;
        if (first >= 0) result = Math.min(result, first);
        if (second >= 0) result = Math.min(result, second);
        return result;
    }

    private static String stripLegacyFormats(String text) {
        return text == null ? "" : text.replaceAll("(?i)\\u00a7[0-9A-FK-OR]", "");
    }

    private static boolean tokenBoundaryBefore(String text, int index) {
        if (index <= 0) return true;
        int cp = text.codePointBefore(index);
        return !Character.isLetterOrDigit(cp) && cp != '_';
    }

    private static boolean tokenBoundaryAfter(String text, int index) {
        if (index >= text.length()) return true;
        int cp = text.codePointAt(index);
        return !Character.isLetterOrDigit(cp) && cp != '_';
    }

    /**
     * Restores dynamic numbers into a text string by replacing
     * {@code ⟦Ni⟧} markers with the original values.
     */
    public static String restoreNumbers(String text, List<String> values) {
        if (text == null || text.isEmpty() || values == null || values.isEmpty()) {
            return text;
        }
        Matcher matcher = DYNAMIC_MARKER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String replacement = index >= 0 && index < values.size() ? values.get(index) : matcher.group();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** True when a Component tree still contains an internal dynamic-number marker. */
    public static boolean containsDynamicMarker(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsJsonPrimitive().isString()
                    && DYNAMIC_MARKER_PATTERN.matcher(element.getAsString()).find();
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (containsDynamicMarker(child)) {
                    return true;
                }
            }
            return false;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (containsDynamicMarker(entry.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Cache templates may reuse only the exact marker multiset owned by their
     * normalized source tree. This rejects model-invented markers and prevents
     * translated punctuation from creating a second, unbindable marker domain.
     */
    public static boolean hasSameDynamicMarkerDomain(JsonElement source, JsonElement candidate) {
        if (source == null || candidate == null) {
            return false;
        }
        Map<Integer, Integer> sourceMarkers = new java.util.HashMap<>();
        Map<Integer, Integer> candidateMarkers = new java.util.HashMap<>();
        collectDynamicMarkers(source, sourceMarkers);
        collectDynamicMarkers(candidate, candidateMarkers);
        return sourceMarkers.equals(candidateMarkers);
    }

    private static void collectDynamicMarkers(JsonElement element, Map<Integer, Integer> counts) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            if (!element.getAsJsonPrimitive().isString()) {
                return;
            }
            Matcher matcher = DYNAMIC_MARKER_PATTERN.matcher(element.getAsString());
            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                counts.merge(index, 1, Integer::sum);
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectDynamicMarkers(child, counts);
            }
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                collectDynamicMarkers(entry.getValue(), counts);
            }
        }
    }

    /**
     * Recursively normalizes all {@code text} fields in a JSON element tree,
     * collecting dynamic values into {@code values}.
     */
    public static JsonElement normalizeNumbersRoot(JsonElement element, List<String> values) {
        if (element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()) {
            return new JsonPrimitive(normalizeNumbers(element.getAsString(), values));
        }
        normalizeNumbersInTree(element, values);
        return element;
    }

    public static void normalizeNumbersInTree(JsonElement element, List<String> values) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                JsonElement child = array.get(index);
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    String text = child.getAsString();
                    String normalized = normalizeNumbers(text, values);
                    if (!normalized.equals(text)) {
                        array.set(index, new JsonPrimitive(normalized));
                    }
                } else {
                    normalizeNumbersInTree(child, values);
                }
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject obj = element.getAsJsonObject();
        if (obj.has("text") && obj.get("text").isJsonPrimitive()) {
            String text = obj.get("text").getAsString();
            String normalized = normalizeNumbers(text, values);
            if (!normalized.equals(text)) {
                obj.addProperty("text", normalized);
            }
        }
        if (obj.has("with") && obj.get("with").isJsonArray()) {
            JsonArray withArray = obj.getAsJsonArray("with");
            for (int i = 0; i < withArray.size(); i++) {
                JsonElement arg = withArray.get(i);
                if (arg.isJsonPrimitive() && arg.getAsJsonPrimitive().isNumber()) {
                    values.add(arg.getAsString());
                    withArray.set(i, new JsonPrimitive(
                            "\u27e6N" + (values.size() - 1) + "\u27e7"));
                } else if (arg.isJsonPrimitive() && arg.getAsJsonPrimitive().isString()) {
                    String text = arg.getAsString();
                    String normalized = normalizeNumbers(text, values);
                    if (!normalized.equals(text)) {
                        withArray.set(i, new JsonPrimitive(normalized));
                    }
                } else {
                    normalizeNumbersInTree(arg, values);
                }
            }
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String key = entry.getKey();
            if (!"text".equals(key) && !"with".equals(key)) {
                normalizeNumbersInTree(entry.getValue(), values);
            }
        }
    }

    /**
     * Recursively restores dynamic numbers into all {@code text} fields.
     */
    public static JsonElement restoreNumbersRoot(JsonElement element, List<String> values) {
        if (element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()) {
            return new JsonPrimitive(restoreNumbers(element.getAsString(), values));
        }
        restoreNumbersInTree(element, values);
        return element;
    }

    public static void restoreNumbersInTree(JsonElement element, List<String> values) {
        if (element == null || element.isJsonNull() || values == null || values.isEmpty()) {
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                JsonElement child = array.get(index);
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    String text = child.getAsString();
                    String restored = restoreNumbers(text, values);
                    if (!restored.equals(text)) {
                        array.set(index, new JsonPrimitive(restored));
                    }
                } else {
                    restoreNumbersInTree(child, values);
                }
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject obj = element.getAsJsonObject();
        if (obj.has("text") && obj.get("text").isJsonPrimitive()) {
            String text = obj.get("text").getAsString();
            String restored = restoreNumbers(text, values);
            if (!restored.equals(text)) {
                obj.addProperty("text", restored);
            }
        }
        if (obj.has("with") && obj.get("with").isJsonArray()) {
            JsonArray withArray = obj.getAsJsonArray("with");
            for (int i = 0; i < withArray.size(); i++) {
                JsonElement arg = withArray.get(i);
                if (arg.isJsonPrimitive()) {
                    String s = arg.getAsString();
                    String restored = restoreNumbers(s, values);
                    if (!restored.equals(s)) {
                        try {
                            JsonElement numeric = new com.google.gson.JsonParser().parse(restored);
                            if (numeric.isJsonPrimitive()
                                    && numeric.getAsJsonPrimitive().isNumber()) {
                                withArray.set(i, numeric);
                            } else {
                                withArray.set(i, new JsonPrimitive(restored));
                            }
                        } catch (RuntimeException e) {
                            withArray.set(i, new JsonPrimitive(restored));
                        }
                    }
                } else {
                    restoreNumbersInTree(arg, values);
                }
            }
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String key = entry.getKey();
            if (!"text".equals(key) && !"with".equals(key)) {
                restoreNumbersInTree(entry.getValue(), values);
            }
        }
    }

    /** Stable prompt form that masks only classified live-value gaps. */
    public static String maskPromptDynamicNumbers(String readable) {
        if (readable == null || readable.isBlank()) {
            return "";
        }
        Matcher matcher = PROMPT_DYNAMIC_NUMBER_PATTERN.matcher(readable);
        StringBuilder masked = new StringBuilder(readable.length());
        int cursor = 0;
        while (matcher.find()) {
            masked.append(readable, cursor, matcher.start());
            if (isDynamicValue(readable, matcher.start(), matcher.end())) {
                // Same split as the request masking: the unit suffix stays visible
                // so prompt context matches the masked request shape ("every <number>m").
                Matcher split = NUMBER_SUFFIX_SPLIT.matcher(matcher.group());
                masked.append("<number>");
                if (split.matches()) {
                    masked.append(split.group(2));
                }
            } else {
                masked.append(matcher.group());
            }
            cursor = matcher.end();
        }
        masked.append(readable, cursor, readable.length());
        return masked.toString();
    }
}
