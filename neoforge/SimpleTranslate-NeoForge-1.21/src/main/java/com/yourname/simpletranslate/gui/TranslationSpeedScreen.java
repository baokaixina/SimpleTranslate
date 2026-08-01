package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Advanced request timing controls. */
public final class TranslationSpeedScreen extends ScrollableSettingsScreen {
    private int maxInFlight;
    private int directBatchDelay;

    public TranslationSpeedScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.translation_speed"), parent);
        this.maxInFlight = ModConfig.API_MAX_IN_FLIGHT_BATCHES.get();
        this.directBatchDelay = ModConfig.API_DIRECT_BATCH_DELAY_MS.get();
        this.contentWidth = 300;
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable("screen.simple_translate.translation_speed.section").getString());

        CycleButton<Integer> maxInFlightButton = CycleButton.<Integer>builder(value -> Component.literal(String.valueOf(value))).withInitialValue(this.maxInFlight)
                .withValues(List.of(1, 2))
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.settings.max_in_flight_batches"),
                        (button, value) -> this.maxInFlight = value);
        withTooltip(maxInFlightButton, "screen.simple_translate.settings.max_in_flight_batches.tooltip");
        addEntry(maxInFlightButton);

        CycleButton<Integer> delayButton = CycleButton.<Integer>builder(value -> Component.literal(value + " ms")).withInitialValue(this.directBatchDelay)
                .withValues(List.of(0, 10, 20, 35, 50, 75, 100, 150, 200))
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.settings.direct_batch_delay"),
                        (button, value) -> this.directBatchDelay = value);
        withTooltip(delayButton, "screen.simple_translate.settings.direct_batch_delay.tooltip");
        addEntry(delayButton);
    }

    @Override
    protected void saveSettings() {
        ModConfig.API_MAX_IN_FLIGHT_BATCHES.set(this.maxInFlight);
        ModConfig.API_DIRECT_BATCH_DELAY_MS.set(this.directBatchDelay);
    }
}
