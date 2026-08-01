package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Version-appropriate counterpart of the baseline's scrollable detail screen.
 * Content scrolls inside a clipped viewport; return controls stay fixed at the
 * bottom at every resolution.
 */
public abstract class ScrollableSettingsScreen extends BaseSimpleTranslateScreen {
    private static final int VIEWPORT_TOP = 46;
    private static final int BOTTOM_BAR_HEIGHT = 35;
    protected static final int BUTTON_HEIGHT = 20;

    private final String titleKey;
    private final String subtitleKey;
    private final Map<GuiButton, Integer> contentButtonY = new IdentityHashMap<GuiButton, Integer>();
    private final Map<GuiTextField, FieldPlacement> contentFields = new IdentityHashMap<GuiTextField, FieldPlacement>();

    protected int contentLeft;
    protected int contentWidth;
    protected int viewportBottom;
    protected int contentHeight;
    protected int scrollOffset;

    protected ScrollableSettingsScreen(GuiScreen parent, TranslationEngine engine, String titleKey, String subtitleKey) {
        super(parent, engine);
        this.titleKey = titleKey;
        this.subtitleKey = subtitleKey;
    }

    @Override
    public final void initGui() {
        this.buttonList.clear();
        this.contentButtonY.clear();
        this.contentFields.clear();
        this.contentWidth = Math.max(200, Math.min(300, this.width - 40));
        this.contentLeft = (this.width - this.contentWidth) / 2;
        this.viewportBottom = Math.max(VIEWPORT_TOP + 34, this.height - BOTTOM_BAR_HEIGHT);
        buildContent();
        clampScroll();
        layoutContentWidgets();
        this.buttonList.add(new HintButton(-1, this.contentLeft, this.height - 25, this.contentWidth, BUTTON_HEIGHT,
                tr(bottomActionKey()), "screen.simple_translate.back.tooltip"));
    }

    protected abstract void buildContent();

    /** Detail pages return to the menu; the root says explicitly that it closes settings. */
    protected String bottomActionKey() {
        return "screen.simple_translate.back";
    }

    /** Draw non-widget content in viewport coordinates. */
    protected void drawContent(int mouseX, int mouseY) {
    }

    /** Return true when a change needs the page's controls rebuilt. */
    protected boolean onContentButton(int id) {
        return false;
    }

    /** Persist edited fields immediately when a page implements text input. */
    protected void onFieldsChanged() {
    }

    protected final GuiButton addContentButton(int id, int contentY, String labelKey, String tooltipKey) {
        return addContentButton(id, contentY, this.contentLeft, this.contentWidth, tr(labelKey), tooltipKey);
    }

    protected final GuiButton addContentTextButton(int id, int contentY, String text, String tooltipKey) {
        return addContentButton(id, contentY, this.contentLeft, this.contentWidth, text, tooltipKey);
    }

    protected final GuiButton addContentButton(int id, int contentY, int x, int width, String text, String tooltipKey) {
        GuiButton button = new HintButton(id, x, contentToScreenY(contentY), width, BUTTON_HEIGHT, text, tooltipKey);
        addContentWidget(button, contentY);
        return button;
    }

    /** Registers a custom content control while retaining scroll clipping. */
    protected final void addContentWidget(GuiButton button, int contentY) {
        this.buttonList.add(button);
        this.contentButtonY.put(button, Integer.valueOf(contentY));
    }

    protected final GuiTextField addTextField(int id, int contentY, String value, int maxLength) {
        return addTextField(id, contentY, 0, this.contentWidth, value, maxLength);
    }

    protected final GuiTextField addTextField(int id, int contentY, int xOffset, int width, String value, int maxLength) {
        GuiTextField field = new GuiTextField(id, this.fontRenderer, this.contentLeft + xOffset,
                contentToScreenY(contentY), width, BUTTON_HEIGHT);
        // GuiTextField starts with Minecraft's 32-character default. Set the
        // requested cap first so setText cannot silently truncate a URL or key.
        field.setMaxStringLength(maxLength);
        field.setText(value == null ? "" : value);
        this.contentFields.put(field, new FieldPlacement(contentY, xOffset, width));
        return field;
    }

    protected final GuiTextField addMaskedTextField(int id, int contentY, String value, int maxLength) {
        GuiTextField field = new MaskedGuiTextField(id, this.fontRenderer, this.contentLeft,
                contentToScreenY(contentY), this.contentWidth, BUTTON_HEIGHT);
        field.setMaxStringLength(maxLength);
        field.setText(value == null ? "" : value);
        this.contentFields.put(field, new FieldPlacement(contentY, 0, this.contentWidth));
        return field;
    }

    protected final void setContentHeight(int height) {
        this.contentHeight = Math.max(0, height);
    }

