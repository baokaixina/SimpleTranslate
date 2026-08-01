package com.yourname.simpletranslate.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Micro-batch scheduler for Component JSON requests on approved high-frequency
 * surfaces. Owns the batch executor, queue, and combined-document dispatch;
 * reliable single-item recovery remains in {@link JsonPassthroughPipeline}.
 */
public final class ComponentJsonBatcher {
    static final int MAX_BATCH_ITEMS = 6;
    static final int MAX_BATCH_CHARS = 9000;
    static final int MAX_BATCH_CONTEXT_CODE_POINTS = 1800;

    private static final ScheduledExecutorService BATCH_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "SimpleTranslate-JsonBatch");
                thread.setDaemon(true);
                return thread;
            });

    private static final Object LOCK = new Object();
    private static final Map<String, List<JsonPassthroughPipeline.BatchItem>> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, ScheduledFuture<?>> SCHEDULED = new ConcurrentHashMap<>();

    private ComponentJsonBatcher() {
    }

    static long batchDelayMs() {
        return Math.max(0L, Math.min(200L, ModConfig.API_DIRECT_BATCH_DELAY_MS.get()));
    }

    static CompletableFuture<List<Component>> enqueue(JsonPassthroughPipeline.BatchItem item) {
        String group = Surface.normalize(item.surface()) + "|"
                + item.role() + "|"
                + TranslationTextDetector.languagePairKey(item.sourceLanguage(), item.targetLanguage());
        List<JsonPassthroughPipeline.BatchItem> ready = null;
        synchronized (LOCK) {
            List<JsonPassthroughPipeline.BatchItem> items = PENDING.computeIfAbsent(group, ignored -> new ArrayList<>());
            items.add(item);
            int chars = 0;
            for (JsonPassthroughPipeline.BatchItem queued : items) {
                chars += queued.sourceJson().length();
            }
            if (items.size() >= MAX_BATCH_ITEMS || chars >= MAX_BATCH_CHARS) {
                ready = detachLocked(group);
            } else if (!SCHEDULED.containsKey(group)) {
                SCHEDULED.put(group, BATCH_EXECUTOR.schedule(
                        () -> flush(group), batchDelayMs(), TimeUnit.MILLISECONDS));
            }
        }
        if (ready != null && !ready.isEmpty()) {
            dispatchDetached(ready);
        }
        return item.future();
    }

    private static void flush(String group) {
        List<JsonPassthroughPipeline.BatchItem> items;
        synchronized (LOCK) {
            items = detachLocked(group);
        }
        processDetached(items);
    }

    private static List<JsonPassthroughPipeline.BatchItem> detachLocked(String group) {
        List<JsonPassthroughPipeline.BatchItem> items = PENDING.remove(group);
        ScheduledFuture<?> scheduled = SCHEDULED.remove(group);
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        return items == null ? List.of() : List.copyOf(items);
    }

    private static void dispatchDetached(List<JsonPassthroughPipeline.BatchItem> items) {
        try {
            BATCH_EXECUTOR.execute(() -> processDetached(items));
        } catch (RuntimeException exception) {
            for (JsonPassthroughPipeline.BatchItem item : items) {
                item.lane().finish(item.lease());
                item.future().complete(null);
            }
        }
    }

    private static void processDetached(List<JsonPassthroughPipeline.BatchItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        // Items captured under an older smart-context revision cannot share
        // a response with the current projection policy.
        List<JsonPassthroughPipeline.BatchItem> currentItems = new ArrayList<>(items.size());
        for (JsonPassthroughPipeline.BatchItem item : items) {
            if (item.textContextRevision() >= 0L
                    && !TextContextMemory.isRevisionCurrent(item.textContextRevision())) {
                completeFromFuture(item, JsonPassthroughPipeline.sendSingle(item));
            } else {
                currentItems.add(item);
            }
        }
        if (currentItems.isEmpty()) {
            return;
        }
        List<ProjectedBatchItem> projected = new ArrayList<>(currentItems.size());
        JsonArray combinedSemantic = new JsonArray();
        for (JsonPassthroughPipeline.BatchItem item : currentItems) {
            ComponentVisualProjection projection = ComponentVisualProjection.project(
                    item.sourceJson(), item.targetLanguage());
            if (projection == null || !projection.hasSlots()) {
                item.lane().finish(item.lease());
                item.future().complete(null);
                continue;
            }
            projected.add(new ProjectedBatchItem(item, projection));
            for (JsonElement element : projection.semanticRoot()) {
                combinedSemantic.add(element.deepCopy());
            }
        }
        if (projected.isEmpty()) {
            return;
        }
        if (projected.size() == 1) {
            JsonPassthroughPipeline.BatchItem item = projected.get(0).item();
            completeFromFuture(item, JsonPassthroughPipeline.sendSingle(item));
            return;
        }
        JsonPassthroughPipeline.BatchItem first = projected.get(0).item();
        String document = combinedSemantic.toString();
        StringBuilder batchContext = new StringBuilder(
                "Combined Component batch. Each item keeps opaque visuals and only classified live values local. "
                        + "A <number> in full_source is a client-owned gap and must not be emitted; ordinary digits "
                        + "remain semantic request text and must appear exactly once in the translated sentence:\n");
        int contextSlot = 0;
        for (int itemIndex = 0; itemIndex < projected.size(); itemIndex++) {
            ProjectedBatchItem batchItem = projected.get(itemIndex);
            int endSlot = contextSlot + batchItem.projection().slotCount();
            batchContext.append("item ").append(itemIndex)
                    .append(" response_slots=").append(contextSlot).append("..").append(endSlot - 1)
                    .append(" full_source:\n")
                    .append(JsonPassthroughPipeline.semanticPromptSourceShape(batchItem.item().originals()))
                    .append('\n');
            contextSlot = endSlot;
        }
        if (batchContext.codePointCount(0, batchContext.length())
                > MAX_BATCH_CONTEXT_CODE_POINTS) {
            for (ProjectedBatchItem item : projected) {
                completeFromFuture(item.item(), JsonPassthroughPipeline.sendSingle(item.item()));
            }
            return;
        }
        TextContextMemory.PromptMetadata promptMetadata = TextContextMemory.buildPromptMetadata(
                batchContext.toString().stripTrailing(), first.surface(), first.role(), document, true,
                first.sourceLanguage(), first.targetLanguage());
        try {
            first.manager().translateComponentJson(
                            JsonPassthroughPipeline.buildUserPayload(document, ""), first.surface(), 1,
                            first.sourceLanguage(), first.targetLanguage(), promptMetadata.json())
                    .whenComplete((response, error) -> completeBatch(
                            List.copyOf(projected), response, error,
                            promptMetadata.contextRevision()));
        } catch (RuntimeException exception) {
            SimpleTranslateMod.getLogger().warn(
                    "JSON micro-batch launch failed itemCount={} reason={}",
                    projected.size(), exception.getClass().getSimpleName());
            for (ProjectedBatchItem item : projected) {
                failBatchItem(item.item());
            }
        }
    }

    private static void completeBatch(List<ProjectedBatchItem> items,
                                       String response, Throwable error,
                                       long textContextRevision) {
        if (error != null || response == null || response.isBlank()) {
            SimpleTranslateMod.getLogger().warn(
                    "JSON micro-batch transport failed itemCount={} reason={}",
                    items.size(), error == null ? "blank-response"
                            : error.getClass().getSimpleName());
            for (ProjectedBatchItem projected : items) {
                failBatchItem(projected.item());
            }
            return;
        }
        JsonArray translatedSlots = null;
        try {
            JsonElement parsed = JsonParser.parseString(response.trim());
            if (parsed.isJsonArray()) {
                translatedSlots = parsed.getAsJsonArray();
            }
        } catch (Exception ignored) {
            translatedSlots = null;
        }
        int expectedSlots = items.stream()
                .mapToInt(item -> item.projection().slotCount()).sum();
        if (translatedSlots == null || translatedSlots.size() != expectedSlots) {
            SimpleTranslateMod.getLogger().warn(
                    "JSON micro-batch rejected itemCount={} slotsExpected={} slotsActual={} reason={}",
                    items.size(), expectedSlots,
                    translatedSlots == null ? -1 : translatedSlots.size(),
                    error == null ? "invalid-json-or-count" : error.getClass().getSimpleName());
            for (ProjectedBatchItem projected : items) {
                completeFromFuture(projected.item(), JsonPassthroughPipeline.sendSingle(projected.item()));
            }
            return;
        }

        int offset = 0;
        for (ProjectedBatchItem projected : items) {
            JsonPassthroughPipeline.BatchItem item = projected.item();
            int end = offset + projected.projection().slotCount();
            JsonArray responseSlice = new JsonArray();
            for (int index = offset; index < end; index++) {
                responseSlice.add(translatedSlots.get(index).deepCopy());
            }
            offset = end;
            try {
                List<Component> semanticSlice = new ArrayList<>(responseSlice.size());
                for (JsonElement element : responseSlice) {
                    Component component = ComponentJsonCompat.fromJson(element);
                    if (component == null) {
                        semanticSlice = null;
                        break;
                    }
                    semanticSlice.add(component);
                }
                if (semanticSlice == null) {
                    completeFromFuture(item, JsonPassthroughPipeline.sendSingle(item));
                    continue;
                }
                JsonArray rebuilt = projected.projection().rebuildResponse(responseSlice);
                String cacheTemplate = rebuilt == null ? null : rebuilt.toString();
                List<Component> slice = rebuilt == null ? null
                        : JsonPassthroughPipeline.finalizeTranslatedTree(
                                rebuilt.deepCopy(), item.originals(), item.surface(), response);
                if (slice == null || cacheTemplate == null
                        || !JsonPassthroughPipeline.cacheTemplateMatchesSourceMarkers(
                        item.sourceJson(), cacheTemplate)) {
                    completeFromFuture(item, JsonPassthroughPipeline.sendSingle(item));
                    continue;
                }
                List<Component> accepted = JsonPassthroughPipeline.acceptRestored(
                        item.originals(), item.sourceJson(), cacheTemplate, slice,
                        item.cacheKey(), item.lane(), item.lease(), item.runtimeRevision(),
                        textContextRevision, item.sourceLanguage(), item.targetLanguage());
                item.future().complete(accepted);
            } catch (Exception exception) {
                completeFromFuture(item, JsonPassthroughPipeline.sendSingle(item));
            }
        }
    }

    private record ProjectedBatchItem(JsonPassthroughPipeline.BatchItem item,
                                      ComponentVisualProjection projection) {
    }

    private static void failBatchItem(JsonPassthroughPipeline.BatchItem item) {
        item.lane().fail(item.lease(), JsonPassthroughPipeline.FAILURE_RETRY_MS);
        RecoveryPolicy.recordRejected(item.cacheKey());
        item.future().complete(null);
    }

    private static void completeFromFuture(JsonPassthroughPipeline.BatchItem item,
                                           CompletableFuture<List<Component>> future) {
        future.whenComplete((result, error) -> {
            if (error != null) {
                item.future().completeExceptionally(error);
            } else {
                item.future().complete(result);
            }
        });
    }

    static void clear() {
        List<ScheduledFuture<?>> scheduled;
        List<JsonPassthroughPipeline.BatchItem> queued = new ArrayList<>();
        synchronized (LOCK) {
            scheduled = List.copyOf(SCHEDULED.values());
            SCHEDULED.clear();
            for (List<JsonPassthroughPipeline.BatchItem> items : PENDING.values()) {
                queued.addAll(items);
            }
            PENDING.clear();
        }
        for (ScheduledFuture<?> future : scheduled) {
            future.cancel(false);
        }
        for (JsonPassthroughPipeline.BatchItem item : queued) {
            item.lane().finish(item.lease());
            item.future().complete(null);
        }
    }

    static void shutdown() {
        clear();
        BATCH_EXECUTOR.shutdownNow();
    }
}
