package com.yourname.simpletranslate.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;

/** Keeps the real API key editable while never drawing it as clear text. */
final class MaskedGuiTextField extends GuiTextField {
    MaskedGuiTextField(int id, FontRenderer font, int x, int y, int width, int height) {
        super(id, font, x, y, width, height);
    }

    @Override
    public void drawTextBox() {
        String actual = getText();
        int cursor = getCursorPosition();
        int selection = getSelectionEnd();
        StringBuilder masked = new StringBuilder(actual.length());
        for (int i = 0; i < actual.length(); i++) masked.append('*');
        setText(masked.toString());
        setCursorPosition(Math.min(cursor, masked.length()));
        setSelectionPos(Math.min(selection, masked.length()));
        super.drawTextBox();
        setText(actual);
        setCursorPosition(Math.min(cursor, actual.length()));
        setSelectionPos(Math.min(selection, actual.length()));
    }
}
