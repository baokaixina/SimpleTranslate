package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.vanillacompat.CycleButton;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;

/**
 * Settings screen for book translation.
 */
public class BookTranslationScreen extends ScrollableSettingsScreen {

    private boolean contentEnabled;
    private boolean hoverEnabled;

    public BookTranslationScreen(Screen parent) {
        super(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.book_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;
        this.contentEnabled = ModConfig.CONTENT_BOOK_ENABLED.get();
        this.hoverEnabled = ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.book.section").getString());

        CycleButton<Boolean> contentButton = CycleButton.onOffBuilder(contentEnabled)
                .create(0, 0, contentWidth, 20,
                        com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.book.content_enabled"),
                        (button, value) -> contentEnabled = value);
        withTooltip(contentButton, "screen.simple_translate.book.content_enabled.tooltip");
        addEntry(contentButton);

        CycleButton<Boolean> hoverButton = CycleButton.onOffBuilder(hoverEnabled)
                .create(0, 0, contentWidth, 20,
                        com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.book.hover_enabled"),
                        (button, value) -> hoverEnabled = value);
        withTooltip(hoverButton, "screen.simple_translate.book.hover_enabled.tooltip");
        addEntry(hoverButton);
    }

    @Override
    protected void saveSettings() {
        ModConfig.CONTENT_BOOK_ENABLED.set(contentEnabled);
        ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.set(hoverEnabled);
    }
}
