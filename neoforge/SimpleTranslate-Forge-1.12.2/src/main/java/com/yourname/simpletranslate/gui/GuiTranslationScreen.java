package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.Loader;

final class GuiTranslationScreen extends ScrollableSettingsScreen {
    GuiTranslationScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.gui_translation", "screen.simple_translate.main.gui");
    }
    @Override protected void buildContent() {
        int y = 0;
        addContentTextButton(100, y, stateLabel("screen.simple_translate.gui.enabled",
                ModConfig.CONTENT_GUI_ENABLED.get()), "screen.simple_translate.gui.enabled.tooltip"); y += 26;
        addContentTextButton(101, y, tr("screen.simple_translate.gui.mode") + ": "
                + tr("screen.simple_translate.gui.mode."
                + ModConfig.CONTENT_GUI_MODE.get().name().toLowerCase(java.util.Locale.ROOT)),
                "screen.simple_translate.gui.mode.tooltip"); y += 26;
        addContentTextButton(102, y, stateLabel("screen.simple_translate.gui.mods.enabled",
                ModConfig.MOD_TRANSLATION_ENABLED.get()), "screen.simple_translate.gui.mods.enabled.tooltip"); y += 26;
        boolean ftb = Loader.isModLoaded("ftbquests") || Loader.isModLoaded("ftblib");
        addContentTextButton(103, y, stateLabel(ftb ? "screen.simple_translate.gui.mods.ftb_quests.detected"
                        : "screen.simple_translate.gui.mods.ftb_quests.missing", ModConfig.MOD_FTB_QUESTS_ENABLED.get()),
                ftb ? "screen.simple_translate.gui.mods.ftb_quests.tooltip"
                        : "screen.simple_translate.gui.mods.ftb_quests.missing.tooltip"); y += 26;
        boolean tips = Loader.isModLoaded("tips");
        addContentTextButton(104, y, stateLabel(tips ? "screen.simple_translate.gui.mods.tips.detected"
                        : "screen.simple_translate.gui.mods.tips.missing", ModConfig.MOD_TIPS_ENABLED.get()),
                tips ? "screen.simple_translate.gui.mods.tips.tooltip"
                        : "screen.simple_translate.gui.mods.tips.missing.tooltip");
        setContentHeight(y + 30);
    }
    @Override protected boolean onContentButton(int id) {
        if (id == 100) ModConfig.CONTENT_GUI_ENABLED.set(!ModConfig.CONTENT_GUI_ENABLED.get());
        else if (id == 101) ModConfig.CONTENT_GUI_MODE.set(ModConfig.CONTENT_GUI_MODE.get() == ModConfig.GuiTranslationMode.AUTO
                ? ModConfig.GuiTranslationMode.SHORTCUT : ModConfig.GuiTranslationMode.AUTO);
        else if (id == 102) ModConfig.MOD_TRANSLATION_ENABLED.set(!ModConfig.MOD_TRANSLATION_ENABLED.get());
        else if (id == 103) ModConfig.MOD_FTB_QUESTS_ENABLED.set(!ModConfig.MOD_FTB_QUESTS_ENABLED.get());
        else if (id == 104) ModConfig.MOD_TIPS_ENABLED.set(!ModConfig.MOD_TIPS_ENABLED.get());
        else return false;
        ModConfig.save();
        if (engine != null) {
            engine.setFeatureEnabled("gui", ModConfig.CONTENT_GUI_ENABLED.get());
            engine.setFeatureEnabled("ftb", ModConfig.MOD_FTB_QUESTS_ENABLED.get());
            engine.setFeatureEnabled("tips", ModConfig.MOD_TIPS_ENABLED.get());
        }
        GuiTranslationController.clearRuntimeState();
        return true;
    }
}
