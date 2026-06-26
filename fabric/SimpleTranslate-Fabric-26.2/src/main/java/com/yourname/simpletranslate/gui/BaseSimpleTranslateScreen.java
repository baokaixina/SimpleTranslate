package com.yourname.simpletranslate.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Duration;

public abstract class BaseSimpleTranslateScreen extends Screen {
    private static final Duration SETTINGS_TOOLTIP_DELAY = Duration.ofMillis(700);

    protected BaseSimpleTranslateScreen(Component title) {
        super(title);
    }

    protected <T extends AbstractWidget> T withTooltip(T widget, String key, Object... args) {
        return withTooltip(widget, Component.translatable(key, args));
    }

    protected <T extends AbstractWidget> T withTooltip(T widget, Component tooltip) {
        widget.setTooltip(Tooltip.create(tooltip));
        widget.setTooltipDelay(SETTINGS_TOOLTIP_DELAY);
        return widget;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Each settings screen draws the SimpleTranslate background at the start
        // of its render method. Keep this override empty so super.extractRenderState() cannot
        // call vanilla blur or draw a second translucent layer over custom labels.
    }
}
