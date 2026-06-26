package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for scrollable settings screens
 */
public abstract class ScrollableSettingsScreen extends BaseSimpleTranslateScreen {
    private static final int BOTTOM_BAR_HEIGHT = 35;
    private static final int BOTTOM_BAR_HORIZONTAL_PADDING = 8;

    protected final Screen parent;
    protected final List<SettingsEntry> entries = new ArrayList<>();

    // Scroll state
    protected double scrollOffset = 0;
    protected int contentHeight = 0;
    protected int viewportHeight = 0;
    protected int viewportTop = 40;
    protected int viewportBottom = 30; // Space for buttons at bottom

    // Layout
    protected int contentWidth = 220;
    protected int entrySpacing = 28;
    private Button backButton;

    public ScrollableSettingsScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        entries.clear();
        scrollOffset = 0;

        viewportHeight = getViewportHeight();

        // Build content
        buildContent();

        // Calculate content height from the real widget sizes. Most settings are
        // compact rows, while previews may be taller.
        contentHeight = 20;
        for (SettingsEntry entry : entries) {
            contentHeight += entry.widget == null
                    ? entrySpacing
                    : Math.max(entrySpacing, entry.widget.getHeight() + 8);
        }

        // Add bottom return button. Settings apply immediately as controls change.
        addBottomButtons();

