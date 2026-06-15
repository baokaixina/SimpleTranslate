package com.yourname.simpletranslate.chat;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.TranslationManager;
import com.yourname.simpletranslate.util.ChatMessageIdentity;
import com.yourname.simpletranslate.util.ChatTranslationRuntime;
import com.yourname.simpletranslate.util.DirectFormattedTranslationPipeline;
import com.yourname.simpletranslate.util.DirectSurfaceTranslator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Chat context batch translation uses the same FIFO history model as
 * {@link com.yourname.simpletranslate.util.HudTranslationHistory}: messages are
 * recorded in arrival order, collected for a window, then the oldest pending
 * slice of that history is translated together as one passage.
 */
public final class ChatContextBatchTranslator {
    public static final String BATCH_SURFACE = ChatTranslationRuntime.CHAT_CONTEXT_BATCH_SURFACE;
    public static final String BATCH_ROLE = "chat-context-batch";
    public static final int MAX_HISTORY_ENTRIES = 160;
    public static final int MAX_BATCH_MESSAGES = 12;
    static final int MAX_BATCH_RETRIES = 3;
    private static final int BACKLOG_FORCE_FLUSH_COUNT = 16;
    private static final long FAILED_RETRY_MS = 6_000L;
    private static final long IN_FLIGHT_TIMEOUT_MS = 120_000L;

    private static final List<PendingEntry> ENTRIES = new ArrayList<>();
    private static long nextSequence = 1L;
    private static long nextEntryId = 1L;
    private static long nextBatchId = 1L;
    private static long inFlightBatchId = -1L;
    private static List<Long> inFlightEntryIds = List.of();
    private static long inFlightStartedAtMillis = 0L;
    private static long nextBatchAllowedAtMillis = 0L;
    private static final Map<ChatTranslationController, ChatAutoTranslator> CONTROLLERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean registered;

