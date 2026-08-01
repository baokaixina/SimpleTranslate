package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraftforge.fml.ModList;
import com.yourname.simpletranslate.vanillacompat.CycleButton;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;

/** Settings for visible ITextComponent-backed screen text translation. */
public class GuiTranslationScreen extends ScrollableSettingsScreen {
    private boolean guiEnabled;
    private ModConfig.GuiTranslationMode mode;
    private boolean modTranslationEnabled;
    private boolean ftbQuestsEnabled;
    private boolean tipsEnabled;
    private final boolean ftbQuestsInstalled;
    private final boolean tipsInstalled;

    public GuiTranslationScreen(Screen parent) {
        super(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.gui_translation"), parent);
        this.contentWidth = 260;
        this.entrySpacing = 26;
        this.guiEnabled = ModConfig.CONTENT_GUI_ENABLED.get();
        this.mode = ModConfig.CONTENT_GUI_MODE.get();
        this.modTranslationEnabled = ModConfig.MOD_TRANSLATION_ENABLED.get();
        this.ftbQuestsEnabled = ModConfig.MOD_FTB_QUESTS_ENABLED.get();
        this.tipsEnabled = ModConfig.MOD_TIPS_ENABLED.get();
        this.ftbQuestsInstalled = ModList.get().isLoaded("ftbquests")
                || ModList.get().isLoaded("ftb-quests");
        this.tipsInstalled = ModList.get().isLoaded("tipsmod");
    }

    @Override
    protected void buildContent() {
        addSectionHeader(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.gui.section").getString());
        CycleButton<Boolean> enabled = CycleButton.onOffBuilder(guiEnabled)
                .create(0, 0, contentWidth, 20,
                        com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.gui.enabled"),
                        (button, value) -> guiEnabled = value);
        withTooltip(enabled, "screen.simple_translate.gui.enabled.tooltip");
        addEntry(enabled);

        CycleButton<ModConfig.GuiTranslationMode> modeButton = CycleButton.<ModConfig.GuiTranslationMode>builder(value ->
                        com.yourname.simpletranslate.core.LegacyComponentFactory.translatable(value == ModConfig.GuiTranslationMode.SHORTCUT
                                ? "screen.simple_translate.gui.mode.shortcut"
                                : "screen.simple_translate.gui.mode.auto")).withInitialValue(mode)
                .withValues(ModConfig.GuiTranslationMode.values())
                .create(0, 0, contentWidth, 20,
                        com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.gui.mode"),
                        (button, value) -> mode = value);
        withTooltip(modeButton, "screen.simple_translate.gui.mode.tooltip");
        addEntry(modeButton);

        addSectionHeader(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.gui.mods.section").getString());
        CycleButton<Boolean> modsEnabled = CycleButton.onOffBuilder(modTranslationEnabled)
                .create(0, 0, contentWidth, 20,
                        com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.gui.mods.enabled"),
                        (button, value) -> modTranslationEnabled = value);
        withTooltip(modsEnabled, "screen.simple_translate.gui.mods.enabled.tooltip");
        addEntry(modsEnabled);

        CycleButton<Boolean> ftb = CycleButton.onOffBuilder(ftbQuestsEnabled)
                .create(0, 0, contentWidth, 20,
                        com.yourname.simpletranslate.core.LegacyComponentFactory.translatable(ftbQuestsInstalled
                                ? "screen.simple_translate.gui.mods.ftb_quests.detected"
                                : "screen.simple_translate.gui.mods.ftb_quests.missing"),
                        (button, value) -> ftbQuestsEnabled = value);
        withTooltip(ftb, ftbQuestsInstalled
                ? "screen.simple_translate.gui.mods.ftb_quests.tooltip"
                : "screen.simple_translate.gui.mods.ftb_quests.missing.tooltip");
        addEntry(ftb);

        CycleButton<Boolean> tips = CycleButton.onOffBuilder(tipsEnabled)
                .create(0, 0, contentWidth, 20,
                        com.yourname.simpletranslate.core.LegacyComponentFactory.translatable(tipsInstalled
                                ? "screen.simple_translate.gui.mods.tips.detected"
                                : "screen.simple_translate.gui.mods.tips.missing"),
                        (button, value) -> tipsEnabled = value);
        withTooltip(tips, tipsInstalled
                ? "screen.simple_translate.gui.mods.tips.tooltip"
                : "screen.simple_translate.gui.mods.tips.missing.tooltip");
        addEntry(tips);
    }

    @Override
    protected void saveSettings() {
        ModConfig.CONTENT_GUI_ENABLED.set(guiEnabled);
        ModConfig.CONTENT_GUI_MODE.set(mode);
        ModConfig.MOD_TRANSLATION_ENABLED.set(modTranslationEnabled);
        ModConfig.MOD_FTB_QUESTS_ENABLED.set(ftbQuestsEnabled);
        ModConfig.MOD_TIPS_ENABLED.set(tipsEnabled);
        ModConfig.save();
        GuiTranslationHelper.clearLocalState();
    }
}
