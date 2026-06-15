package com.yourname.simpletranslate.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import com.yourname.simpletranslate.translation.OcrTranslationService;
import com.yourname.simpletranslate.util.OcrManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import com.yourname.simpletranslate.compat.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class OcrOverlayScreen extends Screen {
    private static final int MIN_WIDTH = 80;
    private static final int MAX_WIDTH = 1600;
    private static final int MIN_HEIGHT = 40;
    private static final int MAX_HEIGHT = 900;
    private static final int HANDLE_SIZE = 12;
    private static final int CONTENT_PADDING = 6;
    private static final int TEXT_LINE_HEIGHT = 10;
    private static final int RESULT_MASK_COLOR = 0xA6000000;
    private static final float MIN_POSITIONED_TEXT_SCALE = 0.50F;
    private static final float MAX_POSITIONED_TEXT_SCALE = 4.00F;
    private static final int POSITIONED_SOURCE_MASK_COLOR = 0xD9000000;

    private final Screen parent;
    private int regionX;
    private int regionY;
    private int regionWidth;
    private int regionHeight;
    private DragMode dragMode = DragMode.NONE;
    private double dragStartMouseX;
    private double dragStartMouseY;
    private int dragStartX;
    private int dragStartY;
    private int dragStartWidth;
    private int dragStartHeight;
    private State state = State.IDLE;
    private OcrTranslationService.OcrResult result;
    private String errorMessage = "";
    private int resultScroll;
    private boolean capturePending;
    private boolean enterHeld;

    public OcrOverlayScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.ocr.overlay_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.regionWidth = Mth.clamp(ModConfig.OCR_REGION_WIDTH.get(), MIN_WIDTH, MAX_WIDTH);
        this.regionHeight = Mth.clamp(ModConfig.OCR_REGION_HEIGHT.get(), MIN_HEIGHT, MAX_HEIGHT);
        int configuredX = ModConfig.OCR_REGION_X.get();
        int configuredY = ModConfig.OCR_REGION_Y.get();
        this.regionX = configuredX < 0 ? (this.width - this.regionWidth) / 2 : configuredX;
        this.regionY = configuredY < 0 ? (this.height - this.regionHeight) / 2 : configuredY;
        clampRegion();
    }
    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        render(new GuiGraphics(poseStack), mouseX, mouseY, partialTick);
    }
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.capturePending) {
            this.capturePending = false;
            captureCurrentRegion();
        }
        drawRegion(graphics);
        drawPanel(graphics, mouseX, mouseY);
    }

    public void renderBackground(GuiGraphics graphics) {
        // Keep the world fully visible behind the OCR frame.
    }

    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the world fully visible behind the OCR frame.
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (isResizeHandle(mouseX, mouseY)) {
            startDrag(DragMode.RESIZE, mouseX, mouseY);
            return true;
        }
        if (isInsideRegion(mouseX, mouseY)) {
            startDrag(DragMode.MOVE, mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button != 0 || this.dragMode == DragMode.NONE) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        int dx = (int) Math.round(mouseX - this.dragStartMouseX);
        int dy = (int) Math.round(mouseY - this.dragStartMouseY);
        if (this.dragMode == DragMode.MOVE) {
            this.regionX = this.dragStartX + dx;
            this.regionY = this.dragStartY + dy;
        } else if (this.dragMode == DragMode.RESIZE) {
            this.regionWidth = Mth.clamp(this.dragStartWidth + dx, MIN_WIDTH, Math.min(MAX_WIDTH, this.width));
            this.regionHeight = Mth.clamp(this.dragStartHeight + dy, MIN_HEIGHT, Math.min(MAX_HEIGHT, this.height));
        }
        clampRegion();
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.dragMode != DragMode.NONE) {
            this.dragMode = DragMode.NONE;
            saveRegion();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return handleMouseScrolled(mouseX, mouseY, delta);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return handleMouseScrolled(mouseX, mouseY, scrollY);
    }

    private boolean handleMouseScrolled(double mouseX, double mouseY, double delta) {
        Rect panel = panelRect();
        if (panel.contains(mouseX, mouseY)) {
            this.resultScroll = Math.max(0, this.resultScroll - (int) Math.signum(delta));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            onClose();
            return true;
        }
        if (ModKeyBindings.matchesOcrToggleKey(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (isEnterKey(keyCode)) {
            if (!this.enterHeld) {
                this.enterHeld = true;
                requestRecognitionShortcut();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (ModKeyBindings.matchesOcrToggleKey(keyCode, scanCode)) {
            return true;
        }
        if (isEnterKey(keyCode)) {
            this.enterHeld = false;
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private static boolean isEnterKey(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
    }

    @Override
    public void onClose() {
        saveRegion();
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void requestCapture() {
        if (this.state == State.RECOGNIZING) {
            return;
        }
        this.state = State.RECOGNIZING;
        this.errorMessage = "";
        this.capturePending = true;
        this.resultScroll = 0;
    }

    public void requestRecognitionShortcut() {
        requestCapture();
    }

    private void captureCurrentRegion() {
        try {
            byte[] png = captureRegionPng();
            OcrManager.recognize(png).thenAccept(result ->
                    Minecraft.getInstance().execute(() -> finishRecognition(result)));
        } catch (Exception e) {
            finishRecognition(OcrTranslationService.OcrResult.failure(
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void finishRecognition(OcrTranslationService.OcrResult result) {
        this.result = result;
        if (result != null && result.success()) {
            this.state = State.DONE;
        } else {
            this.state = State.FAILED;
            this.errorMessage = result == null ? "OCR failed" : result.errorMessage();
        }
    }

    private byte[] captureRegionPng() throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        NativeImage screenshot = takeScreenshotCompat(minecraft.getMainRenderTarget());
        try {
            int framebufferWidth = screenshot.getWidth();
            int framebufferHeight = screenshot.getHeight();
            double scaleX = framebufferWidth / (double) this.width;
            double scaleY = framebufferHeight / (double) this.height;
            int cropX = Mth.clamp((int) Math.round(this.regionX * scaleX), 0, framebufferWidth - 1);
            int cropY = Mth.clamp((int) Math.round(this.regionY * scaleY), 0, framebufferHeight - 1);
            int cropWidth = Mth.clamp((int) Math.round(this.regionWidth * scaleX), 1, framebufferWidth - cropX);
            int cropHeight = Mth.clamp((int) Math.round(this.regionHeight * scaleY), 1, framebufferHeight - cropY);
            NativeImage cropped = new NativeImage(cropWidth, cropHeight, false);
            try {
                for (int y = 0; y < cropHeight; y++) {
                    for (int x = 0; x < cropWidth; x++) {
                        setPixelCompat(cropped, x, y, getPixelCompat(screenshot, cropX + x, cropY + y));
                    }
                }
                NativeImage outgoing = downscaleIfNeeded(cropped);
                try {
                    return toPngBytes(outgoing);
                } finally {
                    if (outgoing != cropped) {
                        outgoing.close();
                    }
                }
            } finally {
                cropped.close();
            }
        } finally {
            screenshot.close();
        }
    }

    private NativeImage downscaleIfNeeded(NativeImage image) throws IOException {
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        int longest = Math.max(sourceWidth, sourceHeight);
        if (longest <= 1024) {
            return image;
        }
        double ratio = 1024.0 / longest;
        int targetWidth = Math.max(1, (int) Math.round(sourceWidth * ratio));
        int targetHeight = Math.max(1, (int) Math.round(sourceHeight * ratio));
        NativeImage scaled = new NativeImage(targetWidth, targetHeight, false);
        for (int y = 0; y < targetHeight; y++) {
            int sourceY = Math.min(sourceHeight - 1, (int) Math.floor(y / ratio));
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = Math.min(sourceWidth - 1, (int) Math.floor(x / ratio));
                setPixelCompat(scaled, x, y, getPixelCompat(image, sourceX, sourceY));
            }
        }
        return scaled;
    }

    private static NativeImage takeScreenshotCompat(RenderTarget target) throws IOException {
        return Screenshot.takeScreenshot(target);
    }

    private static int getPixelCompat(NativeImage image, int x, int y) throws IOException {
        return image.getPixelRGBA(x, y);
    }

    private static void setPixelCompat(NativeImage image, int x, int y, int color) throws IOException {
        image.setPixelRGBA(x, y, color);
    }

    private static byte[] toPngBytes(NativeImage image) throws IOException {
        return image.asByteArray();
    }

    private void drawRegion(GuiGraphics graphics) {
        int color = this.state == State.RECOGNIZING ? 0xFF55FFFF : 0xFF00E5FF;
        if (this.state != State.IDLE) {
            graphics.fill(this.regionX + 1, this.regionY + 1,
                    this.regionX + this.regionWidth - 1,
                    this.regionY + this.regionHeight - 1,
                    RESULT_MASK_COLOR);
        }
        graphics.fill(this.regionX, this.regionY, this.regionX + this.regionWidth, this.regionY + 1, color);
        graphics.fill(this.regionX, this.regionY + this.regionHeight - 1,
                this.regionX + this.regionWidth, this.regionY + this.regionHeight, color);
        graphics.fill(this.regionX, this.regionY, this.regionX + 1, this.regionY + this.regionHeight, color);
        graphics.fill(this.regionX + this.regionWidth - 1, this.regionY,
                this.regionX + this.regionWidth, this.regionY + this.regionHeight, color);
        graphics.fill(this.regionX + this.regionWidth - HANDLE_SIZE,
                this.regionY + this.regionHeight - HANDLE_SIZE,
                this.regionX + this.regionWidth,
                this.regionY + this.regionHeight,
                0x8800E5FF);
    }

    private void drawPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.state == State.IDLE) {
            return;
        }
        if (this.state == State.DONE && this.result != null && this.result.hasPositionedRegions()) {
            drawPositionedTranslations(graphics);
            return;
        }
        Rect panel = panelRect();
        int margin = Math.max(3, Math.min(CONTENT_PADDING, panel.width / 8));
        int textX = panel.x + margin;
        int textY = panel.y + margin;
        int textWidth = Math.max(1, panel.width - margin * 2);
        List<FormattedCharSequence> lines = resultLines(textWidth);
        int maxVisibleLines = Math.max(0, (panel.y + panel.height - margin - textY) / TEXT_LINE_HEIGHT);
        int maxScroll = Math.max(0, lines.size() - maxVisibleLines);
        this.resultScroll = Mth.clamp(this.resultScroll, 0, maxScroll);
        int end = Math.min(lines.size(), this.resultScroll + maxVisibleLines);
        graphics.enableScissor(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height);
        try {
            for (int i = this.resultScroll; i < end; i++) {
                int y = textY + (i - this.resultScroll) * TEXT_LINE_HEIGHT;
                graphics.drawString(this.font, lines.get(i), textX + 1, y + 1, 0xCC000000);
                graphics.drawString(this.font, lines.get(i), textX, y, 0xFFECECEC);
            }
        } finally {
            graphics.disableScissor();
        }
        if (maxScroll > 0 && panel.width >= 46 && panel.height >= 18) {
            graphics.drawString(this.font, Component.literal((this.resultScroll + 1) + "/" + (maxScroll + 1)),
                    panel.x + panel.width - 34, panel.y + panel.height - 12, 0xFFAAAAAA);
        }
    }

    private void drawPositionedTranslations(GuiGraphics graphics) {
        Rect captureArea = new Rect(this.regionX, this.regionY, this.regionWidth, this.regionHeight);
        for (OcrTranslationService.OcrRegion region : this.result.regions()) {
            Rect sourceBox = mapRegionToCaptureArea(captureArea, region);
            if (sourceBox.width <= 0 || sourceBox.height <= 0) {
                continue;
            }
            drawTranslationInsideSourceBox(graphics, sourceBox, region.translationText());
        }
    }

    private Rect mapRegionToCaptureArea(Rect captureArea, OcrTranslationService.OcrRegion region) {
        int left = captureArea.x + Math.round(region.x() * captureArea.width / 1000.0F);
        int top = captureArea.y + Math.round(region.y() * captureArea.height / 1000.0F);
        int right = captureArea.x + Math.round((region.x() + region.width()) * captureArea.width / 1000.0F);
        int bottom = captureArea.y + Math.round((region.y() + region.height()) * captureArea.height / 1000.0F);
        left = Mth.clamp(left, captureArea.x + 1, captureArea.x + captureArea.width - 2);
        top = Mth.clamp(top, captureArea.y + 1, captureArea.y + captureArea.height - 2);
        right = Mth.clamp(right, left + 1, captureArea.x + captureArea.width - 1);
        bottom = Mth.clamp(bottom, top + 1, captureArea.y + captureArea.height - 1);
        return new Rect(left, top, right - left, bottom - top);
    }

    private void drawTranslationInsideSourceBox(GuiGraphics graphics, Rect sourceBox, String translation) {
        String text = translation == null ? ""
                : translation.replaceAll("\\s*\\R\\s*", " ").trim();
        if (text.isBlank()) {
            return;
        }
        int padding = sourceBox.width >= 12 && sourceBox.height >= 12 ? 1 : 0;
        int contentWidth = Math.max(1, sourceBox.width - padding * 2);
        int contentHeight = Math.max(1, sourceBox.height - padding * 2);
        PositionedTextLayout layout = fitPositionedText(text, contentWidth, contentHeight);
        int scaledTextWidth = Math.round(layout.textWidth * layout.scale);
        int scaledTextHeight = Math.round(this.font.lineHeight * layout.scale);
        int drawX = sourceBox.x + padding + Math.max(0, (contentWidth - scaledTextWidth) / 2);
        int drawY = sourceBox.y + padding + Math.max(0, (contentHeight - scaledTextHeight) / 2);

        graphics.fill(sourceBox.x, sourceBox.y,
                sourceBox.x + sourceBox.width, sourceBox.y + sourceBox.height,
                POSITIONED_SOURCE_MASK_COLOR);
        graphics.enableScissor(sourceBox.x, sourceBox.y,
                sourceBox.x + sourceBox.width, sourceBox.y + sourceBox.height);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(drawX, drawY, 0.0F);
            graphics.pose().scale(layout.scale, layout.scale, 1.0F);
            graphics.drawString(this.font, layout.line, 1, 1, 0xE6000000);
            graphics.drawString(this.font, layout.line, 0, 0, 0xFFF2F2F2);
        } finally {
            graphics.pose().popPose();
            graphics.disableScissor();
        }
    }

    private PositionedTextLayout fitPositionedText(String text, int width, int height) {
        FormattedCharSequence line = Component.literal(text).getVisualOrderText();
        int textWidth = Math.max(1, this.font.width(line));
        float heightScale = height / (float) Math.max(1, this.font.lineHeight);
        float widthScale = width / (float) textWidth;
        float scale = Mth.clamp(Math.min(heightScale, widthScale),
                MIN_POSITIONED_TEXT_SCALE, MAX_POSITIONED_TEXT_SCALE);
        return new PositionedTextLayout(scale, line, textWidth);
    }

    private List<FormattedCharSequence> resultLines(int textWidth) {
        Font font = this.font;
        List<FormattedCharSequence> lines = new ArrayList<>();
        int safeTextWidth = Math.max(1, textWidth);
        if (this.state == State.IDLE) {
            return lines;
        }
        if (this.state == State.RECOGNIZING) {
            lines.addAll(font.split(Component.translatable("screen.simple_translate.ocr.recognizing_detail"), safeTextWidth));
            return lines;
        }
        if (this.state == State.FAILED) {
            lines.addAll(font.split(Component.translatable("screen.simple_translate.ocr.failed", this.errorMessage), safeTextWidth));
            return lines;
        }
        String translation = this.result == null ? "" : this.result.translationText();
        if (translation == null || translation.isBlank()) {
            translation = Component.translatable("screen.simple_translate.ocr.no_translation").getString();
        }
        lines.addAll(paragraphLines(font, translation, safeTextWidth));
        return lines;
    }

    private static List<FormattedCharSequence> paragraphLines(Font font, String text, int width) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isBlank()) {
            return lines;
        }
        String[] paragraphs = normalized.split("\\n\\s*\\n+");
        for (String paragraphText : paragraphs) {
            String paragraph = paragraphText.replaceAll("\\s*\\n\\s*", " ").trim();
            if (paragraph.isBlank()) {
                continue;
            }
            if (!lines.isEmpty()) {
                lines.add(FormattedCharSequence.EMPTY);
            }
            lines.addAll(font.split(Component.literal("\u3000\u3000" + paragraph), Math.max(1, width)));
        }
        return lines;
    }

    private Rect panelRect() {
        return new Rect(this.regionX + 2, this.regionY + 2,
                Math.max(1, this.regionWidth - 4),
                Math.max(1, this.regionHeight - 4));
    }

    private void startDrag(DragMode mode, double mouseX, double mouseY) {
        this.dragMode = mode;
        this.dragStartMouseX = mouseX;
        this.dragStartMouseY = mouseY;
        this.dragStartX = this.regionX;
        this.dragStartY = this.regionY;
        this.dragStartWidth = this.regionWidth;
        this.dragStartHeight = this.regionHeight;
    }

    private boolean isInsideRegion(double mouseX, double mouseY) {
        return mouseX >= this.regionX && mouseX <= this.regionX + this.regionWidth
                && mouseY >= this.regionY && mouseY <= this.regionY + this.regionHeight;
    }

    private boolean isResizeHandle(double mouseX, double mouseY) {
        return mouseX >= this.regionX + this.regionWidth - HANDLE_SIZE
                && mouseX <= this.regionX + this.regionWidth
                && mouseY >= this.regionY + this.regionHeight - HANDLE_SIZE
                && mouseY <= this.regionY + this.regionHeight;
    }

    private void clampRegion() {
        this.regionWidth = Mth.clamp(this.regionWidth, MIN_WIDTH, Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, this.width)));
        this.regionHeight = Mth.clamp(this.regionHeight, MIN_HEIGHT, Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, this.height)));
        this.regionX = Mth.clamp(this.regionX, 0, Math.max(0, this.width - this.regionWidth));
        this.regionY = Mth.clamp(this.regionY, 0, Math.max(0, this.height - this.regionHeight));
    }

    private void saveRegion() {
        ModConfig.OCR_REGION_X.set(this.regionX);
        ModConfig.OCR_REGION_Y.set(this.regionY);
        ModConfig.OCR_REGION_WIDTH.set(this.regionWidth);
        ModConfig.OCR_REGION_HEIGHT.set(this.regionHeight);
        ModConfig.save();
    }

    private enum DragMode {
        NONE,
        MOVE,
        RESIZE
    }

    private enum State {
        IDLE,
        RECOGNIZING,
        DONE,
        FAILED
    }

    private record Rect(int x, int y, int width, int height) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    private record PositionedTextLayout(float scale, FormattedCharSequence line, int textWidth) {
    }
}

