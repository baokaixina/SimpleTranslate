package com.yourname.simpletranslate.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
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

    /**
     * 1.21.11 delivers input through value objects.  Keep the small legacy
     * overloads as a bridge so every settings screen can share the existing
     * layout code while Minecraft dispatches the real event exactly once.
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return super.mouseScrolled(mouseX, mouseY, 0.0D, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return mouseScrolled(mouseX, mouseY, scrollY);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return mouseClicked(event.x(), event.y(), event.button());
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), dragX, dragY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return mouseDragged(event.x(), event.y(), event.button(), dragX, dragY);
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return super.keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return keyPressed(event.key(), event.scancode(), event.modifiers());
    }

    public boolean charTyped(char codePoint) {
        return super.charTyped(new CharacterEvent(codePoint));
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return charTyped((char) event.codepoint());
    }
}
