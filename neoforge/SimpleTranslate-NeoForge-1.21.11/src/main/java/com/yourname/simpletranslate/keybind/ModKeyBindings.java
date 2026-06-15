package com.yourname.simpletranslate.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.gui.OcrOverlayScreen;
import com.yourname.simpletranslate.gui.SimpleTranslateScreen;
import com.yourname.simpletranslate.util.SignContextSelectionManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

public class ModKeyBindings {
    public static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(SimpleTranslateMod.MODID, "main")
    );

    private static KeyMapping openSettings;
    private static KeyMapping toggleMode;
    private static KeyMapping toggleSignContextSelection;
    private static KeyMapping submitSignContextSelection;
    private static KeyMapping toggleOcrOverlay;
    private static boolean initialized;

    public static void register(IEventBus modEventBus) {
        if (initialized) {
            return;
        }
        initialized = true;

        openSettings = new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".open_settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                KEY_CATEGORY);

        toggleMode = new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".toggle_mode",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                KEY_CATEGORY);

        toggleSignContextSelection = new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".sign_context_select",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                KEY_CATEGORY);

        submitSignContextSelection = new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".sign_context_submit",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                KEY_CATEGORY);

        toggleOcrOverlay = new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".ocr_toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KEY_CATEGORY);
        modEventBus.addListener(ModKeyBindings::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(ModKeyBindings::onClientTick);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(KEY_CATEGORY);
        event.register(openSettings);
        event.register(toggleMode);
        event.register(toggleSignContextSelection);
        event.register(submitSignContextSelection);
        event.register(toggleOcrOverlay);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
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
        SignContextSelectionManager.tickDragSelection();
    }
    private static void toggleOcrOverlay(Minecraft minecraft) {
        if (!ModConfig.OCR_ENABLED.get()) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("screen.simple_translate.ocr.disabled_hint"),
                        true);
            }
            return;
        }
        if (minecraft.screen instanceof OcrOverlayScreen) {
            minecraft.setScreen(null);
            return;
        }
        minecraft.setScreen(new OcrOverlayScreen(minecraft.screen));
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

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            Component modeName = newMode == com.yourname.simpletranslate.config.ModConfig.TranslationMode.AUTO
                    ? Component.translatable("screen.simple_translate.mode.auto")
                    : Component.translatable("screen.simple_translate.mode.button");
            minecraft.player.displayClientMessage(Component.literal("Translation mode: ").append(modeName), true);
        }
    }
}
