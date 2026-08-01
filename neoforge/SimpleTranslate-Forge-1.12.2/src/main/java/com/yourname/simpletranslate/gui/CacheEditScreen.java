package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.cache.ComponentJsonCacheEditor;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import java.util.List;
import java.util.Optional;

/** Java-8 cache text-node editor preserving the original Component JSON structure. */
final class CacheEditScreen extends ScrollableSettingsScreen {
    private final CacheManagerScreen cacheParent;
    private final String cacheKey;
    private GuiTextField editor;
    private int nodeCount;
    private String source="";
    private String status="";
    CacheEditScreen(CacheManagerScreen parent, TranslationEngine engine,String cacheKey){super(parent,engine,"screen.simple_translate.cache.edit.title","screen.simple_translate.cache.edit.translation");this.cacheParent=parent;this.cacheKey=cacheKey;}
    @Override protected void buildContent(){TranslationCache.CacheViewEntry entry=engine==null?null:engine.getTranslationCache().getEntry(cacheKey).orElse(null);source=entry==null?"":entry.sourceText();List<String> nodes=entry==null?java.util.Collections.<String>emptyList():ComponentJsonCacheEditor.textNodes(entry.translation());nodeCount=nodes.size();editor=addTextField(30,52,ComponentJsonCacheEditor.encodeEditorText(nodes),8000);addContentButton(100,82,"screen.simple_translate.save","screen.simple_translate.cache.edit.save.tooltip");setContentHeight(112);}
    @Override protected void drawContent(int x,int y){drawContentText(tr("screen.simple_translate.cache.edit.source")+": "+shorten(source,90),0,0xDDDDDD);drawContentText(tr("screen.simple_translate.cache.edit.translation"),40,0xFFFFFF);if(!status.isEmpty())drawContentText(status,104,0x88FF88);}
    @Override protected boolean onContentButton(int id){if(id!=100||engine==null)return false;List<String> nodes=ComponentJsonCacheEditor.decodeEditorText(editor.getText(),nodeCount);Optional<String> error=nodes.size()==nodeCount?engine.getTranslationCache().updateComponentJsonTextNodes(cacheKey,nodes):Optional.of("unsupported-format");if(error.isPresent()){status=tr("screen.simple_translate.cache.edit.error."+error.get());return true;}engine.getTranslationCache().saveNow();SimpleTranslateForge1122.onSharedTranslationCacheImported();status=tr("screen.simple_translate.cache.edit.saved");cacheParent.refreshAfterEdit();return true;}
    private static String shorten(String value,int max){String text=value==null?"":value.replace('\n',' ');return text.length()<=max?text:text.substring(0,max-3)+"...";}
}
