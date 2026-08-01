package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.hud.HudTranslationController;
import com.yourname.simpletranslate.feature.hud.ScoreboardTranslationController;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;

final class HudTranslationScreen extends ScrollableSettingsScreen {
    HudTranslationScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.hud_translation", "screen.simple_translate.main.hud");
    }
    @Override protected void buildContent() {
        int y=0;
        addContentTextButton(100,y,stateLabel("screen.simple_translate.hud.scoreboard",ModConfig.HUD_SCOREBOARD_ENABLED.get()),"screen.simple_translate.hud.scoreboard.tooltip");y+=26;
        addContentTextButton(101,y,stateLabel("screen.simple_translate.hud.bossbar",ModConfig.HUD_BOSSBAR_ENABLED.get()),"screen.simple_translate.hud.bossbar.tooltip");y+=26;
        addContentTextButton(102,y,stateLabel("screen.simple_translate.hud.title",ModConfig.HUD_TITLE_ENABLED.get()),"screen.simple_translate.hud.title.tooltip");y+=26;
        addContentTextButton(103,y,stateLabel("screen.simple_translate.hud.actionbar",ModConfig.HUD_ACTIONBAR_ENABLED.get()),"screen.simple_translate.hud.actionbar.tooltip");y+=26;
        addContentTextButton(104,y,stateLabel("screen.simple_translate.hud.title_context",ModConfig.HUD_TITLE_CONTEXT_ENABLED.get()),"screen.simple_translate.hud.title_context.tooltip");y+=26;
        addContentTextButton(105,y,stateLabel("screen.simple_translate.hud.history_chat",ModConfig.HUD_HISTORY_CHAT_ENABLED.get()),"screen.simple_translate.hud.history_chat.tooltip");y+=26;
        addContentTextButton(106,y,tr("screen.simple_translate.hud.caption_batch_interval")+": "+ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.get()+" ms","screen.simple_translate.hud.caption_batch_interval.tooltip");y+=26;
        addContentTextButton(107,y,tr("screen.simple_translate.hud.caption_collect_window")+": "+ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.get()+" ms","screen.simple_translate.hud.caption_collect_window.tooltip");y+=26;
        addContentTextButton(108,y,stateLabel("screen.simple_translate.settings.layout_critical_hud_keep_original",ModConfig.LAYOUT_CRITICAL_HUD_KEEP_ORIGINAL.get()),"screen.simple_translate.settings.layout_critical_hud_keep_original.tooltip");
        setContentHeight(y+30);
    }
    @Override protected boolean onContentButton(int id) {
        if(id==100)ModConfig.HUD_SCOREBOARD_ENABLED.set(!ModConfig.HUD_SCOREBOARD_ENABLED.get());
        else if(id==101)ModConfig.HUD_BOSSBAR_ENABLED.set(!ModConfig.HUD_BOSSBAR_ENABLED.get());
        else if(id==102)ModConfig.HUD_TITLE_ENABLED.set(!ModConfig.HUD_TITLE_ENABLED.get());
        else if(id==103)ModConfig.HUD_ACTIONBAR_ENABLED.set(!ModConfig.HUD_ACTIONBAR_ENABLED.get());
        else if(id==104)ModConfig.HUD_TITLE_CONTEXT_ENABLED.set(!ModConfig.HUD_TITLE_CONTEXT_ENABLED.get());
        else if(id==105)ModConfig.HUD_HISTORY_CHAT_ENABLED.set(!ModConfig.HUD_HISTORY_CHAT_ENABLED.get());
        else if(id==106)ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.set(ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.get()>=10000?500:ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.get()+500);
        else if(id==107)ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.set(ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.get()>=30000?500:ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.get()+500);
        else if(id==108)ModConfig.LAYOUT_CRITICAL_HUD_KEEP_ORIGINAL.set(!ModConfig.LAYOUT_CRITICAL_HUD_KEEP_ORIGINAL.get());
        else return false;
        ModConfig.save();
        if(engine!=null){engine.setFeatureEnabled("hud_scoreboard",ModConfig.HUD_SCOREBOARD_ENABLED.get());engine.setFeatureEnabled("hud_bossbar",ModConfig.HUD_BOSSBAR_ENABLED.get());engine.setFeatureEnabled("hud_title",ModConfig.HUD_TITLE_ENABLED.get());engine.setFeatureEnabled("hud_actionbar",ModConfig.HUD_ACTIONBAR_ENABLED.get());}
        HudTranslationController.resetForSettings();
        ScoreboardTranslationController.clear();
        return true;
    }
}
