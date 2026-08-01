package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.font.GlyphInfo;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.ActiveFontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.MissingGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When the active source {@link FontSet} is genuinely missing a code point,
 * borrow the glyph from the mod-owned {@code simple_translate:cjk} font.
 * This includes {@code minecraft:default}: server resource packs can replace
 * that font with ASCII-only providers. Private-use icons stay on the original
 * font, and the fallback set itself never recurses.
 */
@Mixin(FontSet.class)
public abstract class FontSetMixin {
    @Unique
    private static final ThreadLocal<Boolean> simple_translate$fallingBack =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "getGlyphInfo", at = @At("RETURN"), cancellable = true, require = 1)
    private void simple_translate$fallbackGlyphInfo(int codePoint,
                                                     CallbackInfoReturnable<GlyphInfo> cir) {
        GlyphInfo original = cir.getReturnValue();
        if (original != MissingGlyph.INSTANCE || !simple_translate$canFallback(codePoint)) {
            return;
        }
        FontSet fallback = ActiveFontManager.getCjkFallbackFontSet();
        FontSet self = (FontSet) (Object) this;
        if (fallback == null || fallback == self) {
            return;
        }
        simple_translate$fallingBack.set(Boolean.TRUE);
        try {
            GlyphInfo glyph = fallback.getGlyphInfo(codePoint);
            if (glyph != MissingGlyph.INSTANCE) {
                cir.setReturnValue(glyph);
            }
        } finally {
            simple_translate$fallingBack.set(Boolean.FALSE);
        }
    }

    @Inject(method = "getGlyph", at = @At("RETURN"), cancellable = true, require = 1)
    private void simple_translate$fallbackBakedGlyph(int codePoint,
                                                      CallbackInfoReturnable<BakedGlyph> cir) {
        FontSet self = (FontSet) (Object) this;
        if (cir.getReturnValue() != ((FontSetAccessor) self).simple_translate$getMissingGlyph()
                || !simple_translate$canFallback(codePoint)) {
            return;
        }
        FontSet fallback = ActiveFontManager.getCjkFallbackFontSet();
        if (fallback == null || fallback == self) {
            return;
        }
        simple_translate$fallingBack.set(Boolean.TRUE);
        try {
            BakedGlyph glyph = fallback.getGlyph(codePoint);
            if (glyph != ((FontSetAccessor) fallback).simple_translate$getMissingGlyph()) {
                cir.setReturnValue(glyph);
            }
        } finally {
            simple_translate$fallingBack.set(Boolean.FALSE);
        }
    }

    @Unique
    private static boolean simple_translate$canFallback(int codePoint) {
        return ModConfig.CUSTOM_FONT_CJK_FIX_ENABLED.get()
                && !Boolean.TRUE.equals(simple_translate$fallingBack.get())
                && !simple_translate$isPrivateUse(codePoint);
    }

    @Unique
    private static boolean simple_translate$isPrivateUse(int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                // Positioned resource-pack glyphs also live on the
                // otherwise-unassigned planes 12, 13, 15 and 16.
                || (codePoint >= 0xC0000 && codePoint <= 0xDFFFF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }
}
