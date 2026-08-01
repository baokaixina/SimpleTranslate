package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

final class ItemTooltipScreen extends ScrollableSettingsScreen {
    ItemTooltipScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.item_tooltip_translation", "screen.simple_translate.main.item_tooltip");
    }
    @Override protected void buildContent() {
        int y = 0;
        addContentTextButton(100, y, stateLabel("screen.simple_translate.item.enabled",
                ModConfig.TOOLTIP_ITEM_ENABLED.get()), "screen.simple_translate.item.enabled.tooltip"); y += 26;
        GuiButton trigger = addContentTextButton(101, y, tr("screen.simple_translate.item.trigger_mode")+": "+
                tr("screen.simple_translate.tooltip_trigger_mode."+ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get().name().toLowerCase(java.util.Locale.ROOT)),
                "screen.simple_translate.item.trigger_mode.tooltip");
        trigger.enabled = ModConfig.TOOLTIP_ITEM_ENABLED.get();
        setContentHeight(y + 30);
    }
    @Override protected boolean onContentButton(int id) {
        if (id == 100) ModConfig.TOOLTIP_ITEM_ENABLED.set(!ModConfig.TOOLTIP_ITEM_ENABLED.get());
        else if (id == 101) ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.set(next(ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get()));
        else return false;
        ModConfig.save();
        if (engine != null) {
            engine.setFeatureEnabled("tooltip_item", ModConfig.TOOLTIP_ITEM_ENABLED.get());
            engine.setManualTooltipMode(ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get() == ModConfig.TooltipTriggerMode.SHORTCUT);
        }
        return true;
    }
    private static ModConfig.TooltipTriggerMode next(ModConfig.TooltipTriggerMode value) {
        return value == ModConfig.TooltipTriggerMode.HOVER
                ? ModConfig.TooltipTriggerMode.SHORTCUT : ModConfig.TooltipTriggerMode.HOVER;
    }
}
