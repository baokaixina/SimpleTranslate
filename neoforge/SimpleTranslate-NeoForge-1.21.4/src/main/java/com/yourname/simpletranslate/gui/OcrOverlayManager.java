package com.yourname.simpletranslate.gui;

import com.mojang.blaze3d.platform.Window;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class OcrOverlayManager {
    private static OcrOverlayScreen overlay;
    private static Screen attachedScreen;
    private static int lastWidth = -1;
    private static int lastHeight = -1;

    private OcrOverlayManager() {
    }

    public static boolean isActive() {
        return overlay != null;
    }

    public static boolean isActiveFor(Screen screen) {
        return overlay != null && attachedScreen == screen;
    }

    public static void toggleForScreen(Minecraft minecraft, Screen screen) {
        if (overlay != null) {
            close();
            return;
        }
        if (minecraft == null || screen == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (!ModConfig.OCR_ENABLED.get()) {
            minecraft.player.displayClientMessage(
                    Component.translatable("screen.simple_translate.ocr.disabled_hint"), true);
            return;
        }
        overlay = new OcrOverlayScreen(null, OcrOverlayManager::close);
        attachedScreen = screen;
        lastWidth = screen.width;
        lastHeight = screen.height;
        overlay.init(minecraft, lastWidth, lastHeight);
    }

    public static void closeIfAttached(Screen screen) {
        if (attachedScreen == screen) {
            close();
        }
    }

    public static void close() {
        overlay = null;
        attachedScreen = null;
        lastWidth = -1;
        lastHeight = -1;
    }

    public static void tick(Minecraft minecraft) {
        if (overlay != null && (minecraft == null || minecraft.screen == null || minecraft.screen != attachedScreen)) {
            close();
        }
    }

    public static void render(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!isActiveFor(screen)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (screen.width != lastWidth || screen.height != lastHeight) {
            lastWidth = screen.width;
            lastHeight = screen.height;
            overlay.resize(minecraft, lastWidth, lastHeight);
        }
        overlay.renderOverlay(graphics, mouseX, mouseY, partialTick);
    }

    public static boolean keyPressed(Screen screen, int keyCode, int scanCode, int modifiers) {
        return isActiveFor(screen) && overlay.keyPressed(keyCode, scanCode, modifiers);
    }

    public static boolean keyReleased(Screen screen, int keyCode, int scanCode, int modifiers) {
        return isActiveFor(screen) && overlay.keyReleased(keyCode, scanCode, modifiers);
    }

    public static boolean keyboardEvent(long windowHandle, int keyCode, int scanCode, int action, int modifiers) {
        if (!isWindowActive(windowHandle)) {
            return false;
        }
        if (action == GLFW.GLFW_RELEASE) {
            return overlay.keyReleased(keyCode, scanCode, modifiers);
        }
        if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT) {
            return overlay.keyPressed(keyCode, scanCode, modifiers);
        }
        return false;
    }

    public static boolean mousePress(long windowHandle, int button, int action) {
        if (!isWindowActive(windowHandle)) {
            return false;
        }
        double mouseX = currentMouseX();
        double mouseY = currentMouseY();
        if (action == GLFW.GLFW_PRESS) {
            return overlay.mouseClicked(mouseX, mouseY, button);
        }
        if (action == GLFW.GLFW_RELEASE) {
            return overlay.mouseReleased(mouseX, mouseY, button);
        }
        return false;
    }

    public static boolean mouseMove(long windowHandle, double rawX, double rawY) {
        if (!isWindowActive(windowHandle) || !overlay.isDraggingRegion()) {
            return false;
        }
        return overlay.mouseDragged(toGuiX(rawX), toGuiY(rawY), 0, 0.0D, 0.0D);
    }

    public static boolean mouseScroll(long windowHandle, double scrollY) {
        if (!isWindowActive(windowHandle)) {
            return false;
        }
        return overlay.mouseScrolled(currentMouseX(), currentMouseY(), scrollY);
    }

    private static boolean isWindowActive(long windowHandle) {
        Minecraft minecraft = Minecraft.getInstance();
        return overlay != null
                && minecraft != null
                && minecraft.screen == attachedScreen
                && minecraft.getWindow().getWindow() == windowHandle;
    }

    private static double currentMouseX() {
        return toGuiX(Minecraft.getInstance().mouseHandler.xpos());
    }

    private static double currentMouseY() {
        return toGuiY(Minecraft.getInstance().mouseHandler.ypos());
    }

    private static double toGuiX(double rawX) {
        Window window = Minecraft.getInstance().getWindow();
        return rawX * window.getGuiScaledWidth() / (double) window.getScreenWidth();
    }

    private static double toGuiY(double rawY) {
        Window window = Minecraft.getInstance().getWindow();
        return rawY * window.getGuiScaledHeight() / (double) window.getScreenHeight();
    }
}
