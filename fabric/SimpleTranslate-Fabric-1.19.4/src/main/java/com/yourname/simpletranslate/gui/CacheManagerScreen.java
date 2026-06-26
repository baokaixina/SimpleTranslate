package com.yourname.simpletranslate.gui;

import com.mojang.blaze3d.vertex.PoseStack;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.cache.SharedCacheClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import com.yourname.simpletranslate.compat.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translation cache management screen
 */
public class CacheManagerScreen extends BaseSimpleTranslateScreen {
    private static final int MAX_LIST_WIDTH = 520;
    private static final int MIN_LIST_WIDTH = 180;
    private static final int SEARCH_WIDTH = 220;
    private static final int ROW_HEIGHT = 24;
    private static final int LIST_TOP = 104;
    private static final DateTimeFormatter SHARE_FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Screen parent;
    private final List<CacheEntry> allEntries = new ArrayList<>();
    private CacheList cacheList;
    private EditBox searchInput;
    private Button exportButton;
    private Button importButton;
    private Button editButton;
    private Button serverShareButton;
    private Component statusMessage = Component.empty();
    private int statusColor = 0xAAAAAA;

    public CacheManagerScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.cache_manager"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int listWidth = listWidth();
        int rowLeft = centerX - listWidth / 2;

        // Cache list is added first so top controls and bottom actions always render above it.
        this.cacheList = new CacheList(this.minecraft, this.width, listWidth, LIST_TOP, this.height - 60);
        this.addRenderableWidget(this.cacheList);

        int shareWidth = Math.max(110, Math.min(190, listWidth / 3));
        int searchWidth = Math.max(86, listWidth - shareWidth - 8);
        this.serverShareButton = Button.builder(
                serverShareLabel(),
                button -> toggleServerShare())
                .bounds(rowLeft, 28, shareWidth, 20)
                .build();
        withTooltip(this.serverShareButton, "screen.simple_translate.cache.server_share.tooltip");
        this.addRenderableWidget(this.serverShareButton);

        this.searchInput = new EditBox(this.font,
                rowLeft + shareWidth + 8,
                28,
                searchWidth,
                20,
                Component.translatable("screen.simple_translate.cache.search"));
        this.searchInput.setMaxLength(120);
        this.searchInput.setHint(Component.translatable("screen.simple_translate.cache.search_hint"));
        this.searchInput.setResponder(ignored -> applySearchFilter());
        withTooltip(this.searchInput, "screen.simple_translate.cache.search.tooltip");
        this.addRenderableWidget(this.searchInput);

        // Load cached translations
        refreshCacheList();
        refreshImportSourceStatus();

        // Bottom buttons
        int buttonY = this.height - 50;
        int buttonWidth = 60;
        int spacing = 5;
        int totalWidth = buttonWidth * 4 + spacing * 3;
        int startX = centerX - totalWidth / 2;

        this.editButton = Button.builder(
                Component.translatable("screen.simple_translate.cache.edit"),
                button -> editSelectedEntry())
                .bounds(startX, buttonY, buttonWidth, 20)
                .build();
        withTooltip(this.editButton, "screen.simple_translate.cache.edit.tooltip");
        this.addRenderableWidget(this.editButton);

        this.exportButton = Button.builder(
                Component.translatable("screen.simple_translate.export"),
                button -> exportCache())
                .bounds(startX + buttonWidth + spacing, buttonY, buttonWidth, 20)
                .build();
        withTooltip(this.exportButton, "screen.simple_translate.cache.export.tooltip");
        this.addRenderableWidget(this.exportButton);

        this.importButton = Button.builder(
                Component.translatable("screen.simple_translate.import"),
                button -> importCache())
                .bounds(startX + (buttonWidth + spacing) * 2, buttonY, buttonWidth, 20)
                .build();
        withTooltip(this.importButton, "screen.simple_translate.cache.import.tooltip");
        this.addRenderableWidget(this.importButton);

