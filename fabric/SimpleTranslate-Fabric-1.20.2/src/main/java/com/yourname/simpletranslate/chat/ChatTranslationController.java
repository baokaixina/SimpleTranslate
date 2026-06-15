package com.yourname.simpletranslate.chat;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.translation.TranslationManager;
import com.yourname.simpletranslate.util.ButtonMessageData;
import com.yourname.simpletranslate.util.HudTranslationHistory;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-ChatComponent chat translation coordinator. The mixin only forwards
 * vanilla events here; AUTO mode, BUTTON mode, HUD history presentation and
 * Hold Original swapping live in dedicated collaborators.
 */
public final class ChatTranslationController {
    // Shared across chat instances (same lifetime as the old mixin statics).
    private static final Map<Integer, Boolean> PROCESSED_MESSAGES = new ConcurrentHashMap<>();
    private static final Map<UUID, ButtonMessageData> BUTTON_MESSAGES = new ConcurrentHashMap<>();
    private static final Map<Component, Component> AUTO_PEER_MAP =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static ModConfig.TranslationMode lastMode = null;

    private final ChatComponentAccess access;
    private final ChatMessageReplacer replacer;
    private final ChatAutoTranslator autoTranslator;
    private final ChatButtonController buttonController;
    private final HudHistoryChatPresenter hudHistoryPresenter;
    private final ChatHoldController holdController;
    private long seenBlacklistRevision = -1L;

    public ChatTranslationController(ChatComponentAccess access) {
        this.access = access;
        this.replacer = new ChatMessageReplacer(access);
        this.autoTranslator = new ChatAutoTranslator(this);
        this.buttonController = new ChatButtonController(this);
        this.hudHistoryPresenter = new HudHistoryChatPresenter(this);
        this.holdController = new ChatHoldController(this);
    }

    public ChatComponentAccess access() {
        return access;
    }

    public ChatMessageReplacer replacer() {
        return replacer;
    }

    public HudHistoryChatPresenter hudHistory() {
        return hudHistoryPresenter;
    }

    public ChatButtonController buttons() {
        return buttonController;
    }

    public Map<Integer, Boolean> processedMessages() {
        return PROCESSED_MESSAGES;
    }

    public Map<UUID, ButtonMessageData> buttonMessages() {
        return BUTTON_MESSAGES;
    }

    public Map<Component, Component> autoPeerMap() {
        return AUTO_PEER_MAP;
    }

    public void markProcessed(Component component) {
        PROCESSED_MESSAGES.put(System.identityHashCode(component), true);
    }

    public static void onChatModeChanged() {
        PROCESSED_MESSAGES.clear();
        ChatMessageStore.clear();
        ChatContextBatchTranslator.clear();
        lastMode = ModConfig.CHAT_MODE.get();
    }

    /** Clears cross-session chat controller static state on world/language reset. */
    public static void clearRuntimeState() {
        PROCESSED_MESSAGES.clear();
        BUTTON_MESSAGES.clear();
        AUTO_PEER_MAP.clear();
        lastMode = null;
        ChatMessageStore.clear();
    }

    // ------------------------------------------------------------------
    // Vanilla event entry points
    // ------------------------------------------------------------------

    public void onAddMessage(Component message) {
        syncBlacklistRevision();
        String plainText = message.getString();

        if (hudHistoryPresenter.isHudHistoryChatMessage(message)) {
            return;
        }
        if (!ModConfig.CHAT_ENABLED.get()) {
            return;
        }
        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null) {
            return;
        }

        ModConfig.TranslationMode currentMode = ModConfig.CHAT_MODE.get();
        if (lastMode != null && lastMode != currentMode) {
            onChatModeChanged();
        }
        lastMode = currentMode;

        if (PROCESSED_MESSAGES.containsKey(System.identityHashCode(message))) {
            return;
        }
        if (ChatBlacklistGuard.hasBlacklistedSourceText(message, plainText)) {
            return;
        }
        if (!ChatMessageStore.containsEnglish(plainText)) {
            return;
        }

