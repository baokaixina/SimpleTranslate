package com.yourname.simpletranslate.gui;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.yourname.simpletranslate.core.render.GuiGraphics;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;

/** A Button with a clearly visible hover/focus highlight for the settings UI. */
public final class HoverHighlightButton extends Button {
    public HoverHighlightButton(int x, int y, int width, int height, ITextComponent message,
                                IPressable onPress) {
        super(x, y, width, height, message, onPress);
    }

    @Override
    public void renderButton(MatrixStack poseStack, int mouseX, int mouseY, float partialTick) {
        super.renderButton(poseStack, mouseX, mouseY, partialTick);
        GuiGraphics graphics = GuiGraphics.wrap(poseStack);
        if ((this.isHovered() || this.isFocused()) && this.active) {
            int x0 = this.x;
            int y0 = this.y;
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
