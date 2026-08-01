package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

/** Full chat settings surface adapted to the 1.12 scrollable screen API. */
final class ChatTranslationScreen extends ScrollableSettingsScreen {
    private GuiTextField serverLanguage;
    ChatTranslationScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.chat_translation", "screen.simple_translate.main.chat");
    }
    @Override protected void buildContent() {
        int y = 0;
        addContentTextButton(100, y, stateLabel("screen.simple_translate.chat.enabled", ModConfig.CHAT_ENABLED.get()),
                "screen.simple_translate.chat.enabled.tooltip"); y += 26;
        GuiButton modeButton = addContentTextButton(101, y, tr("screen.simple_translate.chat.mode") + ": "
                + tr(ModConfig.CHAT_MODE.get() == ModConfig.TranslationMode.AUTO
                ? "screen.simple_translate.mode.auto" : "screen.simple_translate.mode.button"),
                "screen.simple_translate.chat.mode.tooltip"); y += 26;
        modeButton.enabled = ModConfig.CHAT_ENABLED.get();
        addContentTextButton(102, y, stateLabel("screen.simple_translate.chat.outgoing_enabled",
                ModConfig.CHAT_OUTGOING_ENABLED.get()), "screen.simple_translate.chat.outgoing_enabled.tooltip"); y += 35;
        serverLanguage = addTextField(4, y, ModConfig.CHAT_OUTGOING_SERVER_LANGUAGE.get(), 64); y += 34;
        boolean autoMode = ModConfig.CHAT_MODE.get() == ModConfig.TranslationMode.AUTO;
        GuiButton contextButton = addContentTextButton(103, y, stateLabel("screen.simple_translate.chat.context_enabled",
                autoMode && ModConfig.CHAT_CONTEXT_ENABLED.get()), "screen.simple_translate.chat.context_enabled.tooltip"); y += 26;
        contextButton.enabled = ModConfig.CHAT_ENABLED.get() && autoMode;
        GuiButton contextCountButton = addContentTextButton(104, y, tr("screen.simple_translate.chat.context_count")+": "+
                tr("screen.simple_translate.chat.context_count.value", ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get()),
                "screen.simple_translate.chat.context_count.tooltip"); y += 34;
        contextCountButton.enabled = ModConfig.CHAT_ENABLED.get() && autoMode && ModConfig.CHAT_CONTEXT_ENABLED.get();
        addContentTextButton(105, y, stateLabel("screen.simple_translate.chat.hover_enabled",
                ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get()), "screen.simple_translate.chat.hover_enabled.tooltip"); y += 26;
        GuiButton hoverModeButton = addContentTextButton(106, y, tr("screen.simple_translate.chat.hover_trigger_mode")+": "+
                tr("screen.simple_translate.tooltip_trigger_mode."+ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.get().name().toLowerCase(java.util.Locale.ROOT)),
                "screen.simple_translate.chat.hover_trigger_mode.tooltip");
        hoverModeButton.enabled = ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get();
        setContentHeight(y + 30);
    }
    @Override protected void drawContent(int mouseX, int mouseY) {
        drawContentText(tr("screen.simple_translate.chat.outgoing_server_language"), 82, 0xFFFFFF);
    }
    @Override protected void onFieldsChanged() {
        if (serverLanguage != null) {
            ModConfig.CHAT_OUTGOING_SERVER_LANGUAGE.set(serverLanguage.getText().trim()); ModConfig.save();
        }
    }
    @Override protected boolean onContentButton(int id) {
        switch (id) {
            case 100: ModConfig.CHAT_ENABLED.set(!ModConfig.CHAT_ENABLED.get()); break;
            case 101:
                ModConfig.CHAT_MODE.set(ModConfig.CHAT_MODE.get() == ModConfig.TranslationMode.AUTO
                        ? ModConfig.TranslationMode.BUTTON : ModConfig.TranslationMode.AUTO);
                if (ModConfig.CHAT_MODE.get() != ModConfig.TranslationMode.AUTO) {
                    ModConfig.CHAT_CONTEXT_ENABLED.set(false);
                }
                break;
            case 102: ModConfig.CHAT_OUTGOING_ENABLED.set(!ModConfig.CHAT_OUTGOING_ENABLED.get()); break;
            case 103:
                if (ModConfig.CHAT_MODE.get() != ModConfig.TranslationMode.AUTO) return false;
                ModConfig.CHAT_CONTEXT_ENABLED.set(!ModConfig.CHAT_CONTEXT_ENABLED.get()); break;
            case 104:
                if (ModConfig.CHAT_MODE.get() != ModConfig.TranslationMode.AUTO
                        || !ModConfig.CHAT_CONTEXT_ENABLED.get()) return false;
                ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.set(
                    ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get() >= 20 ? 0 : ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get() + 1); break;
            case 105: ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.set(!ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get()); break;
            case 106: ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.set(
                    ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.get()==ModConfig.TooltipTriggerMode.HOVER
                            ? ModConfig.TooltipTriggerMode.SHORTCUT : ModConfig.TooltipTriggerMode.HOVER); break;
            default: return false;
        }
        ModConfig.save();
        if (engine != null) {
            engine.setFeatureEnabled("chat", ModConfig.CHAT_ENABLED.get());
            engine.setChatAutoMode(ModConfig.CHAT_MODE.get() == ModConfig.TranslationMode.AUTO);
            engine.setOutgoingChatEnabled(ModConfig.CHAT_OUTGOING_ENABLED.get());
            engine.setFeatureEnabled("tooltip_hover", ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get());
        }
        SimpleTranslateForge1122.onTranslationSettingsChanged();
        return true;
    }
}
