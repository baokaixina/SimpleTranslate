package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TermDictionary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Term dictionary management screen
 */
public class TermManagerScreen extends BaseSimpleTranslateScreen {

    private final Screen parent;
    private TermList termList;
    private EditBox newTermInput;
    private EditBox newTranslationInput;
    private Button addButton;
    private Button deleteButton;
    private Button exportButton;
    private Button importButton;

    public TermManagerScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.term_manager"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // Term list
        this.termList = new TermList(this.minecraft, this.width, this.height - 120, 40, this.height - 80);
        this.addRenderableWidget(this.termList);

        // Load terms
        refreshTermList();

        // Add new term section
        int inputY = this.height - 75;
        int inputWidth = 120;

        this.newTermInput = new EditBox(
                this.font,
                centerX - inputWidth - 55,
                inputY,
                inputWidth,
                20,
                Component.translatable("screen.simple_translate.terms.term"));
        this.newTermInput.setHint(Component.translatable("screen.simple_translate.terms.term.hint"));
        withTooltip(this.newTermInput, "screen.simple_translate.terms.term.tooltip");
        this.addRenderableWidget(this.newTermInput);

        this.newTranslationInput = new EditBox(
                this.font,
                centerX - inputWidth + 75,
                inputY,
                inputWidth,
                20,
                Component.translatable("screen.simple_translate.cache.edit.translation"));
        this.newTranslationInput.setHint(Component.translatable("screen.simple_translate.terms.translation.hint"));
        withTooltip(this.newTranslationInput, "screen.simple_translate.terms.translation.tooltip");
        this.addRenderableWidget(this.newTranslationInput);

        this.addButton = Button.builder(
                Component.literal("+"),
                button -> addTerm())
                .bounds(centerX + 75, inputY, 20, 20)
                .build();
        withTooltip(this.addButton, "screen.simple_translate.terms.add.tooltip");
        this.addRenderableWidget(this.addButton);

        this.deleteButton = Button.builder(
                Component.translatable("screen.simple_translate.terms.delete_short"),
                button -> deleteSelectedTerm())
                .bounds(centerX + 100, inputY, 20, 20)
                .build();
        withTooltip(this.deleteButton, "screen.simple_translate.terms.delete.tooltip");
        this.addRenderableWidget(this.deleteButton);

        // Bottom buttons
        int buttonY = this.height - 45;
        int buttonWidth = 80;

        this.exportButton = Button.builder(
                Component.translatable("screen.simple_translate.export"),
                button -> exportTerms())
                .bounds(centerX - buttonWidth * 2 - 10, buttonY, buttonWidth, 20)
                .build();
        withTooltip(this.exportButton, "screen.simple_translate.terms.export.tooltip");
        this.addRenderableWidget(this.exportButton);

        this.importButton = Button.builder(
                Component.translatable("screen.simple_translate.import"),
                button -> importTerms())
                .bounds(centerX - buttonWidth, buttonY, buttonWidth, 20)
                .build();
        withTooltip(this.importButton, "screen.simple_translate.terms.import.tooltip");
        this.addRenderableWidget(this.importButton);