    private ChatContextBatchTranslator() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> tickTranslator(System.currentTimeMillis()));
    }

    static void trackController(ChatAutoTranslator autoTranslator, ChatTranslationController controller) {
        if (autoTranslator == null || controller == null) {
            return;
        }
        CONTROLLERS.put(controller, autoTranslator);
    }

    public static synchronized void clear() {
        ENTRIES.clear();
        nextSequence = 1L;
        nextEntryId = 1L;
        nextBatchId = 1L;
        inFlightBatchId = -1L;
        inFlightEntryIds = List.of();
        inFlightStartedAtMillis = 0L;
        nextBatchAllowedAtMillis = 0L;
    }

    public static void enqueueVisibleUntranslated(ChatAutoTranslator autoTranslator,
                                                  ChatTranslationController controller) {
        if (!ModConfig.CHAT_CONTEXT_ENABLED.get()
                || ModConfig.CHAT_MODE.get() != ModConfig.TranslationMode.AUTO
                || controller == null) {
            return;
        }

        List<GuiMessage> allMessages = controller.access().simpleTranslateAllMessages();
        if (allMessages == null || allMessages.isEmpty()) {
            return;
        }

        synchronized (ChatContextBatchTranslator.class) {
            pruneStaleEntriesLocked(autoTranslator);
            long now = System.currentTimeMillis();
            for (int i = allMessages.size() - 1; i >= 0; i--) {
                GuiMessage guiMessage = allMessages.get(i);
                if (guiMessage == null || guiMessage.content() == null) {
                    continue;
                }
                Component content = guiMessage.content();
                if (controller.hudHistory().isHudHistoryChatMessage(content)
                        || controller.processedMessages().containsKey(System.identityHashCode(content))
                        || controller.autoPeerMap().containsKey(content)
                        || ChatButtonController.extractMessageId(content) != null) {
                    continue;
                }
                String plainText = controller.getOriginalContextText(content);
                if (plainText == null || plainText.isBlank()
                        || !ChatMessageStore.containsEnglish(plainText)
                        || ChatBlacklistGuard.hasBlacklistedSourceText(content, plainText)) {
                    continue;
                }
                ChatMessageIdentity identity = new ChatMessageIdentity(
                        content,
                        plainText,
                        guiMessage.headerSignature(),
                        guiMessage.tag(),
                        guiMessage.addedTime(),
                        SimpleTranslateMod.getRuntimeRevision());
                String pendingKey = batchPendingKey(identity, plainText);
                if (autoTranslator.tryApplyCachedAutoMessage(identity, content, plainText, pendingKey)) {
                    continue;
                }
                enqueueEntryLocked(autoTranslator, controller, identity, content, plainText, 0, now);
            }
        }
    }

    private static void enqueueEntryLocked(ChatAutoTranslator autoTranslator,
                                           ChatTranslationController controller,
                                           ChatMessageIdentity identity,
                                           Component message,
                                           String plainText,
                                           int attempt,
                                           long now) {
        String pendingKey = batchPendingKey(identity, plainText);
        PendingEntry existing = findByPendingKey(pendingKey);
        if (existing != null) {
            if (existing.status == Status.PENDING
                    || existing.status == Status.IN_FLIGHT
                    || existing.status == Status.FAILED) {
                return;
            }
            ENTRIES.remove(existing);
            ChatMessageStore.removePending(pendingKey);
        }
        if (!ChatMessageStore.markPending(pendingKey)) {
            return;
        }
        ENTRIES.add(new PendingEntry(
                nextEntryId++,
                nextSequence++,
                autoTranslator,
                controller,
                identity,
                message,
                plainText,
                attempt,
                pendingKey,
                Status.PENDING,
                null,
                now,
                0L,
                0));
        trimHistoryLocked();
        if (nextBatchAllowedAtMillis <= now) {
            nextBatchAllowedAtMillis = now + batchIntervalMs();
        }
    }

    public static void tickTranslator(long nowMillis) {
        if (!ModConfig.CHAT_ENABLED.get()
                || !ModConfig.CHAT_CONTEXT_ENABLED.get()
                || ModConfig.CHAT_MODE.get() != ModConfig.TranslationMode.AUTO) {
            return;
        }
        scanVisibleUntranslatedControllers();
        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            return;
        }
        BatchSnapshot batch = prepareNextBatch(nowMillis);
        if (batch == null) {
            return;
        }
        DirectSurfaceTranslator.translateComponentsAsync(
                        batch.components(),
                        BATCH_SURFACE,
                        BATCH_ROLE,
                        false,
                        batch.context())
                .whenComplete((direct, error) -> {
                    long now = System.currentTimeMillis();
                    if (error != null || direct == null || !direct.handled
                            || direct.components == null
                            || direct.components.size() != batch.entryIds().size()) {
                        failBatch(batch.batchId(), now);
                        return;
                    }
                    completeBatch(batch.batchId(), direct.components, now);
                });
    }

    public static synchronized void removeEntry(PendingEntry entry) {
        if (entry != null) {
            ENTRIES.remove(entry);
        }
    }

    private static void scanVisibleUntranslatedControllers() {
        List<Map.Entry<ChatTranslationController, ChatAutoTranslator>> snapshot;
        synchronized (CONTROLLERS) {
            snapshot = new ArrayList<>(CONTROLLERS.entrySet());
        }
        for (Map.Entry<ChatTranslationController, ChatAutoTranslator> entry : snapshot) {
            ChatTranslationController controller = entry.getKey();
            ChatAutoTranslator autoTranslator = entry.getValue();
            if (controller != null && autoTranslator != null) {
                enqueueVisibleUntranslated(autoTranslator, controller);
            }
        }
    }

    public static synchronized int pendingCountForTest() {
        int count = 0;
        for (PendingEntry entry : ENTRIES) {
            if (entry.status == Status.PENDING || entry.status == Status.FAILED) {
                count++;
            }
        }
        return count;
    }

    public static synchronized BatchSnapshot prepareBatchForTest(long nowMillis) {
        return prepareNextBatchLocked(nowMillis);
    }

    public static synchronized void completeBatchForTest(long batchId, List<Component> translatedComponents) {
        completeBatchLocked(batchId, translatedComponents, System.currentTimeMillis());
    }

    public static synchronized long nextBatchAllowedAtForTest() {
        return nextBatchAllowedAtMillis;
    }

    public static long batchIntervalMs() {
        return Math.max(500L, Math.min(10_000L, ModConfig.CHAT_CONTEXT_BATCH_INTERVAL_MS.get()));
    }

    public static long collectWindowMs() {
        return Math.max(500L, Math.min(30_000L, ModConfig.CHAT_CONTEXT_COLLECT_WINDOW_MS.get()));
    }

    @Nullable
    private static BatchSnapshot prepareNextBatch(long nowMillis) {
        synchronized (ChatContextBatchTranslator.class) {
            return prepareNextBatchLocked(nowMillis);
        }
    }

    @Nullable
    private static BatchSnapshot prepareNextBatchLocked(long nowMillis) {
        if (inFlightBatchId >= 0L) {
            if (nowMillis - inFlightStartedAtMillis >= IN_FLIGHT_TIMEOUT_MS) {
                failBatch(inFlightBatchId, nowMillis);
            } else {
                return null;
            }
        }
        if (nowMillis < nextBatchAllowedAtMillis) {
            return null;
        }

        purgeInvisibleEntriesLocked();
        fallbackExhaustedEntriesLocked();

        List<PendingEntry> batchEntries = new ArrayList<>();
        for (PendingEntry entry : ENTRIES) {
            if (entry.status == Status.PENDING
                    || (entry.status == Status.FAILED && nowMillis - entry.lastAttemptMillis >= FAILED_RETRY_MS)) {
                batchEntries.add(entry);
                if (batchEntries.size() >= MAX_BATCH_MESSAGES) {
                    break;
                }
            }
        }
        if (batchEntries.isEmpty()) {
            return null;
        }

        long firstPendingAge = nowMillis - batchEntries.get(0).enqueuedAtMillis;
        long effectiveCollectWindow = effectiveCollectWindowMs(batchEntries.size(), firstPendingAge);
        if (batchEntries.size() < MAX_BATCH_MESSAGES && firstPendingAge < effectiveCollectWindow) {
            return null;
        }

        long batchId = nextBatchId++;
        List<Long> entryIds = new ArrayList<>(batchEntries.size());
        List<Component> components = new ArrayList<>(batchEntries.size());
        long firstSequence = batchEntries.get(0).sequence;
        for (PendingEntry entry : batchEntries) {
            entry.status = Status.IN_FLIGHT;
            entry.lastAttemptMillis = nowMillis;
            entry.attempts++;
            entryIds.add(entry.entryId);
            components.add(entry.message.copy());
        }
        inFlightBatchId = batchId;
        inFlightEntryIds = List.copyOf(entryIds);
        inFlightStartedAtMillis = nowMillis;
        return new BatchSnapshot(batchId, List.copyOf(entryIds), List.copyOf(components),
                buildBatchContextBefore(firstSequence), nowMillis);
    }

    private static long effectiveCollectWindowMs(int pendingBatchSize, long firstPendingAge) {
        int pendingCount = pendingCountLocked();
        long window = collectWindowMs();
        if (pendingCount >= BACKLOG_FORCE_FLUSH_COUNT) {
            return 0L;
        }
        if (pendingCount >= BACKLOG_FORCE_FLUSH_COUNT / 2) {
            return Math.min(window, 1500L);
        }
        if (firstPendingAge >= window * 2L) {
            return 0L;
        }
        return window;
    }

    private static int pendingCountLocked() {
        int count = 0;
        for (PendingEntry entry : ENTRIES) {
            if (entry.status == Status.PENDING || entry.status == Status.FAILED) {
                count++;
            }
        }
        return count;
    }

    private static String buildBatchContextBefore(long firstBatchSequence) {
        int contextLimit = Math.max(0, ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get());
        if (contextLimit <= 0) {
            return "";
        }
        List<PendingEntry> contextEntries = new ArrayList<>();
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            PendingEntry entry = ENTRIES.get(i);
            if (entry.sequence >= firstBatchSequence) {
                continue;
            }
            contextEntries.add(entry);
            if (contextEntries.size() >= contextLimit) {
                break;
            }
        }
        if (contextEntries.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("Recent chat history before the current batch. ");
        context.append("The current batch lines are consecutive chat messages in <st-plain-context>; ");
        context.append("translate them with each other as one passage. Use prior history only for continuity; ");
        context.append("do not return these prior lines.\n");
        int index = 1;
        for (int i = contextEntries.size() - 1; i >= 0; i--) {
            PendingEntry entry = contextEntries.get(i);
            context.append(index++).append(". chat\n");
            context.append("   original: ").append(entry.plainText.trim()).append('\n');
            String translated = entry.translatedComponent == null ? "" : entry.translatedComponent.getString().trim();
            context.append("   translated: ").append(translated.isBlank() ? "[pending]" : translated).append('\n');
        }
        return context.toString().trim();
    }

    private static void purgeInvisibleEntriesLocked() {
        for (PendingEntry entry : new ArrayList<>(ENTRIES)) {
            if (entry.status != Status.PENDING && entry.status != Status.FAILED) {
                continue;
            }
            if (!entry.controller.replacer().isIdentityCurrent(entry.identity)) {
                ENTRIES.remove(entry);
                ChatMessageStore.removePending(entry.pendingKey);
            }
        }
    }

    private static void pruneStaleEntriesLocked(ChatAutoTranslator autoTranslator) {
        for (PendingEntry entry : new ArrayList<>(ENTRIES)) {
            if (entry.status != Status.PENDING && entry.status != Status.FAILED) {
                continue;
            }
            if (!entry.controller.replacer().isIdentityCurrent(entry.identity)) {
                ENTRIES.remove(entry);
                ChatMessageStore.removePending(entry.pendingKey);
                continue;
            }
            if (entry.attempts >= MAX_BATCH_RETRIES && entry.status == Status.FAILED) {
                ENTRIES.remove(entry);
                ChatMessageStore.removePending(entry.pendingKey);
                autoTranslator.fallbackBatchToDirect(entry);
            }
        }
    }

    private static void trimHistoryLocked() {
        while (ENTRIES.size() > MAX_HISTORY_ENTRIES) {
            PendingEntry removed = ENTRIES.remove(0);
            ChatMessageStore.removePending(removed.pendingKey);
        }
    }

    private static void completeBatch(long batchId, List<Component> translatedComponents, long nowMillis) {
        synchronized (ChatContextBatchTranslator.class) {
            completeBatchLocked(batchId, translatedComponents, nowMillis);
        }
    }

    private static void completeBatchLocked(long batchId, List<Component> translatedComponents, long nowMillis) {
        if (batchId != inFlightBatchId || translatedComponents == null
                || translatedComponents.size() != inFlightEntryIds.size()) {
            return;
        }
        for (int i = 0; i < inFlightEntryIds.size(); i++) {
            PendingEntry entry = findByEntryId(inFlightEntryIds.get(i));
            if (entry == null) {
                continue;
            }
            Component translated = translatedComponents.get(i);
            if (translated == null
                    || translated.getString().isBlank()
                    || translated.getString().equals(entry.plainText)
                    || ChatBlacklistGuard.containsBlacklistedText(translated.getString())) {
                markFailedOrFallbackLocked(entry);
                continue;
            }
            entry.translatedComponent = translated.copy();
            entry.status = Status.DONE;
            DirectFormattedTranslationPipeline.cacheRestoredTranslation(
                    entry.message, translated, BATCH_SURFACE, BATCH_ROLE, false, "");
            ChatMessageStore.removePending(entry.pendingKey);
            entry.autoTranslator.applyBatchTranslation(
                    entry.identity, translated, entry.message, entry.pendingKey);
        }
        finishBatch(nowMillis);
    }

    private static void failBatch(long batchId, long nowMillis) {
        synchronized (ChatContextBatchTranslator.class) {
            if (batchId != inFlightBatchId) {
                return;
            }
            for (Long entryId : inFlightEntryIds) {
                PendingEntry entry = findByEntryId(entryId);
                if (entry != null && entry.status == Status.IN_FLIGHT) {
                    markFailedOrFallbackLocked(entry);
                }
            }
            finishBatch(nowMillis);
        }
    }

    private static void fallbackExhaustedEntriesLocked() {
        for (PendingEntry entry : new ArrayList<>(ENTRIES)) {
            if (entry.status == Status.FAILED && entry.attempts >= MAX_BATCH_RETRIES) {
                ENTRIES.remove(entry);
                ChatMessageStore.removePending(entry.pendingKey);
                entry.autoTranslator.fallbackBatchToDirect(entry);
            }
        }
    }

    private static void markFailedOrFallbackLocked(PendingEntry entry) {
        if (entry == null) {
            return;
        }
        ChatMessageStore.removePending(entry.pendingKey);
        if (entry.attempts >= MAX_BATCH_RETRIES) {
            ENTRIES.remove(entry);
            entry.autoTranslator.fallbackBatchToDirect(entry);
            return;
        }
        entry.status = Status.FAILED;
    }

    private static void finishBatch(long nowMillis) {
        inFlightBatchId = -1L;
        inFlightEntryIds = List.of();
        inFlightStartedAtMillis = 0L;
        nextBatchAllowedAtMillis = nowMillis + batchIntervalMs();
    }

    @Nullable
    private static PendingEntry findByPendingKey(String pendingKey) {
        for (PendingEntry entry : ENTRIES) {
            if (entry.pendingKey.equals(pendingKey)) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    private static PendingEntry findByEntryId(long entryId) {
        for (PendingEntry entry : ENTRIES) {
            if (entry.entryId == entryId) {
                return entry;
            }
        }
        return null;
    }

    private static String batchPendingKey(ChatMessageIdentity identity, String plainText) {
        return "chat-context-batch:" + ChatMessageStore.messageKey(identity, plainText);
    }

    private enum Status {
        PENDING,
        IN_FLIGHT,
        DONE,
        FAILED
    }

    public record BatchSnapshot(long batchId,
                                List<Long> entryIds,
                                List<Component> components,
                                String context,
                                long startedAtMillis) {
    }

    static final class PendingEntry {
        final long entryId;
        final long sequence;
        final ChatAutoTranslator autoTranslator;
        final ChatTranslationController controller;
        final ChatMessageIdentity identity;
        final Component message;
        final String plainText;
        final int attempt;
        final String pendingKey;
        Status status;
        @Nullable
        Component translatedComponent;
        final long enqueuedAtMillis;
        long lastAttemptMillis;
        int attempts;

        private PendingEntry(long entryId,
                             long sequence,
                             ChatAutoTranslator autoTranslator,
                             ChatTranslationController controller,
                             ChatMessageIdentity identity,
                             Component message,
                             String plainText,
                             int attempt,
                             String pendingKey,
                             Status status,
                             @Nullable Component translatedComponent,
                             long enqueuedAtMillis,
                             long lastAttemptMillis,
                             int attempts) {
            this.entryId = entryId;
            this.sequence = sequence;
            this.autoTranslator = autoTranslator;
            this.controller = controller;
            this.identity = identity;
            this.message = message;
            this.plainText = plainText;
            this.attempt = attempt;
            this.pendingKey = pendingKey;
            this.status = status;
            this.translatedComponent = translatedComponent;
            this.enqueuedAtMillis = enqueuedAtMillis;
            this.lastAttemptMillis = lastAttemptMillis;
            this.attempts = attempts;
        }
    }
}
