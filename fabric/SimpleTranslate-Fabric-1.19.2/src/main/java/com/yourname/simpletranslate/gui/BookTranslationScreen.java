package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Settings screen for book translation.
 */
public class BookTranslationScreen extends ScrollableSettingsScreen {

    private boolean contentEnabled;
    private boolean hoverEnabled;

    public BookTranslationScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.book_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;
        this.contentEnabled = ModConfig.CONTENT_BOOK_ENABLED.get();
        this.hoverEnabled = ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable("screen.simple_translate.book.section").getString());

        CycleButton<Boolean> contentButton = CycleButton.onOffBuilder(contentEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.book.content_enabled"),
                        (button, value) -> contentEnabled = value);
        withTooltip(contentButton, "screen.simple_translate.book.content_enabled.tooltip");
        addEntry(contentButton);

        CycleButton<Boolean> hoverButton = CycleButton.onOffBuilder(hoverEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.book.hover_enabled"),
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


