package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationBlacklist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

public class BlacklistManagerScreen extends BaseSimpleTranslateScreen {
    private final Screen parent;
    private BlacklistList blacklistList;
    private EditBox newEntryInput;
    private Button addButton;
    private Button deleteButton;
    private Button clearButton;
    private Button exportButton;
    private Button importButton;

    public BlacklistManagerScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.blacklist_manager"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        this.blacklistList = new BlacklistList(this.minecraft, this.width, this.height - 120, 40, this.height - 80);
        this.addRenderableWidget(this.blacklistList);
        refreshBlacklistList();

        int inputY = this.height - 75;
        int inputWidth = 220;
        this.newEntryInput = new EditBox(
                this.font,
                centerX - inputWidth / 2 - 25,
                inputY,
                inputWidth,
                20,
                Component.translatable("screen.simple_translate.blacklist.entry"));
        this.newEntryInput.setMaxLength(256);
        this.newEntryInput.setHint(Component.translatable("screen.simple_translate.blacklist.entry_hint"));
        withTooltip(this.newEntryInput, "screen.simple_translate.blacklist.entry.tooltip");
        this.addRenderableWidget(this.newEntryInput);

        this.addButton = Button.builder(
                Component.literal("+"),
                button -> addEntry())
                .bounds(centerX + inputWidth / 2 + 5, inputY, 20, 20)
                .build();
        withTooltip(this.addButton, "screen.simple_translate.blacklist.add.tooltip");
        this.addRenderableWidget(this.addButton);

        this.deleteButton = Button.builder(
                Component.translatable("screen.simple_translate.delete"),
                button -> deleteSelectedEntry())
                .bounds(centerX + inputWidth / 2 + 30, inputY, 50, 20)
                .build();
        withTooltip(this.deleteButton, "screen.simple_translate.blacklist.delete.tooltip");
        this.addRenderableWidget(this.deleteButton);

        int buttonY = this.height - 45;
        int buttonWidth = 70;
        int spacing = 5;
        int totalWidth = buttonWidth * 4 + spacing * 3;
        int startX = centerX - totalWidth / 2;

        this.clearButton = Button.builder(
                Component.translatable("screen.simple_translate.clear"),
                button -> clearBlacklist())
                .bounds(startX, buttonY, buttonWidth, 20)
                .build();
        withTooltip(this.clearButton, "screen.simple_translate.blacklist.clear.tooltip");
        this.addRenderableWidget(this.clearButton);

        this.exportButton = Button.builder(
                Component.translatable("screen.simple_translate.export"),
                button -> exportBlacklist())
                .bounds(startX + buttonWidth + spacing, buttonY, buttonWidth, 20)
                .build();
        withTooltip(this.exportButton, "screen.simple_translate.blacklist.export.tooltip");
        this.addRenderableWidget(this.exportButton);

        this.importButton = Button.builder(
                Component.translatable("screen.simple_translate.import"),
                button -> importBlacklist())
                .bounds(startX + (buttonWidth + spacing) * 2, buttonY, buttonWidth, 20)
                .build();
        withTooltip(this.importButton, "screen.simple_translate.blacklist.import.tooltip");
        this.addRenderableWidget(this.importButton);

        Button backButton = Button.builder(
                Component.translatable("screen.simple_translate.back"),
                button -> this.onClose())
                .bounds(startX + (buttonWidth + spacing) * 3, buttonY, buttonWidth, 20)
                .build();
        withTooltip(backButton, "screen.simple_translate.back.tooltip");
        this.addRenderableWidget(backButton);
    }

    private void refreshBlacklistList() {
        this.blacklistList.children().clear();
        TranslationBlacklist blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist == null) {
            return;
        }

        List<String> entries = blacklist.getAllEntries();
        for (String entry : entries) {
            this.blacklistList.children().add(new BlacklistEntry(entry));
        }
    }

    private void addEntry() {
        String entry = this.newEntryInput.getValue().trim();
        if (entry.isEmpty()) {
            return;
        }

        TranslationBlacklist blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist != null) {
            blacklist.addEntry(entry);
            this.newEntryInput.setValue("");
            refreshBlacklistList();
        }
    }

    private void deleteSelectedEntry() {
        BlacklistEntry selected = this.blacklistList.getSelected();
        if (selected == null) {
            return;
        }

        TranslationBlacklist blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist != null) {
            blacklist.removeEntry(selected.entry);
            refreshBlacklistList();
        }
    }

    private void clearBlacklist() {
        TranslationBlacklist blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist != null) {
            blacklist.clear();
            refreshBlacklistList();
        }
    }

    private void exportBlacklist() {
        TranslationBlacklist blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist == null) {
            return;
        }

        try {
            java.nio.file.Path exportPath = SimpleTranslateMod.getConfigDir().resolve("blacklist_export.json");
            blacklist.exportToFile(exportPath);
            SimpleTranslateMod.getLogger().info("Blacklist exported to {}", exportPath);
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to export blacklist", e);
        }
    }

    private void importBlacklist() {
        TranslationBlacklist blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist == null) {
            return;
        }

        try {
            java.nio.file.Path importPath = SimpleTranslateMod.getConfigDir().resolve("blacklist_import.json");
            blacklist.importFromFile(importPath, true);
            refreshBlacklistList();
            SimpleTranslateMod.getLogger().info("Blacklist imported from {}", importPath);
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to import blacklist", e);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);

        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);

        TranslationBlacklist blacklist = SimpleTranslateMod.getTranslationBlacklist();
        int count = blacklist != null ? blacklist.size() : 0;
        graphics.text(this.font,
                Component.translatable("screen.simple_translate.blacklist.count", count),
                10,
                30,
                0xFFAAAAAA);

        drawBottomActionMask(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawBottomActionMask(GuiGraphicsExtractor graphics) {
        int top = this.height - 82;
        int left = Math.max(0, this.width / 2 - 205);
        int right = Math.min(this.width, this.width / 2 + 205);
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

    private class BlacklistList extends ObjectSelectionList<BlacklistEntry> {
        public BlacklistList(Minecraft minecraft, int width, int height, int top, int bottom) {
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

    private class BlacklistEntry extends ObjectSelectionList.Entry<BlacklistEntry> {
        private final String entry;

        public BlacklistEntry(String entry) {
            this.entry = entry;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                boolean isMouseOver, float partialTick) {
            int left = this.getContentX();
            int top = this.getContentY();
            String displayEntry = entry.length() > 45 ? entry.substring(0, 42) + "..." : entry;
            graphics.text(BlacklistManagerScreen.this.font, displayEntry, left + 5, top + 5, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            BlacklistManagerScreen.this.blacklistList.setSelected(this);
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(entry);
        }
    }
}
