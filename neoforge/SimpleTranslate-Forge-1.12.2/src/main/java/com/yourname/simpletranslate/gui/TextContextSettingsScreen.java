package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.TextContextMemory;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;

/** Exact smart-context source toggles from the canonical configuration. */
final class TextContextSettingsScreen extends ScrollableSettingsScreen {
    private static final String[] KEYS={"received_chat","sent_chat","item_tooltip","hover_tooltip","book","sign","hud_captions","hud_progress","entity_name"};
    private static final ModConfig.BooleanValue[] VALUES={ModConfig.API_TEXT_CONTEXT_RECEIVED_CHAT,ModConfig.API_TEXT_CONTEXT_SENT_CHAT,ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP,ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP,ModConfig.API_TEXT_CONTEXT_BOOK,ModConfig.API_TEXT_CONTEXT_SIGN,ModConfig.API_TEXT_CONTEXT_HUD_CAPTIONS,ModConfig.API_TEXT_CONTEXT_HUD_PROGRESS,ModConfig.API_TEXT_CONTEXT_ENTITY_NAME};
    TextContextSettingsScreen(GuiScreen parent,TranslationEngine engine){super(parent,engine,"screen.simple_translate.text_context","screen.simple_translate.main.history");}
    @Override protected void buildContent(){int y=0;addContentTextButton(100,y,stateLabel("screen.simple_translate.text_context.enabled",ModConfig.API_TEXT_CONTEXT_ENABLED.get()),"screen.simple_translate.text_context.enabled.tooltip");y+=26;addContentTextButton(101,y,stateLabel("screen.simple_translate.text_context.allow_shared",ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.get()),"screen.simple_translate.text_context.allow_shared.tooltip");y+=34;for(int i=0;i<KEYS.length;i++){addContentTextButton(200+i,y,stateLabel("screen.simple_translate.text_context."+KEYS[i],VALUES[i].get()),"screen.simple_translate.text_context."+KEYS[i]+".tooltip");y+=26;}setContentHeight(y+6);}
    @Override protected boolean onContentButton(int id){if(id==100)ModConfig.API_TEXT_CONTEXT_ENABLED.set(!ModConfig.API_TEXT_CONTEXT_ENABLED.get());else if(id==101)ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.set(!ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.get());else if(id>=200&&id<200+VALUES.length){int i=id-200;VALUES[i].set(!VALUES[i].get());}else return false;ModConfig.save();TextContextMemory.settingsChanged();SimpleTranslateForge1122.onTranslationSettingsChanged();return true;}
}
