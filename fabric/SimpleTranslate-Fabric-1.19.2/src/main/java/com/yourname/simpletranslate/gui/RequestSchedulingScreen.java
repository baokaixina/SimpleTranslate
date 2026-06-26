package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Advanced request batching controls in the classic settings layout. */
public class RequestSchedulingScreen extends ScrollableSettingsScreen {
    private int maxInFlight;
    private int directBatchDelay;
    private boolean customFontCjkFix;

    public RequestSchedulingScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.settings.section.advanced_api"), parent);
        this.maxInFlight = ModConfig.API_MAX_IN_FLIGHT_BATCHES.get();
        this.directBatchDelay = ModConfig.API_DIRECT_BATCH_DELAY_MS.get();
        this.customFontCjkFix = ModConfig.CUSTOM_FONT_CJK_FIX_ENABLED.get();
        this.contentWidth = 300;
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable(
                "screen.simple_translate.settings.section.advanced_api").getString());

        CycleButton<Integer> maxInFlightButton = CycleButton.<Integer>builder(
                        value -> Component.literal(String.valueOf(value)))
                .withValues(List.of(1, 2))
                .withInitialValue(this.maxInFlight)
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.settings.max_in_flight_batches"),
                        (button, value) -> {
                            this.maxInFlight = value;
                            applyLiveSettings();
                        });
        withTooltip(maxInFlightButton, "screen.simple_translate.settings.max_in_flight_batches.tooltip");
        addEntry(maxInFlightButton);

        CycleButton<Integer> delayButton = CycleButton.<Integer>builder(
                        value -> Component.literal(value + " ms"))
                .withValues(List.of(0, 10, 20, 35, 50, 75, 100, 150, 200))
                .withInitialValue(this.directBatchDelay)
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.settings.direct_batch_delay"),
                        (button, value) -> {
                            this.directBatchDelay = value;
                            applyLiveSettings();
                        });
        withTooltip(delayButton, "screen.simple_translate.settings.direct_batch_delay.tooltip");
        addEntry(delayButton);
        addSectionHeader(Component.translatable(
                "screen.simple_translate.settings.section.compatibility").getString());

        CycleButton<Boolean> customFontButton = CycleButton.onOffBuilder(this.customFontCjkFix)
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.settings.custom_font_cjk_fix"),
                        (button, value) -> {
                            this.customFontCjkFix = value;
                            applyLiveSettings();
                        });
        withTooltip(customFontButton, "screen.simple_translate.settings.custom_font_cjk_fix.tooltip");
        addEntry(customFontButton);
    }

    @Override
    protected void saveSettings() {
        ModConfig.API_MAX_IN_FLIGHT_BATCHES.set(this.maxInFlight);
        ModConfig.API_DIRECT_BATCH_DELAY_MS.set(this.directBatchDelay);
        ModConfig.CUSTOM_FONT_CJK_FIX_ENABLED.set(this.customFontCjkFix);
    }
}


