package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Generic display compatibility that is not tied to one server. */
public final class DisplayCompatibilityScreen extends ScrollableSettingsScreen {
    private boolean customFontCjkFix;

    public DisplayCompatibilityScreen(Screen parent) {
        super(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.display_compatibility"), parent);
        this.contentWidth = 320;
        this.customFontCjkFix = ModConfig.CUSTOM_FONT_CJK_FIX_ENABLED.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.display_compatibility.section.fonts").getString());
        CycleButton<Boolean> customFont = CycleButton.onOffBuilder(this.customFontCjkFix)
                .create(0, 0, this.contentWidth, 20,
                        com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.settings.custom_font_cjk_fix"),
                        (button, value) -> this.customFontCjkFix = value);
        withTooltip(customFont, "screen.simple_translate.settings.custom_font_cjk_fix.tooltip");
        addEntry(customFont);
        addDescription(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.display_compatibility.note").getString());
    }

    @Override
    protected void saveSettings() {
        ModConfig.CUSTOM_FONT_CJK_FIX_ENABLED.set(this.customFontCjkFix);
    }
}
