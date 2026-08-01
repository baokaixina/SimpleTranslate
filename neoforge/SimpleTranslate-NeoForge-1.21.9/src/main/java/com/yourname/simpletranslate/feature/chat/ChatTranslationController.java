package com.yourname.simpletranslate.feature.chat;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.TranslationTextDetector;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.transport.TranslationManager;
import com.yourname.simpletranslate.feature.chat.ButtonMessageData;
import com.yourname.simpletranslate.feature.hud.HudTranslationHistory;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-ChatComponent chat translation coordinator. The mixin only forwards
 * vanilla events here; AUTO mode, BUTTON mode, HUD history presentation and
 * Hold Original swapping live in dedicated collaborators.
 */
public final class ChatTranslationController {
    // Shared across chat instances (same lifetime as the old mixin statics).
    private static final Set<Component> PROCESSED_MESSAGES =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final Map<UUID, ButtonMessageData> BUTTON_MESSAGES = new ConcurrentHashMap<>();
    private static final Map<Component, Component> AUTO_PEER_MAP =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final String OUTGOING_SURFACE = "chat.outgoing";
    private static final String OUTGOING_ROLE = "chat-outgoing";
    private static final long OUTGOING_PENDING_TIMEOUT_MS = 45000L;
    private static final Pattern OUTGOING_LATIN_WORD_PATTERN =
            Pattern.compile("[A-Za-z][A-Za-z0-9'’\\-]*");
    private static final Set<String> OUTGOING_ENGLISH_MARKERS = Set.of(
            "a", "an", "and", "are", "am", "bad", "boss", "buy", "can", "come", "diamond",
            "diamonds", "did", "do", "does", "emerald", "find", "for", "found", "go", "gold",
            "good", "guild", "has", "have", "he", "hello", "help", "hi", "home", "how", "i",
            "in", "iron", "is", "it", "look", "market", "mine", "mining", "my", "need", "no",
            "not", "of", "ok", "okay", "on", "or", "party", "please", "price", "quest",
            "sell", "server", "she", "shop", "spawn", "sword", "thanks", "thank", "that",
            "the", "they", "this", "to", "trade", "want", "warp", "was", "we", "were",
            "what", "when", "where", "who", "why", "will", "with", "yes", "you", "your"
    );
    private static ModConfig.TranslationMode lastMode = null;
    private static boolean outgoingPending = false;
    private static long outgoingPendingSinceMs = 0L;
    private static long outgoingRequestSequence = 0L;
    private static long outgoingActiveRequestId = 0L;
    private static String lastFailedOutgoingMessage = "";

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
        ChatContextBatchTranslator.trackController(this.autoTranslator, this);
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

    public Set<Component> processedMessages() {
        return PROCESSED_MESSAGES;
    }

    public Map<UUID, ButtonMessageData> buttonMessages() {
        return BUTTON_MESSAGES;
    }

    public Map<Component, Component> autoPeerMap() {
        return AUTO_PEER_MAP;
    }

    public void markProcessed(Component component) {
        if (component != null) {
            PROCESSED_MESSAGES.add(component);
        }
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
        clearOutgoingPending();
        lastFailedOutgoingMessage = "";
        ChatMessageStore.clear();
    }

    void restoreVisibleOriginalMessages() {
        holdController.restoreTranslatedMessages();
    }

    // ------------------------------------------------------------------
    // Vanilla event entry points
    // ------------------------------------------------------------------

