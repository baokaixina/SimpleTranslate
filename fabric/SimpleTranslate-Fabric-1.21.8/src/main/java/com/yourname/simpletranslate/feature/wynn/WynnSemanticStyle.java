package com.yourname.simpletranslate.feature.wynn;

import com.yourname.simpletranslate.core.ActiveFontManager;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.TextColor;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Builds Wynn prose styles from source-owned visible properties only.
 *
 * <p>The server resource pack remains authoritative for source glyphs. A CJK
 * overlay deliberately changes only the font; colour, shadow and decorations
 * stay with the source text so translated prose cannot silently adopt a model
 * response's styling.</p>
 */
final class WynnSemanticStyle {
    private static final ResourceLocation CJK_FONT = ActiveFontManager.CJK_FALLBACK_FONT;

    private WynnSemanticStyle() {
    }

    static Style forRequest(@Nullable Style source) {
        return build(source, Style.DEFAULT_FONT);
    }

    static Style forOverlay(@Nullable Style source) {
        return build(source, CJK_FONT);
    }

    /**
     * Compares only source properties that affect the visible glyph quad. Hover,
     * click and insertion metadata are intentionally excluded: actionbar
     * overlays cannot render or activate them.
     */
    static boolean sameStableStyle(@Nullable Style first, @Nullable Style second) {
        Style left = safe(first);
        Style right = safe(second);
        return sameOverlayAppearance(left, right)
                && Objects.equals(left.getFont(), right.getFont());
    }

    /**
     * Compares BODY-visible styling only. Callers must separately verify that
     * the source fonts are legitimate variants of one dialogue BODY family;
     * ignoring font identity here is safe only after that structural check.
     */
    static boolean sameBodyOverlayAppearance(@Nullable Style first, @Nullable Style second) {
        return sameOverlayAppearance(safe(first), safe(second));
    }

    private static boolean sameOverlayAppearance(Style left, Style right) {
        return sameColor(left.getColor(), right.getColor())
                && Objects.equals(left.getShadowColor(), right.getShadowColor())
                && left.isBold() == right.isBold()
                && left.isItalic() == right.isItalic()
                && left.isUnderlined() == right.isUnderlined()
                && left.isStrikethrough() == right.isStrikethrough()
                && left.isObfuscated() == right.isObfuscated();
    }

    /**
     * Compares decorations only. Multi-style BODY paragraphs are translatable
     * while bold/italic/underline/strikethrough stay uniform; colour and
     * shadow may vary per run and are mapped back span by span from the
     * source appearances.
     */
    static boolean sameDecorations(@Nullable Style first, @Nullable Style second) {
        Style left = safe(first);
        Style right = safe(second);
        return left.isBold() == right.isBold()
                && left.isItalic() == right.isItalic()
                && left.isUnderlined() == right.isUnderlined()
                && left.isStrikethrough() == right.isStrikethrough();
    }

    /**
     * Wynn's dialogue shader treats #00EB34 as a movement instruction
     * (movementItalic), not as a display colour. A reordered CJK overlay can
     * never reproduce that shader effect, so a BODY containing the marker
     * stays entirely on Wynn's original glyph stream.
     */
    static boolean isShaderMarkerColour(@Nullable Style source) {
        TextColor color = safe(source).getColor();
        return color != null && (color.getValue() & 0xFFFFFF) == 0x00EB34;
    }

    /**
     * A random/obfuscated source run has no stable per-character geometry, so a
     * reordered CJK overlay cannot reproduce it faithfully.
     */
    static boolean isOverlaySafe(@Nullable Style source) {
        return !safe(source).isObfuscated();
    }

    /**
     * Stable source-owned key used for request/layout identities. It includes
     * explicit RGB colours (not only named chat colours) and the source font,
     * while deliberately excluding non-visible interaction metadata.
     */
    static String visualFingerprint(@Nullable Style source) {
        Style raw = safe(source);
        TextColor color = raw.getColor();
        String colorKey = color == null ? "none"
                : Integer.toUnsignedString(color.getValue(), 16);
        return colorKey
                + '/' + Objects.toString(raw.getShadowColor(), "none")
                + '/' + raw.isBold()
                + '/' + raw.isItalic()
                + '/' + raw.isUnderlined()
                + '/' + raw.isStrikethrough()
                + '/' + raw.isObfuscated()
                + '/' + Objects.toString(raw.getFont(), "default");
    }

    private static Style build(@Nullable Style source, ResourceLocation font) {
        Style raw = safe(source);
        Style result = Style.EMPTY
                .withBold(raw.isBold())
                .withItalic(raw.isItalic())
                .withUnderlined(raw.isUnderlined())
                .withStrikethrough(raw.isStrikethrough())
                // Obfuscated text has unstable glyph geometry. BODY projection
                // refuses it before an overlay is installed.
                .withObfuscated(false)
                .withFont(font);
        if (raw.getColor() != null) {
            result = result.withColor(raw.getColor());
        }
        if (raw.getShadowColor() != null) {
            result = result.withShadowColor(raw.getShadowColor());
        }
        return result;
    }

    private static boolean sameColor(@Nullable TextColor first, @Nullable TextColor second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.getValue() == second.getValue();
    }

    private static Style safe(@Nullable Style style) {
        return style == null ? Style.EMPTY : style;
    }
}
