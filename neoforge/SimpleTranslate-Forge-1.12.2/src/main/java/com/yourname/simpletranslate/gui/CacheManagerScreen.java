package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.cache.SharedCacheClient;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Search, edit, import/export, clear and opt-in server sharing for the active scope. */
final class CacheManagerScreen extends ScrollableSettingsScreen {
    private static final int MAX_VISIBLE_RESULTS = 80;
    private final List<String> visibleKeys = new ArrayList<String>();
    private GuiTextField search;
    private String searchValue = "";
    private String status = "";

    CacheManagerScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.cache_manager", "screen.simple_translate.main.cache");
    }

    @Override protected void buildContent() {
        TranslationCache cache = cache();
        int y = 0;
        addContentTextButton(100, y, stateLabel("screen.simple_translate.cache.enabled",
                ModConfig.CACHE_ENABLED.get()), "screen.simple_translate.cache.enabled.tooltip"); y += 26;
        addContentTextButton(101, y, tr(SimpleTranslateForge1122.isCacheServerShareEnabled()
                ? "screen.simple_translate.cache.server_share.on" : "screen.simple_translate.cache.server_share.off"),
                "screen.simple_translate.cache.server_share.tooltip"); y += 26;
        addContentButton(102, y, "screen.simple_translate.export", "screen.simple_translate.cache.export.tooltip"); y += 26;
        addContentButton(103, y, "screen.simple_translate.import", "screen.simple_translate.cache.import.tooltip"); y += 26;
        addContentButton(104, y, "screen.simple_translate.clear", "screen.simple_translate.cache_manager.tooltip"); y += 38;
        search = addTextField(9, y, searchValue, 120); y += 30;
        addContentButton(105, y, "screen.simple_translate.cache.search", "screen.simple_translate.cache.search.tooltip"); y += 28;
        visibleKeys.clear();
        String query = searchValue.trim().toLowerCase(java.util.Locale.ROOT);
        if (cache != null) for (TranslationCache.CacheViewEntry entry : cache.getEntries().values()) {
            if (visibleKeys.size() >= MAX_VISIBLE_RESULTS) break;
            String haystack = (entry.lane()+" "+entry.sourceText()+" "+entry.translationText()+" "+entry.key()).toLowerCase(java.util.Locale.ROOT);
            if (!query.isEmpty() && !haystack.contains(query)) continue;
            int index = visibleKeys.size(); visibleKeys.add(entry.key());
            String source = oneLine(entry.sourceText()); String translated = oneLine(entry.translationText());
            addContentTextButton(200+index, y, shorten("["+entry.lane()+"] "+source+" → "+translated, 72),
                    "screen.simple_translate.cache.edit.tooltip"); y += 24;
        }
        setContentHeight(y+8);
    }

    @Override protected void drawContent(int mouseX,int mouseY){
        TranslationCache cache=cache();
        drawContentText(tr("screen.simple_translate.cache.stats",cache==null?0:cache.size(),visibleKeys.size(),cache==null?0:cache.getLaneSizes().size()),136,0xAAAAAA);
        if(!status.isEmpty())drawContentText(status,148,0x88FF88);
    }

    @Override protected void onFieldsChanged(){
        if(search!=null)searchValue=search.getText();
    }

    @Override protected boolean onContentButton(int id){
        TranslationCache cache=cache();
        if(id==100){ModConfig.CACHE_ENABLED.set(!ModConfig.CACHE_ENABLED.get());ModConfig.save();if(engine!=null)engine.setFeatureEnabled("cache",ModConfig.CACHE_ENABLED.get());return true;}
        if(id==101){boolean enabled=!SimpleTranslateForge1122.isCacheServerShareEnabled();SimpleTranslateForge1122.setCacheServerShareEnabled(enabled);SharedCacheClient.onShareSettingChanged();status=tr(enabled?"screen.simple_translate.cache.server_share.enabled":"screen.simple_translate.cache.server_share.disabled");return true;}
        if(id==102){exportCache(cache);return true;}
        if(id==103){importCache(cache);return true;}
        if(id==104&&cache!=null){if(engine!=null)engine.clearCache();else{cache.clear();cache.saveNow();}SimpleTranslateForge1122.onSharedTranslationCacheImported();status=tr("screen.simple_translate.clear");return true;}
        if(id==105){initGui();return true;}
        if(id>=200&&id<200+visibleKeys.size()){Minecraft.getMinecraft().displayGuiScreen(new CacheEditScreen(this,engine,visibleKeys.get(id-200)));return false;}
        return false;
    }

    void refreshAfterEdit(){initGui();}
    private TranslationCache cache(){return engine==null?null:engine.getTranslationCache();}
    private void exportCache(TranslationCache cache){
        if(cache==null)return;
        try{Path dir=SimpleTranslateForge1122.getConfigDir().resolve("cache_share");Files.createDirectories(dir);String name="SimpleTranslateCache-"+new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())+".zip";TranslationCache.CacheShareMetadata meta=new TranslationCache.CacheShareMetadata(SimpleTranslateForge1122.getCurrentWorldId()==null?"global":"world",SimpleTranslateForge1122.getCurrentWorldId()==null?"global":SimpleTranslateForge1122.getCurrentWorldId());TranslationCache.CacheShareExportResult result=cache.exportShareArchive(dir.resolve(name),meta,null);status=tr("screen.simple_translate.cache.export.done",result.entries(),result.lanes(),name);}catch(Exception error){SimpleTranslateForge1122.getLogger().error("Failed to export cache",error);status=tr("screen.simple_translate.cache.export.failed");}
    }
    private void importCache(TranslationCache cache){
        if(cache==null)return;
        try{List<Path> sources=TranslationCache.discoverImportSources(SimpleTranslateForge1122.getConfigDir());TranslationCache.CacheImportResult result=cache.importFromShareSources(sources);if(result.changed()){cache.saveNow();SimpleTranslateForge1122.onSharedTranslationCacheImported();}status=sources.isEmpty()?tr("screen.simple_translate.cache.import.none"):tr("screen.simple_translate.cache.import.done",result.imported(),result.skippedExisting(),result.skippedInvalid(),result.skippedWorldMismatch(),result.failedFiles());initGui();}catch(Exception error){SimpleTranslateForge1122.getLogger().error("Failed to import cache",error);status=tr("screen.simple_translate.cache.import.failed");}
    }
    private static String oneLine(String value){return value==null?"":value.replace('\n',' ').replace('\r',' ').trim();}
    private static String shorten(String value,int max){return value.length()<=max?value:value.substring(0,max-3)+"...";}
}
