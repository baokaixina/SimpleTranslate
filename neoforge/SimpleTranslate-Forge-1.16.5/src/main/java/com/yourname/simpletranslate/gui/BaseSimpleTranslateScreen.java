package com.yourname.simpletranslate.gui;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.util.Util;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.client.gui.IGuiEventListener;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;

public abstract class BaseSimpleTranslateScreen extends Screen {
    private static final int SETTINGS_TOOLTIP_DELAY = Math.toIntExact(Duration.ofMillis(700).toMillis());

    /**
     * Minecraft 1.19.2 has no {@code net.minecraft.client.gui.components.Tooltip}
     * and no Widget#setTooltip: the delayed settings tooltip is owned by
     * the screen itself and drawn after the widget pass once the hover dwell
     * elapses, matching the 1.19.3+ product behavior.
     */
    private final Map<Widget, ITextComponent> widgetTooltips = new IdentityHashMap<>();
    private final Map<Widget, Long> widgetHoverSince = new IdentityHashMap<>();

    protected BaseSimpleTranslateScreen(ITextComponent title) {
        super(title);
    }

    protected <T extends Widget> T withTooltip(T widget, String key, Object... args) {
        return withTooltip(widget, com.yourname.simpletranslate.core.LegacyComponentFactory.translatable(key, args));
    }

    protected <T extends Widget> T withTooltip(T widget, ITextComponent tooltip) {
        this.widgetTooltips.put(widget, tooltip);
        return widget;
    }

    @Override
    public void render(MatrixStack poseStack, int mouseX, int mouseY, float partialTick) {
        super.render(poseStack, mouseX, mouseY, partialTick);
        renderDelayedWidgetTooltips(poseStack, mouseX, mouseY);
    }

    private void renderDelayedWidgetTooltips(MatrixStack poseStack, int mouseX, int mouseY) {
        long now = Util.getMillis();
        for (Map.Entry<Widget, ITextComponent> entry : this.widgetTooltips.entrySet()) {
            Widget widget = entry.getKey();
            if (!widget.visible || !widget.active || !(widget.isHovered() || widget.isFocused())) {
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

    protected <T extends Widget> T addRenderableWidget(T widget) {
        return this.addButton(widget);
    }

    protected <T extends IGuiEventListener> T addRenderableWidget(T widget) {
        return this.addWidget(widget);
    }

    protected void clearWidgets() {
        this.buttons.clear();
        this.children.clear();
        this.widgetTooltips.clear();
        this.widgetHoverSince.clear();
    }

    @Override
    public void renderBackground(MatrixStack poseStack) {
        // Each settings screen draws the SimpleTranslate background at the start
        // of its render method. Keep this override empty so super.render() cannot
        // call vanilla blur or draw a second translucent layer over custom labels.
    }
}
