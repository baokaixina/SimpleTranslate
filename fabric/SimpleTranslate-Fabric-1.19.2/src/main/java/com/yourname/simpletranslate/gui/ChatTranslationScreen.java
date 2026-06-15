package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.chat.ChatTranslationController;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.stream.IntStream;

/**
 * Settings screen for chat translation.
 */
public class ChatTranslationScreen extends ScrollableSettingsScreen {

    private CycleButton<ModConfig.TranslationMode> modeButton;
    private CycleButton<Boolean> contextEnabledToggle;
    private CycleButton<Integer> contextCountButton;
    private EditBox batchIntervalInput;
    private EditBox collectWindowInput;

    private boolean chatEnabled;
    private ModConfig.TranslationMode currentMode;
    private boolean hoverEnabled;
    private boolean contextEnabled;
    private int contextMessageCount;
    private int batchIntervalMs;
    private int collectWindowMs;

    public ChatTranslationScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.chat_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;

        this.chatEnabled = ModConfig.CHAT_ENABLED.get();
        this.currentMode = ModConfig.CHAT_MODE.get();
        this.hoverEnabled = ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get();
        this.contextEnabled = ModConfig.CHAT_CONTEXT_ENABLED.get();
        if (this.currentMode != ModConfig.TranslationMode.AUTO) {
            this.contextEnabled = false;
        }
        this.contextMessageCount = ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get();
        this.batchIntervalMs = ModConfig.CHAT_CONTEXT_BATCH_INTERVAL_MS.get();
        this.collectWindowMs = ModConfig.CHAT_CONTEXT_COLLECT_WINDOW_MS.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(text("screen.simple_translate.chat.section.messages"));

        CycleButton<Boolean> enabledToggle = CycleButton.onOffBuilder(chatEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.chat.enabled"),
                        (button, value) -> {
                            chatEnabled = value;
                            updateButtonStates();
                        });
        withTooltip(enabledToggle, "screen.simple_translate.chat.enabled.tooltip");
        addEntry(enabledToggle);

