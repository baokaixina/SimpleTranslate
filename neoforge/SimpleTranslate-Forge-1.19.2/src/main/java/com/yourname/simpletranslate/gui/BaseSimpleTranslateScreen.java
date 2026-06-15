package com.yourname.simpletranslate.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.compat.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;

public abstract class BaseSimpleTranslateScreen extends Screen {
    private static final int SETTINGS_TOOLTIP_DELAY_MS = Math.toIntExact(Duration.ofMillis(700).toMillis());
    private final Map<AbstractWidget, Component> simpleTranslateTooltips = new IdentityHashMap<>();
    private AbstractWidget hoveredTooltipWidget;
    private long hoverStartedAtMs;

    protected BaseSimpleTranslateScreen(Component title) {
        super(title);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.render(new GuiGraphics(poseStack), mouseX, mouseY, partialTick);
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics.pose(), mouseX, mouseY, partialTick);
        renderDelayedTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public void renderBackground(PoseStack poseStack) {
        this.renderBackground(new GuiGraphics(poseStack));
    }

    public void renderBackground(GuiGraphics graphics) {
        // Each settings screen draws the SimpleTranslate background at the start
        // of its render method. Keep this override empty so super.render() cannot
        // call vanilla blur or draw a second translucent layer over custom labels.
    }

    protected <T extends AbstractWidget> T withTooltip(T widget, String translationKey, Object... args) {
        return withTooltip(widget, Component.translatable(translationKey, args));
    }

    protected <T extends AbstractWidget> T withTooltip(T widget, Component tooltip) {
        if (widget != null && tooltip != null) {
            simpleTranslateTooltips.put(widget, tooltip);
            setTooltipDelay(widget, SETTINGS_TOOLTIP_DELAY_MS);
        }
        return widget;
    }

    protected void setTooltipDelay(AbstractWidget widget, int delayMs) {
        // 1.19.x does not expose AbstractWidget#setTooltipDelay; the local map
        // and renderDelayedTooltip enforce the same delay behavior.
    }

    protected void renderDelayedTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        AbstractWidget hovered = null;
        Component tooltip = null;
        for (Map.Entry<AbstractWidget, Component> entry : simpleTranslateTooltips.entrySet()) {
            AbstractWidget widget = entry.getKey();
            if (widget != null && widget.visible && widget.isMouseOver(mouseX, mouseY)) {
                hovered = widget;
                tooltip = entry.getValue();
                break;
            }
        }

        long now = System.currentTimeMillis();
        if (hovered != hoveredTooltipWidget) {
            hoveredTooltipWidget = hovered;
            hoverStartedAtMs = now;
        }

        if (hovered != null && tooltip != null && now - hoverStartedAtMs >= SETTINGS_TOOLTIP_DELAY_MS) {
            graphics.renderTooltip(this.font, tooltip, mouseX, mouseY);
        }
    }
}


