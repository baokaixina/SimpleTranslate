package com.yourname.simpletranslate.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 1.18.2-compatible editor surface. The native MultiLineEditBox was added
 * later; EditBox preserves the value/listener contract used by the cache and
 * profile screens until a version-specific multiline widget is available.
 */
public class LegacyMultiLineEditBox extends EditBox {
    public LegacyMultiLineEditBox(Font font, int x, int y, int width, int height,
                                  Component placeholder, Component narration) {
        super(font, x, y, width, height, narration);
    }

    public void setCharacterLimit(int limit) {
        setMaxLength(limit);
    }

    public void setValueListener(Consumer<String> listener) {
        setResponder(listener == null ? ignored -> { } : listener);
    }
}
