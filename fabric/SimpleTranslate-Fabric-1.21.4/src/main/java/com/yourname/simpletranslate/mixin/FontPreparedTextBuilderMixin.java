package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yourname.simpletranslate.feature.wynn.WynnActionbarGlyphOverlayPlan;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Hides callback-marked Wynn selector source glyphs without changing the
 * cursor advance calculated by the font render output.
 *
 * <p>Masking is a ThreadLocal nest flag set for the duration of one
 * {@code accept} call. Matching on Component sourceIndex is intentionally
 * avoided: Font may renumber indices before glyph baking, which previously
 * left English dialogue quads visible under Chinese overlays.</p>
 *
 * <p>Minecraft 1.21.5 has no {@code Font.PreparedTextBuilder} and no
 * {@code BakedGlyph.createGlyph}/{@code TextRenderable} seam: the private
 * {@code Font.StringRenderOutput.accept(int, Style, int)} resolves the glyph
 * internally and queues one {@link BakedGlyph.GlyphInstance} for
 * {@code renderCharacters()}, then always increments its x cursor. Skipping
 * that single queue insertion is therefore the exact 1.21.5 equivalent of
 * returning a null renderable in 1.21.9: the original Wynn resource-pack
 * positioning stream remains intact while an overlay renderer can draw
 * Chinese at the captured glyph coordinates.</p>
 */
@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public abstract class FontPreparedTextBuilderMixin {
    @WrapOperation(
            method = "accept(ILnet/minecraft/network/chat/Style;I)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;add(Ljava/lang/Object;)Z"
            ),
            require = 1
    )
    private boolean simple_translate$maskCurrentWynnGlyph(
            List<BakedGlyph.GlyphInstance> glyphInstances,
            Object instance,
            Operation<Boolean> original) {
        if (WynnActionbarGlyphOverlayPlan.isCurrentGlyphMasked()) {
            return true;
        }
        return original.call(glyphInstances, instance);
    }
}
