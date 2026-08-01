package com.yourname.simpletranslate.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import net.minecraft.util.text.ITextComponent;

/** Exact Minecraft 1.12.2 Component JSON encode/decode adapter. */
public final class ComponentJsonCompat {
    private static final String LOCAL_FONT_NAMESPACE = "simple_translate";
    private static final String LOCAL_FONT_PATH_PREFIX = "local_font/";

    private ComponentJsonCompat() {}

    public static String toJson(ITextComponent component) {
        return ITextComponent.Serializer.componentToJson(component == null
                ? LegacyComponentFactory.empty() : component);
    }

    public static ITextComponent fromJson(String json) {
        ITextComponent parsed = ITextComponent.Serializer.jsonToComponent(json);
        if (parsed == null) throw new JsonParseException("Not a Component");
        return parsed;
    }

    public static ITextComponent fromJson(JsonElement element) {
        if (element == null) throw new JsonParseException("Not a Component");
        return fromJson(element.toString());
    }

    static boolean isLocalFontMarker(String fontId) {
        return fontId != null && fontId.startsWith(
                LOCAL_FONT_NAMESPACE + ":" + LOCAL_FONT_PATH_PREFIX);
    }

    /** 1.12.2 has no per-component font field. */
    public static ITextComponent reattachLocalFonts(ITextComponent translated, ITextComponent original) {
        return translated;
    }
}
