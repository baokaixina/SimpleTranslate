package com.yourname.simpletranslate.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.text.ITextComponent;

import java.util.function.Consumer;

/**
 * 1.18.2-compatible editor surface. The native MultiLineEditBox was added
 * later; TextFieldWidget preserves the value/listener contract used by the cache and
 * profile screens until a version-specific multiline widget is available.
 */
public class LegacyMultiLineEditBox extends TextFieldWidget {
    public LegacyMultiLineEditBox(FontRenderer font, int x, int y, int width, int height,
                                  ITextComponent placeholder, ITextComponent narration) {
        super(font, x, y, width, height, narration);
    }

    public void setCharacterLimit(int limit) {
        setMaxLength(limit);
    }

    public void setValueListener(Consumer<String> listener) {
        setResponder(listener == null ? ignored -> { } : listener);
    }
}
