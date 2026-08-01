package com.yourname.simpletranslate.mixin;

import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FontSet.class)
public interface FontSetAccessor {
    @Accessor("missingGlyph")
    BakedGlyph simple_translate$getMissingGlyph();
}
