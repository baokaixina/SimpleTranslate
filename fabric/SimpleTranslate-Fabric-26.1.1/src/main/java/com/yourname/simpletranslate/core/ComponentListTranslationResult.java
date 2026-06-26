package com.yourname.simpletranslate.core;

import net.minecraft.network.chat.Component;

import java.util.List;

/** Result of translating an ordered Minecraft component list. */
public final class ComponentListTranslationResult {
    public final List<Component> components;
    public final boolean handled;
    public final boolean translated;

    public ComponentListTranslationResult(List<Component> components, boolean handled, boolean translated) {
        this.components = components;
        this.handled = handled;
        this.translated = translated;
    }

    public ComponentTranslationResult asSingle(Component fallback) {
        if (components == null || components.size() != 1) {
            return new ComponentTranslationResult(fallback, handled, translated);
        }
        return new ComponentTranslationResult(components.get(0), handled, translated);
    }
}
