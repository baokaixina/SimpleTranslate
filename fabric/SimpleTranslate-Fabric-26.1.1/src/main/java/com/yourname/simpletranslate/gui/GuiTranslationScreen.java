package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Settings for visible Component-backed screen text translation. */
public class GuiTranslationScreen extends ScrollableSettingsScreen {
    private boolean guiEnabled;
    private ModConfig.GuiTranslationMode mode;
    private boolean modTranslationEnabled;
    private boolean ftbQuestsEnabled;
    private boolean tipsEnabled;
    private final boolean ftbQuestsInstalled;
    private final boolean tipsInstalled;

    public GuiTranslationScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.gui_translation"), parent);
        this.contentWidth = 260;
        this.entrySpacing = 26;
        this.guiEnabled = ModConfig.CONTENT_GUI_ENABLED.get();
        this.mode = ModConfig.CONTENT_GUI_MODE.get();
        this.modTranslationEnabled = ModConfig.MOD_TRANSLATION_ENABLED.get();
        this.ftbQuestsEnabled = ModConfig.MOD_FTB_QUESTS_ENABLED.get();
        this.tipsEnabled = ModConfig.MOD_TIPS_ENABLED.get();
        this.ftbQuestsInstalled = FabricLoader.getInstance().isModLoaded("ftbquests")
                || FabricLoader.getInstance().isModLoaded("ftb-quests");
        this.tipsInstalled = FabricLoader.getInstance().isModLoaded("tipsmod");
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable("screen.simple_translate.gui.section").getString());
        CycleButton<Boolean> enabled = CycleButton.onOffBuilder(guiEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.gui.enabled"),
                        (button, value) -> guiEnabled = value);
        withTooltip(enabled, "screen.simple_translate.gui.enabled.tooltip");
        addEntry(enabled);

        CycleButton<ModConfig.GuiTranslationMode> modeButton = CycleButton.builder(value ->
                        Component.translatable(value == ModConfig.GuiTranslationMode.SHORTCUT
                                ? "screen.simple_translate.gui.mode.shortcut"
                                : "screen.simple_translate.gui.mode.auto"), mode)
                .withValues(ModConfig.GuiTranslationMode.values())
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.gui.mode"),
                        (button, value) -> mode = value);
        withTooltip(modeButton, "screen.simple_translate.gui.mode.tooltip");
        addEntry(modeButton);

        addSectionHeader(Component.translatable("screen.simple_translate.gui.mods.section").getString());
        CycleButton<Boolean> modsEnabled = CycleButton.onOffBuilder(modTranslationEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.gui.mods.enabled"),
                        (button, value) -> modTranslationEnabled = value);
        withTooltip(modsEnabled, "screen.simple_translate.gui.mods.enabled.tooltip");
        addEntry(modsEnabled);

        CycleButton<Boolean> ftb = CycleButton.onOffBuilder(ftbQuestsEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable(ftbQuestsInstalled
                                ? "screen.simple_translate.gui.mods.ftb_quests.detected"
                                : "screen.simple_translate.gui.mods.ftb_quests.missing"),
                        (button, value) -> ftbQuestsEnabled = value);
        withTooltip(ftb, ftbQuestsInstalled
                ? "screen.simple_translate.gui.mods.ftb_quests.tooltip"
                : "screen.simple_translate.gui.mods.ftb_quests.missing.tooltip");
        addEntry(ftb);

        CycleButton<Boolean> tips = CycleButton.onOffBuilder(tipsEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable(tipsInstalled
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
