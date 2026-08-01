package com.yourname.simpletranslate.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/**
 * Component JSON encode/decode for Minecraft 1.21.8 (Mojmap {@code ComponentSerialization}).
 *
 * <p>1.21.8 styles carry plain {@code ResourceLocation} fonts, which the CODEC
 * round-trips. The 1.21.9+ {@code FontDescription} sanitize / marker / restore
 * machinery has no 1.21.8 equivalent: {@link #reattachLocalFonts} is a
 * pass-through kept so shared pipeline call sites stay version-neutral, and
 * {@link #isLocalFontMarker} still recognizes 1.21.9+ marker ids that could
 * arrive through shared-cache entries produced by newer clients.</p>
 */
public final class ComponentJsonCompat {
    private static final String LOCAL_FONT_NAMESPACE = "simple_translate";
    private static final String LOCAL_FONT_PATH_PREFIX = "local_font/";

    private ComponentJsonCompat() {
    }

    public static String toJson(Component component) {
        Component source = component == null ? Component.empty() : component;
        return ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, source)
                .getOrThrow().toString();
    }

    public static Component fromJson(String json) {
        return fromJson(JsonParser.parseString(json));
    }

    public static Component fromJson(JsonElement element) {
        return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
    }

    static boolean isLocalFontMarker(String fontId) {
        return fontId != null && fontId.startsWith(
                LOCAL_FONT_NAMESPACE + ":" + LOCAL_FONT_PATH_PREFIX);
    }

    /**
     * 1.21.8 fonts are plain ResourceLocations and survive JSON round-trips
     * unchanged, so marker restoration is a no-op on this version.
     */
    public static Component reattachLocalFonts(Component translated, Component original) {
        return translated;
    }
}
