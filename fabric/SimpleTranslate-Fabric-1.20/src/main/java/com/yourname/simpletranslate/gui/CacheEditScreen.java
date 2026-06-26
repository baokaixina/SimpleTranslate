package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.ComponentJsonCacheEditor;
import com.yourname.simpletranslate.cache.TranslationCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Edits one cached Component JSON translation as readable text while preserving
 * the returned component payload structure.
 */
public class CacheEditScreen extends BaseSimpleTranslateScreen {
    private static final int PANEL_WIDTH = 440;
    private static final int BOTTOM_PANEL_WIDTH = 230;
    private static final int SOURCE_TOP = 42;
    private static final int SOURCE_HEIGHT = 44;
    private static final int PREVIEW_TOP = 102;
    private static final int PREVIEW_HEIGHT = 76;
    private static final int INPUT_TOP = 198;

    private final Screen parent;
    private final String cacheKey;
    private TranslationCache.CacheViewEntry entry;
    private MultiLineEditBox translationInput;
    private Button saveButton;
    private Component status = Component.empty();
    private int previewScrollLine;
    private int previewMaxScroll;
    private int editableNodeCount;

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
        this.editableNodeCount = editableNodeCount();

        int panelWidth = panelWidth();
        int left = this.width / 2 - panelWidth / 2;
        int inputY = INPUT_TOP;
        int inputHeight = Math.max(56, this.height - inputY - 58);

        this.translationInput = new MultiLineEditBox(
                this.font,
                left,
                inputY,
                panelWidth,
                inputHeight,
                Component.translatable("screen.simple_translate.cache.edit.translation"),
                Component.translatable("screen.simple_translate.cache.edit.translation"));
        this.translationInput.setCharacterLimit(8000);
        this.translationInput.setValue(editorText());
        this.translationInput.setValueListener(ignored -> {
            previewScrollLine = 0;
            updateSaveButtonState();
        });
        this.translationInput.active = entry != null && editableNodeCount > 0;
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

        List<String> nodes = ComponentJsonCacheEditor.decodeEditorText(
                translationInput.getValue(), editableNodeCount);
        Optional<String> error = nodes.size() == editableNodeCount
                ? cache.updateComponentJsonTextNodes(cacheKey, nodes)
                : Optional.of("unsupported-format");
        if (error.isPresent()) {
            status = Component.translatable("screen.simple_translate.cache.edit.error." + error.get());
            return;
        }

        cache.saveNow();
        SimpleTranslateMod.onTranslationCacheEdited();
        this.entry = cache.getEntry(cacheKey).orElse(null);
        this.editableNodeCount = editableNodeCount();
        if (this.entry != null) {
            this.translationInput.setValue(editorText());
        }
        status = Component.translatable("screen.simple_translate.cache.edit.saved");
        updateSaveButtonState();
    }

    private void updateSaveButtonState() {
        if (saveButton != null) {
            saveButton.active = entry != null
                    && translationInput != null
                    && editableNodeCount > 0
                    && ComponentJsonCacheEditor.decodeEditorText(
                            translationInput.getValue(), editableNodeCount).size() == editableNodeCount;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);

        int panelWidth = panelWidth();
        int left = this.width / 2 - panelWidth / 2;
        drawSourcePanel(graphics, left, SOURCE_TOP, panelWidth, SOURCE_HEIGHT);
        drawTranslationPreview(graphics, left, PREVIEW_TOP, panelWidth, PREVIEW_HEIGHT);

        graphics.drawString(this.font, Component.translatable("screen.simple_translate.cache.edit.translation"),
                left, INPUT_TOP - 13, 0xFFFFFF);
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

    private int editableNodeCount() {
        return entry == null ? 0 : ComponentJsonCacheEditor.textNodes(entry.translation()).size();
    }

    private String editorText() {
        return entry == null ? "" : ComponentJsonCacheEditor.encodeEditorText(
                ComponentJsonCacheEditor.textNodes(entry.translation()));
    }

    /** Renders the component JSON produced by replacing the editable text nodes. */
    private void drawTranslationPreview(GuiGraphics graphics, int left, int top, int width, int height) {
        graphics.drawString(this.font, Component.translatable("screen.simple_translate.cache.edit.preview"),
                left, top - 12, 0xFFFFFF);
        graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, 0xAA000000);
        graphics.fill(left, top, left + width, top + height, 0x66202830);

        String edited = translationInput == null ? "" : translationInput.getValue();
        List<String> nodes = ComponentJsonCacheEditor.decodeEditorText(edited, editableNodeCount);
        String projectedJson = nodes.size() == editableNodeCount && entry != null
                ? ComponentJsonCacheEditor.replaceTextNodes(entry.translation(), nodes)
                : null;
        List<Component> projected = ComponentJsonCacheEditor.components(projectedJson);
        List<FormattedCharSequence> visualLines = new ArrayList<>();
        int wrapWidth = Math.max(20, width - 14);
        for (Component styled : projected) {
            List<FormattedCharSequence> wrapped = this.font.split(styled, wrapWidth);
            if (wrapped.isEmpty()) {
                visualLines.add(Component.empty().getVisualOrderText());
            } else {
                visualLines.addAll(wrapped);
            }
        }

        int maxLines = Math.max(1, (height - 8) / this.font.lineHeight);
        previewMaxScroll = Math.max(0, visualLines.size() - maxLines);
        previewScrollLine = Math.max(0, Math.min(previewScrollLine, previewMaxScroll));

        int y = top + 5;
        graphics.enableScissor(left + 1, top + 1, left + width - 6, top + height - 1);
        int end = Math.min(visualLines.size(), previewScrollLine + maxLines);
        for (int i = previewScrollLine; i < end; i++) {
            graphics.drawString(this.font, visualLines.get(i), left + 4, y, 0xFFFFFF);
            y += this.font.lineHeight;
        }
        graphics.disableScissor();
        drawPreviewScrollbar(graphics, left, top, width, height, visualLines.size(), maxLines);
    }

    private void drawPreviewScrollbar(GuiGraphics graphics, int left, int top, int width, int height,
                                      int totalLines, int visibleLines) {
        if (previewMaxScroll <= 0 || totalLines <= visibleLines) {
            return;
        }
        int trackX = left + width - 4;
        int trackTop = top + 3;
        int trackHeight = Math.max(8, height - 6);
        int thumbHeight = Math.max(8, trackHeight * visibleLines / totalLines);
        int travel = Math.max(0, trackHeight - thumbHeight);
        int thumbTop = trackTop + (previewMaxScroll == 0 ? 0 : travel * previewScrollLine / previewMaxScroll);
        graphics.fill(trackX, trackTop, trackX + 2, trackTop + trackHeight, 0x66404040);
        graphics.fill(trackX, thumbTop, trackX + 2, thumbTop + thumbHeight, 0xFF55C7D9);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int panelWidth = panelWidth();
        int left = this.width / 2 - panelWidth / 2;
        boolean overPreview = mouseX >= left && mouseX < left + panelWidth
                && mouseY >= PREVIEW_TOP && mouseY < PREVIEW_TOP + PREVIEW_HEIGHT;
        if (overPreview && previewMaxScroll > 0 && delta != 0.0D) {
            int amount = Math.max(1, (int) Math.ceil(Math.abs(delta) * 3.0D));
            previewScrollLine += delta > 0.0D ? -amount : amount;
            previewScrollLine = Math.max(0, Math.min(previewScrollLine, previewMaxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
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
