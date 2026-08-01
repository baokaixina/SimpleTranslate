package com.yourname.simpletranslate.vanillacompat;

import net.minecraft.util.text.ITextComponent;

/** No-op narration sink used by widgets on the pre-1.19 screen API. */
public interface NarrationElementOutput {
    default void add(NarratedElementType type, ITextComponent component) {
    }
}
