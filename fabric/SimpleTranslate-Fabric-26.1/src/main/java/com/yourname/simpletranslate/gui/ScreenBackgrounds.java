package com.yourname.simpletranslate.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class ScreenBackgrounds {
    private ScreenBackgrounds() {
    }

    public static void renderPlain(GuiGraphicsExtractor graphics, int width, int height) {
        graphics.fill(0, 0, width, height, 0xC0101010);
    }
}