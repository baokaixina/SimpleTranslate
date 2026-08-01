package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Settings screen for item tooltip translation.
 */
public class ItemTooltipScreen extends ScrollableSettingsScreen {

    private boolean itemEnabled;
    private ModConfig.TooltipTriggerMode triggerMode;
    private CycleButton<ModConfig.TooltipTriggerMode> triggerModeButton;

    public ItemTooltipScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.item_tooltip_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;
        this.itemEnabled = ModConfig.TOOLTIP_ITEM_ENABLED.get();
        this.triggerMode = ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable("screen.simple_translate.item.section").getString());

        CycleButton<Boolean> itemButton = CycleButton.onOffBuilder(itemEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.item.enabled"),
                        (button, value) -> {
                            itemEnabled = value;
                            updateButtonStates();
                        });
        withTooltip(itemButton, "screen.simple_translate.item.enabled.tooltip");
        addEntry(itemButton);

        this.triggerModeButton = CycleButton.<ModConfig.TooltipTriggerMode>builder(
                        mode -> Component.translatable(
                                "screen.simple_translate.tooltip_trigger_mode." + mode.name().toLowerCase()),
                        this.triggerMode)
                .withValues(ModConfig.TooltipTriggerMode.values())
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.item.trigger_mode"),
                        (button, value) -> this.triggerMode = value);
        withTooltip(this.triggerModeButton, "screen.simple_translate.item.trigger_mode.tooltip");
        addEntry(this.triggerModeButton);

        updateButtonStates();
    }

    @Override
    protected void repositionEntries() {
        super.repositionEntries();
        updateButtonStates();
    }

    private void updateButtonStates() {
        if (this.triggerModeButton != null) {
            this.triggerModeButton.active = this.triggerModeButton.visible && this.itemEnabled;
        }
    }

    @Override
    protected void saveSettings() {
        ModConfig.TOOLTIP_ITEM_ENABLED.set(itemEnabled);
        ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.set(this.triggerMode);
    }
}
