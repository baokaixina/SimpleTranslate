package com.yourname.simpletranslate.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A Button with a clearly visible hover/focus highlight for the settings UI. */
public final class HoverHighlightButton extends Button {
    public HoverHighlightButton(int x, int y, int width, int height, Component message,
                                OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        if (this.isHoveredOrFocused() && this.active) {
            int x0 = this.getX();
            int y0 = this.getY();
            int x1 = x0 + this.getWidth();
            int y1 = y0 + this.getHeight();
            graphics.fill(x0, y0, x1, y1, 0x2EFFFFFF);
            graphics.fill(x0, y0, x1, y0 + 1, 0xCCFFFFFF);
            graphics.fill(x0, y1 - 1, x1, y1, 0xCCFFFFFF);
            graphics.fill(x0, y0, x0 + 1, y1, 0xCCFFFFFF);
            graphics.fill(x1 - 1, y0, x1, y1, 0xCCFFFFFF);
        }
    }
}
