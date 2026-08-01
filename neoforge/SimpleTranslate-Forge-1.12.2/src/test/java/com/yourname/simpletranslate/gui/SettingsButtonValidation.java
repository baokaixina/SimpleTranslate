package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.KeyChord;
import java.util.LinkedHashSet;
import java.util.Set;

/** Build-only checks for each settings page's own cycle-button path. */
public final class SettingsButtonValidation {
    private SettingsButtonValidation() { }

    public static void run() {
        ChatTranslationScreen chat = new ChatTranslationScreen(null, null);
        ModConfig.CHAT_ENABLED.set(true);
        require(chat.onContentButton(100) && !ModConfig.CHAT_ENABLED.get(), "chat master button did not change");
        require(chat.onContentButton(100) && ModConfig.CHAT_ENABLED.get(), "chat master button did not restore");
        ModConfig.CHAT_MODE.set(ModConfig.TranslationMode.BUTTON);
        require(chat.onContentButton(101), "chat mode button was not handled");
        require(ModConfig.CHAT_MODE.get() == ModConfig.TranslationMode.AUTO, "chat mode did not change");
        ModConfig.CHAT_OUTGOING_ENABLED.set(false);
        require(chat.onContentButton(102) && ModConfig.CHAT_OUTGOING_ENABLED.get(),
                "outgoing chat button did not change");
        ModConfig.CHAT_CONTEXT_ENABLED.set(false);
        require(chat.onContentButton(103) && ModConfig.CHAT_CONTEXT_ENABLED.get(),
                "chat context button did not change");
        ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.set(6);
        require(chat.onContentButton(104) && ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get() == 7,
                "chat context-count button did not change");
        ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.set(true);
        require(chat.onContentButton(105) && !ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get(),
                "chat-hover master button did not change");
        require(chat.onContentButton(105) && ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get(),
                "chat-hover master button did not restore");

        ItemTooltipScreen item = new ItemTooltipScreen(null, null);
        ModConfig.TOOLTIP_ITEM_ENABLED.set(true);
        require(item.onContentButton(100) && !ModConfig.TOOLTIP_ITEM_ENABLED.get(),
                "item-tooltip master button did not change");
        require(item.onContentButton(100) && ModConfig.TOOLTIP_ITEM_ENABLED.get(),
                "item-tooltip master button did not restore");
        ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.set(ModConfig.TooltipTriggerMode.HOVER);
        require(item.onContentButton(101), "item trigger mode button was not handled");
        require(ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get() == ModConfig.TooltipTriggerMode.SHORTCUT,
                "item trigger mode did not change");

        ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.set(ModConfig.TooltipTriggerMode.HOVER);
        require(chat.onContentButton(106), "chat hover trigger mode button was not handled");
        require(ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.get() == ModConfig.TooltipTriggerMode.SHORTCUT,
                "chat hover trigger mode did not change");

        BookTranslationScreen book = new BookTranslationScreen(null, null);
        ModConfig.CONTENT_BOOK_ENABLED.set(true);
        require(book.onContentButton(100) && !ModConfig.CONTENT_BOOK_ENABLED.get(),
                "book master button did not change");
        ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.set(true);
        require(book.onContentButton(101) && !ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get(),
                "book-hover button did not change");

        AdvancementTranslationScreen advancement = new AdvancementTranslationScreen(null, null);
        ModConfig.CONTENT_ADVANCEMENT_ENABLED.set(true);
        require(advancement.onContentButton(100) && !ModConfig.CONTENT_ADVANCEMENT_ENABLED.get(),
                "advancement button did not change");

        GuiTranslationScreen gui = new GuiTranslationScreen(null, null);
        ModConfig.CONTENT_GUI_ENABLED.set(true);
        require(gui.onContentButton(100) && !ModConfig.CONTENT_GUI_ENABLED.get(),
                "GUI master button did not change");
        ModConfig.CONTENT_GUI_MODE.set(ModConfig.GuiTranslationMode.SHORTCUT);
        require(gui.onContentButton(101), "GUI mode button was not handled");
        require(ModConfig.CONTENT_GUI_MODE.get() == ModConfig.GuiTranslationMode.AUTO, "GUI mode did not change");
        ModConfig.MOD_TRANSLATION_ENABLED.set(true);
        require(gui.onContentButton(102) && !ModConfig.MOD_TRANSLATION_ENABLED.get(),
                "mod-translation master button did not change");

        Set<String> screenKeys = new LinkedHashSet<String>();
        screenKeys.add("example.Screen\n0123456789abcdef");
        ModConfig.CONTENT_GUI_FRAME_SCREEN_KEYS.set(GuiTranslationController.join(screenKeys));
        require(GuiTranslationController.persistedScreenKeys().equals(screenKeys),
                "GUI screen key serialization split the title identity");

        ModConfig.MOD_FTB_QUESTS_ENABLED.set(false);
        require(gui.onContentButton(103),
                "missing FTB integration button was not handled");
        require(ModConfig.MOD_FTB_QUESTS_ENABLED.get(), "FTB integration preference did not change");
        ModConfig.MOD_TIPS_ENABLED.set(false);
        require(gui.onContentButton(104),
                "missing Tips integration button was not handled");
        require(ModConfig.MOD_TIPS_ENABLED.get(), "Tips integration preference did not change");

        ModConfig.HOLD_ORIGINAL_ENABLED.set(true);
        require(new ShortcutSettingsScreen(null, null).onContentButton(51),
                "hold-original master button was not handled");
        require(!ModConfig.HOLD_ORIGINAL_ENABLED.get(), "hold-original master did not change");
        require(new KeyChord(KeyChord.InputType.MOUSE, 0, 0).isBound(),
                "mouse button zero was treated as unbound");

        SignTranslationScreen sign = new SignTranslationScreen(null, null);
        ModConfig.CONTENT_SIGN_ENABLED.set(true);
        require(sign.onContentButton(100) && !ModConfig.CONTENT_SIGN_ENABLED.get(),
                "sign master button did not change");
        ModConfig.CONTENT_SIGN_CONTEXT_MODE.set(ModConfig.SignContextMode.AUTO);
        require(sign.onContentButton(101), "sign mode button was not handled");
        require(ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() == ModConfig.SignContextMode.MANUAL,
                "sign mode did not change");

        ModConfig.CONTENT_SIGN_RADIUS.set(5);
        require(sign.onContentButton(102), "sign radius button was not handled");
        require(ModConfig.CONTENT_SIGN_RADIUS.get() == 6, "sign radius did not change");

        EntityNameTranslationScreen entity = new EntityNameTranslationScreen(null, null);
        ModConfig.CONTENT_ENTITY_NAME_ENABLED.set(true);
        require(entity.onContentButton(100) && !ModConfig.CONTENT_ENTITY_NAME_ENABLED.get(),
                "entity-name master button did not change");
        ModConfig.CONTENT_ENTITY_NAME_RADIUS.set(16);
        require(entity.onContentButton(101),
                "entity radius button was not handled");
        require(ModConfig.CONTENT_ENTITY_NAME_RADIUS.get() == 17, "entity radius did not change");

        TooltipGlowSettingsScreen glow = new TooltipGlowSettingsScreen(null, null);
        ModConfig.TOOLTIP_GLOW_ENABLED.set(false);
        require(glow.onContentButton(100) && ModConfig.TOOLTIP_GLOW_ENABLED.get(),
                "tooltip-glow master button did not change");
        ModConfig.TOOLTIP_GLOW_LINE_WIDTH.set(3);
        require(glow.onContentButton(101) && ModConfig.TOOLTIP_GLOW_LINE_WIDTH.get() == 4,
                "tooltip-glow width button did not change");
        ModConfig.TOOLTIP_GLOW_SPREAD.set(6);
        require(glow.onContentButton(102) && ModConfig.TOOLTIP_GLOW_SPREAD.get() == 7,
                "tooltip-glow spread button did not change");
        ModConfig.TOOLTIP_GLOW_CYCLE_MS.set(8000);
        require(glow.onContentButton(103) && ModConfig.TOOLTIP_GLOW_CYCLE_MS.get() == 9000,
                "tooltip-glow cycle button did not change");
        ModConfig.TOOLTIP_GLOW_OPACITY.set(180);
        require(glow.onContentButton(104) && ModConfig.TOOLTIP_GLOW_OPACITY.get() == 195,
                "tooltip-glow opacity button did not change");
        ModConfig.TOOLTIP_GLOW_THEME.set(ModConfig.TooltipGlowTheme.SOFT);
        require(glow.onContentButton(105),
                "tooltip glow theme button was not handled");
        require(ModConfig.TOOLTIP_GLOW_THEME.get() == ModConfig.TooltipGlowTheme.OCEAN,
                "tooltip glow theme did not change");

        HudTranslationScreen hud = new HudTranslationScreen(null, null);
        ModConfig.HUD_SCOREBOARD_ENABLED.set(true);
        ModConfig.HUD_BOSSBAR_ENABLED.set(true);
        ModConfig.HUD_TITLE_ENABLED.set(true);
        ModConfig.HUD_ACTIONBAR_ENABLED.set(true);
        ModConfig.HUD_TITLE_CONTEXT_ENABLED.set(false);
        ModConfig.HUD_HISTORY_CHAT_ENABLED.set(false);
        ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.set(800);
        ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.set(4500);
        ModConfig.LAYOUT_CRITICAL_HUD_KEEP_ORIGINAL.set(false);
        require(hud.onContentButton(100) && !ModConfig.HUD_SCOREBOARD_ENABLED.get(), "HUD scoreboard button failed");
        require(hud.onContentButton(101) && !ModConfig.HUD_BOSSBAR_ENABLED.get(), "HUD bossbar button failed");
        require(hud.onContentButton(102) && !ModConfig.HUD_TITLE_ENABLED.get(), "HUD title button failed");
        require(hud.onContentButton(103) && !ModConfig.HUD_ACTIONBAR_ENABLED.get(), "HUD actionbar button failed");
        require(hud.onContentButton(104) && ModConfig.HUD_TITLE_CONTEXT_ENABLED.get(), "HUD context button failed");
        require(hud.onContentButton(105) && ModConfig.HUD_HISTORY_CHAT_ENABLED.get(), "HUD history button failed");
        require(hud.onContentButton(106) && ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.get() == 1300,
                "HUD batch interval button failed");
        require(hud.onContentButton(107) && ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.get() == 5000,
                "HUD collect window button failed");
        require(hud.onContentButton(108) && ModConfig.LAYOUT_CRITICAL_HUD_KEEP_ORIGINAL.get(),
                "HUD layout guard button failed");

        TranslationSpeedScreen speed = new TranslationSpeedScreen(null, null);
        ModConfig.API_MAX_IN_FLIGHT_BATCHES.set(2);
        require(speed.onContentButton(101) && ModConfig.API_MAX_IN_FLIGHT_BATCHES.get() == 1,
                "in-flight batch button failed");
        ModConfig.API_DIRECT_BATCH_DELAY_MS.set(50);
        require(speed.onContentButton(102) && ModConfig.API_DIRECT_BATCH_DELAY_MS.get() == 60,
                "direct batch-delay button failed");

        TextContextSettingsScreen context = new TextContextSettingsScreen(null, null);
        ModConfig.API_TEXT_CONTEXT_ENABLED.set(true);
        require(context.onContentButton(100) && !ModConfig.API_TEXT_CONTEXT_ENABLED.get(),
                "text-context master button failed");
        ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.set(false);
        require(context.onContentButton(101) && ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.get(),
                "text-context shared button failed");
        ModConfig.BooleanValue[] contextValues = {
                ModConfig.API_TEXT_CONTEXT_RECEIVED_CHAT, ModConfig.API_TEXT_CONTEXT_SENT_CHAT,
                ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP, ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP,
                ModConfig.API_TEXT_CONTEXT_BOOK, ModConfig.API_TEXT_CONTEXT_SIGN,
                ModConfig.API_TEXT_CONTEXT_HUD_CAPTIONS, ModConfig.API_TEXT_CONTEXT_HUD_PROGRESS,
                ModConfig.API_TEXT_CONTEXT_ENTITY_NAME
        };
        for (int i = 0; i < contextValues.length; i++) {
            contextValues[i].set(true);
            require(context.onContentButton(200 + i) && !contextValues[i].get(),
                    "text-context scope button failed at index " + i);
        }

        // Restore defaults so this build-only check cannot influence later transport fixtures.
        ModConfig.CHAT_MODE.set(ModConfig.TranslationMode.BUTTON);
        ModConfig.CHAT_ENABLED.set(true);
        ModConfig.CHAT_OUTGOING_ENABLED.set(false);
        ModConfig.CHAT_CONTEXT_ENABLED.set(false);
        ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.set(6);
        ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.set(true);
        ModConfig.TOOLTIP_ITEM_ENABLED.set(true);
        ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.set(ModConfig.TooltipTriggerMode.HOVER);
        ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.set(ModConfig.TooltipTriggerMode.HOVER);
        ModConfig.CONTENT_BOOK_ENABLED.set(true);
        ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.set(true);
        ModConfig.CONTENT_ADVANCEMENT_ENABLED.set(true);
        ModConfig.CONTENT_GUI_ENABLED.set(true);
        ModConfig.CONTENT_GUI_MODE.set(ModConfig.GuiTranslationMode.SHORTCUT);
        ModConfig.CONTENT_GUI_FRAME_SCREEN_KEYS.set("");
        ModConfig.MOD_TRANSLATION_ENABLED.set(true);
        ModConfig.MOD_FTB_QUESTS_ENABLED.set(true);
        ModConfig.MOD_TIPS_ENABLED.set(true);
        ModConfig.HOLD_ORIGINAL_ENABLED.set(false);
        ModConfig.CONTENT_SIGN_ENABLED.set(true);
        ModConfig.CONTENT_SIGN_CONTEXT_MODE.set(ModConfig.SignContextMode.AUTO);
        ModConfig.CONTENT_SIGN_RADIUS.set(3);
        ModConfig.CONTENT_ENTITY_NAME_ENABLED.set(true);
        ModConfig.CONTENT_ENTITY_NAME_RADIUS.set(16);
        ModConfig.HUD_SCOREBOARD_ENABLED.set(true);
        ModConfig.HUD_BOSSBAR_ENABLED.set(true);
        ModConfig.HUD_TITLE_ENABLED.set(true);
        ModConfig.HUD_ACTIONBAR_ENABLED.set(true);
        ModConfig.HUD_TITLE_CONTEXT_ENABLED.set(false);
        ModConfig.HUD_HISTORY_CHAT_ENABLED.set(false);
        ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.set(800);
        ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.set(4500);
        ModConfig.LAYOUT_CRITICAL_HUD_KEEP_ORIGINAL.set(false);
        ModConfig.API_MAX_IN_FLIGHT_BATCHES.set(2);
        ModConfig.API_DIRECT_BATCH_DELAY_MS.set(50);
        ModConfig.API_TEXT_CONTEXT_ENABLED.set(true);
        ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.set(false);
        for (ModConfig.BooleanValue value : contextValues) value.set(true);
        ModConfig.TOOLTIP_GLOW_ENABLED.set(false);
        ModConfig.TOOLTIP_GLOW_LINE_WIDTH.set(3);
        ModConfig.TOOLTIP_GLOW_SPREAD.set(6);
        ModConfig.TOOLTIP_GLOW_CYCLE_MS.set(8000);
        ModConfig.TOOLTIP_GLOW_OPACITY.set(180);
        ModConfig.TOOLTIP_GLOW_THEME.set(ModConfig.TooltipGlowTheme.SOFT);
        ModConfig.save();
        System.out.println("SETTINGS_BUTTON_VALIDATION_OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