        this.modeButton = CycleButton.<ModConfig.TranslationMode>builder(
                        mode -> Component.translatable("screen.simple_translate.mode." + mode.name().toLowerCase()))
                .withValues(ModConfig.TranslationMode.values())
                .withInitialValue(currentMode)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.chat.mode"),
                        (button, value) -> {
                            currentMode = value;
                            if (currentMode != ModConfig.TranslationMode.AUTO) {
                                contextEnabled = false;
                                if (contextEnabledToggle != null) {
                                    contextEnabledToggle.setValue(false);
                                }
                            }
                            updateButtonStates();
                        });
        withTooltip(this.modeButton, "screen.simple_translate.chat.mode.tooltip");
        addEntry(this.modeButton);

        addSectionHeader(text("screen.simple_translate.chat.section.context"));

        this.contextEnabledToggle = CycleButton.onOffBuilder(contextEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.chat.context_enabled"),
                        (button, value) -> {
                            contextEnabled = value;
                            updateButtonStates();
                        });
        withTooltip(this.contextEnabledToggle, "screen.simple_translate.chat.context_enabled.tooltip");
        addEntry(this.contextEnabledToggle);

        this.contextCountButton = CycleButton.<Integer>builder(
                        value -> Component.translatable("screen.simple_translate.chat.context_count.value", value))
                .withValues(IntStream.rangeClosed(0, 20).boxed().toList())
                .withInitialValue(contextMessageCount)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.chat.context_count"),
                        (button, value) -> contextMessageCount = value);
        withTooltip(this.contextCountButton, "screen.simple_translate.chat.context_count.tooltip");
        addEntry(this.contextCountButton);

        addSectionHeader(text("screen.simple_translate.chat.section.timing"));

        this.batchIntervalInput = new EditBox(this.font, 0, 0, contentWidth, 20,
                Component.translatable("screen.simple_translate.chat.context_batch_interval"));
        this.batchIntervalInput.setMaxLength(5);
        this.batchIntervalInput.setFilter(value -> value == null || value.isEmpty() || value.matches("\\d{0,5}"));
        this.batchIntervalInput.setValue(Integer.toString(this.batchIntervalMs));
        UiCompat.setHint(this.batchIntervalInput, Component.translatable("screen.simple_translate.chat.context_time_hint"));
        withTooltip(this.batchIntervalInput, "screen.simple_translate.chat.context_batch_interval.tooltip");
        addEntry(this.batchIntervalInput);

        this.collectWindowInput = new EditBox(this.font, 0, 0, contentWidth, 20,
                Component.translatable("screen.simple_translate.chat.context_collect_window"));
        this.collectWindowInput.setMaxLength(5);
        this.collectWindowInput.setFilter(value -> value == null || value.isEmpty() || value.matches("\\d{0,5}"));
        this.collectWindowInput.setValue(Integer.toString(this.collectWindowMs));
        UiCompat.setHint(this.collectWindowInput, Component.translatable("screen.simple_translate.chat.context_time_hint"));
        withTooltip(this.collectWindowInput, "screen.simple_translate.chat.context_collect_window.tooltip");
        addEntry(this.collectWindowInput);

        addSectionHeader(text("screen.simple_translate.chat.section.hover"));

        CycleButton<Boolean> hoverEnabledToggle = CycleButton.onOffBuilder(hoverEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.chat.hover_enabled"),
                        (button, value) -> hoverEnabled = value);
        withTooltip(hoverEnabledToggle, "screen.simple_translate.chat.hover_enabled.tooltip");
        addEntry(hoverEnabledToggle);

        updateButtonStates();
    }

    @Override
    protected void repositionEntries() {
        super.repositionEntries();
        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean autoMode = currentMode == ModConfig.TranslationMode.AUTO;
        if (!autoMode) {
            contextEnabled = false;
            if (this.contextEnabledToggle != null) {
                this.contextEnabledToggle.setValue(false);
            }
        }
        if (this.modeButton != null) {
            this.modeButton.active = this.modeButton.visible && chatEnabled;
        }
        if (this.contextEnabledToggle != null) {
            this.contextEnabledToggle.active = this.contextEnabledToggle.visible && chatEnabled && autoMode;
        }
        if (this.contextCountButton != null) {
            this.contextCountButton.active = this.contextCountButton.visible && chatEnabled && autoMode && contextEnabled;
        }
        boolean timingActive = chatEnabled && autoMode && contextEnabled;
        if (this.batchIntervalInput != null) {
            this.batchIntervalInput.active = this.batchIntervalInput.visible && timingActive;
            this.batchIntervalInput.setEditable(timingActive);
        }
        if (this.collectWindowInput != null) {
            this.collectWindowInput.active = this.collectWindowInput.visible && timingActive;
            this.collectWindowInput.setEditable(timingActive);
        }
    }

    @Override
    protected void saveSettings() {
        ModConfig.TranslationMode previousMode = ModConfig.CHAT_MODE.get();
        boolean previousContextEnabled = ModConfig.CHAT_CONTEXT_ENABLED.get();
        boolean savedContextEnabled = currentMode == ModConfig.TranslationMode.AUTO && contextEnabled;
        ModConfig.CHAT_ENABLED.set(chatEnabled);
        ModConfig.CHAT_MODE.set(currentMode);
        ModConfig.CHAT_CONTEXT_ENABLED.set(savedContextEnabled);
        ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.set(contextMessageCount);
        ModConfig.CHAT_CONTEXT_BATCH_INTERVAL_MS.set(parseMillis(batchIntervalInput, batchIntervalMs));
        ModConfig.CHAT_CONTEXT_COLLECT_WINDOW_MS.set(parseMillis(collectWindowInput, collectWindowMs));
        ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.set(hoverEnabled);
        if (previousMode != currentMode || previousContextEnabled != savedContextEnabled) {
            ChatTranslationController.onChatModeChanged();
        }
    }

    private static String text(String key) {
        return Component.translatable(key).getString();
    }

    private static int parseMillis(EditBox input, int fallback) {
        if (input == null || input.getValue() == null || input.getValue().isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(input.getValue().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
