package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.feature.chat.ChatTranslationController;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.CycleButton;
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
    private CycleButton<ModConfig.TooltipTriggerMode> hoverTriggerModeButton;

    private boolean chatEnabled;
    private ModConfig.TranslationMode currentMode;
    private boolean hoverEnabled;
    private ModConfig.TooltipTriggerMode hoverTriggerMode;
    private boolean contextEnabled;
    private int contextMessageCount;

    public ChatTranslationScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.chat_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;

        this.chatEnabled = ModConfig.CHAT_ENABLED.get();
        this.currentMode = ModConfig.CHAT_MODE.get();
        this.hoverEnabled = ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get();
        this.hoverTriggerMode = ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.get();
        this.contextEnabled = ModConfig.CHAT_CONTEXT_ENABLED.get();
        if (this.currentMode != ModConfig.TranslationMode.AUTO) {
            this.contextEnabled = false;
        }
        this.contextMessageCount = ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get();
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
                        mode -> Component.translatable("screen.simple_translate.mode." + mode.name().toLowerCase()),
                        currentMode)
                .withValues(ModConfig.TranslationMode.values())
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
                        value -> Component.translatable("screen.simple_translate.chat.context_count.value", value),
                        contextMessageCount)
                .withValues(IntStream.rangeClosed(0, 20).boxed().toList())
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.chat.context_count"),
                        (button, value) -> contextMessageCount = value);
        withTooltip(this.contextCountButton, "screen.simple_translate.chat.context_count.tooltip");
        addEntry(this.contextCountButton);

        addSectionHeader(text("screen.simple_translate.chat.section.hover"));

        CycleButton<Boolean> hoverEnabledToggle = CycleButton.onOffBuilder(hoverEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.chat.hover_enabled"),
                        (button, value) -> {
                            hoverEnabled = value;
                            updateButtonStates();
                        });
        withTooltip(hoverEnabledToggle, "screen.simple_translate.chat.hover_enabled.tooltip");
        addEntry(hoverEnabledToggle);

        this.hoverTriggerModeButton = CycleButton.<ModConfig.TooltipTriggerMode>builder(
                        mode -> Component.translatable(
                                "screen.simple_translate.tooltip_trigger_mode." + mode.name().toLowerCase()),
                        this.hoverTriggerMode)
                .withValues(ModConfig.TooltipTriggerMode.values())
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.chat.hover_trigger_mode"),
                        (button, value) -> this.hoverTriggerMode = value);
        withTooltip(this.hoverTriggerModeButton, "screen.simple_translate.chat.hover_trigger_mode.tooltip");
        addEntry(this.hoverTriggerModeButton);

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
        if (this.hoverTriggerModeButton != null) {
            this.hoverTriggerModeButton.active = this.hoverTriggerModeButton.visible && this.hoverEnabled;
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
        ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.set(hoverEnabled);
        ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.set(this.hoverTriggerMode);
        if (previousMode != currentMode || previousContextEnabled != savedContextEnabled) {
            ChatTranslationController.onChatModeChanged();
        }
    }

    private static String text(String key) {
        return Component.translatable(key).getString();
    }
}
