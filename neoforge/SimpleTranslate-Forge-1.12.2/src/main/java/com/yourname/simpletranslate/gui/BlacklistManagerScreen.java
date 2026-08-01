package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import com.yourname.simpletranslate.SimpleTranslateForge1122;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Text that must remain local is managed on its own page. */
final class BlacklistManagerScreen extends ScrollableSettingsScreen {
    private GuiTextField entry;
    private final List<String> deleteEntries = new ArrayList<String>();

    BlacklistManagerScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.blacklist_manager", "screen.simple_translate.main.blacklist");
    }

    @Override
    protected void buildContent() {
        entry = addTextField(20, 12, "", 256);
        addContentTextButton(100, 39, "+", "screen.simple_translate.blacklist.add.tooltip");
        addContentButton(101, 65, "screen.simple_translate.clear", "screen.simple_translate.blacklist.clear.tooltip");
        addContentButton(102,91,"screen.simple_translate.export","screen.simple_translate.blacklist.export.tooltip");
        addContentButton(103,117,"screen.simple_translate.import","screen.simple_translate.blacklist.import.tooltip");
        deleteEntries.clear();int y=151;
        if(engine!=null)for(String value:engine.getTranslationBlacklist().entries()){
            deleteEntries.add(value);addContentTextButton(300+deleteEntries.size()-1,y,value,"screen.simple_translate.blacklist.delete.tooltip");y+=22;
        }
        setContentHeight(y+8);
    }

    @Override
    protected void drawContent(int mouseX, int mouseY) {
        drawContentText(tr("screen.simple_translate.blacklist.entry"), 0, 0xFFFFFF);
    }

    @Override
    protected boolean onContentButton(int id) {
        if (engine == null) return false;
        if (id == 100) {
            engine.getTranslationBlacklist().add(entry.getText());
            SimpleTranslateForge1122.onTranslationSettingsChanged();
            return true;
        }
        if (id == 101) {
            engine.getTranslationBlacklist().clear();
            SimpleTranslateForge1122.onTranslationSettingsChanged();
            return true;
        }
        if(id==102){try{Path file=SimpleTranslateForge1122.getConfigDir().resolve("exports").resolve("blacklist.json");engine.getTranslationBlacklist().exportToFile(file);}catch(Exception error){SimpleTranslateForge1122.getLogger().warn("Failed to export blacklist",error);}return true;}
        if(id==103){try{Path file=SimpleTranslateForge1122.getConfigDir().resolve("exports").resolve("blacklist.json");engine.getTranslationBlacklist().importFromFile(file,true);SimpleTranslateForge1122.onTranslationSettingsChanged();}catch(Exception error){SimpleTranslateForge1122.getLogger().warn("Failed to import blacklist",error);}return true;}
        if(id>=300&&id<300+deleteEntries.size()){engine.getTranslationBlacklist().removeEntry(deleteEntries.get(id-300));SimpleTranslateForge1122.onTranslationSettingsChanged();return true;}
        return false;
    }

    private int entryCount() {
        return engine == null ? 0 : engine.getTranslationBlacklist().entries().size();
    }
}