        Button backButton = Button.builder(
                Component.translatable("screen.simple_translate.back"),
                button -> this.onClose())
                .bounds(centerX + 10, buttonY, buttonWidth, 20)
                .build();
        withTooltip(backButton, "screen.simple_translate.back.tooltip");
        this.addRenderableWidget(backButton);
    }

    private void refreshTermList() {
        this.termList.children().clear();
        TermDictionary dict = SimpleTranslateMod.getTermDictionary();
        if (dict != null) {
            Map<String, String> terms = dict.getAllTerms();
            for (Map.Entry<String, String> entry : terms.entrySet()) {
                this.termList.children().add(new TermEntry(entry.getKey(), entry.getValue()));
            }
        }
    }

    private void addTerm() {
        String term = this.newTermInput.getValue().trim();
        String translation = this.newTranslationInput.getValue().trim();

        if (!term.isEmpty()) {
            TermDictionary dict = SimpleTranslateMod.getTermDictionary();
            if (dict != null) {
                dict.addTerm(term, translation);
                refreshTermList();
                this.newTermInput.setValue("");
                this.newTranslationInput.setValue("");
            }
        }
    }

    private void deleteSelectedTerm() {
        TermEntry selected = this.termList.getSelected();
        if (selected != null) {
            TermDictionary dict = SimpleTranslateMod.getTermDictionary();
            if (dict != null) {
                dict.removeTerm(selected.term);
                refreshTermList();
            }
        }
    }

    private void exportTerms() {
        // Export to config directory
        TermDictionary dict = SimpleTranslateMod.getTermDictionary();
        if (dict != null) {
            try {
                java.nio.file.Path exportPath = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                        .resolve(SimpleTranslateMod.MODID)
                        .resolve("terms_export.json");
                dict.exportToFile(exportPath);
                SimpleTranslateMod.getLogger().info("Terms exported to {}", exportPath);
            } catch (Exception e) {
                SimpleTranslateMod.getLogger().error("Failed to export terms", e);
            }
        }
    }

    private void importTerms() {
        // Import from config directory
        TermDictionary dict = SimpleTranslateMod.getTermDictionary();
        if (dict != null) {
            try {
                java.nio.file.Path importPath = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                        .resolve(SimpleTranslateMod.MODID)
                        .resolve("terms_import.json");
                dict.importFromFile(importPath, true);
                refreshTermList();
                SimpleTranslateMod.getLogger().info("Terms imported from {}", importPath);
            } catch (Exception e) {
                SimpleTranslateMod.getLogger().error("Failed to import terms", e);
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);

        // Draw title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);

        // Draw column headers
        graphics.drawString(this.font, Component.translatable("screen.simple_translate.terms.term"), this.width / 2 - 150, 30, 0xFFAAAAAA);
        graphics.drawString(this.font, Component.translatable("screen.simple_translate.cache.edit.translation"), this.width / 2 + 20, 30, 0xFFAAAAAA);

        drawBottomActionMask(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawBottomActionMask(GuiGraphics graphics) {
        int top = this.height - 82;
        int left = Math.max(0, this.width / 2 - 190);
        int right = Math.min(this.width, this.width / 2 + 190);
        graphics.fill(left, top, right, this.height - 2, 0xAA101010);
        graphics.fill(left, top, right, top + 1, 0x55FFFFFF);
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
     * List widget for displaying terms
     */
    private class TermList extends ObjectSelectionList<TermEntry> {
        public TermList(Minecraft minecraft, int width, int height, int top, int bottom) {
            super(minecraft, width, Math.max(1, bottom - top), top, 20);
        }

        @Override
        protected int scrollBarX() {
            return this.width / 2 + 160;
        }

        @Override
        public int getRowWidth() {
            return 300;
        }
    }

    /**
     * Entry for a single term
     */
    private class TermEntry extends ObjectSelectionList.Entry<TermEntry> {
        private final String term;
        private final String translation;

        public TermEntry(String term, String translation) {
            this.term = term;
            this.translation = translation;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
            int centerX = TermManagerScreen.this.width / 2;

            // Draw term
            graphics.drawString(TermManagerScreen.this.font,
                    term.length() > 20 ? term.substring(0, 17) + "..." : term,
                    centerX - 150, top + 5, 0xFFFFFFFF);

            // Draw arrow
            graphics.drawString(TermManagerScreen.this.font, "->", centerX, top + 5, 0xFF888888);

            // Draw translation
            String displayTranslation = translation.isEmpty()
                    ? Component.translatable("screen.simple_translate.terms.empty_translation").getString()
                    : translation;
            int translationColor = translation.isEmpty() ? 0xFF8888 : 0xFFFFFFFF;
            graphics.drawString(TermManagerScreen.this.font,
                    displayTranslation.length() > 20 ? displayTranslation.substring(0, 17) + "..." : displayTranslation,
                    centerX + 20, top + 5, translationColor);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            TermManagerScreen.this.termList.setSelected(this);
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.translatable("screen.simple_translate.terms.narration", term, translation);
        }
    }
}

