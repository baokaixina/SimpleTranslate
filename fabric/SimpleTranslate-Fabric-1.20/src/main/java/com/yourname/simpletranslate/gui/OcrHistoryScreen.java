package com.yourname.simpletranslate.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.util.OcrHistoryManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OcrHistoryScreen extends BaseSimpleTranslateScreen {
    private static final int BOTTOM_BAR_WIDTH = 250;
    private static final int BOTTOM_BUTTON_WIDTH = 116;
    private static final int ROW_HEIGHT = 62;

    private final Screen parent;
    private List<OcrHistoryManager.Entry> entries = List.of();
    private OcrHistoryManager.Entry selected;
    private int listScroll;
    private int detailScroll;
    private final Map<String, TextureRef> textures = new HashMap<>();
    private final Set<String> missingTextures = new HashSet<>();
    private Button previewButton;
    private boolean fullPreview;

    public OcrHistoryScreen(Screen parent) {
        super(net.minecraft.network.chat.Component.translatable("screen.simple_translate.ocr_history.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.entries = OcrHistoryManager.entries();
        if (this.selected == null && !this.entries.isEmpty()) {
            this.selected = this.entries.get(0);
        }
        this.listScroll = Mth.clamp(this.listScroll, 0, maxListScroll());
        int buttonY = this.height - 28;
        int leftButtonX = this.width / 2 - BOTTOM_BAR_WIDTH / 2;
        this.previewButton = Button.builder(
                net.minecraft.network.chat.Component.translatable("screen.simple_translate.ocr_history.preview"),
                button -> this.fullPreview = true)
                .bounds(leftButtonX, buttonY, BOTTOM_BUTTON_WIDTH, 20)
                .build();
        withTooltip(this.previewButton, "screen.simple_translate.ocr_history.preview.tooltip");
        this.addRenderableWidget(this.previewButton);

        Button backButton = Button.builder(
                net.minecraft.network.chat.Component.translatable("screen.simple_translate.back"),
                button -> onClose())
                .bounds(leftButtonX + BOTTOM_BAR_WIDTH - BOTTOM_BUTTON_WIDTH, buttonY, BOTTOM_BUTTON_WIDTH, 20)
                .build();
        withTooltip(backButton, "screen.simple_translate.back.tooltip");
        this.addRenderableWidget(backButton);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.fullPreview) {
            renderFullPreview(graphics);
            return;
        }
        if (this.previewButton != null) {
            this.previewButton.active = textureFor(this.selected) != null;
            this.previewButton.visible = true;
        }
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                net.minecraft.network.chat.Component.translatable(
                        ModConfig.OCR_HISTORY_ENABLED.get()
                                ? "screen.simple_translate.ocr_history.scope"
                                : "screen.simple_translate.ocr_history.disabled",
                        OcrHistoryManager.currentScopeName(), this.entries.size()),
                this.width / 2, 26, ModConfig.OCR_HISTORY_ENABLED.get() ? 0xAAAAAA : 0xFFCC66);

        drawPanels(graphics, mouseX, mouseY);
        drawBottomMask(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawPanels(GuiGraphics graphics, int mouseX, int mouseY) {
        int top = 44;
        int bottom = this.height - 36;
        int gap = 10;
        int listWidth = Math.max(170, Math.min(260, this.width / 3));
        int left = 18;
        int detailLeft = left + listWidth + gap;
        int detailWidth = Math.max(120, this.width - detailLeft - 18);
        if (detailWidth < 150) {
            listWidth = Math.max(150, this.width - 36);
            detailLeft = left;
            detailWidth = listWidth;
        }

        drawListPanel(graphics, left, top, listWidth, bottom - top, mouseX, mouseY);
        if (detailLeft != left || this.selected != null) {
            drawDetailPanel(graphics, detailLeft, top, detailWidth, bottom - top);
        }
    }

    private void drawListPanel(GuiGraphics graphics, int left, int top, int width, int height,
                               int mouseX, int mouseY) {
        graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, 0xAA000000);
        graphics.fill(left, top, left + width, top + height, 0x55202020);
        graphics.drawString(this.font,
                net.minecraft.network.chat.Component.translatable("screen.simple_translate.ocr_history.entries"),
                left + 6, top + 6, 0xFFFFFF);

        if (this.entries.isEmpty()) {
            graphics.drawString(this.font,
                    net.minecraft.network.chat.Component.translatable("screen.simple_translate.ocr_history.empty"),
                    left + 8, top + 28, 0xAAAAAA);
            return;
        }

        int listTop = top + 22;
        int listHeight = height - 26;
        this.listScroll = Mth.clamp(this.listScroll, 0, maxListScroll());
        graphics.enableScissor(left, listTop, left + width, listTop + listHeight);
        try {
            int y = listTop - this.listScroll;
            for (OcrHistoryManager.Entry entry : this.entries) {
                if (y + ROW_HEIGHT >= listTop && y <= listTop + listHeight) {
                    drawListRow(graphics, entry, left + 4, y, width - 8,
                            mouseX >= left + 4 && mouseX <= left + width - 4
                                    && mouseY >= y && mouseY <= y + ROW_HEIGHT);
                }
                y += ROW_HEIGHT;
            }
        } finally {
            graphics.disableScissor();
        }
    }

    private void drawListRow(GuiGraphics graphics, OcrHistoryManager.Entry entry, int x, int y,
                             int width, boolean hovered) {
        boolean selectedRow = this.selected != null && this.selected.id().equals(entry.id());
        int color = selectedRow ? 0x8844AAFF : hovered ? 0x55333333 : 0x33111111;
        graphics.fill(x, y, x + width, y + ROW_HEIGHT - 4, color);
        TextureRef texture = textureFor(entry);
        if (texture != null) {
            blitFitted(graphics, texture, x + 4, y + 5, 48, 38);
        } else {
            graphics.fill(x + 4, y + 5, x + 52, y + 43, 0x88000000);
        }
        graphics.drawString(this.font, entry.displayTime(), x + 58, y + 6, 0xCCCCCC);
        List<FormattedCharSequence> summary = this.font.split(
                net.minecraft.network.chat.Component.literal(entry.summary()), Math.max(20, width - 64));
        int lineY = y + 19;
        for (int i = 0; i < Math.min(3, summary.size()); i++) {
            graphics.drawString(this.font, summary.get(i), x + 58, lineY, 0xE6E6E6);
            lineY += this.font.lineHeight;
        }
    }

    private void drawDetailPanel(GuiGraphics graphics, int left, int top, int width, int height) {
        graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, 0xAA000000);
        graphics.fill(left, top, left + width, top + height, 0x55202020);
        if (this.selected == null) {
            graphics.drawString(this.font,
                    net.minecraft.network.chat.Component.translatable("screen.simple_translate.ocr_history.select_hint"),
                    left + 8, top + 8, 0xAAAAAA);
            return;
        }

        int innerLeft = left + 8;
        int innerTop = top + 8;
        int innerWidth = width - 16;
        int innerHeight = height - 16;
        int y = innerTop - this.detailScroll;
        graphics.enableScissor(left, top, left + width, top + height);
        try {
            TextureRef texture = textureFor(this.selected);
            if (texture != null) {
                int imageHeight = Math.max(70, Math.min(innerHeight - 90, innerHeight / 2));
                y += blitFitted(graphics, texture, innerLeft, y, innerWidth, imageHeight) + 8;
            } else {
                graphics.drawString(this.font,
                        net.minecraft.network.chat.Component.translatable("screen.simple_translate.ocr_history.screenshot_missing"),
                        innerLeft, y, 0xFFCC66);
                y += 18;
            }
            y = drawParagraph(graphics, "screen.simple_translate.ocr_history.source",
                    this.selected.sourceText(), innerLeft, y, innerWidth, 0x88CCFF);
            y += 8;
            drawParagraph(graphics, "screen.simple_translate.ocr_history.translation",
                    this.selected.translationText(), innerLeft, y, innerWidth, 0x88FF88);
        } finally {
            graphics.disableScissor();
        }
    }

    private int drawParagraph(GuiGraphics graphics, String titleKey, String text, int x, int y, int width, int titleColor) {
        graphics.drawString(this.font, net.minecraft.network.chat.Component.translatable(titleKey), x, y, titleColor);
        y += this.font.lineHeight + 2;
        String value = text == null || text.isBlank()
                ? net.minecraft.network.chat.Component.translatable("screen.simple_translate.ocr.no_text").getString()
                : text;
        for (String paragraph : value.replace("\r\n", "\n").replace('\r', '\n').split("\\n")) {
            List<FormattedCharSequence> lines = this.font.split(
                    net.minecraft.network.chat.Component.literal(paragraph), Math.max(1, width));
            for (FormattedCharSequence line : lines) {
                graphics.drawString(this.font, line, x, y, 0xE6E6E6);
                y += this.font.lineHeight;
            }
        }
        return y;
    }

    private void renderFullPreview(GuiGraphics graphics) {
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);
        graphics.fill(0, 0, this.width, this.height, 0xDD000000);
        TextureRef texture = textureFor(this.selected);
        if (texture == null) {
            graphics.drawCenteredString(this.font,
                    net.minecraft.network.chat.Component.translatable(
                            "screen.simple_translate.ocr_history.screenshot_missing"),
                    this.width / 2, this.height / 2 - 5, 0xFFCC66);
        } else {
            graphics.drawCenteredString(this.font,
                    net.minecraft.network.chat.Component.translatable(
                            "screen.simple_translate.ocr_history.preview"),
                    this.width / 2, 12, 0xFFFFFF);
            blitCentered(graphics, texture, 18, 32, this.width - 36, this.height - 62);
        }
        graphics.drawCenteredString(this.font,
                net.minecraft.network.chat.Component.translatable(
                        "screen.simple_translate.ocr_history.preview_hint"),
                this.width / 2, this.height - 20, 0xAAAAAA);
    }

    private int blitFitted(GuiGraphics graphics, TextureRef texture, int x, int y, int maxWidth, int maxHeight) {
        float scale = Math.min(maxWidth / (float) texture.width(), maxHeight / (float) texture.height());
        int drawWidth = Math.max(1, Math.round(texture.width() * scale));
        int drawHeight = Math.max(1, Math.round(texture.height() * scale));
        int drawX = x + Math.max(0, (maxWidth - drawWidth) / 2);
        graphics.blit(texture.location(), drawX, y, drawWidth, drawHeight, 0.0F, 0.0F,
                texture.width(), texture.height(), texture.width(), texture.height());
        return drawHeight;
    }

    private void blitCentered(GuiGraphics graphics, TextureRef texture, int x, int y, int maxWidth, int maxHeight) {
        float scale = Math.min(maxWidth / (float) texture.width(), maxHeight / (float) texture.height());
        int drawWidth = Math.max(1, Math.round(texture.width() * scale));
        int drawHeight = Math.max(1, Math.round(texture.height() * scale));
        int drawX = x + Math.max(0, (maxWidth - drawWidth) / 2);
        int drawY = y + Math.max(0, (maxHeight - drawHeight) / 2);
        graphics.blit(texture.location(), drawX, drawY, drawWidth, drawHeight, 0.0F, 0.0F,
                texture.width(), texture.height(), texture.width(), texture.height());
    }

    private TextureRef textureFor(OcrHistoryManager.Entry entry) {
        if (entry == null || missingTextures.contains(entry.id())) {
            return null;
        }
        TextureRef cached = textures.get(entry.id());
        if (cached != null) {
            return cached;
        }
        Path imagePath = OcrHistoryManager.imagePath(entry);
        if (imagePath == null || !Files.exists(imagePath)) {
            missingTextures.add(entry.id());
            return null;
        }
        try (InputStream input = Files.newInputStream(imagePath)) {
            NativeImage image = NativeImage.read(input);
            int width = image.getWidth();
            int height = image.getHeight();
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation location = Minecraft.getInstance().getTextureManager()
                    .register("simple_translate/ocr_history/" + entry.normalizedId(), texture);
            TextureRef ref = new TextureRef(location, width, height);
            textures.put(entry.id(), ref);
            return ref;
        } catch (Exception e) {
            missingTextures.add(entry.id());
            return null;
        }
    }

    private int maxListScroll() {
        int listHeight = Math.max(1, this.height - 44 - 36 - 26);
        return Math.max(0, this.entries.size() * ROW_HEIGHT - listHeight);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.fullPreview) {
            this.fullPreview = false;
            return true;
        }
        if (button == 0) {
            OcrHistoryManager.Entry clicked = entryAt(mouseX, mouseY);
            if (clicked != null) {
                this.selected = clicked;
                this.detailScroll = 0;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.fullPreview && keyCode == 256) {
            this.fullPreview = false;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private OcrHistoryManager.Entry entryAt(double mouseX, double mouseY) {
        int top = 44;
        int bottom = this.height - 36;
        int listWidth = Math.max(170, Math.min(260, this.width / 3));
        int left = 18;
        int listTop = top + 22;
        int listHeight = bottom - top - 26;
        if (mouseX < left || mouseX > left + listWidth || mouseY < listTop || mouseY > listTop + listHeight) {
            return null;
        }
        int index = (int) ((mouseY - listTop + this.listScroll) / ROW_HEIGHT);
        return index >= 0 && index < this.entries.size() ? this.entries.get(index) : null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int listWidth = Math.max(170, Math.min(260, this.width / 3));
        if (mouseX >= 18 && mouseX <= 18 + listWidth) {
            this.listScroll = Mth.clamp(this.listScroll - (int) Math.round(delta * 28), 0, maxListScroll());
            return true;
        }
        this.detailScroll = Math.max(0, this.detailScroll - (int) Math.round(delta * 28));
        return true;
    }

    private void drawBottomMask(GuiGraphics graphics) {
        int top = this.height - 36;
        int left = Math.max(0, this.width / 2 - BOTTOM_BAR_WIDTH / 2 - 8);
        int right = Math.min(this.width, this.width / 2 + BOTTOM_BAR_WIDTH / 2 + 8);
        graphics.fill(left, top, right, this.height - 2, 0xAA101010);
        graphics.fill(left, top, right, top + 1, 0x55FFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft minecraft = Minecraft.getInstance();
        for (TextureRef ref : this.textures.values()) {
            minecraft.getTextureManager().release(ref.location());
        }
        this.textures.clear();
        minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record TextureRef(ResourceLocation location, int width, int height) {
    }
}
