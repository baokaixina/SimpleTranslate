package com.yourname.simpletranslate.feature.chat;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.hud.HudHistoryChatData;
import com.yourname.simpletranslate.feature.hud.HudTranslationHistory;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.Style;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Publishes translated HUD captions as toggleable chat history lines. */
public final class HudHistoryChatPresenter {
    public static final String HUD_HISTORY_CLICK_PREFIX = "simple_translate:hud_history:";
    private static final int MAX_HUD_HISTORY_CHAT_MESSAGES = 40;

    private final ChatTranslationController controller;
    private final Map<String, HudHistoryChatData> hudHistoryChatMessages = new LinkedHashMap<>();

    HudHistoryChatPresenter(ChatTranslationController controller) {
        this.controller = controller;
    }

    public void upsertHudHistoryCaption(HudTranslationHistory.Entry entry) {
        if (entry == null
                || !ModConfig.HUD_HISTORY_CHAT_ENABLED.get()
                || entry.historyKey() == null
                || entry.historyKey().isBlank()
                || entry.translatedText() == null
                || entry.translatedText().isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && !minecraft.isSameThread()) {
            minecraft.execute(() -> upsertHudHistoryCaption(entry));
            return;
        }

        HudHistoryChatData data = hudHistoryChatMessages.get(entry.historyKey());
        if (data == null) {
            data = new HudHistoryChatData(entry, false);
            hudHistoryChatMessages.put(entry.historyKey(), data);
        } else {
            data.setEntry(entry);
        }

        ITextComponent message = createHudHistoryChatMessage(data);
        controller.markProcessed(message);
        String clickValue = hudHistoryClickValue(entry.historyKey());
        if (!controller.replacer().replaceByClickValue(clickValue, message, 120)) {
            int ticks = minecraft == null || minecraft.gui == null ? 0 : minecraft.gui.getGuiTicks();
            controller.access().simpleTranslateAllMessages().add(0, new ChatLine(ticks, message, 0));
        }
        trimHudHistoryChatMessages();
        controller.access().simpleTranslateRescale();
    }

    public boolean toggleHudHistoryChatMessage(String clickValue) {
        String historyKey = decodeHudHistoryClickValue(clickValue);
        if (historyKey == null || historyKey.isBlank()) {
            return false;
        }
        HudHistoryChatData data = hudHistoryChatMessages.get(historyKey);
        if (data == null) {
            return true;
        }
        data.toggleShowingOriginal();
        ITextComponent message = createHudHistoryChatMessage(data);
        controller.markProcessed(message);
        controller.replacer().replaceByClickValue(clickValue, message, 120);
        return true;
    }

    public boolean isHudHistoryChatMessage(ITextComponent component) {
        if (component == null) {
            return false;
        }
        Style style = component.getStyle();
        if (style != null && style.getClickEvent() != null) {
            String value = ChatMessageReplacer.suggestCommandValue(style.getClickEvent());
            if (value != null && value.startsWith(HUD_HISTORY_CLICK_PREFIX)) {
                return true;
            }
        }
        for (ITextComponent sibling : component.getSiblings()) {
            if (isHudHistoryChatMessage(sibling)) {
                return true;
            }
        }
        return false;
    }

    private ITextComponent createHudHistoryChatMessage(HudHistoryChatData data) {
        boolean showingOriginal = data.showingOriginal();
        HudTranslationHistory.Entry entry = data.entry();
        String bodyText = showingOriginal ? entry.originalText() : entry.translatedText();
        if (bodyText == null) {
            bodyText = "";
        }
        TextFormatting typeColor = switch (entry.type()) {
            case TITLE -> TextFormatting.GOLD;
            case SUBTITLE -> TextFormatting.YELLOW;
            case ACTIONBAR -> TextFormatting.AQUA;
        };
        IFormattableTextComponent content = com.yourname.simpletranslate.core.LegacyComponentFactory.empty()
                .append(com.yourname.simpletranslate.core.LegacyComponentFactory.literal(entry.type().label() + " ").withStyle(typeColor))
                .append(com.yourname.simpletranslate.core.LegacyComponentFactory.literal(bodyText).withStyle(TextFormatting.WHITE));
        String buttonText = showingOriginal
                ? com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("chat.simple_translate.hud_caption.show_translation").getString()
                : com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("chat.simple_translate.hud_caption.show_original").getString();
        String hoverText = showingOriginal
                ? com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("chat.simple_translate.hud_caption.show_translation.hover").getString()
                : com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("chat.simple_translate.hud_caption.show_original.hover").getString();
        TextFormatting buttonColor = showingOriginal ? TextFormatting.AQUA : TextFormatting.YELLOW;
        content.append(com.yourname.simpletranslate.core.LegacyComponentFactory.literal(buttonText)
                .withStyle(style -> style
                        .withColor(buttonColor)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, com.yourname.simpletranslate.core.LegacyComponentFactory.literal(hoverText)))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                hudHistoryClickValue(entry.historyKey())))));
        return content;
    }

    private void trimHudHistoryChatMessages() {
        List<ChatLine<ITextComponent>> allMessages = controller.access().simpleTranslateAllMessages();
        while (hudHistoryChatMessages.size() > MAX_HUD_HISTORY_CHAT_MESSAGES) {
            Iterator<Map.Entry<String, HudHistoryChatData>> iterator = hudHistoryChatMessages.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            String historyKey = iterator.next().getKey();
            iterator.remove();
            String clickValue = hudHistoryClickValue(historyKey);
            for (int i = allMessages.size() - 1; i >= 0; i--) {
                if (ChatMessageReplacer.containsClickEvent(allMessages.get(i).getMessage(), clickValue)) {
                    allMessages.remove(i);
                    break;
                }
            }
        }
    }

    private static String hudHistoryClickValue(String historyKey) {
        return HUD_HISTORY_CLICK_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(historyKey.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeHudHistoryClickValue(String clickValue) {
        if (clickValue == null || !clickValue.startsWith(HUD_HISTORY_CLICK_PREFIX)) {
            return null;
        }
        try {
            String encoded = clickValue.substring(HUD_HISTORY_CLICK_PREFIX.length());
            int padding = encoded.length() % 4;
            if (padding != 0) {
                encoded = encoded + "=".repeat(4 - padding);
            }
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
