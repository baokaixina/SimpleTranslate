package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.RecoveryPolicy;
import com.yourname.simpletranslate.core.StyleSignatures;
import com.yourname.simpletranslate.core.TranslationDocument;
import com.yourname.simpletranslate.core.WireCodec;
import com.yourname.simpletranslate.translation.TranslationManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Direct formatted translation pipeline (minimal-echo protocol).
 *
 * <p>Orchestrates {@link TranslationDocument} build, cache lookup, request
 * lanes, negative caching and graded restore. The model only returns numbered
 * translated lines; styles, events and structure are reattached locally.</p>
 */
public final class DirectFormattedTranslationPipeline {
    private static final long FAILURE_RETRY_MS = 6000L;
    private static final long DIAGNOSTIC_REPEAT_MS = 15_000L;
    private static final int MAX_QUEUED_LOG_KEYS = 2048;
    private static final int MAX_BATCH_ITEMS = 6;
    private static final int MAX_BATCH_CHARS = 9000;
    private static final long BATCH_DELAY_MS = 100L;
    private static final Map<String, Long> DIAGNOSTIC_LAST_LOG = new ConcurrentHashMap<>();
    private static final Set<String> QUEUED_LOGGED_KEYS = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService BATCH_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SimpleTranslate-DirectBatch");
        thread.setDaemon(true);
        return thread;
    });

    private DirectFormattedTranslationPipeline() {
    }

    /** Clears per-session pipeline state on world switch / settings changes. */
    public static void clearRuntimeState() {
        UPGRADE_ATTEMPTS.clear();
        QUEUED_LOGGED_KEYS.clear();
        DIAGNOSTIC_LAST_LOG.clear();
    }

    public static ComponentResult translateComponent(Component component, String surface, String role) {
        return translateComponent(component, surface, role, false);
    }

    public static ComponentResult translateComponent(Component component, String surface, String role,
                                                     boolean fixedLayout) {
        if (component == null) {
            return new ComponentResult(component, false, false);
        }
        return translateComponents(List.of(component), surface, role, fixedLayout).asSingle(component);
    }

    public static ComponentListResult translateComponents(List<Component> components, String surface, String role,
                                                          boolean fixedLayout) {
        return translateComponents(components, surface, role, fixedLayout, "");
    }

    public static ComponentListResult translateComponents(List<Component> components, String surface, String role,
                                                          boolean fixedLayout, String context) {
        TranslationDocument document = TranslationDocument.fromComponents(components, surface, role, fixedLayout, context);
        if (!document.hasEnglish()) {
            return new ComponentListResult(components, false, false);
        }

        List<Component> cached = restoreFromCache(document);
        if (cached != null) {
            return new ComponentListResult(cached, true, true);
        }

        requestAsync(document);
        return new ComponentListResult(components, true, false);
    }

    public static CompletableFuture<ComponentListResult> translateComponentsAsync(List<Component> components,
                                                                                  String surface,
                                                                                  String role,
                                                                                  boolean fixedLayout,
                                                                                  String context) {
        TranslationDocument document = TranslationDocument.fromComponents(components, surface, role, fixedLayout, context);
        if (!document.hasEnglish()) {
            return CompletableFuture.completedFuture(new ComponentListResult(components, false, false));
        }

        List<Component> cached = restoreFromCache(document);
        if (cached != null) {
            return CompletableFuture.completedFuture(new ComponentListResult(cached, true, true));
        }

        return requestAsync(document).thenApply(restored -> {
            if (restored == null) {
                return new ComponentListResult(components, true, false);
            }
            return new ComponentListResult(restored, true, true);
        });
    }

    public static ComponentListResult getCachedComponents(List<Component> components, String surface, String role,
                                                          boolean fixedLayout, String context) {
        TranslationDocument document = TranslationDocument.fromComponents(components, surface, role, fixedLayout, context);
        if (!document.hasEnglish()) {
            return new ComponentListResult(components, false, false);
        }
        List<Component> cached = restoreFromCache(document);
        if (cached != null) {
            return new ComponentListResult(cached, true, true);
        }
        return new ComponentListResult(components, true, false);
    }

    public static CompletableFuture<String> translateTextAsync(String text, String surface, String role,
                                                               String context, String layoutSignature,
                                                               String styleSignature) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }
        String effectiveSurface = surface == null || surface.isBlank() ? "manager.direct" : surface;
        String effectiveRole = role == null || role.isBlank() ? "text" : role;
        TranslationDocument document = TranslationDocument
                .fromComponents(List.of(Component.literal(text)), effectiveSurface, effectiveRole, false, context)
                .withSignatures(layoutSignature, styleSignature);
        if (!document.hasEnglish()) {
            return CompletableFuture.completedFuture(text);
        }

        List<Component> cached = restoreFromCache(document);
        if (cached != null && !cached.isEmpty()) {
            return CompletableFuture.completedFuture(cached.get(0).getString());
        }

        return requestAsync(document).thenApply(restored -> {
            if (restored == null || restored.isEmpty()) {
                return null;
            }
            return restored.get(0).getString();
        });
    }

    public static String getCachedText(String text, String surface, String role, String context,
                                       String layoutSignature, String styleSignature) {
        if (text == null || text.isBlank()) {
            return null;
        }
        TranslationDocument document = TranslationDocument
                .fromComponents(List.of(Component.literal(text)),
                        surface == null || surface.isBlank() ? "manager.direct" : surface,
                        role == null || role.isBlank() ? "text" : role,
                        false,
                        context)
                .withSignatures(layoutSignature, styleSignature);
        List<Component> restored = restoreFromCache(document);
        if (restored == null || restored.isEmpty()) {
            return null;
        }
        String translated = restored.get(0).getString();
        return translated == null || translated.isBlank() ? null : translated;
    }

    // ------------------------------------------------------------------
    // Test hooks (wire format)
    // ------------------------------------------------------------------

    public static String serializeForTest(List<Component> components, String surface, String role, boolean fixedLayout) {
        return TranslationDocument.fromComponents(components, surface, role, fixedLayout, "").document();
    }

    public static String requestPayloadForTest(List<Component> components, String surface, String role, boolean fixedLayout) {
        return TranslationDocument.fromComponents(components, surface, role, fixedLayout, "").requestPayload();
    }

    public static String requestPayloadForTest(List<Component> components, String surface, String role,
                                               boolean fixedLayout, String context) {
        return TranslationDocument.fromComponents(components, surface, role, fixedLayout, context).requestPayload();
    }

    @Nullable
    public static List<Component> restoreForTest(List<Component> components, String surface, String role,
                                                 boolean fixedLayout, String translatedDocument) {
        TranslationDocument document = TranslationDocument.fromComponents(components, surface, role, fixedLayout, "");
        TranslationDocument.RestoreOutcome outcome = document.restore(translatedDocument);
        return outcome == null ? null : outcome.components();
    }

    @Nullable
    public static List<Component> restorePlainFallbackForTest(List<Component> components, String surface, String role,
                                                              boolean fixedLayout, String translatedText) {
        return restoreForTest(components, surface, role, fixedLayout, translatedText);
    }

    @Nullable
    public static List<Component> restoreWithPlainFallbackForTest(List<Component> components, String surface, String role,
                                                                  boolean fixedLayout, String translatedText) {
        return restoreForTest(components, surface, role, fixedLayout, translatedText);
    }

    public static boolean cacheRestoredTranslation(Component source,
                                                   Component translated,
                                                   String surface,
                                                   String role,
                                                   boolean fixedLayout,
                                                   String context) {
        if (source == null || translated == null || translated.getString().isBlank()) {
            return false;
        }
        TranslationDocument sourceDocument =
                TranslationDocument.fromComponents(List.of(source), surface, role, fixedLayout, context);
        if (!sourceDocument.hasEnglish()) {
            return false;
        }
        TranslationDocument translatedDocument =
                TranslationDocument.fromComponents(List.of(translated), surface, role, fixedLayout, context);
        TranslationDocument.RestoreOutcome outcome =
                sourceDocument.restore(translatedDocument.canonicalPayloadFromSourceWire());
        if (outcome == null || outcome.isPartial() || outcome.components().isEmpty()) {
            return false;
        }
        cacheSuccessfulPayload(sourceDocument, outcome.canonicalPayload(), SimpleTranslateMod.getRuntimeRevision(),
                ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
        return true;
    }

    /** Kept for callers that need stable style signatures (e.g. sign documents). */
    static String styleSignature(Style style) {
        return StyleSignatures.of(style);
    }

    // ------------------------------------------------------------------
    // Request orchestration
    // ------------------------------------------------------------------

    /** Bounded per-session attempts to upgrade partially-translated cached payloads. */
    private static final Map<String, Integer> UPGRADE_ATTEMPTS = new ConcurrentHashMap<>();
    private static final int MAX_UPGRADE_ATTEMPTS = 2;
    private static final int MAX_UPGRADE_ENTRIES = 4096;

    @Nullable
    private static List<Component> restoreFromCache(TranslationDocument document) {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null || !ModConfig.CACHE_ENABLED.get()) {
            return null;
        }
        String cacheKey = document.cacheKey();
        String cached = cache.get(cacheKey).orElse(null);
        if (cached == null) {
            return restoreCompatibleSourceCache(document, cache);
        }
        TranslationDocument.RestoreOutcome outcome = document.restore(cached);
        if (outcome != null) {
            if (outcome.isPartial()) {
                if (shouldServePartialCachedRestore(document, outcome)) {
                    maybeUpgradePartialTranslation(document);
                    return outcome.components();
                }
                cache.remove(cacheKey);
                cache.save();
                maybeUpgradePartialTranslation(document);
                return restoreCompatibleSourceCache(document, cache);
            }
            return outcome.components();
        }
        cache.remove(cacheKey);
        cache.save();
        return restoreCompatibleSourceCache(document, cache);
    }

    @Nullable
    private static List<Component> restoreCompatibleSourceCache(TranslationDocument document, TranslationCache cache) {
        if (document == null || cache == null || !isCompatibleSourceCacheSurface(document.surface())) {
            return null;
        }
        for (String candidate : cache.getCompatibleBySource(document.cacheKey())) {
            TranslationDocument.RestoreOutcome outcome = document.restore(candidate);
            if (outcome == null || outcome.components().isEmpty()) {
                continue;
            }
            if (outcome.isPartial() && !shouldServePartialCachedRestore(document, outcome)) {
                continue;
            }
            cacheSuccessfulPayload(document, outcome.canonicalPayload(), SimpleTranslateMod.getRuntimeRevision(),
                    ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
            if (outcome.isPartial()) {
                maybeUpgradePartialTranslation(document);
            }
            return outcome.components();
        }
        return null;
    }

    private static boolean shouldServePartialCachedRestore(TranslationDocument document,
                                                           TranslationDocument.RestoreOutcome outcome) {
        return document != null
                && outcome != null
                && isLenientPartialSurface(document.surface())
                && outcome.translatedLines() > 0;
    }

    private static boolean isCompatibleSourceCacheSurface(String surface) {
        if (surface == null) {
            return false;
        }
        String value = surface.toLowerCase(Locale.ROOT);
        return value.startsWith("tooltip.item_context.")
                || value.startsWith("hover.overlay")
                || value.startsWith("chat.context.batch")
                || value.startsWith("chat.system")
                || value.startsWith("scoreboard.")
                || value.startsWith("bossbar.")
                || value.startsWith("entity.")
                || value.startsWith("text_display.")
                || value.startsWith("advancement.");
    }

    /**
     * A cached payload still contains untranslated/guarded lines (e.g. the model
     * skipped wrapped continuation lines). Re-request in the background a bounded
     * number of times so the cache heals itself instead of staying mixed forever.
     */
    private static void maybeUpgradePartialTranslation(TranslationDocument document) {
        String key = document.cacheKey();
        if (key == null || key.isBlank()) {
            return;
        }
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null && cache.getEntry(key).map(TranslationCache.CacheViewEntry::editedByPlayer).orElse(false)) {
            // Never overwrite a player-edited cache entry with a model retry.
            return;
        }
        if (UPGRADE_ATTEMPTS.size() > MAX_UPGRADE_ENTRIES) {
            UPGRADE_ATTEMPTS.clear();
        }
        int attempts = UPGRADE_ATTEMPTS.getOrDefault(key, 0);
        if (attempts >= MAX_UPGRADE_ATTEMPTS) {
            if (cache != null) {
                cache.remove(key);
                cache.save();
            }
            return;
        }
        TranslationLane lane = TranslationLanes.forSurface(document.surface());
        TranslationTask probe = document.task();
        if (lane.isPending(probe) || lane.isThrottled(probe)) {
            return;
        }
        UPGRADE_ATTEMPTS.put(key, attempts + 1);
        infoLimited("partial-upgrade", document,
                "Direct translation retrying partial cached result surface={} key={} source={}");
        requestAsync(document);
    }

    private static CompletableFuture<List<Component>> requestAsync(TranslationDocument document) {
        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            infoLimited("manager-not-ready", document,
                    "Direct translation skipped reason=manager-not-ready surface={} key={} source={}");
            return CompletableFuture.completedFuture(null);
        }
        if (!RecoveryPolicy.shouldAttempt(document.cacheKey())) {
            infoLimited("negative-cache", document,
                    "Direct translation skipped reason=negative-cache surface={} key={} source={}");
            return CompletableFuture.completedFuture(null);
        }

        TranslationTask task = document.task();
        TranslationLane lane = TranslationLanes.forSurface(document.surface());
        if (!lane.begin(task, FAILURE_RETRY_MS)) {
            infoLimited("pending-or-cooldown", document,
                    "Direct translation skipped reason=pending-or-cooldown surface={} key={} source={}");
            return CompletableFuture.completedFuture(null);
        }
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        String sourceLanguage = ModConfig.SOURCE_LANGUAGE.get();
        String targetLanguage = ModConfig.TARGET_LANGUAGE.get();
        if (rememberQueuedLogKey(document.cacheKey())) {
            SimpleTranslateMod.getLogger().debug(
                    "Direct translation queued surface={} key={} source={}",
                    document.surface(), document.cacheKeySummary(), document.sourceSummary());
        }

        int tokenMultiplier = initialTokenMultiplier(document);
        return requestTranslatedDocument(manager, document, tokenMultiplier).thenCompose(translatedDocument ->
                finishTranslatedDocument(manager, document, translatedDocument, tokenMultiplier, runtimeRevision,
                        sourceLanguage, targetLanguage, task, lane))
                .exceptionally(error -> {
                    lane.fail(task, FAILURE_RETRY_MS);
                    warnLimited(error == null ? "request-error" : error.getClass().getSimpleName(), document,
                            "Direct translation failed reason="
                                    + (error == null ? "request-error" : error.getClass().getSimpleName())
                                    + " surface={} key={} source={}", error);
                    return null;
                });
    }

    private static int initialTokenMultiplier(TranslationDocument document) {
        if (document == null || document.lines() == null) {
            return 1;
        }
        int editableLines = 0;
        for (TranslationDocument.LineSpec line : document.lines()) {
            if (line.hasEditable()) {
                editableLines++;
            }
        }
        String surface = document.surface() == null ? "" : document.surface().toLowerCase(Locale.ROOT);
        if (surface.startsWith("chat.context.batch")) {
            if (editableLines >= 8) {
                return 2;
            }
            return 1;
        }
        if (!surface.startsWith("tooltip.") && !surface.startsWith("hover.")) {
            return 1;
        }
        if (editableLines >= 14) {
            return 3;
        }
        if (editableLines >= 8) {
            return 2;
        }
        return 1;
    }

    private static boolean isLenientPartialSurface(String surface) {
        if (surface == null) {
            return false;
        }
        String value = surface.toLowerCase(Locale.ROOT);
        return value.startsWith("tooltip.") || value.startsWith("hover.")
                || value.startsWith("chat.context.batch");
    }

    private static CompletableFuture<List<Component>> finishTranslatedDocument(TranslationManager manager,
                                                                               TranslationDocument document,
                                                                               String translatedDocument,
                                                                               int tokenMultiplier,
                                                                               long runtimeRevision,
                                                                               String sourceLanguage,
                                                                               String targetLanguage,
                                                                               TranslationTask task,
                                                                               TranslationLane lane) {
        try {
            if (!SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)) {
                lane.finish(task);
                infoLimited("stale-response", document,
                        "Direct translation ignored stale response surface={} key={} source={}");
                return CompletableFuture.completedFuture(null);
            }
            if (translatedDocument == null || translatedDocument.isBlank()) {
                lane.fail(task, FAILURE_RETRY_MS);
                RecoveryPolicy.recordRejected(document.cacheKey());
                warnLimited("blank-response", document,
                        "Direct translation failed reason=blank-response surface={} key={} source={}");
                return CompletableFuture.completedFuture(null);
            }
            TranslationDocument.RestoreOutcome outcome = document.restore(translatedDocument);
            if (outcome == null) {
                String detail = document.failureSummary(translatedDocument);
                lane.fail(task, FAILURE_RETRY_MS);
                RecoveryPolicy.recordRejected(document.cacheKey());
                warnLimited("validation-failed:" + detail, document,
                        "Direct translation rejected reason=validation-failed detail=" + detail
                                + " surface={} key={} source={}");
                return CompletableFuture.completedFuture(null);
            }
            if (outcome.isPartial()) {
                if (tokenMultiplier < 3) {
                    int nextMultiplier = tokenMultiplier + 1;
                    infoLimited("partial-restore-retry", document,
                            "Direct translation retrying incomplete response with expanded token budget surface={} key={} source={}");
                    return requestTranslatedDocument(manager, document, nextMultiplier).thenCompose(retryDocument ->
                            finishTranslatedDocument(manager, document, retryDocument, nextMultiplier, runtimeRevision,
                                    sourceLanguage, targetLanguage, task, lane));
                }
                if (isLenientPartialSurface(document.surface()) && outcome.translatedLines() > 0) {
                    cacheSuccessfulPayload(document, outcome.canonicalPayload(), runtimeRevision,
                            sourceLanguage, targetLanguage);
                    RecoveryPolicy.recordSuccess(document.cacheKey());
                    lane.finish(task);
                    return CompletableFuture.completedFuture(outcome.components());
                }
                infoLimited("partial-restore", document,
                        "Direct translation partially degraded surface={} key={} source={}");
                maybeUpgradePartialTranslation(document);
                lane.finish(task);
                return CompletableFuture.completedFuture(null);
            }
            cacheSuccessfulPayload(document, outcome.canonicalPayload(), runtimeRevision,
                    sourceLanguage, targetLanguage);
            RecoveryPolicy.recordSuccess(document.cacheKey());
            lane.finish(task);
            return CompletableFuture.completedFuture(outcome.components());
        } catch (Exception e) {
            lane.fail(task, FAILURE_RETRY_MS);
            warnLimited("exception", document,
                    "Direct translation validation failed surface={} key={} source={}", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static void cacheSuccessfulPayload(TranslationDocument document, String payload, long runtimeRevision,
                                               String sourceLanguage, String targetLanguage) {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null && ModConfig.CACHE_ENABLED.get()
                && SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                && languageMatches(sourceLanguage, ModConfig.SOURCE_LANGUAGE.get())
                && languageMatches(targetLanguage, ModConfig.TARGET_LANGUAGE.get())) {
            cache.put(document.cacheKey(), payload, document.sourceText(), TranslationCache.displayTextFromValue(payload));
            cache.save();
        }
    }

    private static CompletableFuture<String> requestTranslatedDocument(TranslationManager manager,
                                                                       TranslationDocument document) {
        return requestTranslatedDocument(manager, document, 1);
    }

    private static CompletableFuture<String> requestTranslatedDocument(TranslationManager manager,
                                                                       TranslationDocument document,
                                                                       int maxTokenMultiplier) {
        if (isBatchCandidate(document)) {
            return DirectBatcher.enqueue(manager, document);
        }
        return manager.translateFormattedDocument(document.requestPayload(), document.surface(), maxTokenMultiplier);
    }

    private static boolean isBatchCandidate(TranslationDocument document) {
        if (document == null || document.fixedLayout()) {
            return false;
        }
        String surface = document.surface() == null ? "" : document.surface().toLowerCase(Locale.ROOT);
        if (surface.startsWith("hud.title") || surface.startsWith("hud.actionbar")
                || surface.startsWith("tooltip.") || surface.startsWith("hover.")
                || surface.startsWith("sign.")) {
            return false;
        }
        if (!(surface.startsWith("chat.system")
                || surface.startsWith("scoreboard.")
                || surface.startsWith("advancement.")
                || surface.startsWith("bossbar.")
                || surface.startsWith("entity.")
                || surface.startsWith("text_display."))) {
            return false;
        }
        return document.requestPayload().length() <= MAX_BATCH_CHARS / 2;
    }

    private static boolean rememberQueuedLogKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (QUEUED_LOGGED_KEYS.size() > MAX_QUEUED_LOG_KEYS) {
            synchronized (QUEUED_LOGGED_KEYS) {
                if (QUEUED_LOGGED_KEYS.size() > MAX_QUEUED_LOG_KEYS) {
                    QUEUED_LOGGED_KEYS.clear();
                }
            }
        }
        return QUEUED_LOGGED_KEYS.add(key);
    }

    private static boolean languageMatches(String expected, String current) {
        return expected == null ? current == null : expected.equals(current);
    }

    private static void infoLimited(String reason, TranslationDocument document, String message) {
        if (!shouldLog(reason, document)) {
            return;
        }
        SimpleTranslateMod.getLogger().debug(message, document.surface(), document.cacheKeySummary(),
                document.sourceSummary());
    }

    private static void warnLimited(String reason, TranslationDocument document, String message) {
        warnLimited(reason, document, message, null);
    }

    private static void warnLimited(String reason, TranslationDocument document, String message, Throwable error) {
        if (!shouldLog(reason, document)) {
            return;
        }
        if (error == null) {
            SimpleTranslateMod.getLogger().warn(message, document.surface(), document.cacheKeySummary(),
                    document.sourceSummary());
        } else {
            SimpleTranslateMod.getLogger().warn(message, document.surface(), document.cacheKeySummary(),
                    document.sourceSummary(), error);
        }
    }

    private static boolean shouldLog(String reason, TranslationDocument document) {
        String key = (reason == null ? "unknown" : reason) + "|" + (document == null ? "" : document.surface());
        long now = System.currentTimeMillis();
        Long previous = DIAGNOSTIC_LAST_LOG.get(key);
        if (previous != null && now - previous < DIAGNOSTIC_REPEAT_MS) {
            return false;
        }
        DIAGNOSTIC_LAST_LOG.put(key, now);
        if (DIAGNOSTIC_LAST_LOG.size() > 2048) {
            Set<String> remove = new HashSet<>();
            for (Map.Entry<String, Long> entry : DIAGNOSTIC_LAST_LOG.entrySet()) {
                if (now - entry.getValue() > DIAGNOSTIC_REPEAT_MS * 4) {
                    remove.add(entry.getKey());
                }
            }
            remove.forEach(DIAGNOSTIC_LAST_LOG::remove);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Request batching
    // ------------------------------------------------------------------

    private static final class DirectBatcher {
        private static final Object LOCK = new Object();
        private static final List<BatchItem> PENDING = new ArrayList<>();
        private static ScheduledFuture<?> scheduled;

        private DirectBatcher() {
        }

        private static CompletableFuture<String> enqueue(TranslationManager manager, TranslationDocument document) {
            CompletableFuture<String> future = new CompletableFuture<>();
            synchronized (LOCK) {
                PENDING.add(new BatchItem(manager, document, future));
                int totalChars = 0;
                for (BatchItem item : PENDING) {
                    totalChars += item.document().requestPayload().length();
                }
                if (PENDING.size() >= MAX_BATCH_ITEMS || totalChars >= MAX_BATCH_CHARS) {
                    flushLocked();
                } else if (scheduled == null || scheduled.isDone()) {
                    scheduled = BATCH_EXECUTOR.schedule(() -> {
                        synchronized (LOCK) {
                            flushLocked();
                        }
                    }, BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
                }
            }
            return future;
        }

        private static void flushLocked() {
            if (PENDING.isEmpty()) {
                return;
            }
            if (scheduled != null) {
                scheduled.cancel(false);
                scheduled = null;
            }
            List<BatchItem> items = new ArrayList<>(PENDING);
            PENDING.clear();
            if (items.size() == 1) {
                BatchItem item = items.get(0);
                item.manager().translateFormattedDocument(item.document().requestPayload(), item.document().surface())
                        .whenComplete((result, error) -> completeSingle(item, result, error));
                return;
            }

            List<String> payloads = new ArrayList<>(items.size());
            for (BatchItem item : items) {
                payloads.add(item.document().requestPayload());
            }
            items.get(0).manager().translateFormattedDocument(WireCodec.batchPayload(payloads),
                            items.get(0).document().surface())
                    .whenComplete((result, error) -> completeBatch(items, result, error));
        }

        private static void completeSingle(BatchItem item, String result, Throwable error) {
            if (error != null) {
                item.future().completeExceptionally(error);
            } else {
                item.future().complete(result);
            }
        }

        private static void completeBatch(List<BatchItem> items, String result, Throwable error) {
            if (error != null || result == null || result.isBlank()) {
                for (BatchItem item : items) {
                    if (error != null) {
                        item.future().completeExceptionally(error);
                    } else {
                        item.future().complete(null);
                    }
                }
                return;
            }
            Map<Integer, String> payloads = WireCodec.parseBatchResponse(result);
            SimpleTranslateMod.getLogger().debug(
                    "Direct batch completed batchSize={} returnedItems={}",
                    items.size(), payloads.size());
            if (payloads.isEmpty() && items.size() > 1) {
                // The model ignored the [ITEM k] headers; redispatch each item
                // individually once instead of failing the whole burst.
                SimpleTranslateMod.getLogger().debug(
                        "Direct batch response missing item headers; redispatching {} items individually",
                        items.size());
                for (BatchItem item : items) {
                    item.manager().translateFormattedDocument(item.document().requestPayload(),
                                    item.document().surface())
                            .whenComplete((single, singleError) -> completeSingle(item, single, singleError));
                }
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                String itemPayload = payloads.get(i);
                if (itemPayload == null || itemPayload.isBlank()) {
                    // Single-item responses sometimes omit headers entirely.
                    itemPayload = items.size() == 1 ? result : null;
                }
                items.get(i).future().complete(itemPayload);
            }
        }

        private record BatchItem(TranslationManager manager, TranslationDocument document,
                                 CompletableFuture<String> future) {
        }
    }

    // ------------------------------------------------------------------
    // Result types
    // ------------------------------------------------------------------

    public static final class ComponentResult {
        public final Component component;
        public final boolean handled;
        public final boolean translated;

        private ComponentResult(Component component, boolean handled, boolean translated) {
            this.component = component;
            this.handled = handled;
            this.translated = translated;
        }
    }

    public static final class ComponentListResult {
        public final List<Component> components;
        public final boolean handled;
        public final boolean translated;

        private ComponentListResult(List<Component> components, boolean handled, boolean translated) {
            this.components = components;
            this.handled = handled;
            this.translated = translated;
        }

        private ComponentResult asSingle(Component fallback) {
            if (components == null || components.size() != 1) {
                return new ComponentResult(fallback, handled, translated);
            }
            return new ComponentResult(components.get(0), handled, translated);
        }
    }
}
