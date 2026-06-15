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

    public ItemTooltipScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.item_tooltip_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;
        this.itemEnabled = ModConfig.TOOLTIP_ITEM_ENABLED.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable("screen.simple_translate.item.section").getString());

        CycleButton<Boolean> itemButton = CycleButton.onOffBuilder(itemEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.item.enabled"),
                        (button, value) -> itemEnabled = value);
        withTooltip(itemButton, "screen.simple_translate.item.enabled.tooltip");
        addEntry(itemButton);
    }

    @Override
    protected void saveSettings() {
        ModConfig.TOOLTIP_ITEM_ENABLED.set(itemEnabled);
    }
}
