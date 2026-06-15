package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * HUD translation settings screen for non-title HUD surfaces.
 */
public class HudTranslationScreen extends ScrollableSettingsScreen {

    private boolean scoreboardEnabled;
    private boolean bossbarEnabled;

    public HudTranslationScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.hud_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;

        this.scoreboardEnabled = ModConfig.HUD_SCOREBOARD_ENABLED.get();
        this.bossbarEnabled = ModConfig.HUD_BOSSBAR_ENABLED.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable("screen.simple_translate.hud.section").getString());

        CycleButton<Boolean> scoreboardButton = CycleButton.onOffBuilder(scoreboardEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.hud.scoreboard"),
                        (button, value) -> scoreboardEnabled = value);
        withTooltip(scoreboardButton, "screen.simple_translate.hud.scoreboard.tooltip");
        addEntry(scoreboardButton);

        CycleButton<Boolean> bossbarButton = CycleButton.onOffBuilder(bossbarEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.hud.bossbar"),
                        (button, value) -> bossbarEnabled = value);
        withTooltip(bossbarButton, "screen.simple_translate.hud.bossbar.tooltip");
        addEntry(bossbarButton);
    }

    @Override
    protected void saveSettings() {
        ModConfig.HUD_SCOREBOARD_ENABLED.set(scoreboardEnabled);
        ModConfig.HUD_BOSSBAR_ENABLED.set(bossbarEnabled);
    }
}
