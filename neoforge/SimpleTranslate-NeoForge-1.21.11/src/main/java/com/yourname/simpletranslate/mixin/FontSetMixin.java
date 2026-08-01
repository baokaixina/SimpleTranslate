package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.ActiveFontManager;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * When any active {@link FontSet} has no glyph for a code point, borrow the glyph
 * (including advance) from the mod-owned {@code simple_translate:cjk} font.
 * This includes {@code minecraft:default}: server resource packs can replace
 * that font with ASCII-only providers. Private-use icons stay on the original
 * font, and the fallback set itself never recurses.
 */
@Mixin(FontSet.class)
public abstract class FontSetMixin {
    @Unique
    private static final ThreadLocal<Boolean> simple_translate$fallingBack =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @ModifyReturnValue(method = "computeGlyphInfo", at = @At("RETURN"))
    private FontSet.SelectedGlyphs simple_translate$fallbackMissingGlyph(
            FontSet.SelectedGlyphs original, int codePoint) {
        if (!ModConfig.CUSTOM_FONT_CJK_FIX_ENABLED.get()
                || original == null
                || simple_translate$isPrivateUse(codePoint)
                || Boolean.TRUE.equals(simple_translate$fallingBack.get())) {
            return original;
        }

        FontSet self = (FontSet) (Object) this;
        FontSetAccessor selfAccess = (FontSetAccessor) self;
        if (original != selfAccess.simple_translate$getMissingSelectedGlyphs()) {
            return original;
        }

        FontSet cjkFallback = ActiveFontManager.getCjkFallbackFontSet();
        if (cjkFallback == null || cjkFallback == self) {
            return original;
        }

        simple_translate$fallingBack.set(Boolean.TRUE);
        try {
            FontSetAccessor fallbackAccess = (FontSetAccessor) cjkFallback;
            FontSet.SelectedGlyphs fallback =
                    fallbackAccess.simple_translate$invokeGetGlyph(codePoint);
            if (fallback == null
                    || fallback == fallbackAccess.simple_translate$getMissingSelectedGlyphs()) {
                return original;
            }
            return fallback;
        } catch (Throwable ignored) {
            return original;
        } finally {
            simple_translate$fallingBack.set(Boolean.FALSE);
        }
    }

    @Unique
    private static boolean simple_translate$isPrivateUse(int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                // Wynn selector/dialogue packs use the otherwise-unassigned
                // planes 12 and 13 for positioned resource glyphs.
                || (codePoint >= 0xC0000 && codePoint <= 0xDFFFF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }
}
