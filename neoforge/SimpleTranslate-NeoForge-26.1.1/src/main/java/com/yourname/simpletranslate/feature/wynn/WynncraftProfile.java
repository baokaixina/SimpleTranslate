package com.yourname.simpletranslate.feature.wynn;

import com.yourname.simpletranslate.core.ComponentSegmentHelper;
import com.yourname.simpletranslate.core.TextSegmentInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Structural detection for Wynncraft's decorated text. Exact fonts, private-use
 * anchors, and verified layout grammar are the only ownership signals.
 */
public final class WynncraftProfile {
    private WynncraftProfile() {
    }

    /**
     * Character selection/actionbar detection. It accepts the public Wynn
     * selector grammar only when a PUA anchor and selector font prove this is
     * an absolute-positioned HUD.
     */
    public static boolean matchesActionbar(@Nullable Component component) {
        return matchesActionbarStructure(component);
    }

    /**
     * Package-private structural seam shared by the direct renderer and its
     * deterministic fixtures.
     */
    static boolean matchesActionbarStructure(@Nullable Component component) {
        if (component == null) {
            return false;
        }
        List<TextSegmentInfo> segments = segmentsOf(component);
        boolean selectorFont = false;
        boolean privateUse = false;
        StringBuilder text = new StringBuilder();
        for (TextSegmentInfo segment : segments) {
            String value = segment == null || segment.text == null ? "" : segment.text;
            text.append(value);
            privateUse |= containsPrivateUse(value);
            selectorFont |= isSelectorFont(segment == null ? Style.EMPTY : segment.style);
        }
        String flattened = text.toString();
        return privateUse && selectorFont && matchesKnownSelectorGrammar(flattened);
    }

    /** Shared structural predicate for a semantic projection. */
    static boolean isProtectedFont(@Nullable Style style) {
        String font = fontId(style);
        return font.contains("keybind") || font.contains("font-common")
                || font.contains("font_common") || font.contains("font-five")
                || font.contains("font_five") || font.contains("element")
                || font.contains("icon");
    }

    static boolean isSelectorFont(@Nullable Style style) {
        String font = fontId(style);
        return font.contains("hud/selector") || font.contains("/selector/");
    }

    static String fontId(@Nullable Style style) {
        if (style == null || style.getFont() == null) {
            return "";
        }
        FontDescription font = style.getFont();
        if (font instanceof FontDescription.Resource resource) {
            return resource.id().toString().toLowerCase(Locale.ROOT);
        }
        return font.toString().toLowerCase(Locale.ROOT);
    }

    static boolean containsPrivateUse(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if ((cp >= 0xE000 && cp <= 0xF8FF)
                    || (cp >= 0xC0000 && cp <= 0xDFFFF)
                    || (cp >= 0xF0000 && cp <= 0xFFFFD)
                    || (cp >= 0x100000 && cp <= 0x10FFFD)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * The public character-select grammar is intentionally exact. We tolerate
     * raw controls, legacy formatting and arbitrary fixed spacing, but do not
     * activate merely because a random HUD happens to mention "Left-Click".
     */
    private static boolean matchesKnownSelectorGrammar(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String normalized = normalizeSelectorGrammar(text);
        String selectBrowseReturn = "(?s).*\\uE000.*?left\\s*-\\s*click\\s*to\\s*select.*?"
                + "\\uE002.*?scroll\\s*up\\s*/\\s*down\\s*to\\s*browse.*?"
                + "\\uE001.*?right\\s*-\\s*click\\s*to\\s*return.*";
        String playSwitch = "(?s).*\\uE000.*?left\\s*-\\s*click\\s*to\\s*play.*?"
                + "\\uE001.*?right\\s*-\\s*click\\s*to\\s*switch.*";
        return normalized.matches(selectBrowseReturn) || normalized.matches(playSwitch);
    }

    private static String normalizeSelectorGrammar(String text) {
        StringBuilder normalized = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); ) {
            int cp = text.codePointAt(index);
            if (cp == '§' && index + 1 < text.length()) {
                index += 1 + Character.charCount(text.codePointAt(index + 1));
                continue;
            }
            if (cp < 0x20 || cp == 0x7F) {
                index += Character.charCount(cp);
                continue;
            }
            normalized.appendCodePoint(Character.toLowerCase(cp));
            index += Character.charCount(cp);
        }
        return normalized.toString();
    }

    private static List<TextSegmentInfo> segmentsOf(@Nullable Component component) {
        if (component == null) {
            return List.of();
        }
        List<TextSegmentInfo> result = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, result, Style.EMPTY, false);
        return result;
    }
}
