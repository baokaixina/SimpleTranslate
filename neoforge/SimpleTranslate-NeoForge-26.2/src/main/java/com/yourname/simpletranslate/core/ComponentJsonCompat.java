package com.yourname.simpletranslate.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ComponentJsonCompat {
    private static final String LOCAL_FONT_NAMESPACE = "simple_translate";
    private static final String LOCAL_FONT_PATH_PREFIX = "local_font/";

    private ComponentJsonCompat() {
    }

    public static String toJson(Component component) {
        Component source = component == null ? Component.empty() : component;
        try {
            return encode(source);
        } catch (RuntimeException originalFailure) {
            SanitizedComponent sanitized = sanitizeUnsupportedFonts(source);
            if (!sanitized.changed()) {
                throw originalFailure;
            }
            return encode(sanitized.component());
        }
    }

    public static Component fromJson(String json) {
        return fromJson(JsonParser.parseString(json));
    }

    public static Component fromJson(JsonElement element) {
        return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
    }

    /**
     * The current Minecraft target deliberately excludes AtlasSprite and PlayerSprite from
     * {@link FontDescription#CODEC}. Client overlays can still put those fonts
     * on visible Components, so a direct Component JSON encode otherwise fails
     * before adjacent prose reaches the translation pipeline.
     *
     * <p>The fallback materializes visible runs and substitutes deterministic,
     * serializable Resource markers only for the unsupported local font. The
     * visual projection keeps every marker run model-invisible, and
     * {@link #reattachLocalFonts(Component, Component)} restores the exact live
     * font object after the translated Component has parsed.</p>
     */
    private static SanitizedComponent sanitizeUnsupportedFonts(Component source) {
        MutableComponent visible = Component.empty();
        boolean[] changed = {false};
        source.visit((style, text) -> {
            Style effective = style == null ? Style.EMPTY : style;
            FontDescription font = effective.getFont();
            if (!(font instanceof FontDescription.Resource)) {
                effective = effective.withFont(markerFor(font));
                changed[0] = true;
            }
            if (text != null && !text.isEmpty()) {
                visible.append(Component.literal(text).setStyle(effective));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return new SanitizedComponent(changed[0] ? visible : source, changed[0]);
    }

    /** Restores only local fonts represented by this class's private markers. */
    public static Component reattachLocalFonts(Component translated, Component original) {
        if (translated == null || original == null) {
            return translated;
        }
        Map<String, FontDescription> localFonts = new LinkedHashMap<>();
        original.visit((style, text) -> {
            Style effective = style == null ? Style.EMPTY : style;
            FontDescription font = effective.getFont();
            if (!(font instanceof FontDescription.Resource)) {
                localFonts.put(markerFor(font).id().toString(), font);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return localFonts.isEmpty() ? translated : restoreMarkers(translated, localFonts);
    }

    static boolean isLocalFontMarker(String fontId) {
        return fontId != null && fontId.startsWith(
                LOCAL_FONT_NAMESPACE + ":" + LOCAL_FONT_PATH_PREFIX);
    }

    private static Component restoreMarkers(Component component,
                                            Map<String, FontDescription> localFonts) {
        MutableComponent restored = component.plainCopy();
        Style style = component.getStyle();
        FontDescription font = style.getFont();
        if (font instanceof FontDescription.Resource resource) {
            FontDescription local = localFonts.get(resource.id().toString());
            if (local != null) {
                style = style.withFont(local);
            }
        }
        restored.setStyle(style);
        for (Component sibling : component.getSiblings()) {
            restored.append(restoreMarkers(sibling, localFonts));
        }
        return restored;
    }

    private static FontDescription.Resource markerFor(FontDescription font) {
        String kind = font instanceof FontDescription.AtlasSprite
                ? "atlas_sprite"
                : font instanceof FontDescription.PlayerSprite
                ? "player_sprite" : "unsupported";
        String signature = font.getClass().getName() + '\n' + font;
        String digest = sha256(signature);
        Identifier id = Identifier.fromNamespaceAndPath(
                LOCAL_FONT_NAMESPACE, LOCAL_FONT_PATH_PREFIX + kind + "/" + digest);
        return new FontDescription.Resource(id);
    }

    private static String encode(Component component) {
        return ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, component)
                .getOrThrow().toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record SanitizedComponent(Component component, boolean changed) {
    }
}