        // Position all entries
        repositionEntries();
    }

    /**
     * Override to build settings content
     */
    protected abstract void buildContent();

    /**
     * Add the return button at bottom.
     */
    protected void addBottomButtons() {
        int centerX = this.width / 2;
        int buttonY = this.height - 25;

        this.backButton = Button.builder(
                Component.translatable("screen.simple_translate.back"),
                button -> this.onClose())
                .bounds(centerX - contentWidth / 2, buttonY, contentWidth, 20)
                .build();
        withTooltip(this.backButton, "screen.simple_translate.back.tooltip");
        this.addRenderableWidget(this.backButton);
    }

    /**
     * Add a widget entry
     */
    protected void addEntry(AbstractWidget widget) {
        entries.add(new SettingsEntry(widget, null, 0));
        this.addRenderableWidget(widget);
    }

    /**
     * Add a labeled entry with description
     */
    protected void addEntry(AbstractWidget widget, String label, int labelColor) {
        entries.add(new SettingsEntry(widget, label, labelColor));
        this.addRenderableWidget(widget);
    }

    /**
     * Add a separator/section header
     */
    protected void addSectionHeader(String text) {
        entries.add(new SettingsEntry(null, "=== " + text + " ===", 0x888888));
    }

    /**
     * Reposition entries based on scroll offset
     */
    protected void repositionEntries() {
        int centerX = this.width / 2;
        int y = viewportTop - (int) scrollOffset;

        for (SettingsEntry entry : entries) {
            if (entry.widget != null) {
                entry.widget.setY(y);
                entry.widget.setX(centerX - contentWidth / 2);
                entry.widget.visible = isEntryVisible(y, entry.widget.getHeight());
                entry.widget.active = entry.widget.visible;
            }
            entry.renderY = y;
            y += entry.widget == null
                    ? entrySpacing
                    : Math.max(entrySpacing, entry.widget.getHeight() + 8);
        }
    }

    protected boolean isEntryVisible(int y) {
        return isEntryVisible(y, 20);
    }

    protected boolean isEntryVisible(int y, int entryHeight) {
        int contentBottom = getContentBottom();
        return y >= viewportTop - 20 && y + Math.max(1, entryHeight) <= contentBottom;
    }

    protected int getContentBottom() {
        return this.height - BOTTOM_BAR_HEIGHT;
    }

    protected int getViewportHeight() {
        return Math.max(1, getContentBottom() - viewportTop);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);

        // Draw title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);

        // Enable scissor to clip content area
        int contentBottom = getContentBottom();
        graphics.enableScissor(0, viewportTop, this.width, contentBottom);

        // Draw labels and descriptions
        int centerX = this.width / 2;
        for (SettingsEntry entry : entries) {
            if (entry.label != null && isEntryVisible(entry.renderY)) {
                if (entry.widget == null) {
                    // Section header or description - centered
                    graphics.drawCenteredString(this.font, entry.label, centerX, entry.renderY + 5, entry.labelColor);
                }
            }
        }

        graphics.disableScissor();

        // Draw scroll bar if needed
        if (contentHeight > viewportHeight) {
            drawScrollBar(graphics);
        }

        renderWidgetsWithFixedBottomActions(graphics, mouseX, mouseY, partialTick);
    }

    private void renderWidgetsWithFixedBottomActions(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean backVisible = this.backButton != null && this.backButton.visible;
        if (this.backButton != null) {
            this.backButton.visible = false;
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        if (this.backButton != null) {
            this.backButton.visible = backVisible;
        }

        renderAboveScrollableContentBeforeBottomActions(graphics, mouseX, mouseY, partialTick);
        drawBottomBar(graphics);
        if (this.backButton != null && this.backButton.visible) {
            this.backButton.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    protected void renderAboveScrollableContentBeforeBottomActions(GuiGraphics graphics,
                                                                   int mouseX,
                                                                   int mouseY,
                                                                   float partialTick) {
    }

    protected void drawBottomBar(GuiGraphics graphics) {
        int y = getContentBottom();
        int left = bottomActionPanelLeft();
        int right = bottomActionPanelRight();
        graphics.fill(left, y, right, this.height - 2, 0xAA101010);
        graphics.fill(left, y, right, y + 1, 0x55FFFFFF);
    }

    protected void drawScrollBar(GuiGraphics graphics) {
        int scrollBarX = this.width / 2 + contentWidth / 2 + 10;
        int scrollBarWidth = 6;
        int scrollBarHeight = getViewportHeight();

        // Background
        graphics.fill(scrollBarX, viewportTop, scrollBarX + scrollBarWidth, viewportTop + scrollBarHeight, 0x33FFFFFF);

        // Handle
        int visibleHeight = getViewportHeight();
        double scrollRatio = scrollOffset / Math.max(1, contentHeight - visibleHeight);
        int handleHeight = Math.max(20, (int) ((double) visibleHeight / contentHeight * scrollBarHeight));
        int handleY = viewportTop + (int) ((scrollBarHeight - handleHeight) * scrollRatio);

        graphics.fill(scrollBarX, handleY, scrollBarX + scrollBarWidth, handleY + handleHeight, 0xAAFFFFFF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isInBottomActionPanel(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (contentHeight > viewportHeight) {
            scrollOffset -= scrollY * 20;
            scrollOffset = Math.max(0, Math.min(scrollOffset, contentHeight - viewportHeight));
            repositionEntries();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isInBottomActionPanel(mouseX, mouseY)) {
            if (this.backButton != null && this.backButton.mouseClicked(mouseX, mouseY, button)) {
                this.setFocused(this.backButton);
                return true;
            }
            return true;
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled) {
            applyLiveSettings();
        }
        return handled;
    }

    protected boolean isInBottomActionPanel(double mouseX, double mouseY) {
        return mouseY >= getContentBottom()
                && mouseX >= bottomActionPanelLeft()
                && mouseX <= bottomActionPanelRight();
    }

    private int bottomActionPanelLeft() {
        return Math.max(0, this.width / 2 - contentWidth / 2 - BOTTOM_BAR_HORIZONTAL_PADDING);
    }

    private int bottomActionPanelRight() {
        return Math.min(this.width, this.width / 2 + contentWidth / 2 + BOTTOM_BAR_HORIZONTAL_PADDING);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && contentHeight > viewportHeight && mouseY < getContentBottom()) {
            int scrollBarX = this.width / 2 + contentWidth / 2 + 10;
            if (mouseX >= scrollBarX && mouseX <= scrollBarX + 10) {
                double dragRatio = dragY / viewportHeight;
                scrollOffset += dragRatio * (contentHeight - viewportHeight);
                scrollOffset = Math.max(0, Math.min(scrollOffset, contentHeight - viewportHeight));
                repositionEntries();
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        if (handled) {
            applyLiveSettings();
        }
        return handled;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        boolean handled = super.charTyped(codePoint, modifiers);
        if (handled) {
            applyLiveSettings();
        }
        return handled;
    }

    /**
     * Override to apply settings from current widget/local state.
     */
    protected abstract void saveSettings();

    protected void applyLiveSettings() {
        saveSettings();
        ModConfig.save();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Entry in the settings list
     */
    protected static class SettingsEntry {
        public final AbstractWidget widget;
        public final String label;
        public final int labelColor;
        public int renderY;

        public SettingsEntry(AbstractWidget widget, String label, int labelColor) {
            this.widget = widget;
            this.label = label;
            this.labelColor = labelColor;
        }
    }
}
