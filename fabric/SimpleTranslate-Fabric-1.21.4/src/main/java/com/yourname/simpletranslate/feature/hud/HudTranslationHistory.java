package com.yourname.simpletranslate.feature.hud;
import com.yourname.simpletranslate.feature.chat.HudHistoryChatBridge;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Runtime-only ordered caption history for HUD title/subtitle/actionbar context.
 */
public final class HudTranslationHistory {
    public static final int MAX_HISTORY_ENTRIES = 160;
    public static final int MAX_CONTEXT_ENTRIES = 12;
    public static final int MAX_BATCH_CAPTIONS = 12;
    public static final long DEFAULT_BATCH_INTERVAL_MS = 800L;
    public static final long DEFAULT_BATCH_COLLECT_WINDOW_MS = 4_500L;
    public static final String BATCH_SURFACE = "hud.history.caption_batch.direct";
    public static final String BATCH_ROLE = "hud-caption-history-batch";

    private static final long FAILED_RETRY_MS = 6_000L;

    private static final List<EntryState> ENTRIES = new ArrayList<>();
    private static long nextSequence = 1L;
    private static long nextBatchId = 1L;
    private static final List<InFlightBatch> IN_FLIGHT_BATCHES = new ArrayList<>();
    private static long nextBatchAllowedAtMillis = 0L;
    private static final Set<String> publishedChatHistoryKeys = new HashSet<>();

    private HudTranslationHistory() {
    }

    public static synchronized void clear() {
        ENTRIES.clear();
        nextSequence = 1L;
        nextBatchId = 1L;
        IN_FLIGHT_BATCHES.clear();
        nextBatchAllowedAtMillis = 0L;
        publishedChatHistoryKeys.clear();
    }

    public static Entry recordCaption(CaptionType type,
                                      String historyKey,
                                      String sourceKey,
                                      Component original,
                                      Component requestComponent) {
        return recordCaption(type, historyKey, sourceKey, original, requestComponent, null);
    }

    public static synchronized Entry recordCaption(CaptionType type,
                                                   String historyKey,
                                                   String sourceKey,
                                                   Component original,
                                                   Component requestComponent,
                                                   @Nullable Function<Component, Component> postProcessor) {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return null;
        }
        String originalText = clean(plain(original));
        if (type == null || originalText.isBlank()) {
            return null;
        }
        String key = cleanKey(historyKey, type.name() + "\u0000" + originalText);
        for (EntryState existing : ENTRIES) {
            if (existing.historyKey.equals(key)) {
                return existing.snapshot();
            }
        }

