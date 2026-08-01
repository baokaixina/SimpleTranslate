package com.yourname.simpletranslate.feature.chat;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.transport.TranslationManager;
import com.yourname.simpletranslate.feature.chat.ButtonMessageData;
import com.yourname.simpletranslate.feature.chat.ChatMessageIdentity;
import com.yourname.simpletranslate.feature.chat.ChatTranslationRuntime;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** BUTTON-mode chat translation: [翻译]/[原文] button lifecycle and click routing. */
public final class ChatButtonController {
    public static final String TRANSLATE_CLICK_PREFIX = "simple_translate:";

    private final ChatTranslationController controller;

    ChatButtonController(ChatTranslationController controller) {
        this.controller = controller;
    }

    // ------------------------------------------------------------------
    // Incoming messages and clicks
    // ------------------------------------------------------------------

    void handleIncomingMessage(ITextComponent message, String plainText) {
        UUID messageId = UUID.randomUUID();
        ButtonMessageData data = new ButtonMessageData(message, plainText, SimpleTranslateMod.getRuntimeRevision());
        controller.buttonMessages().put(messageId, data);

        ITextComponent messageWithButton = createMessageWithButton(message, messageId, ButtonMessageData.State.ORIGINAL);
        controller.markProcessed(messageWithButton);

        ChatMessageIdentity identity = controller.replacer().captureIdentity(message, plainText);
        ChatMessageReplacer.runOnClientThread(() -> {
            if (controller.replacer().isIdentityCurrent(identity)) {
                controller.replacer().replaceByIdentity(identity, messageWithButton);
            }
        });

        cleanupOldButtonData();
    }

    boolean handleClickValue(String clickValue) {
        if (clickValue == null || !clickValue.startsWith(TRANSLATE_CLICK_PREFIX)) {
            return false;
        }
        String uuidStr = clickValue.substring(TRANSLATE_CLICK_PREFIX.length());
        try {
            UUID messageId = UUID.fromString(uuidStr);
            handleButtonClick(messageId);
            return true;
        } catch (IllegalArgumentException e) {
            SimpleTranslateMod.getLogger().error("Invalid translation UUID: {}", uuidStr);
            return false;
        }
    }

    boolean showVisibleOriginalMessages() {
        List<ChatLine<ITextComponent>> allMessages = controller.access().simpleTranslateAllMessages();
        List<UUID> visibleMessageIds = new ArrayList<>();
        for (int i = 0; i < Math.min(allMessages.size(), 100); i++) {
            UUID messageId = extractMessageId(allMessages.get(i).getMessage());
            if (messageId != null) {
                visibleMessageIds.add(messageId);
            }
        }

        boolean changed = false;
        for (UUID messageId : visibleMessageIds) {
            ButtonMessageData data = controller.buttonMessages().get(messageId);
            if (data == null || data.state() != ButtonMessageData.State.TRANSLATED) {
                continue;
            }
            showOriginal(messageId, data);
            changed = true;
        }
        return changed;
    }

    private void handleButtonClick(UUID messageId) {
        ButtonMessageData data = controller.buttonMessages().get(messageId);
        if (data == null) {
            return;
        }
        switch (data.state()) {
            case ORIGINAL -> startTranslation(messageId, data);
            case TRANSLATED -> showOriginal(messageId, data);
            case TRANSLATING -> {
                // Ignore clicks while translating.
            }
        }
    }

    // ------------------------------------------------------------------
    // Translation flows
    // ------------------------------------------------------------------

    private void startTranslation(UUID messageId, ButtonMessageData data) {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            showOriginal(messageId, data);
            return;
        }
        if (ChatBlacklistGuard.hasBlacklistedSourceText(data.originalMessage(), data.originalPlainText())) {
            showOriginal(messageId, data);
            return;
        }
        if (data.translatedMessage() != null) {
            if (ChatBlacklistGuard.containsBlacklistedText(data.translatedMessage().getString())) {
                data.setTranslatedMessage(null);
                showOriginal(messageId, data);
                return;
            }
            if (!canApplyButtonTranslation(messageId, data, data.translatedMessage())) {
                revertToOriginal(messageId, data);
                return;
            }
            data.setState(ButtonMessageData.State.TRANSLATED);
            ITextComponent translatedMsg = createMessageWithButton(
                    data.translatedMessage(), messageId, ButtonMessageData.State.TRANSLATED);
            controller.markProcessed(translatedMsg);
            updateMessageById(messageId, translatedMsg);
            return;
        }

