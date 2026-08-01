package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;

final class EntityNameTranslationScreen extends ScrollableSettingsScreen {
    EntityNameTranslationScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.entity_translation", "screen.simple_translate.main.entity");
    }
    @Override protected void buildContent(){int y=0;addContentTextButton(100,y,stateLabel("screen.simple_translate.entity.enabled",ModConfig.CONTENT_ENTITY_NAME_ENABLED.get()),"screen.simple_translate.entity.enabled.tooltip");y+=26;addContentTextButton(101,y,tr("screen.simple_translate.entity.radius")+": "+tr("screen.simple_translate.radius.blocks",ModConfig.CONTENT_ENTITY_NAME_RADIUS.get()),"screen.simple_translate.entity.radius.tooltip");setContentHeight(y+30);}
    @Override protected boolean onContentButton(int id){if(id==100)ModConfig.CONTENT_ENTITY_NAME_ENABLED.set(!ModConfig.CONTENT_ENTITY_NAME_ENABLED.get());else if(id==101)ModConfig.CONTENT_ENTITY_NAME_RADIUS.set(ModConfig.CONTENT_ENTITY_NAME_RADIUS.get()>=64?1:ModConfig.CONTENT_ENTITY_NAME_RADIUS.get()+1);else return false;ModConfig.save();if(engine!=null)engine.setFeatureEnabled("entity_name",ModConfig.CONTENT_ENTITY_NAME_ENABLED.get());return true;}
}
