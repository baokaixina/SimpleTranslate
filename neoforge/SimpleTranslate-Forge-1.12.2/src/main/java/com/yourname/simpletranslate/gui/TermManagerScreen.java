package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.SimpleTranslateForge1122;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Editable term dictionary, separate from the blacklist as in the baseline UI. */
final class TermManagerScreen extends ScrollableSettingsScreen {
    private GuiTextField source;
    private GuiTextField translation;
    private final List<String> deleteSources = new ArrayList<String>();

    TermManagerScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.term_manager", "screen.simple_translate.main.terms");
    }

    @Override
    protected void buildContent() {
        int gap = 6;
        int halfWidth = (contentWidth - gap) / 2;
        source = addTextField(20, 12, 0, halfWidth, "", 128);
        translation = addTextField(21, 12, halfWidth + gap, contentWidth - halfWidth - gap, "", 128);
        addContentTextButton(100, 39, "+", "screen.simple_translate.terms.add.tooltip");
        addContentTextButton(102, 65, stateLabel("screen.simple_translate.terms.auto_detect", ModConfig.TERM_AUTO_DETECT_ENABLED.get()), "screen.simple_translate.terms.auto_detect.tooltip");
        addContentTextButton(103, 91, tr("screen.simple_translate.terms.auto_detect_count", ModConfig.TERM_AUTO_DETECT_COUNT.get()), "screen.simple_translate.terms.auto_detect_count.tooltip");
        addContentButton(104,117,"screen.simple_translate.export","screen.simple_translate.terms.export.tooltip");
        addContentButton(105,143,"screen.simple_translate.import","screen.simple_translate.terms.import.tooltip");
        deleteSources.clear();
        int row=177;
        if(engine!=null)for(Map.Entry<String,String> entry:engine.getTermDictionary().getAllTerms().entrySet()){
            deleteSources.add(entry.getKey());
            addContentTextButton(300+deleteSources.size()-1,row,entry.getKey()+" → "+entry.getValue(),"screen.simple_translate.terms.delete.tooltip");
            row+=22;
        }
        setContentHeight(row+8);
    }

    @Override
    protected void drawContent(int mouseX, int mouseY) {
        int half = (contentWidth + 6) / 2;
        drawContentText(tr("screen.simple_translate.terms.term"), 0, 0xFFFFFF);
        // The field caption on the right is drawn directly so its alignment is
        // preserved without introducing a fake text-widget.
        int screenY = 46 + 0 - scrollOffset;
        if (screenY >= 46 && screenY + fontRenderer.FONT_HEIGHT <= viewportBottom) {
            drawString(fontRenderer, fontRenderer.trimStringToWidth(tr("screen.simple_translate.cache.edit.translation"),
                    Math.max(0, contentWidth - half - 4)), contentLeft + half, screenY, 0xFFFFFF);
        }
    }

    @Override
    protected boolean onContentButton(int id) {
        if (engine == null) return false;
        if (id == 100) {
            engine.getTermDictionary().put(source.getText(), translation.getText());
            SimpleTranslateForge1122.onTranslationSettingsChanged();
            return true;
        }
        if (id == 102) {
            ModConfig.TERM_AUTO_DETECT_ENABLED.set(!ModConfig.TERM_AUTO_DETECT_ENABLED.get());
            ModConfig.save();
            return true;
        }
        if (id == 103) {
            ModConfig.TERM_AUTO_DETECT_COUNT.set(ModConfig.TERM_AUTO_DETECT_COUNT.get() >= 100
                    ? 1 : ModConfig.TERM_AUTO_DETECT_COUNT.get() + 1);
            ModConfig.save();
            return true;
        }
        if(id==104){try{Path file=SimpleTranslateForge1122.getConfigDir().resolve("exports").resolve("terms.json");engine.getTermDictionary().exportToFile(file);}catch(Exception error){SimpleTranslateForge1122.getLogger().warn("Failed to export terms",error);}return true;}
        if(id==105){try{Path file=SimpleTranslateForge1122.getConfigDir().resolve("exports").resolve("terms.json");engine.getTermDictionary().importFromFile(file,true);SimpleTranslateForge1122.onTranslationSettingsChanged();}catch(Exception error){SimpleTranslateForge1122.getLogger().warn("Failed to import terms",error);}return true;}
        if(id>=300&&id<300+deleteSources.size()){engine.getTermDictionary().remove(deleteSources.get(id-300));SimpleTranslateForge1122.onTranslationSettingsChanged();return true;}
        return false;
    }

    private int entryCount() {
        return engine == null ? 0 : engine.getTermDictionary().entries().size();
    }
}
