package com.yourname.simpletranslate.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.transport.TranslationLane;
import com.yourname.simpletranslate.transport.TranslationLanes;
import com.yourname.simpletranslate.transport.TranslationManager;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON-passthrough translation pipeline for Minecraft components.
 *
 * <p>Serializes each {@link Component} to Minecraft Component JSON, sends the
 * JSON array to the model, and deserializes the translated JSON array back to
 * Component objects. Hidden hover events are stripped from request JSON and
 * reattached from the original components after translation, so ordinary
 * surfaces translate only visible text while hover tooltips remain controlled by
 * the dedicated tooltip path. The model sees the visible structure (colors,
 * click events, nesting) and only swaps text content — no {@code <n>} tags,
 * no wire protocol, no style-restoration logic.</p>
 *
 * <p>Every game translation surface uses this pipeline. A response is accepted
 * when it is a parseable component array with the same top-level size as the
 * request. Styles and nested structure are intentionally left to the model.</p>
 */
public final class JsonPassthroughPipeline {
    private static final long FAILURE_RETRY_MS = 6000L;
    private static final int MAX_BATCH_ITEMS = 6;
    private static final int MAX_BATCH_CHARS = 9000;
    private static final ScheduledExecutorService BATCH_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "SimpleTranslate-JsonBatch");
                thread.setDaemon(true);
                return thread;
            });

    private JsonPassthroughPipeline() {
    }

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:[.,:]\\d+)*%?");

    private static final Pattern DYNAMIC_MARKER_PATTERN = Pattern.compile("\u27e6N(\\d+)\u27e7");

    /**
     * Extracts dynamic numbers from a text string, replacing them with
     * {@code ⟦N0⟧}, {@code ⟦N1⟧}, ... markers so that changing values (e.g.
     * "Durability: 69/80" → "68/80") produce identical cache keys.
     */
    /**
     * Extracts dynamic numbers from a text string, replacing them with
     * {@code ⟦N0⟧}, {@code ⟦N1⟧}, ... markers so that changing values (e.g.
     * "Durability: 69/80" → "68/80") produce identical cache keys.
     */
    private static String normalizeNumbers(String text, List<String> values) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            values.add(matcher.group());
            matcher.appendReplacement(sb, "\u27e6N" + (values.size() - 1) + "\u27e7");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Restores dynamic numbers into a text string by replacing
     * {@code ⟦Ni⟧} markers with the original values.
     */
    private static String restoreNumbers(String text, List<String> values) {
        if (text == null || text.isEmpty() || values == null || values.isEmpty()) {
            return text;
        }
        Matcher matcher = DYNAMIC_MARKER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String replacement = index >= 0 && index < values.size() ? values.get(index) : matcher.group();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Recursively normalizes all {@code text} fields in a JSON element tree,
     * collecting dynamic values into {@code values}.
     */
    private static void normalizeNumbersInTree(JsonElement element, List<String> values) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                normalizeNumbersInTree(child, values);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject obj = element.getAsJsonObject();
        if (obj.has("text") && obj.get("text").isJsonPrimitive()) {
            String text = obj.get("text").getAsString();
            String normalized = normalizeNumbers(text, values);
            if (!normalized.equals(text)) {
                obj.addProperty("text", normalized);
            }
        }
        if (obj.has("with") && obj.get("with").isJsonArray()) {
            com.google.gson.JsonArray withArray = obj.getAsJsonArray("with");
            for (int i = 0; i < withArray.size(); i++) {
                com.google.gson.JsonElement arg = withArray.get(i);
                if (arg.isJsonPrimitive() && arg.getAsJsonPrimitive().isNumber()) {
                    values.add(arg.getAsString());
                    withArray.set(i, new com.google.gson.JsonPrimitive(
                            "\u27e6N" + (values.size() - 1) + "\u27e7"));
                }
            }
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String key = entry.getKey();
            if (!"text".equals(key) && !"with".equals(key)) {
                normalizeNumbersInTree(entry.getValue(), values);
            }
        }
    }

    /**
     * Recursively restores dynamic numbers into all {@code text} fields.
     */
    private static void restoreNumbersInTree(JsonElement element, List<String> values) {
        if (element == null || element.isJsonNull() || values == null || values.isEmpty()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                restoreNumbersInTree(child, values);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject obj = element.getAsJsonObject();
        if (obj.has("text") && obj.get("text").isJsonPrimitive()) {
            String text = obj.get("text").getAsString();
            String restored = restoreNumbers(text, values);
            if (!restored.equals(text)) {
                obj.addProperty("text", restored);
            }
        }
        if (obj.has("with") && obj.get("with").isJsonArray()) {
            com.google.gson.JsonArray withArray = obj.getAsJsonArray("with");
            for (int i = 0; i < withArray.size(); i++) {
                com.google.gson.JsonElement arg = withArray.get(i);
                if (arg.isJsonPrimitive()) {
                    String s = arg.getAsString();
                    String restored = restoreNumbers(s, values);
                    if (!restored.equals(s)) {
                        try {
                            double d = Double.parseDouble(restored);
                            withArray.set(i, new com.google.gson.JsonPrimitive(d));
                        } catch (NumberFormatException e) {
                            withArray.set(i, new com.google.gson.JsonPrimitive(restored));
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String key = entry.getKey();
            if (!"text".equals(key) && !"with".equals(key)) {
                restoreNumbersInTree(entry.getValue(), values);
            }
        }
    }

    /**
     * Extracts dynamic values from original components for later restoration.
     */
    private static List<String> extractDynamicValues(List<Component> originals) {
        List<String> values = new ArrayList<>();
        String sourceJson = serializeComponentsRaw(originals);
        if (sourceJson == null) {
            return values;
        }
        try {
            JsonElement root = JsonParser.parseString(sourceJson);
            normalizeNumbersInTree(root, values);
        } catch (Exception e) {
            SafeTranslate.logLimited("json-passthrough.extractDynamicValues", e);
        }
        return values;
    }

    private static String serializeComponentsRaw(List<Component> components) {
        try {
            JsonArray array = new JsonArray();
            for (Component component : components) {
                String json = Component.Serializer.toJson(
                        component == null ? Component.empty() : component);
                JsonElement element = JsonParser.parseString(json);
                stripHoverEvents(element);
                array.add(element);
            }
            return array.toString();
        } catch (Exception e) {
            SafeTranslate.logLimited("json-passthrough.serializeComponentsRaw", e);
            return null;
        }
    }

    public static void clearRuntimeState() {
        JsonBatcher.clear();
    }

    public static void shutdown() {
        clearRuntimeState();
        BATCH_EXECUTOR.shutdownNow();
    }

    public static boolean isEnabledForSurface(String surface) {
        return true;
    }

    public static ComponentListTranslationResult translateComponents(
            List<Component> components, String surface, String role, boolean fixedLayout, String context) {
        return SafeTranslate.guard(() -> {
            if (!ModConfig.GLOBAL_ENABLED.get()) {
                return new ComponentListTranslationResult(components, false, false);
            }
            if (components == null || components.isEmpty()) {
                return new ComponentListTranslationResult(components, false, false);
            }
            if (!hasEnglish(components)) {
                return new ComponentListTranslationResult(components, false, false);
            }



            String sourceJson = serializeComponents(components);
            if (sourceJson == null) {
                return new ComponentListTranslationResult(components, false, false);
            }
            String cacheKey = buildCacheKey(surface, sourceJson, context);
            List<Component> cached = restoreFromCache(cacheKey, components);
            if (cached == null && canUseContextlessLegacyCache(context)) {
                cached = restoreLegacyJsonCache(surface, sourceJson, cacheKey, components);
            }
            if (cached != null) {
                return new ComponentListTranslationResult(cached, true, true);
            }

            requestAsync(components, sourceJson, surface, context, cacheKey);
            return new ComponentListTranslationResult(components, true, false);
        }, new ComponentListTranslationResult(components, false, false),
                "json-passthrough.translateComponents." + surface);
    }

    public static ComponentListTranslationResult getCachedComponents(
            List<Component> components, String surface, String role, boolean fixedLayout, String context) {
        return SafeTranslate.guard(() -> {
            if (!ModConfig.GLOBAL_ENABLED.get()) {
                return new ComponentListTranslationResult(components, false, false);
            }
            if (components == null || components.isEmpty()) {
                return new ComponentListTranslationResult(components, false, false);
            }
            if (!hasEnglish(components)) {
                return new ComponentListTranslationResult(components, false, false);
            }


            String sourceJson = serializeComponents(components);
            if (sourceJson == null) {
                return new ComponentListTranslationResult(components, false, false);
            }
            String cacheKey = buildCacheKey(surface, sourceJson, context);
            List<Component> cached = restoreFromCache(cacheKey, components);
            if (cached == null && canUseContextlessLegacyCache(context)) {
                cached = restoreLegacyJsonCache(surface, sourceJson, cacheKey, components);
            }
            if (cached != null) {
                return new ComponentListTranslationResult(cached, true, true);
            }
            return new ComponentListTranslationResult(components, true, false);
        }, new ComponentListTranslationResult(components, false, false),
                "json-passthrough.getCachedComponents." + surface);
    }

    public static CompletableFuture<ComponentListTranslationResult> translateComponentsAsync(
            List<Component> components, String surface, String role, boolean fixedLayout, String context) {
        return SafeTranslate.guard(() -> {
            if (!ModConfig.GLOBAL_ENABLED.get()) {
                return CompletableFuture.completedFuture(new ComponentListTranslationResult(components, false, false));
            }
            if (components == null || components.isEmpty()) {
                return CompletableFuture.completedFuture(new ComponentListTranslationResult(components, false, false));
            }

            String sourceJson = serializeComponents(components);
            if (sourceJson == null) {
                return CompletableFuture.completedFuture(new ComponentListTranslationResult(components, false, false));
            }

            if (!hasEnglish(components)) {
                return CompletableFuture.completedFuture(new ComponentListTranslationResult(components, false, false));
            }


            String cacheKey = buildCacheKey(surface, sourceJson, context);
            List<Component> cached = restoreFromCache(cacheKey, components);
            if (cached == null && canUseContextlessLegacyCache(context)) {
                cached = restoreLegacyJsonCache(surface, sourceJson, cacheKey, components);
            }
            if (cached != null) {
                return CompletableFuture.completedFuture(new ComponentListTranslationResult(cached, true, true));
            }
            return requestAsync(components, sourceJson, surface, context, cacheKey)
                    .thenApply(restored -> {
                        if (restored == null) {
                            return new ComponentListTranslationResult(components, true, false);
                        }
                        return new ComponentListTranslationResult(restored, true, true);
                    });
        }, CompletableFuture.completedFuture(new ComponentListTranslationResult(components, false, false)),
                "json-passthrough.translateComponentsAsync." + surface);
    }

    public static String serializeComponents(List<Component> components) {
        return serializeComponents(components, false);
    }

    private static String serializeComponents(List<Component> components, boolean includeHoverEvents) {
        try {
            JsonArray array = new JsonArray();
            List<String> dynamicValues = includeHoverEvents ? null : new ArrayList<>();
            for (Component component : components) {
                String json = Component.Serializer.toJson(
                        component == null ? Component.empty() : component);
                JsonElement element = JsonParser.parseString(json);
                if (!includeHoverEvents) {
                    stripHoverEvents(element);
                    normalizeNumbersInTree(element, dynamicValues);
                }
                array.add(element);
            }
            return array.toString();
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().warn("JSON passthrough: failed to serialize components", e);
            return null;
        }
    }

    private static boolean hasEnglish(List<Component> components) {
        for (Component component : components) {
            if (component != null && TranslationTextDetector.containsTranslatableText(component.getString())) {
                return true;
            }
        }
        return false;
    }

    private static String buildCacheKey(String surface, String sourceJson, String context) {
        String normalizedContext = context;
        if (context != null && !context.isBlank()) {
            List<String> ctxValues = new ArrayList<>();
            normalizedContext = normalizeNumbers(context, ctxValues);
        }
        return TranslationCacheKeys.componentJsonKey(surface, sourceJson, normalizedContext);
    }

    private static boolean canUseContextlessLegacyCache(String context) {
        return context == null || context.isBlank();
    }

    @Nullable
    private static List<Component> restoreLegacyJsonCache(String surface, String sourceJson,
                                                          String currentKey, List<Component> originals) {
        if (!ModConfig.CACHE_ENABLED.get()) {
            return null;
        }
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null) {
            return null;
        }
        String legacyKey = TranslationCacheKeys.legacyComponentJsonKey(surface, sourceJson);
        String cached = cache.get(legacyKey).orElse(null);
        if (cached == null || cached.isBlank()) {
            return null;
        }
        List<Component> restored = deserializeComponents(cached, originals);
        if (restored == null) {
            cache.remove(legacyKey);
            cache.save();
            return null;
        }
        String canonical = serializeComponents(restored);
        List<Component> visibleRestored = reattachOriginalHoverEvents(restored, originals);
        cache.putComponentJson(currentKey, canonical == null ? cached : canonical, sourceJson,
                plainText(originals), plainText(visibleRestored));
        cache.remove(legacyKey);
        cache.save();
        return visibleRestored;
    }

    @Nullable
    private static List<Component> restoreFromCache(String cacheKey, List<Component> originals) {
        if (!ModConfig.CACHE_ENABLED.get()) {
            return null;
        }
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null) {
            return null;
        }
        String cached = cache.get(cacheKey).orElse(null);
        if (cached == null || cached.isBlank()) {
            return null;
        }
        List<Component> restored = deserializeComponents(cached, originals);
        if (restored == null) {
            cache.remove(cacheKey);
            cache.save();
            return null;
        }
        String canonical = serializeComponents(restored);
        List<Component> visibleRestored = reattachOriginalHoverEvents(restored, originals);
        if (canonical != null && !canonical.equals(cached)) {
            String sourceJson = serializeComponents(originals);
            cache.putComponentJson(cacheKey, canonical, sourceJson,
                    plainText(originals), plainText(visibleRestored));
            cache.save();
        }
        return visibleRestored;
    }

    private static CompletableFuture<List<Component>> requestAsync(
            List<Component> components, String sourceJson, String surface,
            String context, String cacheKey) {
        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            SimpleTranslateMod.getLogger().debug(
                    "JSON passthrough: manager not ready surface={} key={}", surface, cacheKey);
            return CompletableFuture.completedFuture(null);
        }
        if (!RecoveryPolicy.shouldAttempt(cacheKey)) {
            SimpleTranslateMod.getLogger().debug(
                    "JSON passthrough: negative cache frozen surface={} key={}", surface, cacheKey);
            return CompletableFuture.completedFuture(null);
        }

        TranslationLane lane = TranslationLanes.forSurface(surface);
        if (!lane.begin(cacheKey, FAILURE_RETRY_MS)) {
            SimpleTranslateMod.getLogger().debug(
                    "JSON passthrough: pending or cooldown surface={} key={}", surface, cacheKey);
            return CompletableFuture.completedFuture(null);
        }

        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        String sourceLanguage = ModConfig.SOURCE_LANGUAGE.get();
        String targetLanguage = ModConfig.TARGET_LANGUAGE.get();
        BatchItem item = new BatchItem(manager, List.copyOf(components), sourceJson, surface,
                context == null ? "" : context, cacheKey, lane, runtimeRevision,
                sourceLanguage, targetLanguage, new CompletableFuture<>());
        if (Surface.directBatchCandidate(surface) && item.context().isBlank()) {
            return JsonBatcher.enqueue(item);
        }
        return sendSingle(item);
    }

    private static CompletableFuture<List<Component>> sendSingle(BatchItem item) {
        String userPayload = buildUserPayload(item.sourceJson(), item.context());
        return item.manager().translateComponentJson(userPayload, item.surface(), 1)
                .thenCompose(response -> finishRequest(
                        item.originals(), item.sourceJson(), response, item.surface(), item.cacheKey(), item.lane(),
                        item.runtimeRevision(), item.sourceLanguage(), item.targetLanguage()))
                .exceptionally(error -> {
                    item.lane().fail(item.cacheKey(), FAILURE_RETRY_MS);
                    RecoveryPolicy.recordRejected(item.cacheKey());
                    SimpleTranslateMod.getLogger().warn(
                            "JSON passthrough: request failed surface={} key={} reason={}",
                            item.surface(), item.cacheKey(),
                            error == null ? "unknown" : error.getClass().getSimpleName());
                    return null;
                });
    }

    public static String buildUserPayload(String sourceJson, String context) {
        StringBuilder payload = new StringBuilder();
        if (context != null && !context.isBlank()) {
            payload.append("[CONTEXT]\n").append(context.trim()).append("\n[/CONTEXT]\n");
        }
        payload.append(sourceJson);
        return payload.toString();
    }

    private static CompletableFuture<List<Component>> finishRequest(
            List<Component> originals, String sourceJson, String response,
            String surface, String cacheKey, TranslationLane lane,
            long runtimeRevision, String sourceLanguage, String targetLanguage) {
        try {
            if (!ModConfig.GLOBAL_ENABLED.get()
                    || !SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)) {
                lane.finish(cacheKey);
                return CompletableFuture.completedFuture(null);
            }
            if (response == null || response.isBlank()) {
                lane.fail(cacheKey, FAILURE_RETRY_MS);
                RecoveryPolicy.recordRejected(cacheKey);
                SimpleTranslateMod.getLogger().warn(
                        "JSON passthrough: blank response surface={} key={}", surface, cacheKey);
                return CompletableFuture.completedFuture(null);
            }

            List<Component> restored = deserializeComponents(response, originals, surface);
            if (restored == null) {
                lane.fail(cacheKey, FAILURE_RETRY_MS);
                RecoveryPolicy.recordRejected(cacheKey);
                SimpleTranslateMod.getLogger().warn(
                        "JSON passthrough: deserialization failed surface={} key={}", surface, cacheKey);
                return CompletableFuture.completedFuture(null);
            }

            String canonical = serializeComponents(restored);
            if (canonical == null) {
                lane.fail(cacheKey, FAILURE_RETRY_MS);
                RecoveryPolicy.recordRejected(cacheKey);
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.completedFuture(acceptRestored(
                    originals, sourceJson, canonical, restored, cacheKey, lane,
                    runtimeRevision, sourceLanguage, targetLanguage));
        } catch (Exception e) {
            lane.fail(cacheKey, FAILURE_RETRY_MS);
            RecoveryPolicy.recordRejected(cacheKey);
            SimpleTranslateMod.getLogger().warn(
                    "JSON passthrough: exception surface={} key={}", surface, cacheKey, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static List<Component> acceptRestored(
            List<Component> originals, String sourceJson, String response, List<Component> restored,
            String cacheKey, TranslationLane lane, long runtimeRevision,
            String sourceLanguage, String targetLanguage) {
        if (!ModConfig.GLOBAL_ENABLED.get()
                || !SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)) {
            lane.finish(cacheKey);
            return null;
        }
        List<Component> visibleRestored = reattachOriginalHoverEvents(restored, originals);
        cacheSuccessfulResponse(cacheKey, response, sourceJson, originals, visibleRestored,
                runtimeRevision, sourceLanguage, targetLanguage);
        RecoveryPolicy.recordSuccess(cacheKey);
        lane.finish(cacheKey);
        return visibleRestored;
    }

    @Nullable
    public static List<Component> deserializeComponents(String response, List<Component> originals) {
        return deserializeComponents(response, originals, null);
    }

    @Nullable
    private static List<Component> deserializeComponents(
            String response, List<Component> originals, @Nullable String surface) {
        List<String> dynamicValues = extractDynamicValues(originals);
        try {
            String cleaned = stripNonJson(response);
            JsonElement root = JsonParser.parseString(cleaned);
            sanitizeTranslatedFonts(root);
            if (!root.isJsonArray()) {
                logDeserializationFailure(surface, "root-not-array", originals, -1, response);
                return null;
            }
            JsonArray array = root.getAsJsonArray();
            if (array.size() == 0) {
                logDeserializationFailure(surface, "empty-array", originals, 0, response);
                return null;
            }
            int expected = originals.size();
            if (array.size() != expected) {
                // Wrong count would be padded with empty / truncated and then cached, poisoning
                // the surface. Reject so finishRequest applies the normal cooldown + keeps original.
                logDeserializationFailure(surface, "size-mismatch", originals, array.size(), response);
                return null;
            }
            List<Component> result = new ArrayList<>(expected);
            for (int i = 0; i < expected; i++) {
                if (i < array.size()) {
                    JsonElement rawElement = array.get(i);
                    if (rawElement == null || rawElement.isJsonNull()) {
                        logDeserializationFailure(surface, "null-element", originals, array.size(), response);
                        return null;
                    }
                    JsonElement element = normalizeComponentJson(rawElement);
                    if (isContentlessComponentObject(element)) {
                        logDeserializationFailure(surface, "contentless-element", originals, array.size(), response);
                        return null;
                    }
                    try {
                        Component component = Component.Serializer.fromJson(element.toString());
                        if (component == null) {
                            logDeserializationFailure(surface, "null-component", originals, array.size(), response);
                            return null;
                        } else {
                            result.add(component);
                        }
                    } catch (Exception e) {
                        SafeTranslate.logLimited("json-passthrough.deserializeElement",
                                "element {} failed, using original: {}", i, e.getMessage());
                        result.add(originals.get(i));
                    }
                } else {
                    result.add(Component.empty());
                }
            }
            if (!dynamicValues.isEmpty()) {
            result = restoreDynamicValues(result, dynamicValues);
        }
        return List.copyOf(result);
        } catch (Exception e) {
            SafeTranslate.logLimited("json-passthrough.deserializeRoot",
                    "root parse failed: {}", e.getMessage());
            return null;
        }
    }

    private static List<Component> restoreDynamicValues(List<Component> components, List<String> values) {
        if (components == null || components.isEmpty() || values == null || values.isEmpty()) {
            return components;
        }
        List<Component> restored = new ArrayList<>(components.size());
        for (Component component : components) {
            if (component == null) {
                restored.add(Component.empty());
                continue;
            }
            try {
                String json = Component.Serializer.toJson(component);
                JsonElement element = JsonParser.parseString(json);
                restoreNumbersInTree(element, values);
                Component r = Component.Serializer.fromJson(element.toString());
                restored.add(r == null ? component : r);
            } catch (Exception e) {
                SafeTranslate.logLimited("json-passthrough.restoreDynamicValues", e);
                restored.add(component);
            }
        }
        return restored;
    }
private static boolean isContentlessComponentObject(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        return !hasComponentContent(object)
                && (!object.has("extra")
                || !object.get("extra").isJsonArray()
                || object.getAsJsonArray("extra").size() == 0);
    }    private static void sanitizeTranslatedFonts(JsonElement element) {
        sanitizeTranslatedFonts(element, false);
    }

    private static void sanitizeTranslatedFonts(JsonElement element, boolean inheritedCustomFont) {
        if (!ModConfig.CUSTOM_FONT_CJK_FIX_ENABLED.get() || element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                sanitizeTranslatedFonts(child, inheritedCustomFont);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        boolean explicitCustomFont = hasCustomFont(object);
        boolean effectiveCustomFont = explicitCustomFont || inheritedCustomFont;
        if (effectiveCustomFont && object.has("text") && object.get("text").isJsonPrimitive()) {
            String text = object.get("text").getAsString();
            if (containsCjk(text)) {
                if (containsPrivateUse(text)) {
                    splitMixedCustomFontText(object, text, inheritedCustomFont && !explicitCustomFont);
                } else if (explicitCustomFont) {
                    object.remove("font");
                } else {
                    object.addProperty("font", "minecraft:default");
                }
            }
        }

        boolean childInheritedCustomFont = hasCustomFont(object)
                || (inheritedCustomFont && !hasDefaultFont(object));
        for (Map.Entry<String, JsonElement> entry : List.copyOf(object.entrySet())) {
            sanitizeTranslatedFonts(entry.getValue(), childInheritedCustomFont);
        }
    }

    private static boolean hasCustomFont(JsonObject object) {
        if (object == null || !object.has("font") || !object.get("font").isJsonPrimitive()) {
            return false;
        }
        String font = object.get("font").getAsString();
        return font != null && !font.isBlank() && !"minecraft:default".equals(font);
    }

    private static boolean hasDefaultFont(JsonObject object) {
        if (object == null || !object.has("font") || !object.get("font").isJsonPrimitive()) {
            return false;
        }
        return "minecraft:default".equals(object.get("font").getAsString());
    }

    private static void splitMixedCustomFontText(JsonObject object, String text, boolean inheritedOnlyCustomFont) {
        JsonArray existingExtra = object.has("extra") && object.get("extra").isJsonArray()
                ? object.getAsJsonArray("extra")
                : null;
        JsonArray split = new JsonArray();
        int index = 0;
        while (index < text.length()) {
            int cp = text.codePointAt(index);
            boolean privateUse = isPrivateUse(cp);
            int end = index + Character.charCount(cp);
            while (end < text.length() && isPrivateUse(text.codePointAt(end)) == privateUse) {
                end += Character.charCount(text.codePointAt(end));
            }
            JsonObject segment = object.deepCopy();
            segment.addProperty("text", text.substring(index, end));
            segment.remove("extra");
            if (!privateUse) {
                if (inheritedOnlyCustomFont) {
                    segment.addProperty("font", "minecraft:default");
                } else {
                    segment.remove("font");
                }
            }
            split.add(segment);
            index = end;
        }
        if (existingExtra != null) {
            for (JsonElement child : existingExtra) {
                split.add(child);
            }
        }
        object.addProperty("text", "");
        object.add("extra", split);
    }

    private static boolean containsCjk(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean containsPrivateUse(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isPrivateUse(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isPrivateUse(int cp) {
        return (cp >= 0xE000 && cp <= 0xF8FF)
                || (cp >= 0xF0000 && cp <= 0xFFFFD)
                || (cp >= 0x100000 && cp <= 0x10FFFD);
    }
    /**
     * Minecraft 1.20.1 rejects a component object that has only {@code extra}
     * children. Models commonly omit the empty root text emitted by vanilla,
     * so restore that one deterministic field without changing translated text.
     */    private static JsonElement normalizeComponentJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return element;
        }
        JsonObject normalized = element.getAsJsonObject().deepCopy();
        normalizeComponentChildren(normalized, "extra");
        normalizeComponentChildren(normalized, "with");
        normalizeHoverComponent(normalized);
        if (!hasComponentContent(normalized)
                && normalized.has("extra")
                && normalized.get("extra").isJsonArray()) {
            normalized.addProperty("text", "");
        }
        return normalized;
    }

    private static void normalizeComponentChildren(JsonObject component, String key) {
        if (!component.has(key) || !component.get(key).isJsonArray()) {
            return;
        }
        JsonArray source = component.getAsJsonArray(key);
        JsonArray normalized = new JsonArray();
        for (JsonElement child : source) {
            normalized.add(normalizeComponentJson(child));
        }
        component.add(key, normalized);
    }

    private static void normalizeHoverComponent(JsonObject component) {
        if (!component.has("hoverEvent") || !component.get("hoverEvent").isJsonObject()) {
            return;
        }
        JsonObject hoverEvent = component.getAsJsonObject("hoverEvent");
        if (hoverEvent.has("contents")) {
            hoverEvent.add("contents", normalizeComponentJson(hoverEvent.get("contents")));
        }
        if (hoverEvent.has("value")) {
            hoverEvent.add("value", normalizeComponentJson(hoverEvent.get("value")));
        }
    }

    private static boolean hasComponentContent(JsonObject component) {
        return component.has("text")
                || component.has("translate")
                || component.has("score")
                || component.has("selector")
                || component.has("keybind")
                || component.has("nbt");
    }

    private static void logDeserializationFailure(
            @Nullable String surface, String reason, List<Component> originals, int actual, String response) {
        if (surface == null || surface.isBlank()) {
            return;
        }
        SimpleTranslateMod.getLogger().warn(
                "JSON passthrough: invalid component array surface={} reason={} expected={} actual={} responseChars={}",
                surface, reason, originals == null ? 0 : originals.size(), actual,
                response == null ? 0 : response.length());
    }

    /**
     * Strips markdown code fences and leading/trailing non-JSON text that
     * some models wrap around the response.
     */
    private static String stripNonJson(String response) {
        String text = response.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int lastFence = text.lastIndexOf("```");
            if (lastFence >= 0) {
                text = text.substring(0, lastFence);
            }
            text = text.trim();
        }
        int arrayStart = text.indexOf('[');
        int arrayEnd = text.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            text = text.substring(arrayStart, arrayEnd + 1);
        }
        return text;
    }

    private static void stripHoverEvents(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                stripHoverEvents(child);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        object.remove("hoverEvent");
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            keys.add(entry.getKey());
        }
        for (String key : keys) {
            stripHoverEvents(object.get(key));
        }
    }

    private static List<Component> reattachOriginalHoverEvents(List<Component> translated, List<Component> originals) {
        if (translated == null || translated.isEmpty() || originals == null || originals.size() != translated.size()) {
            return translated;
        }
        List<Component> result = new ArrayList<>(translated.size());
        boolean changed = false;
        for (int i = 0; i < translated.size(); i++) {
            Component translatedComponent = translated.get(i);
            Component originalComponent = originals.get(i);
            Component restored = reattachOriginalHoverEvents(translatedComponent, originalComponent);
            result.add(restored);
            changed |= restored != translatedComponent;
        }
        return changed ? List.copyOf(result) : translated;
    }

    private static Component reattachOriginalHoverEvents(Component translated, Component original) {
        if (translated == null || original == null) {
            return translated;
        }
        try {
            JsonElement translatedJson = JsonParser.parseString(Component.Serializer.toJson(translated));
            JsonElement originalJson = JsonParser.parseString(Component.Serializer.toJson(original));
            copyOriginalHoverEvents(originalJson, translatedJson);
            Component restored = Component.Serializer.fromJson(translatedJson.toString());
            return restored == null ? translated : restored;
        } catch (Exception e) {
            SafeTranslate.logLimited("json-passthrough.reattachHoverEvents", e);
            return translated;
        }
    }

    private static void copyOriginalHoverEvents(JsonElement original, JsonElement translated) {
        if (translated == null || translated.isJsonNull()) {
            return;
        }
        if (original == null || original.isJsonNull()) {
            stripHoverEvents(translated);
            return;
        }
        if (original.isJsonArray() && translated.isJsonArray()) {
            JsonArray originalArray = original.getAsJsonArray();
            JsonArray translatedArray = translated.getAsJsonArray();
            for (int i = 0; i < translatedArray.size(); i++) {
                JsonElement originalChild = i < originalArray.size() ? originalArray.get(i) : null;
                copyOriginalHoverEvents(originalChild, translatedArray.get(i));
            }
            return;
        }
        if (!original.isJsonObject() || !translated.isJsonObject()) {
            stripHoverEvents(translated);
            return;
        }

        JsonObject originalObject = original.getAsJsonObject();
        JsonObject translatedObject = translated.getAsJsonObject();
        if (originalObject.has("hoverEvent")) {
            translatedObject.add("hoverEvent", originalObject.get("hoverEvent").deepCopy());
        } else {
            translatedObject.remove("hoverEvent");
        }

        List<Map.Entry<String, JsonElement>> translatedEntries = new ArrayList<>(translatedObject.entrySet());
        for (Map.Entry<String, JsonElement> entry : translatedEntries) {
            String key = entry.getKey();
            if ("hoverEvent".equals(key)) {
                continue;
            }
            JsonElement originalChild = originalObject.get(key);
            copyOriginalHoverEvents(originalChild, entry.getValue());
        }
    }

    private static void cacheSuccessfulResponse(
            String cacheKey, String response, String sourceJson,
            List<Component> originals, List<Component> restored,
            long runtimeRevision,
            String sourceLanguage, String targetLanguage) {
        if (!ModConfig.CACHE_ENABLED.get()) {
            return;
        }
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null) {
            return;
        }
        if (!SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)) {
            return;
        }
        if (!sourceLanguage.equals(ModConfig.SOURCE_LANGUAGE.get())) {
            return;
        }
        if (!targetLanguage.equals(ModConfig.TARGET_LANGUAGE.get())) {
            return;
        }
        cache.putComponentJson(cacheKey, response, sourceJson,
                plainText(originals), plainText(restored));
        cache.save();
    }

    private static String plainText(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            Component component = components.get(i);
            if (component != null) {
                text.append(component.getString());
            }
        }
        return text.toString();
    }

    private static long batchDelayMs() {
        return Math.max(0L, Math.min(200L, ModConfig.API_DIRECT_BATCH_DELAY_MS.get()));
    }

    private static final class JsonBatcher {
        private static final Object LOCK = new Object();
        private static final Map<String, List<BatchItem>> PENDING = new ConcurrentHashMap<>();
        private static final Map<String, ScheduledFuture<?>> SCHEDULED = new ConcurrentHashMap<>();

        private JsonBatcher() {
        }

        private static CompletableFuture<List<Component>> enqueue(BatchItem item) {
            String group = Surface.normalize(item.surface());
            synchronized (LOCK) {
                List<BatchItem> items = PENDING.computeIfAbsent(group, ignored -> new ArrayList<>());
                items.add(item);
                int chars = 0;
                for (BatchItem queued : items) {
                    chars += queued.sourceJson().length();
                }
                if (items.size() >= MAX_BATCH_ITEMS || chars >= MAX_BATCH_CHARS) {
                    flushLocked(group);
                } else if (!SCHEDULED.containsKey(group)) {
                    SCHEDULED.put(group, BATCH_EXECUTOR.schedule(() -> {
                        synchronized (LOCK) {
                            flushLocked(group);
                        }
                    }, batchDelayMs(), TimeUnit.MILLISECONDS));
                }
            }
            return item.future();
        }

        private static void flushLocked(String group) {
            List<BatchItem> items = PENDING.remove(group);
            ScheduledFuture<?> scheduled = SCHEDULED.remove(group);
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            if (items == null || items.isEmpty()) {
                return;
            }
            if (items.size() == 1) {
                completeFromFuture(items.get(0), sendSingle(items.get(0)));
                return;
            }

            List<Component> combined = new ArrayList<>();
            for (BatchItem item : items) {
                combined.addAll(item.originals());
            }
            String combinedJson = serializeComponents(combined);
            BatchItem first = items.get(0);
            first.manager().translateComponentJson(
                            buildUserPayload(combinedJson, ""), first.surface(), 1)
                    .whenComplete((response, error) -> completeBatch(items, combined, response, error));
        }

        private static void completeBatch(List<BatchItem> items, List<Component> combined,
                                          String response, Throwable error) {
            BatchItem first = items.get(0);
            List<Component> translated = error == null && response != null
                    ? deserializeComponents(response, combined, first.surface())
                    : null;
            if (translated == null) {
                SimpleTranslateMod.getLogger().debug(
                        "JSON micro-batch invalid; retrying {} item(s) individually", items.size());
                for (BatchItem item : items) {
                    completeFromFuture(item, sendSingle(item));
                }
                return;
            }

            int offset = 0;
            for (BatchItem item : items) {
                int end = offset + item.originals().size();
                List<Component> slice = List.copyOf(translated.subList(offset, end));
                offset = end;
                String canonical = serializeComponents(slice);
                try {
                    List<Component> accepted = acceptRestored(
                            item.originals(), item.sourceJson(), canonical, slice,
                            item.cacheKey(), item.lane(), item.runtimeRevision(),
                            item.sourceLanguage(), item.targetLanguage());
                    item.future().complete(accepted);
                } catch (Exception exception) {
                    item.lane().fail(item.cacheKey(), FAILURE_RETRY_MS);
                    item.future().complete(null);
                }
            }
        }

        private static void completeFromFuture(BatchItem item, CompletableFuture<List<Component>> future) {
            future.whenComplete((result, error) -> {
                if (error != null) {
                    item.future().completeExceptionally(error);
                } else {
                    item.future().complete(result);
                }
            });
        }

        private static void clear() {
            synchronized (LOCK) {
                for (ScheduledFuture<?> future : SCHEDULED.values()) {
                    future.cancel(false);
                }
                SCHEDULED.clear();
                for (List<BatchItem> items : PENDING.values()) {
                    for (BatchItem item : items) {
                        item.lane().finish(item.cacheKey());
                        item.future().complete(null);
                    }
                }
                PENDING.clear();
            }
        }
    }

    private record BatchItem(TranslationManager manager,
                             List<Component> originals,
                             String sourceJson,
                             String surface,
                             String context,
                             String cacheKey,
                             TranslationLane lane,
                             long runtimeRevision,
                             String sourceLanguage,
                             String targetLanguage,
                             CompletableFuture<List<Component>> future) {
    }
}
