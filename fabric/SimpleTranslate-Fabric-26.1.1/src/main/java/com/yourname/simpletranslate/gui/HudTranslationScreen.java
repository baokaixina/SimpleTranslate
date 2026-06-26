package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Unified HUD translation settings screen for scoreboard, bossbar, title,
 * actionbar, caption context, history, and timing controls.
 */
public class HudTranslationScreen extends ScrollableSettingsScreen {

    private boolean scoreboardEnabled;
    private boolean bossbarEnabled;
    private boolean titleEnabled;
    private boolean actionbarEnabled;
    private boolean titleContextEnabled;
    private boolean historyChatEnabled;
    private int batchIntervalMs;
    private int collectWindowMs;
    private EditBox batchIntervalInput;
    private EditBox collectWindowInput;

    public HudTranslationScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.hud_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;

        this.scoreboardEnabled = ModConfig.HUD_SCOREBOARD_ENABLED.get();
        this.bossbarEnabled = ModConfig.HUD_BOSSBAR_ENABLED.get();
        this.titleEnabled = ModConfig.HUD_TITLE_ENABLED.get();
        this.actionbarEnabled = ModConfig.HUD_ACTIONBAR_ENABLED.get();
        this.titleContextEnabled = ModConfig.HUD_TITLE_CONTEXT_ENABLED.get();
        this.historyChatEnabled = ModConfig.HUD_HISTORY_CHAT_ENABLED.get();
        this.batchIntervalMs = ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.get();
        this.collectWindowMs = ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.get();
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

        addSectionHeader(text("screen.simple_translate.title.section.title"));

        CycleButton<Boolean> titleButton = CycleButton.onOffBuilder(titleEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.hud.title"),
                        (button, value) -> titleEnabled = value);
        withTooltip(titleButton, "screen.simple_translate.hud.title.tooltip");
        addEntry(titleButton);

        CycleButton<Boolean> actionbarButton = CycleButton.onOffBuilder(actionbarEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.hud.actionbar"),
                        (button, value) -> actionbarEnabled = value);
        withTooltip(actionbarButton, "screen.simple_translate.hud.actionbar.tooltip");
        addEntry(actionbarButton);

        addSectionHeader(text("screen.simple_translate.title.section.history"));

        CycleButton<Boolean> titleContextButton = CycleButton.onOffBuilder(titleContextEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.hud.title_context"),
                        (button, value) -> titleContextEnabled = value);
        withTooltip(titleContextButton, "screen.simple_translate.hud.title_context.tooltip");
        addEntry(titleContextButton);

        CycleButton<Boolean> historyChatButton = CycleButton.onOffBuilder(historyChatEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.hud.history_chat"),
                        (button, value) -> historyChatEnabled = value);
        withTooltip(historyChatButton, "screen.simple_translate.hud.history_chat.tooltip");
        addEntry(historyChatButton);

        addSectionHeader(text("screen.simple_translate.title.section.timing"));

        this.batchIntervalInput = new EditBox(this.font, 0, 0, contentWidth, 20,
                Component.translatable("screen.simple_translate.hud.caption_batch_interval"));
        this.batchIntervalInput.setMaxLength(5);
        this.batchIntervalInput.setValue(Integer.toString(this.batchIntervalMs));
        this.batchIntervalInput.setHint(Component.translatable("screen.simple_translate.hud.caption_time_hint"));
        withTooltip(this.batchIntervalInput, "screen.simple_translate.hud.caption_batch_interval.tooltip");
        addEntry(this.batchIntervalInput);

        this.collectWindowInput = new EditBox(this.font, 0, 0, contentWidth, 20,
                Component.translatable("screen.simple_translate.hud.caption_collect_window"));
        this.collectWindowInput.setMaxLength(5);
        this.collectWindowInput.setValue(Integer.toString(this.collectWindowMs));
        this.collectWindowInput.setHint(Component.translatable("screen.simple_translate.hud.caption_time_hint"));
        withTooltip(this.collectWindowInput, "screen.simple_translate.hud.caption_collect_window.tooltip");
        addEntry(this.collectWindowInput);
    }

    @Override
    protected void saveSettings() {
        ModConfig.HUD_SCOREBOARD_ENABLED.set(scoreboardEnabled);
        ModConfig.HUD_BOSSBAR_ENABLED.set(bossbarEnabled);
        ModConfig.HUD_TITLE_ENABLED.set(titleEnabled);
        ModConfig.HUD_ACTIONBAR_ENABLED.set(actionbarEnabled);
        ModConfig.HUD_TITLE_CONTEXT_ENABLED.set(titleContextEnabled);
        ModConfig.HUD_HISTORY_CHAT_ENABLED.set(historyChatEnabled);
        ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.set(parseMillis(batchIntervalInput, batchIntervalMs));
        ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.set(parseMillis(collectWindowInput, collectWindowMs));
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