    public static boolean tryTranslateOutgoingMessage(
            ChatScreen screen, String rawMessage, Supplier<String> currentMessageSupplier) {
        if (!ModConfig.CHAT_OUTGOING_ENABLED.get()) {
            return false;
        }
        if (screen == null || currentMessageSupplier == null) {
            return false;
        }

        String normalized = normalizeOutgoingMessage(screen, rawMessage);
        if (normalized.isBlank() || normalized.startsWith("/")) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return true;
        }
        if (consumeFailedOutgoingRetry(normalized)) {
            return false;
        }
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            rememberOutgoingStatusFailure(normalized);
            showOutgoingStatus(minecraft, "message.simple_translate.chat_outgoing.disabled");
            return true;
        }

        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            rememberOutgoingStatusFailure(normalized);
            showOutgoingStatus(minecraft, "message.simple_translate.chat_outgoing.not_ready");
            return true;
        }

        if (hasPendingOutgoing()) {
            showOutgoingStatus(minecraft, "message.simple_translate.chat_outgoing.pending");
            return true;
        }

        String localLanguage = outgoingLocalLanguage();
        String serverLanguage = outgoingServerLanguage();
        if (Objects.equals(localLanguage, serverLanguage)) {
            return false;
        }

        String targetLanguage = chooseOutgoingTargetLanguage(normalized, localLanguage, serverLanguage);
        if (!TranslationTextDetector.hasLanguageSignal(normalized, "auto")) {
            return false;
        }

        long requestId = beginOutgoingRequest();
        showOutgoingStatus(minecraft, "message.simple_translate.chat_outgoing.translating",
                TranslationTextDetector.displayLanguageName(targetLanguage));
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        CompletableFuture<String> future;
        try {
            future = manager.translateRaw(normalized, OUTGOING_SURFACE, OUTGOING_ROLE, "auto", targetLanguage)
                    .orTimeout(OUTGOING_PENDING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable throwable) {
            finishOutgoingRequest(requestId);
            markOutgoingFailure(minecraft, normalized);
            SimpleTranslateMod.getLogger().warn("Outgoing chat translation could not start", throwable);
            return true;
        }
        future.whenComplete((translated, error) -> {
            try {
                minecraft.execute(() -> {
                    if (!finishOutgoingRequest(requestId)) {
                        return;
                    }
                    if (!SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                            || Minecraft.getInstance().screen != screen) {
                        return;
                    }
                    String current = normalizeOutgoingMessage(screen, currentMessageSupplier.get());
                    if (!normalized.equals(current)) {
                        showOutgoingStatus(minecraft, "message.simple_translate.chat_outgoing.changed");
                        return;
                    }
                    if (error != null || translated == null || translated.isBlank()
                            || translated.equals(normalized)) {
                        markOutgoingFailure(minecraft, normalized);
                        return;
                    }
                    String normalizedTranslation = normalizeOutgoingMessage(screen, translated);
                    if (normalizedTranslation.isBlank() || normalizedTranslation.equals(normalized)) {
                        markOutgoingFailure(minecraft, normalized);
                        return;
                    }
                    lastFailedOutgoingMessage = "";
                    screen.handleChatInput(normalizedTranslation, true);
                    minecraft.setScreen(null);
                });
            } catch (Throwable throwable) {
                finishOutgoingRequest(requestId);
                SimpleTranslateMod.getLogger().warn("Outgoing chat translation callback failed", throwable);
            }
        });
        return true;
    }

    public void onAddMessage(Component message) {
        syncBlacklistRevision();
        String plainText = message.getString();

        if (hudHistoryPresenter.isHudHistoryChatMessage(message)) {
            return;
        }
        if (!ModConfig.GLOBAL_ENABLED.get() || !ModConfig.CHAT_ENABLED.get()) {
            return;
        }
        TranslationManager manager = SimpleTranslateMod.getTranslationManager();

        ModConfig.TranslationMode currentMode = ModConfig.CHAT_MODE.get();
        if (lastMode != null && lastMode != currentMode) {
            onChatModeChanged();
        }
        lastMode = currentMode;

        if (PROCESSED_MESSAGES.contains(message)) {
            return;
        }
        if (ChatBlacklistGuard.hasBlacklistedSourceText(message, plainText)) {
            return;
        }
        if (!ChatMessageStore.containsEnglish(plainText)) {
            return;
        }

        if (currentMode == ModConfig.TranslationMode.AUTO
                && !ChatAutoTranslationFilter.shouldAutoTranslate(plainText)) {
            return;
        }

        if (currentMode == ModConfig.TranslationMode.AUTO
                && (ModConfig.CHAT_CONTEXT_ENABLED.get() || manager != null)) {
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
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return false;
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
        List<String> priorLines = new ArrayList<>();
        for (int i = index - 1; i >= 0 && priorLines.size() < contextLimit; i--) {
            Component content = allMessages.get(i).content();
            if (hudHistoryPresenter.isHudHistoryChatMessage(content)) {
                continue;
            }
            String text = getOriginalContextText(content);
            if (text != null && !text.isBlank()) {
                priorLines.add(text);
            }
        }
        Collections.reverse(priorLines);
        context.addAll(priorLines);

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

    private static String normalizeOutgoingMessage(ChatScreen screen, String message) {
        return screen.normalizeChatMessage(message == null ? "" : message);
    }

    private static String outgoingLocalLanguage() {
        String local = TranslationTextDetector.canonicalLanguageCode(ModConfig.TARGET_LANGUAGE.get());
        return local.isBlank() || "auto".equals(local) ? "zh_cn" : local;
    }

    private static String outgoingServerLanguage() {
        String server = TranslationTextDetector.canonicalLanguageCode(
                ModConfig.CHAT_OUTGOING_SERVER_LANGUAGE.get());
        return server.isBlank() || "auto".equals(server) ? "en" : server;
    }

    private static String chooseOutgoingTargetLanguage(String text, String localLanguage, String serverLanguage) {
        boolean localSignal = TranslationTextDetector.hasLanguageSignal(text, localLanguage);
        if (localSignal) {
            return serverLanguage;
        }
        if (looksLikeServerLanguageOnly(text, serverLanguage)) {
            return localLanguage;
        }
        return serverLanguage;
    }

    private static boolean looksLikeServerLanguageOnly(String text, String serverLanguage) {
        String server = TranslationTextDetector.canonicalLanguageCode(serverLanguage);
        if ("en".equals(server)) {
            return looksLikeEnglishOutgoingText(text);
        }
        return !TranslationTextDetector.containsTranslatableText(text, 1, server);
    }

    private static boolean looksLikeEnglishOutgoingText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (TranslationTextDetector.containsTranslatableText(text, 1, "en")) {
            return false;
        }
        String normalized = TranslationTextDetector.normalizeForDetection(text).toLowerCase(Locale.ROOT);
        Matcher matcher = OUTGOING_LATIN_WORD_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String word = matcher.group().replace('’', '\'');
            if (OUTGOING_ENGLISH_MARKERS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static long beginOutgoingRequest() {
        outgoingPending = true;
        outgoingPendingSinceMs = System.currentTimeMillis();
        outgoingActiveRequestId = ++outgoingRequestSequence;
        return outgoingActiveRequestId;
    }

    private static boolean finishOutgoingRequest(long requestId) {
        if (outgoingActiveRequestId != requestId) {
            return false;
        }
        clearOutgoingPending();
        return true;
    }

    private static void clearOutgoingPending() {
        outgoingPending = false;
        outgoingPendingSinceMs = 0L;
        outgoingActiveRequestId = 0L;
    }

    private static boolean hasPendingOutgoing() {
        if (!outgoingPending) {
            return false;
        }
        long age = System.currentTimeMillis() - outgoingPendingSinceMs;
        if (age > OUTGOING_PENDING_TIMEOUT_MS) {
            clearOutgoingPending();
            return false;
        }
        return true;
    }

    private static boolean consumeFailedOutgoingRetry(String normalized) {
        if (normalized == null || normalized.isBlank() || !normalized.equals(lastFailedOutgoingMessage)) {
            return false;
        }
        lastFailedOutgoingMessage = "";
        return true;
    }

    private static void markOutgoingFailure(Minecraft minecraft, String normalized) {
        rememberOutgoingStatusFailure(normalized);
        showOutgoingStatus(minecraft, "message.simple_translate.chat_outgoing.failed");
    }

    private static void rememberOutgoingStatusFailure(String normalized) {
        lastFailedOutgoingMessage = normalized == null ? "" : normalized;
    }

    private static void showOutgoingStatus(Minecraft minecraft, String key, Object... args) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(key, args), true);
        }
    }
}
