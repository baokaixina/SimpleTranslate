package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.TranslationProfileManager;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import com.yourname.simpletranslate.SimpleTranslateForge1122;

/** Player-owned prompt preference kept outside the transport configuration. */
final class TranslationProfileScreen extends ScrollableSettingsScreen {
    private GuiTextField profile;

    TranslationProfileScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.translation_profile", "screen.simple_translate.main.reference_prompt");
    }

    @Override
    protected void buildContent() {
        profile = addTextField(20, 12, TranslationProfileManager.current(), 512);
        addContentButton(100, 40, "screen.simple_translate.translation_profile.reset",
                "screen.simple_translate.translation_profile.reset.tooltip");
        setContentHeight(70);
    }

    @Override
    protected void drawContent(int mouseX, int mouseY) {
        drawContentText(tr("screen.simple_translate.translation_profile.context"), 0, 0xFFFFFF);
    }

    @Override
    protected void onFieldsChanged() {
        TranslationProfileManager.saveCurrent(profile.getText());
        SimpleTranslateForge1122.onTranslationSettingsChanged();
    }

    @Override protected boolean onContentButton(int id) {
        if(id!=100)return false;
        TranslationProfileManager.resetCurrent();
        SimpleTranslateForge1122.onTranslationSettingsChanged();
        return true;
    }
}
