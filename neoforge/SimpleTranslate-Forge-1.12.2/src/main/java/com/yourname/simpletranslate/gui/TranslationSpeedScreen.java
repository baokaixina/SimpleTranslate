package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;

final class TranslationSpeedScreen extends ScrollableSettingsScreen {
    TranslationSpeedScreen(GuiScreen parent,TranslationEngine engine){super(parent,engine,"screen.simple_translate.translation_speed","screen.simple_translate.main.speed");}
    @Override protected void buildContent(){int y=0;addContentTextButton(101,y,tr("screen.simple_translate.settings.max_in_flight_batches")+": "+ModConfig.API_MAX_IN_FLIGHT_BATCHES.get(),"screen.simple_translate.settings.max_in_flight_batches.tooltip");y+=26;addContentTextButton(102,y,tr("screen.simple_translate.settings.direct_batch_delay")+": "+ModConfig.API_DIRECT_BATCH_DELAY_MS.get()+" ms","screen.simple_translate.settings.direct_batch_delay.tooltip");setContentHeight(y+30);}
    @Override protected boolean onContentButton(int id){if(id==101)ModConfig.API_MAX_IN_FLIGHT_BATCHES.set(ModConfig.API_MAX_IN_FLIGHT_BATCHES.get()>=2?1:2);else if(id==102)ModConfig.API_DIRECT_BATCH_DELAY_MS.set((ModConfig.API_DIRECT_BATCH_DELAY_MS.get()+10)%210);else return false;ModConfig.save();return true;}
}
