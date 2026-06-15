package com.yourname.simpletranslate.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.OcrTranslationService;
import com.yourname.simpletranslate.util.OcrManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class OcrOverlayScreen extends Screen {
    private static final int MIN_WIDTH = 80;
    private static final int MAX_WIDTH = 1600;
    private static final int MIN_HEIGHT = 40;
    private static final int MAX_HEIGHT = 900;
    private static final int HANDLE_SIZE = 12;
    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 150;

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

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        Rect recognize = recognizeButton();
        Rect close = closeButton();
        if (recognize.contains(mouseX, mouseY)) {
            requestCapture();
            return true;
        }
        if (close.contains(mouseX, mouseY)) {
            onClose();
            return true;
        }
        if (isResizeHandle(mouseX, mouseY)) {
            startDrag(DragMode.RESIZE, mouseX, mouseY);
            return true;
        }
        if (isInsideRegion(mouseX, mouseY)) {
            startDrag(DragMode.MOVE, mouseX, mouseY);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button != 0 || this.dragMode == DragMode.NONE) {
            return false;
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

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.dragMode != DragMode.NONE) {
            this.dragMode = DragMode.NONE;
            saveRegion();
            return true;
        }
        return false;
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

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return mouseClicked(event.x(), event.y(), event.button());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return mouseDragged(event.x(), event.y(), event.button(), dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return mouseReleased(event.x(), event.y(), event.button());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return keyPressed(event.key(), event.scancode(), event.modifiers());
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
        try {
            Method oldMethod = Screenshot.class.getMethod("takeScreenshot", RenderTarget.class);
            return (NativeImage) oldMethod.invoke(null, target);
        } catch (NoSuchMethodException ignored) {
            CompletableFuture<NativeImage> future = new CompletableFuture<>();
            try {
                Method newMethod = Screenshot.class.getMethod("takeScreenshot", RenderTarget.class, Consumer.class);
                newMethod.invoke(null, target, (Consumer<NativeImage>) future::complete);
                return future.join();
            } catch (ReflectiveOperationException e) {
                throw new IOException("Unsupported screenshot API", e);
            }
        } catch (ReflectiveOperationException e) {
            throw new IOException("Failed to capture screenshot", e);
        }
    }

    private static int getPixelCompat(NativeImage image, int x, int y) throws IOException {
        try {
            Method method = NativeImage.class.getMethod("getPixelRGBA", int.class, int.class);
            return (Integer) method.invoke(image, x, y);
        } catch (NoSuchMethodException ignored) {
            try {
                Method method = NativeImage.class.getMethod("getPixel", int.class, int.class);
                return (Integer) method.invoke(image, x, y);
            } catch (ReflectiveOperationException e) {
                throw new IOException("Unsupported NativeImage pixel read API", e);
            }
        } catch (ReflectiveOperationException e) {
            throw new IOException("Failed to read screenshot pixel", e);
        }
    }

    private static void setPixelCompat(NativeImage image, int x, int y, int color) throws IOException {
        try {
            Method method = NativeImage.class.getMethod("setPixelRGBA", int.class, int.class, int.class);
            method.invoke(image, x, y, color);
        } catch (NoSuchMethodException ignored) {
            try {
                Method method = NativeImage.class.getMethod("setPixel", int.class, int.class, int.class);
                method.invoke(image, x, y, color);
            } catch (ReflectiveOperationException e) {
                throw new IOException("Unsupported NativeImage pixel write API", e);
            }
        } catch (ReflectiveOperationException e) {
            throw new IOException("Failed to write screenshot pixel", e);
        }
    }

    private static byte[] toPngBytes(NativeImage image) throws IOException {
        try {
            Method method = NativeImage.class.getMethod("asByteArray");
            return (byte[]) method.invoke(image);
        } catch (NoSuchMethodException ignored) {
            Path temp = Files.createTempFile("simpletranslate-ocr-", ".png");
            try {
                image.writeToFile(temp);
                return Files.readAllBytes(temp);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (ReflectiveOperationException e) {
            throw new IOException("Failed to encode screenshot", e);
        }
    }

    private void drawRegion(GuiGraphics graphics) {
        int color = this.state == State.RECOGNIZING ? 0xFF55FFFF : 0xFF00E5FF;
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
        graphics.drawString(this.font, Component.translatable("screen.simple_translate.ocr.drag_hint"),
                this.regionX + 6, this.regionY + 6, 0xFFFFFFFF);
    }

    private void drawPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        Rect panel = panelRect();
        graphics.fill(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height, 0xCC101010);
        graphics.fill(panel.x, panel.y, panel.x + panel.width, panel.y + 1, 0x66FFFFFF);
        graphics.drawString(this.font, Component.translatable("screen.simple_translate.ocr.panel_title"),
                panel.x + 8, panel.y + 7, 0xFFFFFFFF);

        Rect recognize = recognizeButton();
        Rect close = closeButton();
        drawButton(graphics, recognize, Component.translatable(this.state == State.RECOGNIZING
                ? "screen.simple_translate.ocr.recognizing"
                : "screen.simple_translate.ocr.recognize"), this.state != State.RECOGNIZING);
        drawButton(graphics, close, Component.translatable("screen.simple_translate.ocr.close"), true);

        int textX = panel.x + 8;
        int textY = panel.y + 34;
        int textWidth = panel.width - 16;
        List<FormattedCharSequence> lines = resultLines(textWidth);
        int maxVisibleLines = Math.max(1, (panel.height - 42) / 10);
        int maxScroll = Math.max(0, lines.size() - maxVisibleLines);
        this.resultScroll = Mth.clamp(this.resultScroll, 0, maxScroll);
        int end = Math.min(lines.size(), this.resultScroll + maxVisibleLines);
        for (int i = this.resultScroll; i < end; i++) {
            graphics.drawString(this.font, lines.get(i), textX, textY + (i - this.resultScroll) * 10, 0xFFECECEC);
        }
        if (maxScroll > 0) {
            graphics.drawString(this.font, Component.literal((this.resultScroll + 1) + "/" + (maxScroll + 1)),
                    panel.x + panel.width - 34, panel.y + panel.height - 12, 0xFFAAAAAA);
        }
    }

    private void drawButton(GuiGraphics graphics, Rect rect, Component label, boolean enabled) {
        int fill = enabled ? 0xFF303030 : 0xFF202020;
        int border = enabled ? 0xFFAAAAAA : 0xFF555555;
        graphics.fill(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height, fill);
        graphics.fill(rect.x, rect.y, rect.x + rect.width, rect.y + 1, border);
        graphics.fill(rect.x, rect.y + rect.height - 1, rect.x + rect.width, rect.y + rect.height, border);
        graphics.fill(rect.x, rect.y, rect.x + 1, rect.y + rect.height, border);
        graphics.fill(rect.x + rect.width - 1, rect.y, rect.x + rect.width, rect.y + rect.height, border);
        int textX = rect.x + (rect.width - this.font.width(label)) / 2;
        graphics.drawString(this.font, label, textX, rect.y + 6, enabled ? 0xFFFFFFFF : 0xFF999999);
    }

    private List<FormattedCharSequence> resultLines(int textWidth) {
        Font font = this.font;
        List<FormattedCharSequence> lines = new ArrayList<>();
        if (this.state == State.IDLE) {
            lines.addAll(font.split(Component.translatable("screen.simple_translate.ocr.idle"), textWidth));
            lines.addAll(font.split(Component.literal(OcrManager.activeProfileDescription()), textWidth));
            return lines;
        }
        if (this.state == State.RECOGNIZING) {
            lines.addAll(font.split(Component.translatable("screen.simple_translate.ocr.recognizing_detail"), textWidth));
            return lines;
        }
        if (this.state == State.FAILED) {
            lines.addAll(font.split(Component.translatable("screen.simple_translate.ocr.failed", this.errorMessage), textWidth));
            return lines;
        }
        String source = this.result == null ? "" : this.result.sourceText();
        String translation = this.result == null ? "" : this.result.translationText();
        if (source == null || source.isBlank()) {
            source = Component.translatable("screen.simple_translate.ocr.no_text").getString();
        }
        if (translation == null || translation.isBlank()) {
            translation = Component.translatable("screen.simple_translate.ocr.no_translation").getString();
        }
        lines.addAll(font.split(Component.translatable("screen.simple_translate.ocr.source", source), textWidth));
        lines.add(FormattedCharSequence.EMPTY);
        lines.addAll(font.split(Component.translatable("screen.simple_translate.ocr.translation", translation), textWidth));
        return lines;
    }

    private Rect panelRect() {
        int x = this.regionX + this.regionWidth + 8;
        if (x + PANEL_WIDTH > this.width) {
            x = Math.max(4, this.regionX - PANEL_WIDTH - 8);
        }
        int y = Mth.clamp(this.regionY, 4, Math.max(4, this.height - PANEL_HEIGHT - 4));
        return new Rect(x, y, PANEL_WIDTH, PANEL_HEIGHT);
    }

    private Rect recognizeButton() {
        Rect panel = panelRect();
        return new Rect(panel.x + panel.width - 104, panel.y + 5, 58, 20);
    }

    private Rect closeButton() {
        Rect panel = panelRect();
        return new Rect(panel.x + panel.width - 42, panel.y + 5, 34, 20);
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
}
