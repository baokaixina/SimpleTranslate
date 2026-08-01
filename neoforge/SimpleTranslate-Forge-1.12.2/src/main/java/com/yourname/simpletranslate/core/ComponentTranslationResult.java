package com.yourname.simpletranslate.core;

import net.minecraft.util.text.ITextComponent;

/** Result of translating one Minecraft component. */
public final class ComponentTranslationResult {
    public final ITextComponent component;
    public final boolean handled;
    public final boolean translated;

    public ComponentTranslationResult(ITextComponent component, boolean handled, boolean translated) {
        this.component = component;
        this.handled = handled;
        this.translated = translated;
    }
}
