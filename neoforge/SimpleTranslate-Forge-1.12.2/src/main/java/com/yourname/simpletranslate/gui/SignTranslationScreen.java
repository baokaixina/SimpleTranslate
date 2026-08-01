package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.sign.SignContextSelectionManager;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;

final class SignTranslationScreen extends ScrollableSettingsScreen {
    SignTranslationScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.sign_translation", "screen.simple_translate.main.sign");
    }
    @Override protected void buildContent() {
        int y=0;
        addContentTextButton(100,y,stateLabel("screen.simple_translate.sign.enabled",ModConfig.CONTENT_SIGN_ENABLED.get()),"screen.simple_translate.sign.enabled.tooltip");y+=26;
        ModConfig.SignContextMode mode=ModConfig.CONTENT_SIGN_CONTEXT_MODE.get();
        addContentTextButton(101,y,tr("screen.simple_translate.sign.context_mode")+": "+
                tr("screen.simple_translate.sign.context_mode."+mode.name().toLowerCase(java.util.Locale.ROOT)),
                "screen.simple_translate.sign.context_mode.tooltip");y+=26;
        addContentTextButton(102,y,tr("screen.simple_translate.sign.radius")+": "+
                tr("screen.simple_translate.radius.blocks",ModConfig.CONTENT_SIGN_RADIUS.get()),
                "screen.simple_translate.sign.radius.tooltip");
        setContentHeight(y+30);
    }
    @Override protected boolean onContentButton(int id) {
        ModConfig.SignContextMode previousMode=ModConfig.CONTENT_SIGN_CONTEXT_MODE.get();
        int previousRadius=ModConfig.CONTENT_SIGN_RADIUS.get();
        if(id==100)ModConfig.CONTENT_SIGN_ENABLED.set(!ModConfig.CONTENT_SIGN_ENABLED.get());
        else if(id==101)ModConfig.CONTENT_SIGN_CONTEXT_MODE.set(ModConfig.CONTENT_SIGN_CONTEXT_MODE.get()==ModConfig.SignContextMode.AUTO?ModConfig.SignContextMode.MANUAL:ModConfig.SignContextMode.AUTO);
        else if(id==102)ModConfig.CONTENT_SIGN_RADIUS.set(ModConfig.CONTENT_SIGN_RADIUS.get()>=32?1:ModConfig.CONTENT_SIGN_RADIUS.get()+1);
        else return false;
        ModConfig.save();
        if(id==100)SignContextSelectionManager.clearRuntimeState();
        else SignContextSelectionManager.handleSettingsChanged(previousMode,
                ModConfig.CONTENT_SIGN_CONTEXT_MODE.get(),previousRadius!=ModConfig.CONTENT_SIGN_RADIUS.get());
        if(engine!=null)engine.setFeatureEnabled("sign",ModConfig.CONTENT_SIGN_ENABLED.get());
        return true;
    }
}
