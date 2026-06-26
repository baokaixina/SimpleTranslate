package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Advancement translation settings screen.
 */
public class AdvancementTranslationScreen extends ScrollableSettingsScreen {

    private boolean advancementEnabled;

    public AdvancementTranslationScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.advancement_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;
        this.advancementEnabled = ModConfig.CONTENT_ADVANCEMENT_ENABLED.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable("screen.simple_translate.advancement.section").getString());

        CycleButton<Boolean> advancementButton = CycleButton.onOffBuilder(advancementEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.advancement.enabled"),
                        (button, value) -> advancementEnabled = value);
        withTooltip(advancementButton, "screen.simple_translate.advancement.enabled.tooltip");
        addEntry(advancementButton);
    }

    @Override
    protected void saveSettings() {
        ModConfig.CONTENT_ADVANCEMENT_ENABLED.set(advancementEnabled);
    }
}


