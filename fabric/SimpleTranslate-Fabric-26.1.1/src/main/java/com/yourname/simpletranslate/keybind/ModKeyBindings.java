package com.yourname.simpletranslate.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.gui.SimpleTranslateScreen;
import com.yourname.simpletranslate.feature.sign.SignContextSelectionManager;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationTriggerState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ModKeyBindings {
    public static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath(SimpleTranslateMod.MODID, "main"));

    private static KeyMapping openSettings;
    private static KeyMapping toggleMode;
    private static KeyMapping toggleSignContextSelection;
    private static KeyMapping submitSignContextSelection;
    private static KeyMapping translateHoveredTooltip;
    private static boolean initialized;

    public static void register() {
        if (initialized) {
            return;
        }
        initialized = true;

        openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".open_settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                KEY_CATEGORY));

        toggleMode = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".toggle_mode",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                KEY_CATEGORY));

        toggleSignContextSelection = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".sign_context_select",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                KEY_CATEGORY));

        submitSignContextSelection = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".sign_context_submit",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                KEY_CATEGORY));

        translateHoveredTooltip = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + SimpleTranslateMod.MODID + ".translate_hovered_tooltip",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
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

        // Screen key events arm the request directly. Drain the KeyMapping
        // click count here so one physical press cannot leak into a later GUI.
        while (translateHoveredTooltip.consumeClick()) {
            if (minecraft.screen == null) {
                TooltipTranslationTriggerState.clearShortcutRequest();
            }
        }

        SignContextSelectionManager.tickDragSelection();
    }

    public static boolean matchesTranslateHoveredTooltipKey(int keyCode, int scanCode) {
        return matchesTranslateHoveredTooltipKey(new KeyEvent(keyCode, scanCode, 0));
    }

    public static boolean matchesTranslateHoveredTooltipKey(KeyEvent event) {
        return translateHoveredTooltip != null && translateHoveredTooltip.matches(event);
    }

    private static void openSettingsScreenSafely(Minecraft minecraft) {
        try {
            minecraft.setScreen(new SimpleTranslateScreen(minecraft.screen));
        } catch (Throwable t) {
            SimpleTranslateMod.getLogger().error("Failed to open Simple Translate settings screen", t);
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.translatable("screen.simple_translate.settings.open_failed"));
            }
        }
    }

    private static void toggleTranslationMode() {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return;
        }
        var currentMode = com.yourname.simpletranslate.config.ModConfig.CHAT_MODE.get();
        var newMode = currentMode == com.yourname.simpletranslate.config.ModConfig.TranslationMode.AUTO
                ? com.yourname.simpletranslate.config.ModConfig.TranslationMode.BUTTON
                : com.yourname.simpletranslate.config.ModConfig.TranslationMode.AUTO;

        com.yourname.simpletranslate.config.ModConfig.CHAT_MODE.set(newMode);
        com.yourname.simpletranslate.config.ModConfig.save();
        com.yourname.simpletranslate.feature.chat.ChatTranslationController.onChatModeChanged();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            Component modeName = newMode == com.yourname.simpletranslate.config.ModConfig.TranslationMode.AUTO
                    ? Component.translatable("screen.simple_translate.mode.auto")
                    : Component.translatable("screen.simple_translate.mode.button");
            minecraft.player.sendOverlayMessage(Component.translatable("screen.simple_translate.mode.toggle_message", modeName));
        }
    }
}
