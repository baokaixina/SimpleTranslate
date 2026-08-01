package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;

final class AdvancementTranslationScreen extends ScrollableSettingsScreen {
    AdvancementTranslationScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.advancement_translation", "screen.simple_translate.main.advancement");
    }
    @Override protected void buildContent() {
        addContentTextButton(100, 0, stateLabel("screen.simple_translate.advancement.enabled",
                ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()), "screen.simple_translate.advancement.enabled.tooltip");
        setContentHeight(32);
    }
    @Override protected boolean onContentButton(int id) {
        if (id != 100) return false;
        ModConfig.CONTENT_ADVANCEMENT_ENABLED.set(!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()); ModConfig.save();
        if (engine != null) engine.setFeatureEnabled("advancement", ModConfig.CONTENT_ADVANCEMENT_ENABLED.get());
        return true;
    }
}
