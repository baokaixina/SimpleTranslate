package com.yourname.simpletranslate.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.config.ModConfig;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Layout-critical HUD / custom-font guards for Component JSON trees.
 *
 * <p>Keeps absolute-positioned PUA streams (resource-pack HUD actionbars,
 * multi-font HUD packs) from being remounted onto {@code minecraft:default}
 * after translation. Used by {@link JsonPassthroughPipeline}.</p>
 */
public final class ComponentJsonLayoutGuard {
    private ComponentJsonLayoutGuard() {
    }

    /**
     * True when a CJK target should leave a multi-region PUA-positioned HUD tree
     * untranslated. Custom layout fonts rarely ship CJK glyphs; translating and
     * then remounting CJK onto default collapses absolute positioning.
     */
    public static boolean shouldKeepLayoutCriticalHudOriginal(String sourceJson, String targetLanguage) {
        return shouldKeepLayoutCriticalHudOriginal("hud.actionbar", sourceJson, targetLanguage);
    }

    /**
     * The explicit original-only escape hatch is for actionbar coordinate
     * streams. It must not swallow a plain tooltip merely because the tooltip
     * happens to contain a decorative custom font or a PUA icon.
     */
    public static boolean shouldKeepLayoutCriticalHudOriginal(String surface, String sourceJson,
                                                              String targetLanguage) {
        String normalizedSurface = Surface.normalize(surface);
        if (!normalizedSurface.startsWith("hud.actionbar") && !normalizedSurface.startsWith("actionbar.")) {
            return false;
        }
        if (!ModConfig.LAYOUT_CRITICAL_HUD_KEEP_ORIGINAL.get()) {
            return false;
        }
        if (!isCjkTargetLanguage(targetLanguage) || sourceJson == null || sourceJson.isBlank()) {
            return false;
        }
        try {
            return isLayoutCriticalHudTree(new com.google.gson.JsonParser().parse(sourceJson));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Detects HUD trees whose font metrics encode placement. A known layout
     * font (for example resource-pack {@code hud/selector} families) needs only
     * one private-use positioning glyph to be layout-critical. The older
     * multi-font/PUA threshold remains as a fallback for unknown font packs.
     */
    public static boolean isLayoutCriticalHudTree(JsonElement root) {
        if (root == null || root.isJsonNull()) {
            return false;
        }
        LayoutFontStats stats = new LayoutFontStats();
        collectLayoutFontStats(root, false, stats);
        return (stats.hasKnownLayoutFont && stats.privateUseGlyphs > 0)
                || (stats.layoutFontIds.size() >= 2 && stats.privateUseGlyphs >= 4);
    }

    /**
     * In-place layout contract for layout-critical HUD trees: the translated
     * tree must mirror the source skeleton node-for-node — same array sizes,
     * same object keys, identical fonts/styles/click data — with only {@code
     * text} content (and translatable bare-string leaves) allowed to change.
     */
    public static boolean satisfiesInPlaceLayoutContract(String translationJson, String sourceJson) {
        if (sourceJson == null || sourceJson.isBlank()) {
            return true;
        }
        JsonElement source;
        try {
            source = new com.google.gson.JsonParser().parse(sourceJson);
        } catch (Exception e) {
            // Unparseable source: the contract cannot be assessed and does not apply.
            return true;
        }
        if (!isLayoutCriticalHudTree(source)) {
            return true;
        }
        if (translationJson == null || translationJson.isBlank()) {
            return false;
        }
        try {
            JsonElement translation = new com.google.gson.JsonParser().parse(translationJson);
            return matchesSkeletonExceptText(source, translation);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True when a cached translation remounted CJK onto {@code minecraft:default}
     * while the source still uses layout-critical fonts — the v3 collapse pattern.
     */
    public static boolean isLayoutBrokenCustomFontTranslation(String translationJson, String sourceJson) {
        if (translationJson == null || sourceJson == null) {
            return false;
        }
        try {
            JsonElement sourceRoot = new com.google.gson.JsonParser().parse(sourceJson);
            JsonElement translationRoot = new com.google.gson.JsonParser().parse(translationJson);
            if (!isLayoutCriticalHudTree(sourceRoot)) {
                return false;
            }
            return countCjkOnDefaultFont(translationRoot) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fonts whose resource path encodes screen regions (resource-pack HUD
     * selector fonts, etc.). Remounting visible text off these fonts breaks
     * absolute positioning even when PUA siblings keep the original font.
     */
    public static boolean isLayoutCriticalFont(@Nullable String font) {
        if (font == null || font.isBlank() || "minecraft:default".equals(font)) {
            return false;
        }
        String normalized = font.toLowerCase(Locale.ROOT);
        return normalized.contains("/hud/")
                || normalized.contains(":hud/")
                || normalized.startsWith("minecraft:hud")
                || normalized.contains("hud/selector")
                || normalized.contains("/selector/");
    }

    static void sanitizeTranslatedFonts(JsonElement element) {
        sanitizeTranslatedFonts(element, false, false);
    }

    private static void sanitizeTranslatedFonts(JsonElement element, boolean inheritedCustomFont,
                                                boolean inheritedLayoutFont) {
        if (!ModConfig.CUSTOM_FONT_CJK_FIX_ENABLED.get() || element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                sanitizeTranslatedFonts(child, inheritedCustomFont, inheritedLayoutFont);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        boolean explicitCustomFont = hasCustomFont(object);
        String explicitFont = readFont(object);
        boolean explicitLayoutFont = isLayoutCriticalFont(explicitFont);
        boolean effectiveCustomFont = explicitCustomFont || inheritedCustomFont;
        boolean effectiveLayoutFont = explicitLayoutFont
                || (inheritedLayoutFont && !hasDefaultFont(object));
        String text = null;
        if (object.has("text") && object.get("text").isJsonPrimitive()) {
            text = object.get("text").getAsString();
        }
        // Custom fonts that carry private-use positioning glyphs share one metric
        // space with their visible text. Remounting only the CJK half onto
        // minecraft:default splits that coordinate system (PUA actionbars).
        boolean puaLayoutFont = effectiveCustomFont && text != null
                && countPrivateUseCodepoints(text) > 0;
        // Layout fonts encode absolute screen coordinates. Remounting CJK onto
        // minecraft:default while leaving PUA siblings on the layout font splits
        // the coordinate system and collapses multi-region actionbars.
        if (effectiveCustomFont && !effectiveLayoutFont && !puaLayoutFont && text != null) {
            if (containsCjk(text)) {
                if (containsProtectedFontRuns(text)) {
                    splitMixedCustomFontText(object, text);
                } else {
                    object.addProperty("font", "minecraft:default");
                }
            }
        }

        boolean childInheritedCustomFont = hasCustomFont(object)
                || (inheritedCustomFont && !hasDefaultFont(object));
        boolean childInheritedLayoutFont = isLayoutCriticalFont(readFont(object))
                || puaLayoutFont
                || (inheritedLayoutFont && !hasDefaultFont(object));
        for (Map.Entry<String, JsonElement> entry : List.copyOf(object.entrySet())) {
            sanitizeTranslatedFonts(entry.getValue(), childInheritedCustomFont, childInheritedLayoutFont);
        }
    }

    private static boolean matchesSkeletonExceptText(JsonElement source, JsonElement translation) {
        if (source == null || source.isJsonNull()) {
            return translation == null || translation.isJsonNull();
        }
        if (translation == null || translation.isJsonNull()) {
            return false;
        }
        if (source.isJsonArray()) {
            if (!translation.isJsonArray()) {
                return false;
            }
            JsonArray sourceArray = source.getAsJsonArray();
            JsonArray translationArray = translation.getAsJsonArray();
            if (sourceArray.size() != translationArray.size()) {
                return false;
            }
            for (int i = 0; i < sourceArray.size(); i++) {
                if (!matchesSkeletonExceptText(sourceArray.get(i), translationArray.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (source.isJsonObject()) {
            return matchesComponentExceptText(source, translation);
        }
        if (!source.isJsonPrimitive() || !translation.isJsonPrimitive()) {
            return false;
        }
        // Bare string list elements are inline text content and may be translated.
        if (source.getAsJsonPrimitive().isString() && translation.getAsJsonPrimitive().isString()) {
            return true;
        }
        return source.equals(translation);
    }

    private static boolean matchesComponentExceptText(JsonElement source, JsonElement translation) {
        if (!source.isJsonObject() || translation == null || !translation.isJsonObject()) {
            return false;
        }
        JsonObject sourceObject = source.getAsJsonObject();
        JsonObject translationObject = translation.getAsJsonObject();
        // "text" is the one mutable field; serializer round trips may also omit
        // an empty text, so its presence is not part of the skeleton shape.
        Set<String> sourceKeys = new java.util.HashSet<>(com.yourname.simpletranslate.core.JsonCompat.keySet(sourceObject));
        Set<String> translationKeys = new java.util.HashSet<>(com.yourname.simpletranslate.core.JsonCompat.keySet(translationObject));
        sourceKeys.remove("text");
        translationKeys.remove("text");
        if (!sourceKeys.equals(translationKeys)) {
            return false;
        }
        if (translationObject.has("text")
                && !(translationObject.get("text").isJsonPrimitive()
                && translationObject.get("text").getAsJsonPrimitive().isString())) {
            return false;
        }
        for (String key : sourceKeys) {
            JsonElement sourceValue = sourceObject.get(key);
            JsonElement translationValue = translationObject.get(key);
            if ("extra".equals(key) || "with".equals(key)) {
                if (!matchesSkeletonExceptText(sourceValue, translationValue)) {
                    return false;
                }
                continue;
            }
            // font, color, style flags, click/hover data, shadow_color, etc.
            // must survive byte-for-byte.
            if (!sourceValue.equals(translationValue)) {
                return false;
            }
        }
        return true;
    }

    private static final class LayoutFontStats {
        final LinkedHashSet<String> layoutFontIds = new LinkedHashSet<>();
        boolean hasKnownLayoutFont;
        int privateUseGlyphs;
    }

    private static void collectLayoutFontStats(JsonElement element, boolean inheritedLayoutFont,
                                               LayoutFontStats stats) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectLayoutFontStats(child, inheritedLayoutFont, stats);
            }
            return;
        }
        if (!element.isJsonObject()) {
            // Bare string children inherit their parent's component style. Count
            // their positioning glyphs as well, otherwise a known layout-font
            // wrapper plus a string PUA leaf would evade layout detection.
            if (inheritedLayoutFont && element.isJsonPrimitive()
                    && element.getAsJsonPrimitive().isString()) {
                stats.privateUseGlyphs += countPrivateUseCodepoints(element.getAsString());
            }
            return;
        }
        JsonObject object = element.getAsJsonObject();
        String font = readFont(object);
        String text = null;
        if (object.has("text") && object.get("text").isJsonPrimitive()) {
            text = object.get("text").getAsString();
        }
        int puaCount = text == null ? 0 : countPrivateUseCodepoints(text);
        boolean puaHeavyCustomFont = hasCustomFont(object) && puaCount > 0;
        boolean knownLayoutFont = isLayoutCriticalFont(font);
        if (knownLayoutFont) {
            stats.hasKnownLayoutFont = true;
        }
        boolean layoutFont = knownLayoutFont
                || puaHeavyCustomFont
                || (inheritedLayoutFont && !hasDefaultFont(object));
        if (layoutFont && font != null && !font.isBlank() && !"minecraft:default".equals(font)) {
            stats.layoutFontIds.add(font);
        } else if (layoutFont && (font == null || font.isBlank())) {
            stats.layoutFontIds.add("__inherited_layout__");
        }
        if (layoutFont && puaCount > 0) {
            stats.privateUseGlyphs += puaCount;
        }
        boolean childInherited = layoutFont && !hasDefaultFont(object);
        if (object.has("extra")) {
            collectLayoutFontStats(object.get("extra"), childInherited, stats);
        }
        if (object.has("with")) {
            collectLayoutFontStats(object.get("with"), childInherited, stats);
        }
    }

    private static int countCjkOnDefaultFont(JsonElement element) {
        return countCjkOnDefaultFont(element, false);
    }

    private static int countCjkOnDefaultFont(JsonElement element, boolean inheritedDefault) {
        if (element == null || element.isJsonNull()) {
            return 0;
        }
        if (element.isJsonArray()) {
            int total = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                total += countCjkOnDefaultFont(child, inheritedDefault);
            }
            return total;
        }
        if (!element.isJsonObject()) {
            return 0;
        }
        JsonObject object = element.getAsJsonObject();
        boolean onDefault = hasDefaultFont(object)
                || (inheritedDefault && !hasCustomFont(object));
        int total = 0;
        if (onDefault && object.has("text") && object.get("text").isJsonPrimitive()
                && containsCjk(object.get("text").getAsString())) {
            total++;
        }
        boolean childInheritedDefault = hasDefaultFont(object)
                || (inheritedDefault && !hasCustomFont(object));
        if (object.has("extra")) {
            total += countCjkOnDefaultFont(object.get("extra"), childInheritedDefault);
        }
        if (object.has("with")) {
            total += countCjkOnDefaultFont(object.get("with"), childInheritedDefault);
        }
        return total;
    }

    private static boolean isCjkTargetLanguage(String targetLanguage) {
        String code = TranslationTextDetector.canonicalLanguageCode(targetLanguage);
        return "zh_cn".equals(code) || "zh_tw".equals(code)
                || "ja".equals(code) || "ko".equals(code);
    }

    private static boolean hasCustomFont(JsonObject object) {
        String font = readFont(object);
        return font != null && !font.isBlank() && !"minecraft:default".equals(font);
    }

    private static boolean hasDefaultFont(JsonObject object) {
        return "minecraft:default".equals(readFont(object));
    }

    @Nullable
    private static String readFont(JsonObject object) {
        if (object == null || !object.has("font") || !object.get("font").isJsonPrimitive()) {
            return null;
        }
        return object.get("font").getAsString();
    }

    private static void splitMixedCustomFontText(JsonObject object, String text) {
        JsonArray existingExtra = object.has("extra") && object.get("extra").isJsonArray()
                ? object.getAsJsonArray("extra")
                : null;
        JsonArray split = new JsonArray();
        int index = 0;
        while (index < text.length()) {
            int cp = text.codePointAt(index);
            boolean protectedRun;
            int end;
            if (cp == '\u00a7' && index + 1 < text.length()) {
                protectedRun = true;
                end = index + 1 + Character.charCount(text.codePointAt(index + 1));
            } else {
                protectedRun = isFontSplitProtected(cp);
                end = index + Character.charCount(cp);
                while (end < text.length()) {
                    int next = text.codePointAt(end);
                    if (next == '\u00a7' && end + 1 < text.length()) {
                        break;
                    }
                    if (isFontSplitProtected(next) != protectedRun) {
                        break;
                    }
                    end += Character.charCount(next);
                }
            }
            JsonObject segment = com.yourname.simpletranslate.core.JsonCompat.deepCopy(object);
            segment.addProperty("text", text.substring(index, end));
            segment.remove("extra");
            if (!protectedRun) {
                segment.addProperty("font", "minecraft:default");
            }
            split.add(segment);
            index = end;
        }
        if (existingExtra != null) {
            for (JsonElement child : existingExtra) {
                split.add(child);
            }
        }
        object.addProperty("text", "");
        object.add("extra", split);
    }

    private static int countPrivateUseCodepoints(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isPrivateUse(cp)) {
                count++;
            }
            i += Character.charCount(cp);
        }
        return count;
    }

    private static boolean containsCjk(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    static boolean containsProtectedFontRuns(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == '\u00a7' && i + 1 < text.length()) {
                return true;
            }
            if (isFontSplitProtected(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isFontSplitProtected(int cp) {
        return isPrivateUse(cp) || cp < 0x20 || cp == 0x7F;
    }

    private static boolean isPrivateUse(int cp) {
        return (cp >= 0xE000 && cp <= 0xF8FF)
                || (cp >= 0xC0000 && cp <= 0xDFFFF)
                || (cp >= 0xF0000 && cp <= 0xFFFFD)
                || (cp >= 0x100000 && cp <= 0x10FFFD);
    }
}
