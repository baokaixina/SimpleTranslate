package com.yourname.simpletranslate.core;

import net.minecraft.network.chat.Component;

/** Result of translating one Minecraft component. */
public final class ComponentTranslationResult {
    public final Component component;
    public final boolean handled;
    public final boolean translated;

    public ComponentTranslationResult(Component component, boolean handled, boolean translated) {
        this.component = component;
        this.handled = handled;
        this.translated = translated;
    }
}
