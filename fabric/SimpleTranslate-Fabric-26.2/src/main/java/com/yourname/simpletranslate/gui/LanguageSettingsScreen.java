package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.TranslationTextDetector;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class LanguageSettingsScreen extends ScrollableSettingsScreen {
    private static final String CUSTOM = "custom";
    private static final List<String> SOURCE_PRESETS = List.of(
            "auto", "zh_cn", "en", "ja", "ko", "es", "fr", "de", "ru", CUSTOM);
    private static final List<String> TARGET_PRESETS = List.of(
            "zh_cn", "en", "ja", "ko", "es", "fr", "de", "ru", CUSTOM);

    private String sourcePreset;
    private String targetPreset;
    private String sourceCustom;
    private String targetCustom;
    private EditBox sourceCustomInput;
    private EditBox targetCustomInput;

    public LanguageSettingsScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.language_settings"), parent);
        String source = TranslationTextDetector.canonicalLanguageCode(ModConfig.SOURCE_LANGUAGE.get());
        String target = TranslationTextDetector.canonicalLanguageCode(ModConfig.TARGET_LANGUAGE.get());
        this.sourcePreset = SOURCE_PRESETS.contains(source) ? source : CUSTOM;
        this.targetPreset = TARGET_PRESETS.contains(target) ? target : CUSTOM;
        this.sourceCustom = CUSTOM.equals(this.sourcePreset) ? source : "";
        this.targetCustom = CUSTOM.equals(this.targetPreset) ? target : "";
        this.contentWidth = 300;
    }

    @Override
    protected void buildContent() {
        addSectionHeader(text("screen.simple_translate.language_settings.section"));

        CycleButton<String> sourceButton = CycleButton.<String>builder(this::languageLabel, this.sourcePreset)
                .withValues(SOURCE_PRESETS)
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.language_settings.source"),
                        (button, value) -> {
                            this.sourcePreset = value;
                            refreshCustomInputs();
                        });
        withTooltip(sourceButton, "screen.simple_translate.language_settings.source.tooltip");
        addEntry(sourceButton);

        this.sourceCustomInput = new EditBox(this.font, 0, 0, this.contentWidth, 20,
                Component.translatable("screen.simple_translate.language_settings.source_custom"));
        this.sourceCustomInput.setMaxLength(48);
        this.sourceCustomInput.setValue(this.sourceCustom);
        this.sourceCustomInput.setHint(Component.translatable("screen.simple_translate.language_settings.custom_hint"));
        withTooltip(this.sourceCustomInput, "screen.simple_translate.language_settings.source_custom.tooltip");
        addEntry(this.sourceCustomInput);

        CycleButton<String> targetButton = CycleButton.<String>builder(this::languageLabel, this.targetPreset)
                .withValues(TARGET_PRESETS)
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.language_settings.target"),
                        (button, value) -> {
                            this.targetPreset = value;
                            refreshCustomInputs();
                        });
        withTooltip(targetButton, "screen.simple_translate.language_settings.target.tooltip");
        addEntry(targetButton);

        this.targetCustomInput = new EditBox(this.font, 0, 0, this.contentWidth, 20,
                Component.translatable("screen.simple_translate.language_settings.target_custom"));
        this.targetCustomInput.setMaxLength(48);
        this.targetCustomInput.setValue(this.targetCustom);
        this.targetCustomInput.setHint(Component.translatable("screen.simple_translate.language_settings.custom_hint"));
        withTooltip(this.targetCustomInput, "screen.simple_translate.language_settings.target_custom.tooltip");
        addEntry(this.targetCustomInput);

    }

    @Override
    protected void repositionEntries() {
        super.repositionEntries();
        refreshCustomInputs();
    }

    private void refreshCustomInputs() {
        if (this.sourceCustomInput != null) {
            boolean custom = CUSTOM.equals(this.sourcePreset);
            this.sourceCustomInput.visible = custom
                    && isEntryVisible(this.sourceCustomInput.getY(), this.sourceCustomInput.getHeight());
            this.sourceCustomInput.active = this.sourceCustomInput.visible;
        }
        if (this.targetCustomInput != null) {
            boolean custom = CUSTOM.equals(this.targetPreset);
            this.targetCustomInput.visible = custom
                    && isEntryVisible(this.targetCustomInput.getY(), this.targetCustomInput.getHeight());
            this.targetCustomInput.active = this.targetCustomInput.visible;
        }
    }

    @Override
    protected void saveSettings() {
        String oldSource = TranslationTextDetector.canonicalLanguageCode(ModConfig.SOURCE_LANGUAGE.get());
        String oldTarget = TranslationTextDetector.canonicalLanguageCode(ModConfig.TARGET_LANGUAGE.get());
        String newSource = effectiveCode(this.sourcePreset, this.sourceCustomInput, "auto");
        String newTarget = effectiveCode(this.targetPreset, this.targetCustomInput, "zh_cn");
        if ("auto".equals(newTarget)) {
            newTarget = "zh_cn";
        }
        ModConfig.SOURCE_LANGUAGE.set(newSource);
        ModConfig.TARGET_LANGUAGE.set(newTarget);
        boolean languageChanged = !oldSource.equals(newSource) || !oldTarget.equals(newTarget);
        if (languageChanged) {
            SimpleTranslateMod.onLanguageSettingsChanged();
            SimpleTranslateMod.getLogger().info("Language settings changed {} -> {}",
                    oldSource + "->" + oldTarget, newSource + "->" + newTarget);
        }
    }

    private String effectiveCode(String preset, EditBox customInput, String fallback) {
        String raw = CUSTOM.equals(preset)
                ? (customInput == null ? "" : customInput.getValue())
                : preset;
        String canonical = TranslationTextDetector.canonicalLanguageCode(raw);
        return canonical.isBlank() ? fallback : canonical;
    }

    private Component languageLabel(String code) {
        return Component.translatable("screen.simple_translate.language_settings.lang." + code);
    }

    private String text(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

}