        if (currentMode == ModConfig.TranslationMode.AUTO) {
            autoTranslator.handleIncomingMessage(message, plainText, manager);
        } else if (currentMode == ModConfig.TranslationMode.BUTTON) {
            buttonController.handleIncomingMessage(message, plainText);
        }
    }

    public boolean handleButtonClickEvent(String clickValue) {
        syncBlacklistRevision();
        if (clickValue != null && clickValue.startsWith(HudHistoryChatPresenter.HUD_HISTORY_CLICK_PREFIX)) {
            return hudHistoryPresenter.toggleHudHistoryChatMessage(clickValue);
        }
        return buttonController.handleClickValue(clickValue);
    }

    public boolean showVisibleOriginalMessages() {
        return buttonController.showVisibleOriginalMessages();
    }

    public void upsertHudHistoryCaption(HudTranslationHistory.Entry entry) {
        hudHistoryPresenter.upsertHudHistoryCaption(entry);
    }

    public void onHoldOriginalChanged(HoldOriginalFeature feature, boolean holding) {
        if (feature != HoldOriginalFeature.CHAT) {
            return;
        }
        try {
            if (holding) {
                holdController.applyChatHold();
            } else {
                holdController.releaseChatHold();
            }
        } catch (Throwable t) {
            SimpleTranslateMod.getLogger().error("Chat hold toggle failed", t);
        }
    }

    public boolean refreshBlacklistedTranslations() {
        seenBlacklistRevision = -1L;
        syncBlacklistRevision();
        return true;
    }

    // ------------------------------------------------------------------
    // Context collection
    // ------------------------------------------------------------------

    public List<String> collectContextLines(Component message, String plainText, int[] targetIndexHolder) {
        List<String> context = new ArrayList<>();
        targetIndexHolder[0] = 0;
        List<GuiMessage> allMessages = access.simpleTranslateAllMessages();

        if (allMessages == null || allMessages.isEmpty()) {
            context.add(plainText);
            return context;
        }

        int index = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            GuiMessage msg = allMessages.get(i);
            if (hudHistoryPresenter.isHudHistoryChatMessage(msg.content())) {
                continue;
            }
            if (msg.content() == message) {
                index = i;
                break;
            }
        }

        if (index < 0) {
            for (int i = 0; i < allMessages.size(); i++) {
                GuiMessage msg = allMessages.get(i);
                Component content = msg.content();
                if (hudHistoryPresenter.isHudHistoryChatMessage(content)) {
                    continue;
                }
                String text = getOriginalContextText(content);
                if (plainText.equals(text)) {
                    index = i;
                    break;
                }
            }
        }

        if (index < 0) {
            context.add(plainText);
            return context;
        }

        int contextLimit = Math.max(0, ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get());
        int end = Math.min(allMessages.size() - 1, index + contextLimit);

        for (int i = end; i > index; i--) {
            Component content = allMessages.get(i).content();
            if (hudHistoryPresenter.isHudHistoryChatMessage(content)) {
                continue;
            }
            String text = getOriginalContextText(content);
            if (text != null && !text.isBlank()) {
                context.add(text);
            }
        }

        targetIndexHolder[0] = context.size();
        context.add(plainText);

        if (context.isEmpty() || targetIndexHolder[0] < 0 || targetIndexHolder[0] >= context.size()) {
            context.clear();
            context.add(plainText);
            targetIndexHolder[0] = 0;
        }

        return context;
    }

    public String getOriginalContextText(Component content) {
        if (content == null) {
            return "";
        }
        Component originalPeer = AUTO_PEER_MAP.get(content);
        if (originalPeer != null) {
            return ChatContextHelper.stripChatButtonSuffix(originalPeer.getString());
        }
        UUID messageId = ChatButtonController.extractMessageId(content);
        if (messageId != null) {
            ButtonMessageData data = BUTTON_MESSAGES.get(messageId);
            if (data != null && data.originalPlainText() != null && !data.originalPlainText().isBlank()) {
                return data.originalPlainText();
            }
        }
        return ChatContextHelper.stripChatButtonSuffix(content.getString());
    }

    // ------------------------------------------------------------------
    // Blacklist refresh
    // ------------------------------------------------------------------

    public void syncBlacklistRevision() {
        long revision = SimpleTranslateMod.getBlacklistRevision();
        if (seenBlacklistRevision == revision) {
            return;
        }
        seenBlacklistRevision = revision;

        PROCESSED_MESSAGES.clear();
        holdController.clearSwapState();

        List<GuiMessage> allMessages = access.simpleTranslateAllMessages();
        try {
            boolean changed = false;
            for (int i = 0; i < allMessages.size(); i++) {
                GuiMessage msg = allMessages.get(i);
                Component content = msg.content();
                Component originalPeer = AUTO_PEER_MAP.get(content);
                if (originalPeer != null
                        && (ChatBlacklistGuard.hasBlacklistedSourceText(originalPeer, originalPeer.getString())
                        || ChatBlacklistGuard.containsBlacklistedText(content.getString()))) {
                    allMessages.set(i, new GuiMessage(msg.addedTime(), originalPeer, msg.signature(), msg.tag()));
                    AUTO_PEER_MAP.remove(content);
                    changed = true;
                    continue;
                }

                UUID messageId = ChatButtonController.extractMessageId(content);
                if (messageId == null) {
                    continue;
                }
                ButtonMessageData data = BUTTON_MESSAGES.get(messageId);
                if (data == null) {
                    continue;
                }
                boolean blocked = ChatBlacklistGuard.hasBlacklistedSourceText(data.originalMessage(), data.originalPlainText())
                        || ChatBlacklistGuard.containsBlacklistedText(content.getString())
                        || (data.translatedMessage() != null
                        && ChatBlacklistGuard.containsBlacklistedText(data.translatedMessage().getString()));
                if (blocked) {
                    data.setTranslatedMessage(null);
                    data.setState(ButtonMessageData.State.ORIGINAL);
                    Component originalWithButton = buttonController.createMessageWithButton(
                            data.originalMessage(), messageId, ButtonMessageData.State.ORIGINAL);
                    markProcessed(originalWithButton);
                    allMessages.set(i, new GuiMessage(msg.addedTime(), originalWithButton, msg.signature(), msg.tag()));
                    changed = true;
                }
            }
            if (changed) {
                access.simpleTranslateRescale();
            }
        } catch (Throwable t) {
            SimpleTranslateMod.getLogger().error("Failed to refresh chat after blacklist change", t);
        }
    }
}