        long now = System.currentTimeMillis();
        EntryState entry = new EntryState(
                type,
                nextSequence++,
                key,
                cleanKey(sourceKey, originalText),
                copyOrLiteral(original, originalText),
                copyOrLiteral(requestComponent, originalText),
                postProcessor,
                Status.PENDING,
                null,
                null,
                now,
                0L,
                0);
        ENTRIES.add(entry);
        trimEntries();
        if (nextBatchAllowedAtMillis <= now) {
            nextBatchAllowedAtMillis = now + batchIntervalMs();
        }
        return entry.snapshot();
    }

    @Nullable
    public static synchronized Component translatedComponent(String historyKey) {
        EntryState entry = findEntry(historyKey);
        return entry == null || entry.status != Status.DONE || entry.translatedDisplayComponent == null
                ? null
                : entry.translatedDisplayComponent.copy();
    }

    @Nullable
    public static synchronized Component translatedRequestComponent(String historyKey) {
        EntryState entry = findEntry(historyKey);
        return entry == null || entry.status != Status.DONE || entry.translatedRequestComponent == null
                ? null
                : entry.translatedRequestComponent.copy();
    }

    public static synchronized List<Entry> entriesSnapshot() {
        List<Entry> snapshot = new ArrayList<>(ENTRIES.size());
        for (EntryState entry : ENTRIES) {
            snapshot.add(entry.snapshot());
        }
        return List.copyOf(snapshot);
    }

    public static void tickTranslator(long nowMillis) {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return;
        }
        publishReadyChatMessages();
        while (true) {
            BatchSnapshot batch;
            synchronized (HudTranslationHistory.class) {
                batch = prepareNextBatchLocked(nowMillis);
            }
            if (batch == null) {
                break;
            }
            DirectSurfaceTranslator.translateComponentsAsync(
                            batch.components(),
                            BATCH_SURFACE,
                            BATCH_ROLE,
                            false,
                            batch.context())
                    .whenComplete((direct, error) -> {
                        if (error != null || direct == null || !direct.handled
                                || direct.components == null || direct.components.size() != batch.historyKeys().size()) {
                            failBatch(batch.batchId(), System.currentTimeMillis());
                            return;
                        }
                        completeBatch(batch.batchId(), direct.components, System.currentTimeMillis());
                    });
            synchronized (HudTranslationHistory.class) {
                if (IN_FLIGHT_BATCHES.size() >= maxInFlightBatches()) {
                    break;
                }
            }
        }
    }

    @Nullable
    public static synchronized BatchSnapshot prepareBatchForTest(long nowMillis) {
        return prepareNextBatchLocked(nowMillis);
    }

    public static synchronized void completeBatchForTest(long batchId, List<Component> translatedComponents) {
        completeBatchLocked(batchId, translatedComponents, System.currentTimeMillis());
    }

    public static synchronized void completeCaptionForTest(String historyKey, Component translatedComponent) {
        EntryState entry = findEntry(historyKey);
        if (entry == null) {
            return;
        }
        applyTranslation(entry, translatedComponent);
    }

    public static List<Entry> drainReadyChatEntriesForTest() {
        synchronized (HudTranslationHistory.class) {
            List<Entry> entries = readyChatEntriesLocked();
            for (Entry entry : entries) {
                publishedChatHistoryKeys.add(entry.historyKey());
            }
            return entries;
        }
    }

    public static synchronized int pendingCountForTest() {
        int count = 0;
        for (EntryState entry : ENTRIES) {
            if (entry.status == Status.PENDING || entry.status == Status.FAILED) {
                count++;
            }
        }
        return count;
    }

    public static synchronized int inFlightCountForTest() {
        int count = 0;
        for (EntryState entry : ENTRIES) {
            if (entry.status == Status.IN_FLIGHT) {
                count++;
            }
        }
        return count;
    }

    public static synchronized int inFlightBatchCountForTest() {
        return IN_FLIGHT_BATCHES.size();
    }

    public static synchronized long nextBatchAllowedAtForTest() {
        return nextBatchAllowedAtMillis;
    }

    @Nullable
    private static BatchSnapshot prepareNextBatch(long nowMillis) {
        synchronized (HudTranslationHistory.class) {
            return prepareNextBatchLocked(nowMillis);
        }
    }

    @Nullable
    private static BatchSnapshot prepareNextBatchLocked(long nowMillis) {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return null;
        }
        if (IN_FLIGHT_BATCHES.size() >= maxInFlightBatches()) {
            return null;
        }
        if (IN_FLIGHT_BATCHES.isEmpty() && nowMillis < nextBatchAllowedAtMillis) {
            return null;
        }
        List<EntryState> batchEntries = new ArrayList<>();
        for (EntryState entry : ENTRIES) {
            if (entry.status == Status.PENDING
                    || (entry.status == Status.FAILED && nowMillis - entry.lastAttemptMillis >= FAILED_RETRY_MS)) {
                batchEntries.add(entry);
                if (batchEntries.size() >= MAX_BATCH_CAPTIONS) {
                    break;
                }
            }
        }
        if (batchEntries.isEmpty()) {
            return null;
        }
        long firstPendingAge = nowMillis - batchEntries.get(0).createdAtMillis;
        if (batchEntries.size() < MAX_BATCH_CAPTIONS && firstPendingAge < collectWindowMs()) {
            return null;
        }

        long batchId = nextBatchId++;
        List<String> historyKeys = new ArrayList<>(batchEntries.size());
        List<Component> components = new ArrayList<>(batchEntries.size());
        long firstSequence = batchEntries.get(0).sequence;
        for (EntryState entry : batchEntries) {
            entry.status = Status.IN_FLIGHT;
            entry.lastAttemptMillis = nowMillis;
            entry.attempts++;
            historyKeys.add(entry.historyKey);
            components.add(entry.requestComponent.copy());
        }
        IN_FLIGHT_BATCHES.add(new InFlightBatch(batchId, List.copyOf(historyKeys), nowMillis));
        return new BatchSnapshot(batchId, List.copyOf(historyKeys), List.copyOf(components),
                buildBatchContextBefore(firstSequence), nowMillis);
    }

    public static int maxInFlightBatches() {
        return Math.max(1, Math.min(2, ModConfig.API_MAX_IN_FLIGHT_BATCHES.get()));
    }

    private static void completeBatch(long batchId, List<Component> translatedComponents, long nowMillis) {
        synchronized (HudTranslationHistory.class) {
            completeBatchLocked(batchId, translatedComponents, nowMillis);
        }
    }

    private static void completeBatchLocked(long batchId, List<Component> translatedComponents, long nowMillis) {
        InFlightBatch batch = findInFlightBatch(batchId);
        if (batch == null || translatedComponents == null
                || translatedComponents.size() != batch.historyKeys.size()) {
            return;
        }
        for (int i = 0; i < batch.historyKeys.size(); i++) {
            EntryState entry = findEntry(batch.historyKeys.get(i));
            if (entry != null) {
                applyTranslation(entry, translatedComponents.get(i));
            }
        }
        finishBatchLocked(batchId, nowMillis);
    }

    private static void failBatch(long batchId, long nowMillis) {
        synchronized (HudTranslationHistory.class) {
            failBatchLocked(batchId, nowMillis);
        }
    }

    private static void failBatchLocked(long batchId, long nowMillis) {
        InFlightBatch batch = findInFlightBatch(batchId);
        if (batch == null) {
            return;
        }
        for (String key : batch.historyKeys) {
            EntryState entry = findEntry(key);
            if (entry != null && entry.status == Status.IN_FLIGHT) {
                entry.status = Status.FAILED;
            }
        }
        finishBatchLocked(batchId, nowMillis);
    }

    private static void finishBatchLocked(long batchId, long nowMillis) {
        IN_FLIGHT_BATCHES.removeIf(batch -> batch.batchId == batchId);
        if (IN_FLIGHT_BATCHES.isEmpty()) {
            nextBatchAllowedAtMillis = nowMillis + batchIntervalMs();
        }
    }

    @Nullable
    private static InFlightBatch findInFlightBatch(long batchId) {
        for (InFlightBatch batch : IN_FLIGHT_BATCHES) {
            if (batch.batchId == batchId) {
                return batch;
            }
        }
        return null;
    }

    private static final class InFlightBatch {
        final long batchId;
        final List<String> historyKeys;
        final long startedAtMillis;

        private InFlightBatch(long batchId, List<String> historyKeys, long startedAtMillis) {
            this.batchId = batchId;
            this.historyKeys = historyKeys;
            this.startedAtMillis = startedAtMillis;
        }
    }

    public static long batchIntervalMs() {
        return Math.max(500L, Math.min(10_000L, ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.get()));
    }

    public static long collectWindowMs() {
        return Math.max(500L, Math.min(30_000L, ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.get()));
    }

    private static void applyTranslation(EntryState entry, Component translatedRequestComponent) {
        if (entry == null || translatedRequestComponent == null) {
            return;
        }
        Component displayComponent;
        try {
            displayComponent = entry.postProcessor == null
                    ? translatedRequestComponent
                    : entry.postProcessor.apply(translatedRequestComponent);
        } catch (Throwable ignored) {
            displayComponent = null;
        }
        if (displayComponent == null || clean(displayComponent.getString()).isBlank()) {
            entry.status = Status.FAILED;
            return;
        }
        entry.translatedRequestComponent = translatedRequestComponent.copy();
        entry.translatedDisplayComponent = displayComponent.copy();
        entry.status = Status.DONE;
    }

    private static void publishReadyChatMessages() {
        List<Entry> ready;
        synchronized (HudTranslationHistory.class) {
            ready = readyChatEntriesLocked();
        }
        for (Entry entry : ready) {
            if (HudHistoryChatBridge.publish(entry)) {
                synchronized (HudTranslationHistory.class) {
                    publishedChatHistoryKeys.add(entry.historyKey());
                }
            }
        }
    }

    private static List<Entry> readyChatEntriesLocked() {
        if (!ModConfig.GLOBAL_ENABLED.get() || !ModConfig.HUD_HISTORY_CHAT_ENABLED.get()) {
            return List.of();
        }
        List<Entry> ready = new ArrayList<>();
        for (EntryState entry : ENTRIES) {
            if (entry.status != Status.DONE
                    || entry.translatedDisplayComponent == null
                    || publishedChatHistoryKeys.contains(entry.historyKey)) {
                continue;
            }
            String translatedText = clean(entry.translatedDisplayComponent.getString());
            if (translatedText.isBlank()) {
                continue;
            }
            ready.add(entry.snapshot());
        }
        return ready;
    }

    private static String buildBatchContextBefore(long firstBatchSequence) {
        if (!ModConfig.HUD_TITLE_CONTEXT_ENABLED.get()) {
            return "";
        }
        List<EntryState> contextEntries = new ArrayList<>();
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            EntryState entry = ENTRIES.get(i);
            if (entry.sequence >= firstBatchSequence) {
                continue;
            }
            contextEntries.add(entry);
            if (contextEntries.size() >= MAX_CONTEXT_ENTRIES) {
                break;
            }
        }
        if (contextEntries.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        context.append("Recent HUD caption history before the current batch. ");
        context.append("The current Component JSON array contains consecutive subtitle fragments; ");
        context.append("translate them with each other as one passage. Use prior history only for continuity; do not return these prior lines.\n");
        int index = 1;
        for (int i = contextEntries.size() - 1; i >= 0; i--) {
            EntryState entry = contextEntries.get(i);
            context.append(index++).append(". ").append(entry.type.label()).append('\n');
            context.append("   original: ").append(clean(entry.originalComponent.getString())).append('\n');
            String translated = entry.translatedDisplayComponent == null ? "" : clean(entry.translatedDisplayComponent.getString());
            context.append("   translated: ").append(translated.isBlank() ? "[pending]" : translated).append('\n');
        }
        return context.toString().trim();
    }

    @Nullable
    private static EntryState findEntry(String historyKey) {
        String key = cleanKey(historyKey, "");
        if (key.isBlank()) {
            return null;
        }
        for (EntryState entry : ENTRIES) {
            if (entry.historyKey.equals(key)) {
                return entry;
            }
        }
        return null;
    }

    private static void trimEntries() {
        while (ENTRIES.size() > MAX_HISTORY_ENTRIES) {
            ENTRIES.remove(0);
        }
    }

    private static Component copyOrLiteral(@Nullable Component component, String fallbackText) {
        if (component != null) {
            return component.copy();
        }
        return Component.literal(fallbackText == null ? "" : fallbackText);
    }

    private static String plain(@Nullable Component component) {
        return component == null ? "" : component.getString();
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String cleanKey(String key, String fallback) {
        String cleaned = clean(key);
        return cleaned.isBlank() ? clean(fallback) : cleaned;
    }

    public enum CaptionType {
        TITLE("screen.simple_translate.hud.caption.title"),
        SUBTITLE("screen.simple_translate.hud.caption.subtitle"),
        ACTIONBAR("screen.simple_translate.hud.caption.actionbar");

        private final String langKey;

        CaptionType(String langKey) {
            this.langKey = langKey;
        }

        public String label() {
            return net.minecraft.network.chat.Component.translatable(langKey).getString();
        }
    }

    public enum Status {
        PENDING,
        IN_FLIGHT,
        DONE,
        FAILED
    }

    public record Entry(CaptionType type,
                        long sequence,
                        String historyKey,
                        String sourceKey,
                        String originalText,
                        String translatedText,
                        Status status,
                        long createdAtMillis,
                        long lastAttemptMillis,
                        int attempts) {
    }

    public record BatchSnapshot(long batchId,
                                List<String> historyKeys,
                                List<Component> components,
                                String context,
                                long startedAtMillis) {
    }

    private static final class EntryState {
        private final CaptionType type;
        private final long sequence;
        private final String historyKey;
        private final String sourceKey;
        private final Component originalComponent;
        private final Component requestComponent;
        @Nullable
        private final Function<Component, Component> postProcessor;
        private Status status;
        @Nullable
        private Component translatedRequestComponent;
        @Nullable
        private Component translatedDisplayComponent;
        private final long createdAtMillis;
        private long lastAttemptMillis;
        private int attempts;

        private EntryState(CaptionType type,
                           long sequence,
                           String historyKey,
                           String sourceKey,
                           Component originalComponent,
                           Component requestComponent,
                           @Nullable Function<Component, Component> postProcessor,
                           Status status,
                           @Nullable Component translatedRequestComponent,
                           @Nullable Component translatedDisplayComponent,
                           long createdAtMillis,
                           long lastAttemptMillis,
                           int attempts) {
            this.type = type;
            this.sequence = sequence;
            this.historyKey = historyKey;
            this.sourceKey = sourceKey;
            this.originalComponent = originalComponent;
            this.requestComponent = requestComponent;
            this.postProcessor = postProcessor;
            this.status = status;
            this.translatedRequestComponent = translatedRequestComponent;
            this.translatedDisplayComponent = translatedDisplayComponent;
            this.createdAtMillis = createdAtMillis;
            this.lastAttemptMillis = lastAttemptMillis;
            this.attempts = attempts;
        }

        private Entry snapshot() {
            String translatedText = translatedDisplayComponent == null ? "" : clean(translatedDisplayComponent.getString());
            return new Entry(
                    type,
                    sequence,
                    historyKey,
                    sourceKey,
                    clean(originalComponent.getString()),
                    translatedText,
                    status,
                    createdAtMillis,
                    lastAttemptMillis,
                    attempts);
        }
    }
}
