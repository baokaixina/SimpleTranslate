package com.yourname.simpletranslate.core;

import net.minecraft.util.text.ITextComponent;

import java.util.List;

/** Result of translating an ordered Minecraft component list. */
public final class ComponentListTranslationResult {
    public final List<ITextComponent> components;
    public final boolean handled;
    public final boolean translated;

    public ComponentListTranslationResult(List<ITextComponent> components, boolean handled, boolean translated) {
        this.components = components;
        this.handled = handled;
        this.translated = translated;
    }

    public ComponentTranslationResult asSingle(ITextComponent fallback) {
        if (components == null || components.size() != 1) {
            return new ComponentTranslationResult(fallback, handled, translated);
        }
        return new ComponentTranslationResult(components.get(0), handled, translated);
    }
}
