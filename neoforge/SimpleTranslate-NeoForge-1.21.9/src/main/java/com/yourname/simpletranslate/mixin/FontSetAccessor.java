package com.yourname.simpletranslate.mixin;

import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessors for private FontSet glyph cache APIs. {@code SelectedGlyphs} is made
 * accessible via the mod access widener so the accessor descriptors match the
 * real field/method types (Object widening fails at apply time).
 */
@Mixin(FontSet.class)
public interface FontSetAccessor {
    @Accessor("missingSelectedGlyphs")
    FontSet.SelectedGlyphs simple_translate$getMissingSelectedGlyphs();

    @Invoker("getGlyph")
    FontSet.SelectedGlyphs simple_translate$invokeGetGlyph(int codePoint);
}
