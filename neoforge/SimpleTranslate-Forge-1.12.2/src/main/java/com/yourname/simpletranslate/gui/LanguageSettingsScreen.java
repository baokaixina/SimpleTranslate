package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

/** Source and target language settings kept separate from service credentials. */
final class LanguageSettingsScreen extends ScrollableSettingsScreen {
    private GuiTextField source;
    private GuiTextField target;

    LanguageSettingsScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.language_settings", "screen.simple_translate.main.language");
    }

    @Override
    protected void buildContent() {
        source = addTextField(0, 12, engine == null ? "auto" : engine.getSourceLanguage(), 64);
        target = addTextField(1, 57, engine == null ? "zh_cn" : engine.getTargetLanguage(), 64);
        setContentHeight(90);
    }

    @Override
    protected void drawContent(int mouseX, int mouseY) {
        drawContentText(tr("screen.simple_translate.language_settings.source"), 0, 0xFFFFFF);
        drawContentText(tr("screen.simple_translate.language_settings.target"), 45, 0xFFFFFF);
    }

    @Override
    protected void onFieldsChanged() {
        if (engine != null) engine.updateConfiguration(engine.getEndpoint(), engine.getApiKey(), engine.getModel(),
                source.getText(), target.getText());
    }
}
