package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Text display translation settings screen.
 */
public class TextDisplayTranslationScreen extends ScrollableSettingsScreen {

    private boolean textDisplayEnabled;
    private int textDisplayRadius;

    public TextDisplayTranslationScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.text_display_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;
        this.textDisplayEnabled = ModConfig.CONTENT_TEXT_DISPLAY_ENABLED.get();
        this.textDisplayRadius = ModConfig.CONTENT_TEXT_DISPLAY_RADIUS.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable("screen.simple_translate.text_display.section").getString());

        CycleButton<Boolean> textDisplayButton = CycleButton.onOffBuilder(textDisplayEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.text_display.enabled"),
                        (button, value) -> textDisplayEnabled = value);
        withTooltip(textDisplayButton, "screen.simple_translate.text_display.enabled.tooltip");
        addEntry(textDisplayButton);

        CycleButton<Integer> radiusButton = CycleButton.<Integer>builder(
                        value -> Component.translatable("screen.simple_translate.radius.blocks", value),
                        textDisplayRadius)
                .withValues(4, 8, 12, 16, 20, 24, 32, 48, 64)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.text_display.radius"),
                        (button, value) -> textDisplayRadius = value);
        withTooltip(radiusButton, "screen.simple_translate.text_display.radius.tooltip");
        addEntry(radiusButton);
    }

    @Override
    protected void saveSettings() {
        ModConfig.CONTENT_TEXT_DISPLAY_ENABLED.set(textDisplayEnabled);
        ModConfig.CONTENT_TEXT_DISPLAY_RADIUS.set(textDisplayRadius);
    }
}
