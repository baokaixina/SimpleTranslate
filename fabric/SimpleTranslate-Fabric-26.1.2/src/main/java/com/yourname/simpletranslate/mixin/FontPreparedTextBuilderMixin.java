package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yourname.simpletranslate.feature.wynn.WynnActionbarGlyphOverlayPlan;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hides callback-marked Wynn selector source glyphs without changing the
 * cursor advance calculated by {@code Font.PreparedTextBuilder}.
 *
 * <p>Masking is a ThreadLocal nest flag set for the duration of one
 * {@code accept} call. Matching on Component sourceIndex is intentionally
 * avoided: Font may renumber indices before createGlyph, which previously left
 * English dialogue quads visible under Chinese overlays.</p>
 *
 * <p>The enclosing vanilla method reads the glyph advance before this
 * invocation, always increments its x cursor afterwards, and treats a null
 * {@link TextRenderable.Styled} as "nothing to draw". Consequently the
 * original Wynn resource-pack positioning stream remains intact while an
 * overlay renderer can draw Chinese at the captured glyph coordinates.</p>
 */
@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public abstract class FontPreparedTextBuilderMixin {
    @WrapOperation(
            method = "accept(ILnet/minecraft/network/chat/Style;Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;createGlyph(FFIILnet/minecraft/network/chat/Style;FF)Lnet/minecraft/client/gui/font/TextRenderable$Styled;"
            ),
            require = 1
    )
    private TextRenderable.Styled simple_translate$maskCurrentWynnGlyph(
            BakedGlyph glyph,
            float x,
            float y,
            int textColor,
            int shadowColor,
            Style style,
            float boldOffset,
            float shadowOffset,
            Operation<TextRenderable.Styled> original) {
        if (WynnActionbarGlyphOverlayPlan.isCurrentGlyphMasked()) {
            return null;
        }
        return original.call(glyph, x, y, textColor, shadowColor, style, boldOffset, shadowOffset);
    }
}
