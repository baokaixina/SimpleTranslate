package com.yourname.simpletranslate.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

/**
 * Component JSON encode/decode for Minecraft 1.20 (Mojmap {@code Component.Serializer}).
 *
 * <p>1.20 styles carry plain {@code ResourceLocation} fonts, which the Gson
 * serializer always round-trips. The 1.21.x {@code FontDescription} sanitize /
 * marker / restore machinery has no 1.20 equivalent: {@link #reattachLocalFonts}
 * is a pass-through kept so shared pipeline call sites stay version-neutral, and
 * {@link #isLocalFontMarker} still recognizes 1.21.x marker ids that could arrive
 * through shared-cache entries produced by newer clients.</p>
 */
public final class ComponentJsonCompat {
    private static final String LOCAL_FONT_NAMESPACE = "simple_translate";
    private static final String LOCAL_FONT_PATH_PREFIX = "local_font/";

    private ComponentJsonCompat() {
    }

    public static String toJson(Component component) {
        Component source = component == null ? Component.empty() : component;
        return Component.Serializer.toJson(source);
    }

    public static Component fromJson(String json) {
        return fromJson(JsonParser.parseString(json));
    }

    public static Component fromJson(JsonElement element) {
        Component parsed = Component.Serializer.fromJson(element.toString());
        if (parsed == null) {
            throw new JsonParseException("Not a Component: " + element);
        }
        return parsed;
    }

    static boolean isLocalFontMarker(String fontId) {
        return fontId != null && fontId.startsWith(
                LOCAL_FONT_NAMESPACE + ":" + LOCAL_FONT_PATH_PREFIX);
    }

    /**
     * 1.20 fonts are plain ResourceLocations and survive JSON round-trips
     * unchanged, so marker restoration is a no-op on this version.
     */
    public static Component reattachLocalFonts(Component translated, Component original) {
        return translated;
    }
}
