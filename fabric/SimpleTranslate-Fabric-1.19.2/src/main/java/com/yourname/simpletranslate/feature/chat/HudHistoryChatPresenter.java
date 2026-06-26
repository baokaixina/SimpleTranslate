package com.yourname.simpletranslate.feature.chat;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.hud.HudHistoryChatData;
import com.yourname.simpletranslate.feature.hud.HudTranslationHistory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

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

        Component message = createHudHistoryChatMessage(data);
        controller.markProcessed(message);
        String clickValue = hudHistoryClickValue(entry.historyKey());
        if (!controller.replacer().replaceByClickValue(clickValue, message, 120)) {
            int ticks = minecraft == null || minecraft.gui == null ? 0 : minecraft.gui.getGuiTicks();
            controller.access().simpleTranslateAllMessages().add(0, new GuiMessage(ticks, message, null, null));
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
        Component message = createHudHistoryChatMessage(data);
        controller.markProcessed(message);
        controller.replacer().replaceByClickValue(clickValue, message, 120);
        return true;
    }

    public boolean isHudHistoryChatMessage(Component component) {
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
        for (Component sibling : component.getSiblings()) {
            if (isHudHistoryChatMessage(sibling)) {
                return true;
            }
        }
        return false;
    }

    private Component createHudHistoryChatMessage(HudHistoryChatData data) {
        boolean showingOriginal = data.showingOriginal();
        HudTranslationHistory.Entry entry = data.entry();
        String bodyText = showingOriginal ? entry.originalText() : entry.translatedText();
        if (bodyText == null) {
            bodyText = "";
        }
        ChatFormatting typeColor = switch (entry.type()) {
            case TITLE -> ChatFormatting.GOLD;
            case SUBTITLE -> ChatFormatting.YELLOW;
            case ACTIONBAR -> ChatFormatting.AQUA;
        };
        MutableComponent content = Component.empty()
                .append(Component.literal(entry.type().label() + " ").withStyle(typeColor))
                .append(Component.literal(bodyText).withStyle(ChatFormatting.WHITE));
        String buttonText = showingOriginal
                ? Component.translatable("chat.simple_translate.hud_caption.show_translation").getString()
                : Component.translatable("chat.simple_translate.hud_caption.show_original").getString();
        String hoverText = showingOriginal
                ? Component.translatable("chat.simple_translate.hud_caption.show_translation.hover").getString()
                : Component.translatable("chat.simple_translate.hud_caption.show_original.hover").getString();
        ChatFormatting buttonColor = showingOriginal ? ChatFormatting.AQUA : ChatFormatting.YELLOW;
        content.append(Component.literal(buttonText)
                .withStyle(style -> style
                        .withColor(buttonColor)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hoverText)))
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.SUGGEST_COMMAND,
                                hudHistoryClickValue(entry.historyKey())))));
        return content;
    }

    private void trimHudHistoryChatMessages() {
        List<GuiMessage> allMessages = controller.access().simpleTranslateAllMessages();
        while (hudHistoryChatMessages.size() > MAX_HUD_HISTORY_CHAT_MESSAGES) {
            Iterator<Map.Entry<String, HudHistoryChatData>> iterator = hudHistoryChatMessages.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            String historyKey = iterator.next().getKey();
            iterator.remove();
            String clickValue = hudHistoryClickValue(historyKey);
            for (int i = allMessages.size() - 1; i >= 0; i--) {
                if (ChatMessageReplacer.containsClickEvent(allMessages.get(i).content(), clickValue)) {
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


