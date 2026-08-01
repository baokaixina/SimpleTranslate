package com.yourname.simpletranslate.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;

public abstract class BaseSimpleTranslateScreen extends Screen {
    private static final int SETTINGS_TOOLTIP_DELAY = Math.toIntExact(Duration.ofMillis(700).toMillis());

    /**
     * Minecraft 1.19.2 has no {@code net.minecraft.client.gui.components.Tooltip}
     * and no AbstractWidget#setTooltip: the delayed settings tooltip is owned by
     * the screen itself and drawn after the widget pass once the hover dwell
     * elapses, matching the 1.19.3+ product behavior.
     */
    private final Map<AbstractWidget, Component> widgetTooltips = new IdentityHashMap<>();
    private final Map<AbstractWidget, Long> widgetHoverSince = new IdentityHashMap<>();

    protected BaseSimpleTranslateScreen(Component title) {
        super(title);
    }

    /** Rebuilds this screen's widget list on the 1.18.2 Screen API. */
    protected final void rebuildWidgets() {
        clearWidgets();
        init();
    }

    protected <T extends AbstractWidget> T withTooltip(T widget, String key, Object... args) {
        return withTooltip(widget, com.yourname.simpletranslate.core.LegacyComponentFactory.translatable(key, args));
    }

    protected <T extends AbstractWidget> T withTooltip(T widget, Component tooltip) {
        this.widgetTooltips.put(widget, tooltip);
        return widget;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        super.render(poseStack, mouseX, mouseY, partialTick);
        renderDelayedWidgetTooltips(poseStack, mouseX, mouseY);
    }

    private void renderDelayedWidgetTooltips(PoseStack poseStack, int mouseX, int mouseY) {
        long now = Util.getMillis();
        for (Map.Entry<AbstractWidget, Component> entry : this.widgetTooltips.entrySet()) {
            AbstractWidget widget = entry.getKey();
            if (!widget.visible || !widget.active || !widget.isHoveredOrFocused()) {
                this.widgetHoverSince.remove(widget);
                continue;
            }
            Long since = this.widgetHoverSince.computeIfAbsent(widget, key -> now);
            if (now - since >= SETTINGS_TOOLTIP_DELAY) {
                renderTooltip(poseStack, entry.getValue(), mouseX, mouseY);
                break;
            }
        }
    }

    @Override
    public void renderBackground(PoseStack poseStack) {
        // Each settings screen draws the SimpleTranslate background at the start
        // of its render method. Keep this override empty so super.render() cannot
        // call vanilla blur or draw a second translucent layer over custom labels.
    }
}
