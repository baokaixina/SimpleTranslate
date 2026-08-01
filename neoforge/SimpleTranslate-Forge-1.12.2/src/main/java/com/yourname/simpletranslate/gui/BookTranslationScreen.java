package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.book.BookTranslationSession;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;

final class BookTranslationScreen extends ScrollableSettingsScreen {
    BookTranslationScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.book_translation", "screen.simple_translate.main.book");
    }
    @Override protected void buildContent() {
        int y = 0;
        addContentTextButton(100, y, stateLabel("screen.simple_translate.book.content_enabled",
                ModConfig.CONTENT_BOOK_ENABLED.get()), "screen.simple_translate.book.content_enabled.tooltip"); y += 26;
        addContentTextButton(101, y, stateLabel("screen.simple_translate.book.hover_enabled",
                ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get()), "screen.simple_translate.book.hover_enabled.tooltip");
        setContentHeight(y + 30);
    }
    @Override protected boolean onContentButton(int id) {
        if (id == 100) ModConfig.CONTENT_BOOK_ENABLED.set(!ModConfig.CONTENT_BOOK_ENABLED.get());
        else if (id == 101) ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.set(!ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get());
        else return false;
        ModConfig.save();
        if (engine != null) engine.setFeatureEnabled("book", ModConfig.CONTENT_BOOK_ENABLED.get());
        BookTranslationSession.clear();
        return true;
    }
}
