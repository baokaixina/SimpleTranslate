package com.yourname.simpletranslate.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.gui.OcrHistoryScreen;
import com.yourname.simpletranslate.gui.OcrOverlayManager;
import com.yourname.simpletranslate.gui.OcrOverlayScreen;
import com.yourname.simpletranslate.gui.SimpleTranslateScreen;
import com.yourname.simpletranslate.util.SignContextSelectionManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

public class ModKeyBindings {
    public static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(ResourceLocation.fromNamespaceAndPath(SimpleTranslateMod.MODID, "main"));

    private static KeyMapping openSettings;
    private static KeyMapping toggleMode;
    private static KeyMapping toggleSignContextSelection;
    private static KeyMapping submitSignContextSelection;
    private static KeyMapping toggleOcrOverlay;
    private static KeyMapping openOcrHistory;
    private static boolean initialized;

    public static void register() {
        if (initialized) {
            return;
        }
        initialized = true;

        openSettings = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".open_settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                KEY_CATEGORY));

        toggleMode = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".toggle_mode",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                KEY_CATEGORY));

        toggleSignContextSelection = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".sign_context_select",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                KEY_CATEGORY));

        submitSignContextSelection = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".sign_context_submit",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                KEY_CATEGORY));

        toggleOcrOverlay = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".ocr_toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KEY_CATEGORY));

        openOcrHistory = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".ocr_history",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                KEY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(ModKeyBindings::onClientTick);
    }

    private static void onClientTick(Minecraft minecraft) {
        while (openSettings.consumeClick()) {
            if (minecraft.screen == null) {
                openSettingsScreenSafely(minecraft);
            }
        }

        while (toggleMode.consumeClick()) {
            toggleTranslationMode();
        }

        while (toggleSignContextSelection.consumeClick()) {
            SignContextSelectionManager.toggleDragSelectionMode();
        }

        while (submitSignContextSelection.consumeClick()) {
            SignContextSelectionManager.submitSelection();
        }

        while (toggleOcrOverlay.consumeClick()) {
            toggleOcrOverlay(minecraft);
        }

        while (openOcrHistory.consumeClick()) {
            toggleOcrHistory(minecraft);
        }

        OcrOverlayManager.tick(minecraft);
        SignContextSelectionManager.tickDragSelection();
    }

    public static void toggleOcrOverlay(Minecraft minecraft) {
        if (minecraft == null) {
            return;
        }
        if (!ModConfig.OCR_ENABLED.get()) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("screen.simple_translate.ocr.disabled_hint"), true);
            }
            return;
        }
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (minecraft.screen instanceof OcrOverlayScreen) {
            ((OcrOverlayScreen) minecraft.screen).onClose();
            return;
        }
        if (minecraft.screen != null) {
            OcrOverlayManager.toggleForScreen(minecraft, minecraft.screen);
            return;
        }
        minecraft.setScreen(new OcrOverlayScreen(minecraft.screen));
    }

    private static void toggleOcrHistory(Minecraft minecraft) {
        if (minecraft == null) {
            return;
        }
        if (minecraft.screen instanceof OcrHistoryScreen) {
            ((OcrHistoryScreen) minecraft.screen).onClose();
            return;
        }
        if (minecraft.screen == null) {
            minecraft.setScreen(new OcrHistoryScreen(null));
        }
    }

    public static boolean matchesOcrToggleKey(int keyCode, int scanCode) {
        return matchesOcrToggleKey(new KeyEvent(keyCode, scanCode, 0));
    }

    public static boolean matchesOcrToggleKey(KeyEvent event) {
        return toggleOcrOverlay != null && toggleOcrOverlay.matches(event);
    }

    private static void openSettingsScreenSafely(Minecraft minecraft) {
        try {
            minecraft.setScreen(new SimpleTranslateScreen(minecraft.screen));
        } catch (Throwable t) {
            SimpleTranslateMod.getLogger().error("Failed to open Simple Translate settings screen", t);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.literal("Failed to open Simple Translate settings. Check latest.log."),
                        false
                );
            }
        }
    }

    private static void toggleTranslationMode() {
        var currentMode = com.yourname.simpletranslate.config.ModConfig.CHAT_MODE.get();
        var newMode = currentMode == com.yourname.simpletranslate.config.ModConfig.TranslationMode.AUTO
                ? com.yourname.simpletranslate.config.ModConfig.TranslationMode.BUTTON
                : com.yourname.simpletranslate.config.ModConfig.TranslationMode.AUTO;

        com.yourname.simpletranslate.config.ModConfig.CHAT_MODE.set(newMode);
        com.yourname.simpletranslate.config.ModConfig.save();
        com.yourname.simpletranslate.chat.ChatTranslationController.onChatModeChanged();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            Component modeName = newMode == com.yourname.simpletranslate.config.ModConfig.TranslationMode.AUTO
                    ? Component.translatable("screen.simple_translate.mode.auto")
                    : Component.translatable("screen.simple_translate.mode.button");
            minecraft.player.displayClientMessage(Component.literal("Translation mode: ").append(modeName), true);
        }
    }
}
