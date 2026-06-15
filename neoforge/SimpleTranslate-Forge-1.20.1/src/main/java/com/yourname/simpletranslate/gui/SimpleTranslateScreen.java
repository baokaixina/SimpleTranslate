package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Main settings screen for Simple Translate
 */
public class SimpleTranslateScreen extends BaseSimpleTranslateScreen {

    private final Screen parent;

    // UI Components
    private Button modelSettingsButton;

    // Scroll state
    private double scrollOffset = 0;
    private int contentHeight = 0;
    private final int viewportTop = 35;
    private final int viewportBottom = 5;

    // Scrollable widgets list
    private final List<AbstractWidget> scrollableWidgets = new ArrayList<>();
    private final List<Integer> widgetBaseY = new ArrayList<>();

    // Fixed bottom buttons (not scrollable)
    private Button saveButton;
    private Button cancelButton;

    public SimpleTranslateScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        scrollOffset = 0;
        scrollableWidgets.clear();
        widgetBaseY.clear();

        int centerX = this.width / 2;
        int startY = 50;
        int buttonWidth = 220;
        int buttonHeight = 20;
        int spacing = 26;

        // ==================== API Settings Section ====================

        this.modelSettingsButton = Button.builder(
                Component.translatable("screen.simple_translate.model_settings"),
                button -> openModelSettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(this.modelSettingsButton, "screen.simple_translate.model_settings.tooltip");
        addScrollableWidget(this.modelSettingsButton, startY);

        startY += spacing;

        Button ocrButton = Button.builder(
                Component.translatable("screen.simple_translate.ocr_settings"),
                button -> openOcrSettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(ocrButton, "screen.simple_translate.ocr_settings.tooltip");
        addScrollableWidget(ocrButton, startY);

        startY += spacing;

        Button languageButton = Button.builder(
                Component.translatable("screen.simple_translate.language_settings"),
                button -> openLanguageSettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(languageButton, "screen.simple_translate.language_settings.tooltip");
        addScrollableWidget(languageButton, startY);

        startY += spacing + 20;

        // ==================== Translation Features Section ====================

        // Chat translation
        Button chatButton = Button.builder(
                Component.translatable("screen.simple_translate.chat_translation"),
                button -> openChatSettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(chatButton, "screen.simple_translate.chat_translation.tooltip");
        addScrollableWidget(chatButton, startY);

        startY += spacing;

        // Book translation
        Button bookButton = Button.builder(
                Component.translatable("screen.simple_translate.book_translation"),
                button -> openBookSettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(bookButton, "screen.simple_translate.book_translation.tooltip");
        addScrollableWidget(bookButton, startY);

        startY += spacing;

        // Item tooltip translation
        Button itemButton = Button.builder(
                Component.translatable("screen.simple_translate.item_tooltip_translation"),
                button -> openItemTooltipSettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(itemButton, "screen.simple_translate.item_tooltip_translation.tooltip");
        addScrollableWidget(itemButton, startY);

        startY += spacing;

        Button titleButton = Button.builder(
                Component.translatable("screen.simple_translate.title_translation"),
                button -> openTitleSettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(titleButton, "screen.simple_translate.title_translation.tooltip");
        addScrollableWidget(titleButton, startY);

        startY += spacing;

        // HUD translation
        Button hudButton = Button.builder(
                Component.translatable("screen.simple_translate.hud_translation"),
                button -> openHudSettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(hudButton, "screen.simple_translate.hud_translation.tooltip");
        addScrollableWidget(hudButton, startY);

        startY += spacing;

        // Sign translation
        Button signButton = Button.builder(
                Component.translatable("screen.simple_translate.sign_translation"),
                button -> openSignSettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(signButton, "screen.simple_translate.sign_translation.tooltip");
        addScrollableWidget(signButton, startY);

        startY += spacing;

        // Advancement translation
        Button advancementButton = Button.builder(
                Component.translatable("screen.simple_translate.advancement_translation"),
                button -> openAdvancementSettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(advancementButton, "screen.simple_translate.advancement_translation.tooltip");
        addScrollableWidget(advancementButton, startY);

        startY += spacing;

        // Entity name translation
        Button entityButton = Button.builder(
                Component.translatable("screen.simple_translate.entity_translation"),
                button -> openEntityNameSettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(entityButton, "screen.simple_translate.entity_translation.tooltip");
        addScrollableWidget(entityButton, startY);

        startY += spacing;

        // Text display translation
        Button textDisplayButton = Button.builder(
                Component.translatable("screen.simple_translate.text_display_translation"),
                button -> openTextDisplaySettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(textDisplayButton, "screen.simple_translate.text_display_translation.tooltip");
        addScrollableWidget(textDisplayButton, startY);

        startY += spacing;

        startY += 20;

        // ==================== Management Section
        // ======================================
        int halfWidth = (buttonWidth - 10) / 2;

        // Term manager
        Button termButton = Button.builder(
                Component.translatable("screen.simple_translate.term_manager"),
                button -> openTermManager())
                .bounds(centerX - buttonWidth / 2, startY, halfWidth, buttonHeight)
                .build();
        withTooltip(termButton, "screen.simple_translate.term_manager.tooltip");
        addScrollableWidget(termButton, startY);

        // Cache manager
        Button cacheButton = Button.builder(
                Component.translatable("screen.simple_translate.cache_manager"),
                button -> openCacheManager())
                .bounds(centerX + 5, startY, halfWidth, buttonHeight)
                .build();
        withTooltip(cacheButton, "screen.simple_translate.cache_manager.tooltip");
        addScrollableWidget(cacheButton, startY);

        startY += spacing;

        Button blacklistButton = Button.builder(
                Component.translatable("screen.simple_translate.blacklist_manager"),
                button -> openBlacklistManager())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(blacklistButton, "screen.simple_translate.blacklist_manager.tooltip");
        addScrollableWidget(blacklistButton, startY);

        startY += spacing;

        // Hold original
        Button holdOriginalButton = Button.builder(
                Component.translatable("screen.simple_translate.hold_original"),
                button -> openHoldOriginalSettings())
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                .build();
        withTooltip(holdOriginalButton, "screen.simple_translate.hold_original.tooltip");
        addScrollableWidget(holdOriginalButton, startY);

        startY += spacing;

        contentHeight = startY + 10;

        // ==================== Fixed Save/Cancel Section (at bottom)
        // ====================
        int bottomY = Math.max(2, this.height - 28);

        this.saveButton = Button.builder(
                Component.translatable("screen.simple_translate.save"),
                button -> saveAndClose())
                .bounds(centerX - buttonWidth / 2, bottomY, halfWidth, buttonHeight)
                .build();
        withTooltip(this.saveButton, "screen.simple_translate.save.tooltip");
        this.addRenderableWidget(this.saveButton);

        this.cancelButton = Button.builder(
                Component.translatable("screen.simple_translate.cancel"),
                button -> this.onClose())
                .bounds(centerX + 5, bottomY, halfWidth, buttonHeight)
                .build();
        withTooltip(this.cancelButton, "screen.simple_translate.cancel.tooltip");
        this.addRenderableWidget(this.cancelButton);

        // Initial positioning
        repositionWidgets();
    }

    private void addScrollableWidget(AbstractWidget widget, int baseY) {
        scrollableWidgets.add(widget);
        widgetBaseY.add(baseY);
        this.addRenderableWidget(widget);
    }

    private void repositionWidgets() {
        int viewportHeight = getViewportHeight();

        for (int i = 0; i < scrollableWidgets.size(); i++) {
            AbstractWidget widget = scrollableWidgets.get(i);
            int baseY = widgetBaseY.get(i);
            int newY = baseY - (int) scrollOffset;

            widget.setY(newY);

            // Hide widgets outside viewport
            boolean visible = newY >= viewportTop - 10 && newY < this.height - viewportBottom - 35;
            widget.visible = visible;
            widget.active = visible;
        }
    }

    private int getViewportHeight() {
        return this.height - viewportTop - viewportBottom - 35;
    }

    private int getMaxScroll() {
        int viewportHeight = getViewportHeight();
        return Math.max(0, contentHeight - viewportHeight);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);

        // Draw title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        // Draw status indicator
        boolean isReady = SimpleTranslateMod.getTranslationManager() != null
                && SimpleTranslateMod.getTranslationManager().isReady();
        Component status = Component.translatable(isReady
                ? "screen.simple_translate.status.ready"
                : "screen.simple_translate.status.not_configured");
        int statusColor = isReady ? 0x55FF55 : 0xFF5555;
        graphics.drawString(this.font, status, this.width / 2 + 80, 12, statusColor);

        // Enable scissor to clip scrollable area
        int clipBottom = this.height - viewportBottom - 35;
        graphics.enableScissor(0, viewportTop, this.width, clipBottom);

        // Draw section labels (offset by scroll)
        int labelX = this.width / 2 - 110;
        int apiLabelY = 38 - (int) scrollOffset;
        int featLabelY = 136 - (int) scrollOffset;
        int mgmtLabelY = 390 - (int) scrollOffset;

        if (apiLabelY >= viewportTop - 10 && apiLabelY < clipBottom) {
            graphics.drawString(this.font,
                    Component.translatable("screen.simple_translate.section.api"), labelX, apiLabelY, 0x888888);
        }
        if (featLabelY >= viewportTop - 10 && featLabelY < clipBottom) {
            graphics.drawString(this.font,
                    Component.translatable("screen.simple_translate.section.features"), labelX, featLabelY, 0x888888);
        }
        if (mgmtLabelY >= viewportTop - 10 && mgmtLabelY < clipBottom) {
            graphics.drawString(this.font,
                    Component.translatable("screen.simple_translate.section.management"), labelX, mgmtLabelY, 0x888888);
        }

        graphics.disableScissor();

        // Draw scroll indicator if needed
        if (getMaxScroll() > 0) {
            drawScrollBar(graphics);
        }

        drawBottomActionMask(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawBottomActionMask(GuiGraphics graphics) {
        int top = this.height - 35;
        int left = Math.max(0, this.width / 2 - 118);
        int right = Math.min(this.width, this.width / 2 + 118);
        graphics.fill(left, top, right, this.height - 2, 0xAA101010);
        graphics.fill(left, top, right, top + 1, 0x55FFFFFF);
    }

    private void drawScrollBar(GuiGraphics graphics) {
        int scrollBarX = this.width / 2 + 120;
        int scrollBarWidth = 4;
        int scrollBarTop = viewportTop;
        int scrollBarHeight = this.height - viewportTop - viewportBottom - 35;

        // Background
        graphics.fill(scrollBarX, scrollBarTop, scrollBarX + scrollBarWidth, scrollBarTop + scrollBarHeight,
                0x33FFFFFF);

        // Handle
        int maxScroll = getMaxScroll();
        if (maxScroll > 0) {
            double scrollRatio = scrollOffset / maxScroll;
            int handleHeight = Math.max(20, (int) ((double) scrollBarHeight * scrollBarHeight / contentHeight));
            int handleY = scrollBarTop + (int) ((scrollBarHeight - handleHeight) * scrollRatio);

            graphics.fill(scrollBarX, handleY, scrollBarX + scrollBarWidth, handleY + handleHeight, 0xAAFFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = getMaxScroll();
        if (maxScroll > 0) {
            scrollOffset -= delta * 25;
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
            repositionWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        int scrollBarX = this.width / 2 + 120;
        int maxScroll = getMaxScroll();

        if (button == 0 && maxScroll > 0 && mouseX >= scrollBarX - 5 && mouseX <= scrollBarX + 10) {
            int scrollBarHeight = this.height - viewportTop - viewportBottom - 35;
            double dragRatio = dragY / scrollBarHeight;
            scrollOffset += dragRatio * maxScroll;
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
            repositionWidgets();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private void saveAndClose() {
        SimpleTranslateMod.getLogger().info("Settings saved");
        ModConfig.save();
        this.onClose();
    }

    private void openModelSettings() {
        Minecraft.getInstance().setScreen(new ModelSettingsScreen(this));
    }

    private void openLanguageSettings() {
        Minecraft.getInstance().setScreen(new LanguageSettingsScreen(this));
    }

    private void openChatSettings() {
        Minecraft.getInstance().setScreen(new ChatTranslationScreen(this));
    }

    private void openBookSettings() {
        Minecraft.getInstance().setScreen(new BookTranslationScreen(this));
    }

    private void openItemTooltipSettings() {
        Minecraft.getInstance().setScreen(new ItemTooltipScreen(this));
    }

    private void openHudSettings() {
        Minecraft.getInstance().setScreen(new HudTranslationScreen(this));
    }

    private void openTitleSettings() {
        Minecraft.getInstance().setScreen(new TitleTranslationScreen(this));
    }

    private void openSignSettings() {
        Minecraft.getInstance().setScreen(new SignTranslationScreen(this));
    }

    private void openAdvancementSettings() {
        Minecraft.getInstance().setScreen(new AdvancementTranslationScreen(this));
    }

    private void openEntityNameSettings() {
        Minecraft.getInstance().setScreen(new EntityNameTranslationScreen(this));
    }

    private void openTextDisplaySettings() {
        Minecraft.getInstance().setScreen(new TextDisplayTranslationScreen(this));
    }

    private void openOcrSettings() {
        Minecraft.getInstance().setScreen(new OcrTranslationScreen(this));
    }

    private void openTermManager() {
        Minecraft.getInstance().setScreen(new TermManagerScreen(this));
    }

    private void openCacheManager() {
        Minecraft.getInstance().setScreen(new CacheManagerScreen(this));
    }

    private void openBlacklistManager() {
        Minecraft.getInstance().setScreen(new BlacklistManagerScreen(this));
    }

    private void openHoldOriginalSettings() {
        Minecraft.getInstance().setScreen(new HoldOriginalScreen(this));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
