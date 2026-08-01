package com.yourname.simpletranslate.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/** Standard settings button with the baseline's delayed help affordance. */
final class HintButton extends GuiButton {
    private static final long TOOLTIP_DELAY_MS = 650L;

    private final String tooltipKey;
    private long hoverStartedAt;
    private boolean hovered;

    HintButton(int id, int x, int y, int width, int height, String text, String tooltipKey) {
        super(id, x, y, width, height, text);
        this.tooltipKey = tooltipKey;
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
        super.drawButton(minecraft, mouseX, mouseY, partialTicks);
        boolean nowHovered = this.visible && this.enabled
                && mouseX >= this.x && mouseY >= this.y
                && mouseX < this.x + this.width && mouseY < this.y + this.height;
        if (nowHovered) {
            if (!this.hovered) this.hoverStartedAt = System.currentTimeMillis();
        } else {
            this.hoverStartedAt = 0L;
        }
        this.hovered = nowHovered;
    }

    String getVisibleTooltip() {
        if (this.tooltipKey == null || this.tooltipKey.isEmpty() || !this.hovered) return null;
        if (System.currentTimeMillis() - this.hoverStartedAt < TOOLTIP_DELAY_MS) return null;
        return BaseSimpleTranslateScreen.tr(this.tooltipKey);
    }
}