        if (tryApplyCachedButtonTranslation(messageId, data)) {
            return;
        }

        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            return;
        }

        data.setState(ButtonMessageData.State.TRANSLATING);
        ITextComponent translatingMsg = createMessageWithButton(
                data.originalMessage(), messageId, ButtonMessageData.State.TRANSLATING);
        controller.markProcessed(translatingMsg);
        updateMessageById(messageId, translatingMsg);

        startDirectMessageTranslation(messageId, data);
    }

    private void startDirectMessageTranslation(UUID messageId, ButtonMessageData data) {
        ChatTranslationRuntime.translateAutoMessage(data.originalMessage()).thenAccept(translatedContent -> {
            if (translatedContent == null
                    || translatedContent.getString().isBlank()
                    || translatedContent.getString().equals(data.originalPlainText())
                    || ChatBlacklistGuard.hasBlacklistedSourceText(data.originalMessage(), data.originalPlainText())
                    || ChatBlacklistGuard.containsBlacklistedText(translatedContent.getString())) {
                revertToOriginal(messageId, data);
                return;
            }
            if (!canApplyButtonTranslation(messageId, data, translatedContent)) {
                revertToOriginal(messageId, data);
                return;
            }
            data.setTranslatedMessage(translatedContent);
            data.setState(ButtonMessageData.State.TRANSLATED);

            ChatMessageReplacer.runOnClientThread(() -> {
                ITextComponent translatedMsg = createMessageWithButton(
                        translatedContent, messageId, ButtonMessageData.State.TRANSLATED);
                controller.markProcessed(translatedMsg);
                updateMessageById(messageId, translatedMsg);
            });
        });
    }

    private void revertToOriginal(UUID messageId, ButtonMessageData data) {
        data.setTranslatedMessage(null);
        data.setState(ButtonMessageData.State.ORIGINAL);
        ChatMessageReplacer.runOnClientThread(() -> showOriginal(messageId, data));
    }

    private boolean tryApplyCachedButtonTranslation(UUID messageId, ButtonMessageData data) {
        ITextComponent cached = getCachedButtonTranslation(data);
        if (cached == null
                || cached.getString().isBlank()
                || cached.getString().equals(data.originalPlainText())
                || ChatBlacklistGuard.containsBlacklistedText(cached.getString())
                || !canApplyButtonTranslation(messageId, data, cached)) {
            return false;
        }

        data.setTranslatedMessage(cached);
        data.setState(ButtonMessageData.State.TRANSLATED);
        ITextComponent translatedMsg = createMessageWithButton(cached, messageId, ButtonMessageData.State.TRANSLATED);
        controller.markProcessed(translatedMsg);
        updateMessageById(messageId, translatedMsg);
        return true;
    }

    private ITextComponent getCachedButtonTranslation(ButtonMessageData data) {
        ITextComponent original = data.originalMessage();
        String plainText = data.originalPlainText();
        if (ChatBlacklistGuard.hasBlacklistedSourceText(original, plainText)) {
            return null;
        }
        return ChatTranslationRuntime.buildCachedAutoMessageTranslation(original);
    }

    // ------------------------------------------------------------------
    // Message presentation
    // ------------------------------------------------------------------

    void showOriginal(UUID messageId, ButtonMessageData data) {
        data.setState(ButtonMessageData.State.ORIGINAL);
        ITextComponent originalMsg = createMessageWithButton(
                data.originalMessage(), messageId, ButtonMessageData.State.ORIGINAL);
        controller.markProcessed(originalMsg);
        updateMessageById(messageId, originalMsg);
    }

    public ITextComponent createMessageWithButton(ITextComponent content, UUID messageId, ButtonMessageData.State state) {
        String buttonText;
        TextFormatting buttonColor;
        String hoverText;

        switch (state) {
            case TRANSLATING -> {
                buttonText = com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("chat.simple_translate.button.translating").getString();
                buttonColor = TextFormatting.GRAY;
                hoverText = com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("chat.simple_translate.button.translating.hover").getString();
            }
            case TRANSLATED -> {
                buttonText = com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("chat.simple_translate.button.original").getString();
                buttonColor = TextFormatting.YELLOW;
                hoverText = com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("chat.simple_translate.button.original.hover").getString();
            }
            default -> {
                buttonText = com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("chat.simple_translate.button.translate").getString();
                buttonColor = TextFormatting.AQUA;
                hoverText = com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("chat.simple_translate.button.translate.hover").getString();
            }
        }

        IFormattableTextComponent button = com.yourname.simpletranslate.core.LegacyComponentFactory.literal(buttonText)
                .withStyle(style -> style
                        .withColor(buttonColor)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, com.yourname.simpletranslate.core.LegacyComponentFactory.literal(hoverText)))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                TRANSLATE_CLICK_PREFIX + messageId)));

        return content.copy().append(button);
    }

    private void updateMessageById(UUID messageId, ITextComponent newComponent) {
        ChatMessageReplacer.runOnClientThread(() ->
                controller.replacer().replaceByClickValue(
                        TRANSLATE_CLICK_PREFIX + messageId.toString(), newComponent, Integer.MAX_VALUE));
    }

    private boolean canApplyButtonTranslation(UUID messageId, ButtonMessageData data, ITextComponent translatedComponent) {
        if (messageId == null || data == null || translatedComponent == null) {
            return false;
        }
        if (controller.buttonMessages().get(messageId) != data) {
            return false;
        }
        if (!SimpleTranslateMod.isRuntimeRevisionCurrent(data.runtimeRevision())) {
            return false;
        }
        // Buttons created in BUTTON mode must keep working after the player
        // switches to AUTO mode; only a disabled chat feature blocks them.
        if (!ModConfig.GLOBAL_ENABLED.get() || !ModConfig.CHAT_ENABLED.get()) {
            return false;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.CHAT)) {
            return false;
        }
        return !ChatBlacklistGuard.hasBlacklistedSourceText(data.originalMessage(), data.originalPlainText())
                && !ChatBlacklistGuard.containsBlacklistedText(translatedComponent.getString());
    }

    private void cleanupOldButtonData() {
        var buttonMessages = controller.buttonMessages();
        if (buttonMessages.size() <= 100) {
            return;
        }
        List<ChatLine<ITextComponent>> allMessages = controller.access().simpleTranslateAllMessages();
        java.util.Set<UUID> visibleIds = new java.util.HashSet<>();
        if (allMessages != null) {
            for (ChatLine<ITextComponent> msg : allMessages) {
                UUID messageId = extractMessageId(msg.getMessage());
                if (messageId != null) {
                    visibleIds.add(messageId);
                }
            }
        }
        var iterator = buttonMessages.entrySet().iterator();
        int toRemove = buttonMessages.size() - 80;
        while (toRemove > 0 && iterator.hasNext()) {
            var entry = iterator.next();
            if (visibleIds.contains(entry.getKey())) {
                continue;
            }
            iterator.remove();
            toRemove--;
        }
    }

    public static UUID extractMessageId(ITextComponent component) {
        if (component == null) {
            return null;
        }
        Style style = component.getStyle();
        if (style != null && style.getClickEvent() != null) {
            String value = ChatMessageReplacer.suggestCommandValue(style.getClickEvent());
            if (value != null && value.startsWith(TRANSLATE_CLICK_PREFIX)) {
                String uuidStr = value.substring(TRANSLATE_CLICK_PREFIX.length());
                try {
                    return UUID.fromString(uuidStr);
                } catch (IllegalArgumentException ignored) {
                    // Ignore invalid click values in chat components.
                }
            }
        }
        for (ITextComponent sibling : component.getSiblings()) {
            UUID messageId = extractMessageId(sibling);
            if (messageId != null) {
                return messageId;
            }
        }
        return null;
    }
}
