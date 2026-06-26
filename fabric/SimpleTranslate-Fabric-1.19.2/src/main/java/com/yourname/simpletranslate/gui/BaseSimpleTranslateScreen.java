package com.yourname.simpletranslate.gui;

import com.mojang.blaze3d.vertex.PoseStack;

import com.yourname.simpletranslate.compat.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Duration;

public abstract class BaseSimpleTranslateScreen extends Screen {
    private static final int SETTINGS_TOOLTIP_DELAY = Math.toIntExact(Duration.ofMillis(700).toMillis());

    protected BaseSimpleTranslateScreen(Component title) {
        super(title);
    }

    protected <T extends AbstractWidget> T withTooltip(T widget, String key, Object... args) {
        return withTooltip(widget, Component.translatable(key, args));
    }

    protected <T extends AbstractWidget> T withTooltip(T widget, Component tooltip) {
        return widget;
    }

    @Override
    public void renderBackground(PoseStack poseStack) {
        GuiGraphics graphics = new GuiGraphics(poseStack);
        // Each settings screen draws the SimpleTranslate background at the start
        // of its render method. Keep this override empty so super.render() cannot
        // call vanilla blur or draw a second translucent layer over custom labels.
    }
}


