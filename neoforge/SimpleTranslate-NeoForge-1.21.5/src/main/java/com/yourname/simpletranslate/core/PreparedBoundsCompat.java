package com.yourname.simpletranslate.core;

import com.mojang.blaze3d.font.GlyphInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 1.21.5 replacement for the 1.21.6+ {@code Font#prepareText(...).bounds()}
 * query. Walks a {@link FormattedCharSequence} through the live {@link FontSet}
 * stack and accumulates exactly the per-glyph quad bounds that 1.21.6 computes:
 * {@code x + glyph.left + min(shear) - thickness} through
 * {@code x + glyph.right + shadowOffset + max(shear) + thickness}, with
 * {@code shearTop = 1 - 0.25 * up}, {@code shearBottom = 1 - 0.25 * down} and
 * {@code extraThickness(bold) = 0.1}, mirroring the decompiled 1.21.6+ formula.
 *
 * <p>Font#getFontSet and the BakedGlyph quad fields are private on 1.21.5.
 * They are reached reflectively (cached handles) so this helper works both in
 * the client and inside the mixin-less offline fixture harness. Every call
 * site draws with a shadow, so the right/bottom shadowOffset expansion is
 * applied exactly as vanilla does when hasShadow() is true.</p>
 */
public final class PreparedBoundsCompat {
    @Nullable
    private static final Method GET_FONT_SET = findGetFontSet();
    @Nullable
    private static final Field GLYPH_LEFT = findGlyphField("left");
    @Nullable
    private static final Field GLYPH_RIGHT = findGlyphField("right");
    @Nullable
    private static final Field GLYPH_UP = findGlyphField("up");
    @Nullable
    private static final Field GLYPH_DOWN = findGlyphField("down");

    private PreparedBoundsCompat() {
    }

    @Nullable
    public static ScreenRectangle bounds(Font font, FormattedCharSequence sequence) {
        return bounds(font, sequence, true);
    }

    /**
     * Measures one sequence exactly as 1.21.6's prepareText(..., shadow, 0)
     * would: the right/bottom shadowOffset expansion applies only when
     * {@code shadow} is true (absolute blocker probes pass false).
     */
    @Nullable
    public static ScreenRectangle bounds(Font font, FormattedCharSequence sequence, boolean shadow) {
        if (font == null || sequence == null || GET_FONT_SET == null
                || GLYPH_LEFT == null || GLYPH_RIGHT == null || GLYPH_UP == null || GLYPH_DOWN == null) {
            return null;
        }
        Walker walker = new Walker(font, shadow);
        sequence.accept(walker);
        if (!walker.anyGlyph) {
            return null;
        }
        int left = (int) Math.floor(walker.left);
        int top = (int) Math.floor(walker.top);
        int right = (int) Math.ceil(walker.right);
        int bottom = (int) Math.ceil(walker.bottom);
        return new ScreenRectangle(left, top, right - left, bottom - top);
    }

    @Nullable
    private static FontSet fontSet(Font font, ResourceLocation id) {
        try {
            return (FontSet) GET_FONT_SET.invoke(font, id);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static float quad(Field field, BakedGlyph glyph) {
        try {
            return field.getFloat(glyph);
        } catch (ReflectiveOperationException impossible) {
            return 0.0F;
        }
    }

    @Nullable
    private static Method findGetFontSet() {
        try {
            Method method = Font.class.getDeclaredMethod("getFontSet", ResourceLocation.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException missing) {
            return null;
        }
    }

    @Nullable
    private static Field findGlyphField(String name) {
        try {
            Field field = BakedGlyph.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException missing) {
            return null;
        }
    }

    private static final class Walker implements FormattedCharSink {
        private final Font font;
        private final boolean shadow;
        private float x;
        private float left = Float.MAX_VALUE;
        private float top = Float.MAX_VALUE;
        private float right = -Float.MAX_VALUE;
        private float bottom = -Float.MAX_VALUE;
        private boolean anyGlyph;

        private Walker(Font font, boolean shadow) {
            this.font = font;
            this.shadow = shadow;
        }

        @Override
        public boolean accept(int index, Style style, int codePoint) {
            Style safe = style == null ? Style.EMPTY : style;
            FontSet fontSet = fontSet(font, safe.getFont());
            if (fontSet == null) {
                return true;
            }
            // Minecraft constructs the client Font with filterFishyGlyphs=false.
            GlyphInfo info = fontSet.getGlyphInfo(codePoint, false);
            BakedGlyph glyph = safe.isObfuscated() && codePoint != 32
                    ? fontSet.getRandomGlyph(info) : fontSet.getGlyph(codePoint);
            boolean bold = safe.isBold();
            float advance = info.getAdvance(bold);
            if (!(glyph instanceof EmptyGlyph)) {
                float up = quad(GLYPH_UP, glyph);
                float down = quad(GLYPH_DOWN, glyph);
                float shearTop = 1.0F - 0.25F * up;
                float shearBottom = 1.0F - 0.25F * down;
                boolean italic = safe.isItalic();
                float thickness = bold ? 0.1F : 0.0F;
                float shadowOffset = shadow ? info.getShadowOffset() : 0.0F;
                float glyphLeft = x + quad(GLYPH_LEFT, glyph)
                        + (italic ? Math.min(shearTop, shearBottom) : 0.0F) - thickness;
                float glyphTop = up - thickness;
                float glyphRight = x + quad(GLYPH_RIGHT, glyph) + shadowOffset
                        + (italic ? Math.max(shearTop, shearBottom) : 0.0F) + thickness;
                float glyphBottom = down + shadowOffset + thickness;
                left = Math.min(left, glyphLeft);
                top = Math.min(top, glyphTop);
                right = Math.max(right, glyphRight);
                bottom = Math.max(bottom, glyphBottom);
                anyGlyph = true;
            }
            x += advance;
            return true;
        }
    }
}