        Button backButton = Button.builder(
                Component.translatable("screen.simple_translate.back"),
                button -> this.onClose())
                .bounds(startX + (buttonWidth + spacing) * 3, buttonY, buttonWidth, 20)
                .build();
        withTooltip(backButton, "screen.simple_translate.back.tooltip");
        this.addRenderableWidget(backButton);
    }

    private void refreshCacheList() {
        this.allEntries.clear();
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null) {
            Map<String, TranslationCache.CacheViewEntry> translations = cache.getEntries();
            for (TranslationCache.CacheViewEntry entry : translations.values()) {
                this.allEntries.add(new CacheEntry(
                        entry.lane(),
                        entry.key(),
                        entry.translationText(),
                        entry.sourceText(),
                        entry.surface(),
                        entry.editedByPlayer()));
            }
        }
        applySearchFilter();
    }

    private void applySearchFilter() {
        if (this.cacheList == null) {
            return;
        }
        String query = this.searchInput == null ? "" : normalizeSearchText(this.searchInput.getValue());
        String selectedKey = this.cacheList.getSelected() == null ? null : this.cacheList.getSelected().key;
        CacheEntry nextSelection = null;
        this.cacheList.children().clear();
        for (CacheEntry entry : allEntries) {
            if (entry.matches(query)) {
                this.cacheList.children().add(entry);
                if (selectedKey != null && selectedKey.equals(entry.key)) {
                    nextSelection = entry;
                }
            }
        }
        this.cacheList.setSelected(nextSelection);
    }

    void refreshAfterEdit() {
        refreshCacheList();
    }

    private void refreshImportSourceStatus() {
        List<Path> sources = TranslationCache.discoverImportSources(configDir());
        if (sources.isEmpty()) {
            setStatus(Component.translatable("screen.simple_translate.cache.import.choose_folder"), 0xAAAAAA);
        } else {
            setStatus(Component.translatable("screen.simple_translate.cache.import.available", sources.size()), 0x88CCFF);
        }
    }

    private void setStatus(Component message, int color) {
        this.statusMessage = message == null ? Component.empty() : message;
        this.statusColor = color;
    }

    private Component serverShareLabel() {
        return Component.translatable(SimpleTranslateMod.isCacheServerShareEnabled()
                ? "screen.simple_translate.cache.server_share.on"
                : "screen.simple_translate.cache.server_share.off");
    }

    private Component serverShareStatus() {
        if (!SimpleTranslateMod.isCacheServerShareEnabled()) {
            return Component.empty();
        }
        if (!SharedCacheClient.isServerSupported()) {
            return Component.translatable("screen.simple_translate.cache.server_share.status.unsupported");
        }
        return Component.translatable(
                "screen.simple_translate.cache.server_share.status.connected",
                SharedCacheClient.queuedUploadCount(),
                SharedCacheClient.lastSnapshotQueued(),
                SharedCacheClient.uploadedEntries(),
                SharedCacheClient.receivedEntries());
    }

    private void toggleServerShare() {
        boolean enabled = !SimpleTranslateMod.isCacheServerShareEnabled();
        SimpleTranslateMod.setCacheServerShareEnabled(enabled);
        SharedCacheClient.onShareSettingChanged();
        if (this.serverShareButton != null) {
            this.serverShareButton.setMessage(serverShareLabel());
        }
        setStatus(Component.translatable(enabled
                ? "screen.simple_translate.cache.server_share.enabled"
                : "screen.simple_translate.cache.server_share.disabled"), enabled ? 0x88FF88 : 0xAAAAAA);
    }

    private void editSelectedEntry() {
        CacheEntry selected = this.cacheList.getSelected();
        if (selected != null) {
            Minecraft.getInstance().setScreen(new CacheEditScreen(this, selected.key));
        }
    }

    private void exportCache() {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null) {
            try {
                Path configDir = configDir();
                TranslationCache.CacheShareMetadata metadata = currentCacheShareMetadata();
                String fileName = "SimpleTranslateCache-"
                        + safeFilePart(metadata.worldKind())
                        + "-"
                        + safeFilePart(metadata.worldName())
                        + "-"
                        + LocalDateTime.now().format(SHARE_FILE_TIME)
                        + ".zip";
                TranslationCache.CacheShareExportResult result = cache.exportShareArchive(
                        configDir.resolve(fileName),
                        metadata,
                        null);
                setStatus(Component.translatable("screen.simple_translate.cache.export.done",
                        result.entries(), result.lanes(), fileName), 0x88FF88);
            } catch (Exception e) {
                SimpleTranslateMod.getLogger().error("Failed to export cache", e);
                setStatus(Component.translatable("screen.simple_translate.cache.export.failed"), 0xFF7777);
            }
        }
    }

    private void importCache() {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null) {
            try {
                Path selectedSource = chooseImportSource();
                if (selectedSource == null) {
                    setStatus(Component.translatable("screen.simple_translate.cache.import.cancelled"), 0xAAAAAA);
                    return;
                }
                TranslationCache.CacheImportResult result = cache.importFromShareSources(
                        List.of(selectedSource),
                        currentCacheShareMetadata().worldName());
                if (result.changed()) {
                    cache.saveNow();
                    SimpleTranslateMod.onTranslationCacheEdited();
                }
                refreshCacheList();
                if (result.imported() == 0
                        && result.skippedExisting() == 0
                        && result.skippedInvalid() == 0
                        && result.skippedWorldMismatch() == 0
                        && result.failedFiles() == 0) {
                    setStatus(Component.translatable("screen.simple_translate.cache.import.none"), 0xFFCC66);
                    return;
                }
                if (result.imported() == 0 && result.skippedWorldMismatch() > 0) {
                    setStatus(Component.translatable("screen.simple_translate.cache.import.world_mismatch"), 0xFFCC66);
                    return;
                }
                setStatus(Component.translatable("screen.simple_translate.cache.import.done",
                        result.imported(),
                        result.skippedExisting(),
                        result.skippedInvalid(),
                        result.skippedWorldMismatch(),
                        result.failedFiles()),
                        result.failedFiles() > 0 ? 0xFFCC66 : 0x88FF88);
            } catch (Exception e) {
                SimpleTranslateMod.getLogger().error("Failed to import cache", e);
                setStatus(Component.translatable("screen.simple_translate.cache.import.failed"), 0xFF7777);
            }
        }
    }

    private Path chooseImportSource() {
        String selectedFile = TinyFileDialogs.tinyfd_openFileDialog(
                Component.translatable("screen.simple_translate.cache.import.dialog").getString(),
                configDir().toString(),
                null,
                "Simple Translate Cache (*.zip, *.json)",
                false);
        if (selectedFile != null && !selectedFile.isBlank()) {
            return Path.of(selectedFile);
        }
        return null;
    }

    private TranslationCache.CacheShareMetadata currentCacheShareMetadata() {
        Minecraft client = Minecraft.getInstance();
        String worldKind = "global";
        String worldName = "";
        try {
            var serverData = client.getCurrentServer();
            if (serverData != null) {
                worldKind = "server";
                worldName = firstNonBlank(serverData.name, serverData.ip);
            } else if (client.getSingleplayerServer() != null) {
                worldKind = "local";
                worldName = client.getSingleplayerServer().getWorldData().getLevelName();
            }
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().debug("Unable to read cache share world name", e);
        }
        worldName = stripMinecraftFormatting(firstNonBlank(worldName, SimpleTranslateMod.getCurrentWorldId(), "global"));
        return new TranslationCache.CacheShareMetadata(worldKind, worldName);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String stripMinecraftFormatting(String text) {
        return text == null ? "" : text.replaceAll("(?i)§[0-9A-FK-OR]", "").trim();
    }

    private static String safeFilePart(String value) {
        String sanitized = stripMinecraftFormatting(value)
                .replaceAll("[\\\\/:*?\"<>|]+", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return sanitized.isBlank() ? "global" : sanitized;
    }

    private Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(SimpleTranslateMod.MODID);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        GuiGraphics graphics = new GuiGraphics(poseStack);
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);

        // Draw title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);

        // Draw stats
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        int count = cache != null ? cache.size() : 0;
        int categoryCount = cache != null ? cache.getLaneSizes().size() : 0;
        int rowLeft = this.cacheList == null ? this.width / 2 - listWidth() / 2 : this.cacheList.getRowLeft();
        int rowRight = this.cacheList == null ? this.width / 2 + listWidth() / 2 : this.cacheList.getRowRight();
        graphics.drawString(this.font, Component.translatable("screen.simple_translate.cache.stats",
                count,
                this.cacheList == null ? 0 : this.cacheList.children().size(),
                categoryCount), rowLeft, 54, 0xAAAAAA);
        Component shareStatus = serverShareStatus();
        int statusY = 66;
        if (!shareStatus.getString().isBlank()) {
            graphics.drawString(this.font,
                    Component.literal(fitText(shareStatus.getString(), Math.max(80, rowRight - rowLeft - 8))),
                    rowLeft, statusY, SharedCacheClient.isServerSupported() ? 0x80FFAA : 0xFFCC66);
            statusY += 10;
        }
        if (this.statusMessage != null && !this.statusMessage.getString().isBlank()) {
            graphics.drawString(this.font,
                    Component.literal(fitText(this.statusMessage.getString(), Math.max(80, rowRight - rowLeft - 8))),
                    rowLeft, statusY, statusColor);
        }

        // Draw column headers
        graphics.drawString(this.font, Component.translatable("screen.simple_translate.cache.header.lane"),
                rowLeft + 8, LIST_TOP - 10, 0xAAAAAA);
        graphics.drawString(this.font, Component.translatable("screen.simple_translate.cache.header.key"),
                rowLeft + 82, LIST_TOP - 10, 0xAAAAAA);
        graphics.drawString(this.font, Component.translatable("screen.simple_translate.cache.header.translation"),
                rowLeft + 250, LIST_TOP - 10, 0xAAAAAA);
        graphics.fill(rowLeft, LIST_TOP - 2, rowRight, LIST_TOP - 1, 0x44FFFFFF);

        if (this.cacheList != null && this.cacheList.children().isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.simple_translate.cache.no_matches"),
                    this.width / 2, LIST_TOP + 10, 0x777777);
        }

        boolean hasSelection = this.cacheList != null && this.cacheList.getSelected() != null;
        if (this.editButton != null) {
            this.editButton.active = hasSelection;
        }
        if (this.serverShareButton != null) {
            this.serverShareButton.setMessage(serverShareLabel());
        }

        drawBottomActionMask(graphics);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    private void drawBottomActionMask(GuiGraphics graphics) {
        int top = this.height - 60;
        int left = Math.max(0, this.width / 2 - 150);
        int right = Math.min(this.width, this.width / 2 + 150);
        graphics.fill(left, top, right, this.height - 2, 0xAA101010);
        graphics.fill(left, top, right, top + 1, 0x55FFFFFF);
    }

    private int listWidth() {
        return Math.max(MIN_LIST_WIDTH, Math.min(MAX_LIST_WIDTH, this.width - 40));
    }

    private static String normalizeSearchText(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private String fitText(String text, int maxWidth) {
        String value = text == null ? "" : text.replace('\n', ' ');
        if (this.font.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        int suffixWidth = this.font.width(suffix);
        while (!value.isEmpty() && this.font.width(value) + suffixWidth > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? suffix : value + suffix;
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
     * List widget for displaying cached translations
     */
    private class CacheList extends ObjectSelectionList<CacheEntry> {
        private final int rowWidth;

        public CacheList(Minecraft minecraft, int screenWidth, int rowWidth, int top, int bottom) {
            super(minecraft, screenWidth, Math.max(1, bottom - top), top, bottom, ROW_HEIGHT);
            this.rowWidth = rowWidth;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.getRowRight() + 4;
        }

        @Override
        public int getRowWidth() {
            return rowWidth - 12;
        }
    }

    /**
     * Entry for a single cached translation
     */
    private class CacheEntry extends ObjectSelectionList.Entry<CacheEntry> {
        private final String lane;
        private final String key;
        private final String translation;
        private final String sourceText;
        private final String surface;
        private final boolean editedByPlayer;

        public CacheEntry(String lane, String key, String translation, String sourceText, String surface,
                          boolean editedByPlayer) {
            this.lane = lane;
            this.key = key;
            this.translation = translation;
            this.sourceText = sourceText;
            this.surface = surface;
            this.editedByPlayer = editedByPlayer;
        }

        @Override
        public void render(PoseStack poseStack, int index, int top, int left, int width, int height,
                int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
            GuiGraphics graphics = new GuiGraphics(poseStack);
            int laneWidth = 66;
            int keyWidth = 154;
            int translationWidth = Math.max(80, width - laneWidth - keyWidth - 28);

            String displayLane = CacheManagerScreen.this.fitText(lane, laneWidth);
            graphics.drawString(CacheManagerScreen.this.font, displayLane, left + 5, top + 5, 0x88CCFF);

            String displayKey = surface + ":" + key.substring(Math.max(0, key.length() - 12));
            displayKey = CacheManagerScreen.this.fitText(displayKey, keyWidth);
            graphics.drawString(CacheManagerScreen.this.font, displayKey, left + 78, top + 5, 0xFFFFFF);

            String displayTranslation = translation.isBlank() ? sourceText : translation;
            if (editedByPlayer) {
                displayTranslation = Component.translatable("screen.simple_translate.cache.entry_edited").getString()
                        + " " + displayTranslation;
            }
            displayTranslation = CacheManagerScreen.this.fitText(displayTranslation, translationWidth);
            graphics.drawString(CacheManagerScreen.this.font, displayTranslation, left + 240, top + 5, 0x88FF88);
        }

        private boolean matches(String normalizedQuery) {
            if (normalizedQuery == null || normalizedQuery.isBlank()) {
                return true;
            }
            return normalizeSearchText(lane).contains(normalizedQuery)
                    || normalizeSearchText(key).contains(normalizedQuery)
                    || normalizeSearchText(surface).contains(normalizedQuery)
                    || normalizeSearchText(sourceText).contains(normalizedQuery)
                    || normalizeSearchText(translation).contains(normalizedQuery);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            CacheManagerScreen.this.cacheList.setSelected(this);
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.translatable("screen.simple_translate.cache.entry_narration", lane, key, translation);
        }
    }
}