    protected final void drawContentText(String text, int contentY, int color) {
        int y = contentToScreenY(contentY);
        if (y < VIEWPORT_TOP || y + this.fontRenderer.FONT_HEIGHT > this.viewportBottom) return;
        drawString(this.fontRenderer, this.fontRenderer.trimStringToWidth(text, this.contentWidth - 4),
                this.contentLeft, y, color);
    }

    @Override
    public void updateScreen() {
        for (GuiTextField field : this.contentFields.keySet()) field.updateCursorCounter();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == -1) {
            returnToParent();
            return;
        }
        if (onContentButton(button.id)) initGui();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            returnToParent();
            return;
        }
        boolean handled = false;
        for (GuiTextField field : this.contentFields.keySet()) {
            handled = field.textboxKeyTyped(typedChar, keyCode) || handled;
        }
        if (handled) onFieldsChanged();
        else super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        for (GuiTextField field : this.contentFields.keySet()) {
            if (field.getVisible()) field.mouseClicked(mouseX, mouseY, mouseButton);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() throws IOException {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && isMouseOverViewport()) {
            this.scrollOffset += wheel > 0 ? -24 : 24;
            clampScroll();
            layoutContentWidgets();
        }
        super.handleMouseInput();
    }

    @Override
    public final void drawScreen(int mouseX, int mouseY, float partialTicks) {
        clampScroll();
        layoutContentWidgets();
        drawDefaultBackground();
        drawCenteredString(this.fontRenderer, tr(this.titleKey), this.width / 2, 8, 0xFFFFFF);
        drawCenteredString(this.fontRenderer, tr(this.subtitleKey), this.width / 2, 24, 0xAAAAAA);
        drawContent(mouseX, mouseY);
        drawScrollBar();
        drawBottomBar();
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (GuiTextField field : this.contentFields.keySet()) {
            if (field.getVisible()) field.drawTextBox();
        }
        drawDelayedTooltip(mouseX, mouseY);
    }

    private boolean isMouseOverViewport() {
        if (this.mc == null || this.mc.displayWidth <= 0 || this.mc.displayHeight <= 0) return false;
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        return mouseX >= this.contentLeft && mouseX <= this.contentLeft + this.contentWidth + 8
                && mouseY >= VIEWPORT_TOP && mouseY <= this.viewportBottom;
    }

    private void layoutContentWidgets() {
        for (Map.Entry<GuiButton, Integer> entry : this.contentButtonY.entrySet()) {
            GuiButton button = entry.getKey();
            button.y = contentToScreenY(entry.getValue().intValue());
            button.visible = isVisible(button.y, button.height);
        }
        for (Map.Entry<GuiTextField, FieldPlacement> entry : this.contentFields.entrySet()) {
            FieldPlacement placement = entry.getValue();
            GuiTextField field = entry.getKey();
            field.x = this.contentLeft + placement.xOffset;
            field.y = contentToScreenY(placement.contentY);
            field.width = placement.width;
            field.setVisible(isVisible(field.y, field.height));
        }
    }

    private boolean isVisible(int y, int height) {
        return y >= VIEWPORT_TOP && y + height <= this.viewportBottom;
    }

    private int contentToScreenY(int contentY) {
        return VIEWPORT_TOP + contentY - this.scrollOffset;
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - (this.viewportBottom - VIEWPORT_TOP));
    }

    private void clampScroll() {
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, maxScroll()));
    }

    private void drawBottomBar() {
        drawRect(this.contentLeft - 8, this.viewportBottom, this.contentLeft + this.contentWidth + 8,
                this.height - 2, 0xAA101010);
        drawRect(this.contentLeft - 8, this.viewportBottom, this.contentLeft + this.contentWidth + 8,
                this.viewportBottom + 1, 0x55FFFFFF);
    }

    private void drawScrollBar() {
        int maximum = maxScroll();
        if (maximum <= 0) return;
        int x = this.contentLeft + this.contentWidth + 5;
        int trackHeight = this.viewportBottom - VIEWPORT_TOP;
        int thumbHeight = Math.max(16, trackHeight * trackHeight / Math.max(trackHeight, this.contentHeight));
        int thumbY = VIEWPORT_TOP + (trackHeight - thumbHeight) * this.scrollOffset / maximum;
        drawRect(x, VIEWPORT_TOP, x + 3, this.viewportBottom, 0x66000000);
        drawRect(x, thumbY, x + 3, thumbY + thumbHeight, 0xFFAAAAAA);
    }

    private static final class FieldPlacement {
        private final int contentY;
        private final int xOffset;
        private final int width;

        private FieldPlacement(int contentY, int xOffset, int width) {
            this.contentY = contentY;
            this.xOffset = xOffset;
            this.width = width;
        }
    }
}
