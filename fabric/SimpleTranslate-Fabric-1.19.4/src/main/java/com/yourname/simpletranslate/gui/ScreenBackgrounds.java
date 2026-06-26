package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.compat.GuiGraphics;

public final class ScreenBackgrounds {
    private ScreenBackgrounds() {
    }

    public static void renderPlain(GuiGraphics graphics, int width, int height) {
        graphics.fill(0, 0, width, height, 0xC0101010);
    }
}