package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Optional;

/**
 * Edits one cached translation as readable text while preserving the cache
 * payload structure used by formatted surfaces.
 */
public class CacheEditScreen extends BaseSimpleTranslateScreen {
    private static final int PANEL_WIDTH = 440;
    private static final int BOTTOM_PANEL_WIDTH = 230;

    private final Screen parent;
    private final String cacheKey;
    private TranslationCache.CacheViewEntry entry;
    private MultiLineEditBox translationInput;
    private Button saveButton;
    private Component status = Component.empty();

    public CacheEditScreen(Screen parent, String cacheKey) {
        super(Component.translatable("screen.simple_translate.cache.edit.title"));
        this.parent = parent;
        this.cacheKey = cacheKey;
    }

    @Override
    protected void init() {
        super.init();
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        this.entry = cache == null ? null : cache.getEntry(cacheKey).orElse(null);

        int panelWidth = panelWidth();
        int left = this.width / 2 - panelWidth / 2;
        int inputY = 128;
        int inputHeight = Math.max(70, this.height - inputY - 64);

        this.translationInput = new MultiLineEditBox(
                this.font,
                left,
                inputY,
                panelWidth,
                inputHeight,
                Component.translatable("screen.simple_translate.cache.edit.translation"),
                Component.translatable("screen.simple_translate.cache.edit.translation"));
        this.translationInput.setCharacterLimit(8000);
        this.translationInput.setValue(entry == null ? "" : entry.translationText());
        this.translationInput.setValueListener(ignored -> updateSaveButtonState());
        this.translationInput.active = entry != null;
        withTooltip(this.translationInput, "screen.simple_translate.cache.edit.input.tooltip");
        this.addRenderableWidget(this.translationInput);

        int buttonY = this.height - 28;
        int halfWidth = (BOTTOM_PANEL_WIDTH - 10) / 2;
        this.saveButton = Button.builder(
                Component.translatable("screen.simple_translate.save"),
                button -> saveEditedTranslation())
                .bounds(this.width / 2 - BOTTOM_PANEL_WIDTH / 2, buttonY, halfWidth, 20)
                .build();
        withTooltip(this.saveButton, "screen.simple_translate.cache.edit.save.tooltip");
        this.addRenderableWidget(this.saveButton);

        Button backButton = Button.builder(
                Component.translatable("screen.simple_translate.back"),
                button -> this.onClose())
                .bounds(this.width / 2 + 5, buttonY, halfWidth, 20)
                .build();
        withTooltip(backButton, "screen.simple_translate.back.tooltip");
        this.addRenderableWidget(backButton);

        if (entry == null) {
            status = Component.translatable("screen.simple_translate.cache.edit.error.missing-entry");
        }
        updateSaveButtonState();
    }

    private void saveEditedTranslation() {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null) {
            status = Component.translatable("screen.simple_translate.cache.edit.error.missing-entry");
            return;
        }

        Optional<String> error = cache.updateEditableTranslationText(cacheKey, translationInput.getValue());
        if (error.isPresent()) {
            status = Component.translatable("screen.simple_translate.cache.edit.error." + error.get());
            return;
        }

        cache.saveNow();
        SimpleTranslateMod.onTranslationCacheEdited();
        this.entry = cache.getEntry(cacheKey).orElse(null);
        if (this.entry != null) {
            this.translationInput.setValue(this.entry.translationText());
        }
        status = Component.translatable("screen.simple_translate.cache.edit.saved");
        updateSaveButtonState();
    }

    private void updateSaveButtonState() {
        if (saveButton != null) {
            saveButton.active = entry != null
                    && translationInput != null
                    && !translationInput.getValue().isBlank();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);

        int panelWidth = panelWidth();
        int left = this.width / 2 - panelWidth / 2;
        drawSourcePanel(graphics, left, 42, panelWidth, 62);

        graphics.drawString(this.font, Component.translatable("screen.simple_translate.cache.edit.translation"),
                left, 115, 0xFFFFFF);
        if (status != null && !status.getString().isBlank()) {
            graphics.drawCenteredString(this.font, status, this.width / 2, this.height - 42, 0x88FF88);
        }

        drawBottomActionMask(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawSourcePanel(GuiGraphics graphics, int left, int top, int width, int height) {
        graphics.drawString(this.font, Component.translatable("screen.simple_translate.cache.edit.source"),
                left, top - 12, 0xFFFFFF);
        graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, 0xAA000000);
        graphics.fill(left, top, left + width, top + height, 0x66303030);

        String source = entry == null ? "" : entry.sourceText();
        if (source == null || source.isBlank()) {
            source = Component.translatable("screen.simple_translate.cache.edit.source_missing").getString();
        }
        List<FormattedCharSequence> lines = this.font.split(Component.literal(source), width - 8);
        int y = top + 5;
        int maxLines = Math.max(1, (height - 8) / this.font.lineHeight);
        for (int i = 0; i < Math.min(lines.size(), maxLines); i++) {
            graphics.drawString(this.font, lines.get(i), left + 4, y, 0xDDDDDD);
            y += this.font.lineHeight;
        }
        if (lines.size() > maxLines) {
            graphics.drawString(this.font, "...", left + width - 16, top + height - this.font.lineHeight - 2, 0xAAAAAA);
        }
    }

    private void drawBottomActionMask(GuiGraphics graphics) {
        int top = this.height - 36;
        int left = Math.max(0, this.width / 2 - BOTTOM_PANEL_WIDTH / 2 - 8);
        int right = Math.min(this.width, this.width / 2 + BOTTOM_PANEL_WIDTH / 2 + 8);
        graphics.fill(left, top, right, this.height - 2, 0xAA101010);
        graphics.fill(left, top, right, top + 1, 0x55FFFFFF);
    }

    private int panelWidth() {
        return Math.min(PANEL_WIDTH, Math.max(120, this.width - 40));
    }

    @Override
    public void onClose() {
        if (parent instanceof CacheManagerScreen cacheManagerScreen) {
            cacheManagerScreen.refreshAfterEdit();
        }
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
