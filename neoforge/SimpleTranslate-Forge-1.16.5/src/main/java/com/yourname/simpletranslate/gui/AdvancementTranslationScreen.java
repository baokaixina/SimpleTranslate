package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.vanillacompat.CycleButton;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;

/**
 * Advancement translation settings screen.
 */
public class AdvancementTranslationScreen extends ScrollableSettingsScreen {

    private boolean advancementEnabled;

    public AdvancementTranslationScreen(Screen parent) {
        super(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.advancement_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;
        this.advancementEnabled = ModConfig.CONTENT_ADVANCEMENT_ENABLED.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.advancement.section").getString());

        CycleButton<Boolean> advancementButton = CycleButton.onOffBuilder(advancementEnabled)
                .create(0, 0, contentWidth, 20,
                        com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.advancement.enabled"),
                        (button, value) -> advancementEnabled = value);
        withTooltip(advancementButton, "screen.simple_translate.advancement.enabled.tooltip");
        addEntry(advancementButton);
    }

    @Override
    protected void saveSettings() {
        ModConfig.CONTENT_ADVANCEMENT_ENABLED.set(advancementEnabled);
    }
}
