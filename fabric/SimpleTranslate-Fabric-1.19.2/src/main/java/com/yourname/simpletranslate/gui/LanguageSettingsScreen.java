package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.util.TranslationTextDetector;
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
    private String stylePrompt;
    private EditBox sourceCustomInput;
    private EditBox targetCustomInput;
    private EditBox stylePromptInput;

    public LanguageSettingsScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.language_settings"), parent);
        String source = TranslationTextDetector.canonicalLanguageCode(ModConfig.SOURCE_LANGUAGE.get());
        String target = TranslationTextDetector.canonicalLanguageCode(ModConfig.TARGET_LANGUAGE.get());
        this.sourcePreset = SOURCE_PRESETS.contains(source) ? source : CUSTOM;
        this.targetPreset = TARGET_PRESETS.contains(target) ? target : CUSTOM;
        this.sourceCustom = CUSTOM.equals(this.sourcePreset) ? source : "";
        this.targetCustom = CUSTOM.equals(this.targetPreset) ? target : "";
        this.stylePrompt = ModConfig.TRANSLATION_STYLE_PROMPT.get();
        if (this.stylePrompt == null) {
            this.stylePrompt = "";
        }
        this.contentWidth = 300;
    }

    @Override
    protected void buildContent() {
        addSectionHeader(text("screen.simple_translate.language_settings.section"));

        CycleButton<String> sourceButton = CycleButton.<String>builder(this::languageLabel)
                .withValues(SOURCE_PRESETS)
                .withInitialValue(this.sourcePreset)
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
        UiCompat.setHint(this.sourceCustomInput, Component.translatable("screen.simple_translate.language_settings.custom_hint"));
        withTooltip(this.sourceCustomInput, "screen.simple_translate.language_settings.source_custom.tooltip");
        addEntry(this.sourceCustomInput);

        CycleButton<String> targetButton = CycleButton.<String>builder(this::languageLabel)
                .withValues(TARGET_PRESETS)
                .withInitialValue(this.targetPreset)
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
        UiCompat.setHint(this.targetCustomInput, Component.translatable("screen.simple_translate.language_settings.custom_hint"));
        withTooltip(this.targetCustomInput, "screen.simple_translate.language_settings.target_custom.tooltip");
        addEntry(this.targetCustomInput);

        addSectionHeader(text("screen.simple_translate.language_settings.section.style"));
        this.stylePromptInput = new EditBox(this.font, 0, 0, this.contentWidth, 20,
                Component.translatable("screen.simple_translate.translation_style"));
        this.stylePromptInput.setMaxLength(300);
        this.stylePromptInput.setValue(this.stylePrompt);
        UiCompat.setHint(this.stylePromptInput, Component.translatable("screen.simple_translate.translation_style_hint"));
        withTooltip(this.stylePromptInput, "screen.simple_translate.translation_style.tooltip");
        addEntry(this.stylePromptInput);
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
                    && isEntryVisible(UiCompat.getY(this.sourceCustomInput), this.sourceCustomInput.getHeight());
            this.sourceCustomInput.active = this.sourceCustomInput.visible;
        }
        if (this.targetCustomInput != null) {
            boolean custom = CUSTOM.equals(this.targetPreset);
            this.targetCustomInput.visible = custom
                    && isEntryVisible(UiCompat.getY(this.targetCustomInput), this.targetCustomInput.getHeight());
            this.targetCustomInput.active = this.targetCustomInput.visible;
        }
    }

    @Override
    protected void saveSettings() {
        String oldSource = TranslationTextDetector.canonicalLanguageCode(ModConfig.SOURCE_LANGUAGE.get());
        String oldTarget = TranslationTextDetector.canonicalLanguageCode(ModConfig.TARGET_LANGUAGE.get());
        String oldStylePrompt = ModConfig.TRANSLATION_STYLE_PROMPT.get();
        String newSource = effectiveCode(this.sourcePreset, this.sourceCustomInput, "auto");
        String newTarget = effectiveCode(this.targetPreset, this.targetCustomInput, "zh_cn");
        String newStylePrompt = this.stylePromptInput == null ? "" : this.stylePromptInput.getValue();
        if ("auto".equals(newTarget)) {
            newTarget = "zh_cn";
        }
        ModConfig.SOURCE_LANGUAGE.set(newSource);
        ModConfig.TARGET_LANGUAGE.set(newTarget);
        ModConfig.TRANSLATION_STYLE_PROMPT.set(newStylePrompt);
        boolean languageChanged = !oldSource.equals(newSource) || !oldTarget.equals(newTarget);
        boolean styleChanged = !normalizeStylePrompt(oldStylePrompt).equals(normalizeStylePrompt(newStylePrompt));
        if (languageChanged) {
            SimpleTranslateMod.onLanguageSettingsChanged();
            SimpleTranslateMod.getLogger().info("Language settings changed {} -> {}",
                    oldSource + "->" + oldTarget, newSource + "->" + newTarget);
        } else if (styleChanged) {
            SimpleTranslateMod.onStyleSettingsChanged();
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

    private static String normalizeStylePrompt(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
