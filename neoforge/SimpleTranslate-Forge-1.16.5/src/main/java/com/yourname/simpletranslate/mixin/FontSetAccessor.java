package com.yourname.simpletranslate.mixin;

import net.minecraft.client.gui.fonts.Font;
import net.minecraft.client.gui.fonts.TexturedGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Font.class)
public interface FontSetAccessor {
    @Accessor("missingGlyph")
    TexturedGlyph simple_translate$getMissingGlyph();
}
