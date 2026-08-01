package com.yourname.simpletranslate.keybind;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.feature.sign.SignContextSelectionManager;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationTriggerState;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationController;
import com.yourname.simpletranslate.gui.BaseSimpleTranslateScreen;
import com.yourname.simpletranslate.gui.SimpleTranslateScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;
import java.util.EnumSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Unified exact-modifier shortcut dispatcher for keyboard and mouse chords. */
public final class ModKeyBindings {
    /**
     * Deliberately the only Simple Translate binding registered with Minecraft.
     * It belongs in Controls so players can discover and change the settings
     * key with the rest of the game's bindings. Modifier chords remain in the
     * dedicated page because vanilla KeyMapping cannot represent them.
     */
    private static final KeyMapping.Category SIMPLE_TRANSLATE_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(SimpleTranslateMod.MODID, "general"));
    private static final KeyMapping OPEN_SETTINGS = new KeyMapping(
            "key.simple_translate.open_settings",
            InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_U,
            SIMPLE_TRANSLATE_CATEGORY);
    private static final EnumMap<ShortcutAction, ShortcutEdgeTracker> EDGES = new EnumMap<>(ShortcutAction.class);
    private static final EnumSet<ShortcutAction> EVENT_LATCH = EnumSet.noneOf(ShortcutAction.class);
    private static boolean suppressNextTooltipPoll;
    private static boolean suppressNextGuiPoll;
    private static boolean initialized;

    private ModKeyBindings() {
    }

    public static void register(IEventBus modEventBus) {
        if (initialized) return;
        initialized = true;
        migrateLegacyOpenSettingsBinding();
        migrateLegacyOptions(Minecraft.getInstance());
        for (ShortcutAction action : ShortcutAction.values()) EDGES.put(action, new ShortcutEdgeTracker());
        modEventBus.addListener(ModKeyBindings::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(ModKeyBindings::onClientTick);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SETTINGS);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN_SETTINGS.consumeClick()) {
            if (minecraft.screen == null) {
                openSettingsScreenSafely(minecraft);
            }
        }
        boolean editingText = hasFocusedTextInput(minecraft);
        boolean editingModSettings = minecraft.screen instanceof BaseSimpleTranslateScreen;
        for (ShortcutAction action : ShortcutAction.values()) {
            KeyChord chord = action.chord();
            boolean primaryDown = chord.isPrimaryDown(minecraft);
            boolean edge = EDGES.get(action).update(primaryDown,
                    chord.modifiers() == KeyChord.currentModifiers(minecraft));
            if (edge && !editingText && !editingModSettings) {
                if (action == ShortcutAction.TRANSLATE_GUI && suppressNextGuiPoll) {
                    suppressNextGuiPoll = false;
                } else if (action == ShortcutAction.TRANSLATE_TOOLTIP && suppressNextTooltipPoll) {
                    suppressNextTooltipPoll = false;
                } else {
                    dispatch(action, minecraft);
                }
            }
            if (!primaryDown) {
                EVENT_LATCH.remove(action);
                if (action == ShortcutAction.TRANSLATE_TOOLTIP) suppressNextTooltipPoll = false;
                if (action == ShortcutAction.TRANSLATE_GUI) suppressNextGuiPoll = false;
            }
        }
        SignContextSelectionManager.tickDragSelection();
    }

    private static void dispatch(ShortcutAction action, Minecraft minecraft) {
        switch (action) {
            case TOGGLE_GLOBAL_TRANSLATION -> toggleGlobalTranslation();
            case TOGGLE_CHAT_MODE -> toggleTranslationMode();
            case TRANSLATE_GUI -> {
                requestCurrentGuiOrHudTranslation(minecraft);
            }
            case TRANSLATE_TOOLTIP -> {
                TooltipTranslationController.RenderContext context =
                        TooltipTranslationController.resolveRenderContext();
                if (minecraft.screen != null
                        && TooltipTranslationTriggerState.hasEnabledShortcutMode(context)) {
                    TooltipTranslationTriggerState.armShortcutRequest();
                }
            }
            case SIGN_SELECT -> {
                if (minecraft.screen == null) SignContextSelectionManager.toggleDragSelectionMode();
            }
            case SIGN_SUBMIT -> {
                if (minecraft.screen == null) SignContextSelectionManager.submitSelection();
            }
        }
    }

    public static boolean matchesTranslateHoveredTooltipKey(int keyCode, int scanCode) {
        return matchesTranslateHoveredTooltipKey(new KeyEvent(keyCode, scanCode, 0));
    }

    public static boolean matchesTranslateHoveredTooltipKey(KeyEvent event) {
        KeyChord chord = ShortcutAction.TRANSLATE_TOOLTIP.chord();
        boolean matches = !EVENT_LATCH.contains(ShortcutAction.TRANSLATE_TOOLTIP)
                && chord.matchesKeyboardEvent(event.key(), Minecraft.getInstance())
                && !hasFocusedTextInput(Minecraft.getInstance());
        if (matches) {
            EVENT_LATCH.add(ShortcutAction.TRANSLATE_TOOLTIP);
            suppressNextTooltipPoll = true;
        }
        return matches;
    }

    public static boolean handleTranslateGuiKey(KeyEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        KeyChord chord = ShortcutAction.TRANSLATE_GUI.chord();
        if (minecraft.screen instanceof BaseSimpleTranslateScreen || hasFocusedTextInput(minecraft)
                || EVENT_LATCH.contains(ShortcutAction.TRANSLATE_GUI)
                || !chord.matchesKeyboardEvent(event.key(), minecraft)) return false;
        EVENT_LATCH.add(ShortcutAction.TRANSLATE_GUI);
        suppressNextGuiPoll = true;
        requestCurrentGuiOrHudTranslation(minecraft);
        return true;
    }

    private static void requestCurrentGuiOrHudTranslation(Minecraft minecraft) {
        if (minecraft == null) {
            return;
        }
        if (minecraft.screen == null) {
            GuiTranslationHelper.requestCurrentHudTranslation();
        } else if (!(minecraft.screen instanceof BaseSimpleTranslateScreen)) {
            GuiTranslationHelper.requestCurrentScreenAndOverlayTranslation();
        }
    }

    public static boolean hasConflict(KeyChord chord, ShortcutAction exceptAction, HoldOriginalFeature exceptHold) {
        if (chord == null || !chord.isBound()) return false;
        for (ShortcutAction action : ShortcutAction.values()) {
            if (action != exceptAction && action.chord().equals(chord)) return true;
        }
        for (HoldOriginalFeature feature : HoldOriginalFeature.values()) {
            if (feature != exceptHold && feature.chord().equals(chord)) return true;
        }
        return false;
    }

    public static void setChord(ShortcutAction action, KeyChord chord) {
        ModConfig.getShortcutChord(action).set((chord == null ? KeyChord.NONE : chord).serialize());
        ModConfig.save();
    }

    public static void setChord(HoldOriginalFeature feature, KeyChord chord) {
        ModConfig.getHoldOriginalChord(feature).set((chord == null ? KeyChord.NONE : chord).serialize());
        ModConfig.save();
    }

    private static boolean hasFocusedTextInput(Minecraft minecraft) {
        if (minecraft == null || minecraft.screen == null) return false;
        Object focused = minecraft.screen.getFocused();
        return focused instanceof EditBox || focused instanceof MultiLineEditBox;
    }

    private static void migrateLegacyOptions(Minecraft minecraft) {
        if (minecraft == null || minecraft.gameDirectory == null) return;
        EnumMap<ShortcutAction, Boolean> missing = new EnumMap<>(ShortcutAction.class);
        boolean anyMissing = false;
        for (ShortcutAction action : ShortcutAction.values()) {
            boolean value = !ModConfig.hasPersistedKey(ModConfig.getShortcutChord(action).getKey());
            missing.put(action, value);
            anyMissing |= value;
        }
        if (!anyMissing) return;
        Path options = minecraft.gameDirectory.toPath().resolve("options.txt");
        if (!Files.isRegularFile(options)) return;
        try {
            List<String> lines = Files.readAllLines(options);
            for (String line : lines) {
                int separator = line.indexOf(':');
                if (separator <= 0) continue;
                ShortcutAction action = legacyAction(line.substring(0, separator));
                if (action == null || !missing.getOrDefault(action, false)) continue;
                KeyChord chord = KeyChord.fromInputName(line.substring(separator + 1).trim());
                ModConfig.getShortcutChord(action).set(chord.serialize());
            }
            ModConfig.save();
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().warn("Could not migrate legacy shortcut keys from options.txt", e);
        }
    }

    private static void migrateLegacyOpenSettingsBinding() {
        KeyChord legacy = ModConfig.consumeLegacyOpenSettingsChord();
        if (!legacy.isBound()) {
            return;
        }
        if (legacy.modifiers() != 0) {
            SimpleTranslateMod.getLogger().info(
                    "Simple Translate settings shortcut used modifiers and cannot migrate to vanilla Controls; keeping its default binding");
            return;
        }
        InputConstants.Type type = legacy.type() == KeyChord.InputType.MOUSE
                ? InputConstants.Type.MOUSE : InputConstants.Type.KEYSYM;
        OPEN_SETTINGS.setKey(type.getOrCreate(legacy.code()));
        KeyMapping.resetMapping();
    }

    static ShortcutAction legacyAction(String optionKey) {
        String key = optionKey == null ? "" : optionKey;
        if (key.endsWith("simple_translate.toggle_mode")) return ShortcutAction.TOGGLE_CHAT_MODE;
        if (key.endsWith("simple_translate.translate_hovered_tooltip")) return ShortcutAction.TRANSLATE_TOOLTIP;
        if (key.endsWith("simple_translate.sign_context_select")) return ShortcutAction.SIGN_SELECT;
        if (key.endsWith("simple_translate.sign_context_submit")) return ShortcutAction.SIGN_SUBMIT;
        return null;
    }

    private static void openSettingsScreenSafely(Minecraft minecraft) {
        try {
            minecraft.setScreen(new SimpleTranslateScreen(minecraft.screen));
        } catch (Throwable t) {
            SimpleTranslateMod.getLogger().error("Failed to open Simple Translate settings screen", t);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("screen.simple_translate.settings.open_failed"), false);
            }
        }
    }

    private static void toggleTranslationMode() {
        if (!ModConfig.GLOBAL_ENABLED.get()) return;
        ModConfig.TranslationMode current = ModConfig.CHAT_MODE.get();
        ModConfig.TranslationMode next = current == ModConfig.TranslationMode.AUTO
                ? ModConfig.TranslationMode.BUTTON : ModConfig.TranslationMode.AUTO;
        ModConfig.CHAT_MODE.set(next);
        ModConfig.save();
        com.yourname.simpletranslate.feature.chat.ChatTranslationController.onChatModeChanged();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            Component mode = Component.translatable(next == ModConfig.TranslationMode.AUTO
                    ? "screen.simple_translate.mode.auto" : "screen.simple_translate.mode.button");
            minecraft.player.displayClientMessage(
                    Component.translatable("screen.simple_translate.mode.toggle_message", mode), true);
        }
    }

    private static void toggleGlobalTranslation() {
        boolean enabled = !ModConfig.GLOBAL_ENABLED.get();
        ModConfig.GLOBAL_ENABLED.set(enabled);
        SimpleTranslateMod.onGlobalTranslationSettingChanged(enabled);
        ModConfig.save();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(enabled
                    ? "screen.simple_translate.global_toggle.enabled"
                    : "screen.simple_translate.global_toggle.disabled"), true);
        }
    }
}
