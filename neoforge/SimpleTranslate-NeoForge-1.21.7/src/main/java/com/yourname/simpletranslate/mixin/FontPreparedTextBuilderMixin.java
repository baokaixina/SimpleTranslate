package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yourname.simpletranslate.feature.wynn.WynnActionbarGlyphOverlayPlan;
import net.minecraft.client.gui.Font;
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
 * avoided: Font may renumber indices before glyph baking, which previously
 * left English dialogue quads visible under Chinese overlays.</p>
 *
 * <p>Minecraft 1.21.8 has no {@code BakedGlyph.createGlyph}/{@code TextRenderable}
 * seam: {@code PreparedTextBuilder.accept(int, Style, int)} resolves the glyph
 * internally and submits one {@link BakedGlyph.GlyphInstance} through the
 * private {@code addGlyph} call, then always increments its x cursor. Skipping
 * that single submission is therefore the exact 1.21.8 equivalent of returning
 * a null renderable in 1.21.9: the original Wynn resource-pack positioning
 * stream remains intact while an overlay renderer can draw Chinese at the
 * captured glyph coordinates.</p>
 */
@Mixin(Font.PreparedTextBuilder.class)
public abstract class FontPreparedTextBuilderMixin {
    @WrapOperation(
            method = "accept(ILnet/minecraft/network/chat/Style;I)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font$PreparedTextBuilder;addGlyph(Lnet/minecraft/client/gui/font/glyphs/BakedGlyph$GlyphInstance;)V"
            ),
            require = 1
    )
    private void simple_translate$maskCurrentWynnGlyph(
            Font.PreparedTextBuilder self,
            BakedGlyph.GlyphInstance instance,
            Operation<Void> original) {
        if (WynnActionbarGlyphOverlayPlan.isCurrentGlyphMasked()) {
            return;
        }
        original.call(self, instance);
    }
}
