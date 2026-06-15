package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.util.SignContextSelectionManager;
import com.yourname.simpletranslate.util.SignTranslationHelper;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.stream.IntStream;

/**
 * Sign translation settings screen.
 */
public class SignTranslationScreen extends ScrollableSettingsScreen {

    private boolean signEnabled;
    private ModConfig.SignContextMode signContextMode;
    private int signRadius;

    public SignTranslationScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.sign_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;
        this.signEnabled = ModConfig.CONTENT_SIGN_ENABLED.get();
        this.signContextMode = ModConfig.CONTENT_SIGN_CONTEXT_MODE.get();
        this.signRadius = ModConfig.CONTENT_SIGN_RADIUS.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable("screen.simple_translate.sign.section").getString());

        CycleButton<Boolean> signButton = CycleButton.onOffBuilder(signEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.sign.enabled"),
                        (button, value) -> signEnabled = value);
        withTooltip(signButton, "screen.simple_translate.sign.enabled.tooltip");
        addEntry(signButton);

        CycleButton<ModConfig.SignContextMode> contextModeButton = CycleButton.<ModConfig.SignContextMode>builder(
                        mode -> Component.translatable("screen.simple_translate.sign.context_mode." + mode.name().toLowerCase()),
                        signContextMode)
                .withValues(ModConfig.SignContextMode.values())
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.sign.context_mode"),
                        (button, value) -> signContextMode = value);
        withTooltip(contextModeButton, "screen.simple_translate.sign.context_mode.tooltip");
        addEntry(contextModeButton);

        CycleButton<Integer> radiusButton = CycleButton.<Integer>builder(
                        value -> Component.translatable("screen.simple_translate.radius.blocks", value),
                        signRadius)
                .withValues(IntStream.rangeClosed(1, 32).boxed().toList())
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.sign.radius"),
                        (button, value) -> signRadius = value);
        withTooltip(radiusButton, "screen.simple_translate.sign.radius.tooltip");
        addEntry(radiusButton);
    }

    @Override
    protected void saveSettings() {
        boolean previousEnabled = ModConfig.CONTENT_SIGN_ENABLED.get();
        ModConfig.SignContextMode previousMode = ModConfig.CONTENT_SIGN_CONTEXT_MODE.get();
        int previousRadius = ModConfig.CONTENT_SIGN_RADIUS.get();

        ModConfig.CONTENT_SIGN_ENABLED.set(signEnabled);
        ModConfig.CONTENT_SIGN_CONTEXT_MODE.set(signContextMode);
        ModConfig.CONTENT_SIGN_RADIUS.set(signRadius);

        if (previousEnabled != signEnabled || previousMode != signContextMode || previousRadius != signRadius) {
            SignTranslationHelper.handleSignSettingsChanged(previousMode, signContextMode);
            SignContextSelectionManager.clearAll();
        }
    }
}
