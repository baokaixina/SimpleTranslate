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
import com.yourname.simpletranslate.transport.TranslationPromptPolicy;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * JSON-passthrough translation pipeline for Minecraft components.
 *
 * <p>Serializes each {@link Component}, projects every natural-language run to
 * an ordered top-level Component slot, and keeps opaque visuals, custom-font
 * glyphs, format controls, dynamic values and hover payloads local. Hidden hover
 * events are reattached after translation; dedicated tooltip paths translate
 * hover content independently.</p>
 *
 * <p>Every game translation surface uses this pipeline. The wire contract
 * requires the same top-level slot count and a parseable Component at every
 * index. {@link ComponentVisualProjection} then binds each accepted entry by
 * ordinal into the untouched local source skeleton. Translation quality is
 * directed by the shared prompt and full-document context; it is deliberately
 * not guessed from retained Latin words, names, or technical terms. The legacy
 * request-mode setting is compatibility-only; no game surface sends string
 * arrays or marker contracts.</p>
 */
public final class JsonPassthroughPipeline {
    static final long FAILURE_RETRY_MS = 6000L;
    /** One initial request plus two whole-document structural correction attempts. */
    private static final int MAX_COMPONENT_STRUCTURE_ATTEMPTS = 3;
    private JsonPassthroughPipeline() {
    }

    // Dynamic-number masking lives in ComponentJsonNumberNormalizer; thin wrappers
    // keep production call sites and logic-check string contracts stable.
    private static String normalizeNumbers(String text, List<String> values) {
        return ComponentJsonNumberNormalizer.normalizeNumbers(text, values);
    }

    private static JsonElement normalizeNumbersInTree(JsonElement element, List<String> values) {
        return ComponentJsonNumberNormalizer.normalizeNumbersRoot(element, values);
    }

    private static JsonElement restoreNumbersInTree(JsonElement element, List<String> values) {
        return ComponentJsonNumberNormalizer.restoreNumbersRoot(element, values);
    }

    static boolean cacheTemplateMatchesSourceMarkers(String sourceJson, String cacheTemplate) {
        if (sourceJson == null || sourceJson.isBlank()
                || cacheTemplate == null || cacheTemplate.isBlank()) {
            return false;
        }
        try {
            return ComponentJsonNumberNormalizer.hasSameDynamicMarkerDomain(
                    JsonParser.parseString(sourceJson), JsonParser.parseString(cacheTemplate));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean cacheTemplateContainsDynamicMarkers(String cacheTemplate) {
        if (cacheTemplate == null || cacheTemplate.isBlank()) {
            return false;
        }
        try {
            return ComponentJsonNumberNormalizer.containsDynamicMarker(
                    JsonParser.parseString(cacheTemplate));
        } catch (RuntimeException ignored) {
            return true;
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
        if (components == null) {
            return null;
        }
        try {
            JsonArray array = new JsonArray();
            for (Component component : components) {
                String json = ComponentJsonCompat.toJson(
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
        ComponentJsonBatcher.clear();
    }

    public static void shutdown() {
        ComponentJsonBatcher.shutdown();
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
            String sourceLanguage = ModConfig.SOURCE_LANGUAGE.get();
            String targetLanguage = ModConfig.TARGET_LANGUAGE.get();
            if (!isOutgoingChatSurface(surface) && !hasTranslatableText(components, targetLanguage)) {
                return new ComponentListTranslationResult(components, false, false);
            }

            String sourceJson = serializeComponents(components);
            if (sourceJson == null) {
                return new ComponentListTranslationResult(components, false, false);
            }
            String cacheKey = buildCacheKey(surface, sourceJson, context, role, sourceLanguage, targetLanguage);
            List<Component> cached = restoreCachedComponents(
                    surface, sourceJson, context, sourceLanguage, targetLanguage, cacheKey, components);
            if (cached != null) {
                return new ComponentListTranslationResult(cached, true, true);
            }

            requestAsync(components, sourceJson, surface, role, context, cacheKey, sourceLanguage, targetLanguage);
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
            String sourceLanguage = ModConfig.SOURCE_LANGUAGE.get();
            String targetLanguage = ModConfig.TARGET_LANGUAGE.get();
            if (!isOutgoingChatSurface(surface) && !hasTranslatableText(components, targetLanguage)) {
                return new ComponentListTranslationResult(components, false, false);
            }

            String sourceJson = serializeComponents(components);
            if (sourceJson == null) {
                return new ComponentListTranslationResult(components, false, false);
            }
            String cacheKey = buildCacheKey(surface, sourceJson, context, role, sourceLanguage, targetLanguage);
            List<Component> cached = restoreCachedComponents(
                    surface, sourceJson, context, sourceLanguage, targetLanguage, cacheKey, components);
            if (cached != null) {
                return new ComponentListTranslationResult(cached, true, true);
            }
            return new ComponentListTranslationResult(components, true, false);
        }, new ComponentListTranslationResult(components, false, false),
                "json-passthrough.getCachedComponents." + surface);
    }

    public static CompletableFuture<ComponentListTranslationResult> translateComponentsAsync(
            List<Component> components, String surface, String role, boolean fixedLayout, String context) {
        return translateComponentsAsync(components, surface, role, fixedLayout, context, "", "");
    }

    public static CompletableFuture<ComponentListTranslationResult> translateComponentsAsync(
            List<Component> components, String surface, String role, boolean fixedLayout, String context,
            String sourceLanguageOverride, String targetLanguageOverride) {
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

            String sourceLanguage = effectiveSourceLanguage(sourceLanguageOverride);
            String targetLanguage = effectiveTargetLanguage(targetLanguageOverride);
            if (!isOutgoingChatSurface(surface) && !hasTranslatableText(components, targetLanguage)) {
                return CompletableFuture.completedFuture(new ComponentListTranslationResult(components, false, false));
            }
            String cacheKey = buildCacheKey(surface, sourceJson, context, role, sourceLanguage, targetLanguage);
            List<Component> cached = restoreCachedComponents(
                    surface, sourceJson, context, sourceLanguage, targetLanguage, cacheKey, components);
            if (cached != null) {
                return CompletableFuture.completedFuture(new ComponentListTranslationResult(cached, true, true));
            }
            return requestAsync(components, sourceJson, surface, role, context, cacheKey, sourceLanguage, targetLanguage)
                    .thenApply(restored -> {
                        if (restored == null) {
                            return new ComponentListTranslationResult(components, true, false);
                        }
                        return new ComponentListTranslationResult(restored, true, true);
                    });
        }, CompletableFuture.completedFuture(new ComponentListTranslationResult(components, false, false)),
                "json-passthrough.translateComponentsAsync." + surface);
    }

    /**
     * Materializes a locally merged Component result into the ordinary exact
     * block cache.  Incremental surface coordinators use this only after every
     * semantic slot has either been reused from an accepted scoped entry or
     * returned by a successful Component JSON request.  The stored value is
     * therefore still one complete source tree -> one complete translated tree;
     * fuzzy source structures never enter the block cache.
     */
    public static void cacheResolvedComponents(
            List<Component> originals, List<Component> translated,
            String surface, String role, String context, long runtimeRevision) {
        if (!ModConfig.GLOBAL_ENABLED.get() || !ModConfig.CACHE_ENABLED.get()
                || originals == null || originals.isEmpty()
                || translated == null || translated.size() != originals.size()
                || !SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)) {
            return;
        }
        try {
            String sourceLanguage = ModConfig.SOURCE_LANGUAGE.get();
            String targetLanguage = ModConfig.TARGET_LANGUAGE.get();
            String sourceJson = serializeComponents(originals);
            String response = buildCacheTemplateFromResolvedComponents(
                    sourceJson, originals, translated, targetLanguage);
            if (sourceJson == null || response == null) {
                return;
            }
            List<Component> replayed = deserializeComponents(response, originals);
            if (replayed == null) {
                return;
            }
            String cacheKey = buildCacheKey(
                    surface, sourceJson, context, role, sourceLanguage, targetLanguage);
            cacheSuccessfulResponse(cacheKey, response, sourceJson, originals, replayed,
                    runtimeRevision, sourceLanguage, targetLanguage);
        } catch (RuntimeException exception) {
            SafeTranslate.logLimited("json-passthrough.cacheResolvedComponents", exception);
        }
    }

    @Nullable
    private static String buildCacheTemplateFromResolvedComponents(
            String sourceJson, List<Component> originals,
            List<Component> translated, String targetLanguage) {
        if (sourceJson == null || sourceJson.isBlank()
                || originals == null || originals.isEmpty()
                || translated == null || translated.size() != originals.size()) {
            return null;
        }
        try {
            ComponentVisualProjection normalizedProjection = ComponentVisualProjection.project(
                    sourceJson, targetLanguage);
            ComponentVisualProjection liveProjection = projectLiveComponents(
                    originals, targetLanguage);
            String translatedJson = serializeComponentsRaw(translated);
            if (normalizedProjection == null || !normalizedProjection.hasSlots()
                    || liveProjection == null || !liveProjection.hasSlots()
                    || normalizedProjection.slotCount() != liveProjection.slotCount()
                    || translatedJson == null) {
                return null;
            }
            // The resolved tree still contains this frame's concrete numbers, so
            // only the marker-free live projection can align its opaque atoms.
            // Rebuilding through the normalized projection then restores the
            // source-owned N markers without ever normalizing translated literals.
            List<String> translatedSlots = liveProjection.alignedTranslatedSlotTexts(
                    JsonParser.parseString(translatedJson));
            if (translatedSlots == null
                    || translatedSlots.size() != normalizedProjection.slotCount()) {
                return null;
            }
            JsonArray rebuilt = normalizedProjection.rebuildComponents(translatedSlots.stream()
                    .map(text -> (Component) Component.literal(text))
                    .toList());
            String template = rebuilt == null ? null : rebuilt.toString();
            return cacheTemplateMatchesSourceMarkers(sourceJson, template) ? template : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String serializeComponents(List<Component> components) {
        return serializeComponents(components, false);
    }

    /**
     * Sanitized live skeleton used by render coordinators that need to rebind a
     * ready semantic response against current dynamic values. Hover payloads are
     * excluded, while numbers and visual atoms remain exact and client-owned.
     */
    @Nullable
    public static String serializeProjectionSource(List<Component> components) {
        return serializeComponentsRaw(components);
    }

    /** Builds the same marker-free projection from a live, hover-sanitized tree. */
    @Nullable
    private static List<Component> liveProjectionRows;
    private static String liveProjectionLanguage;
    private static ComponentVisualProjection liveProjectionCached;

    /**
     * Render-thread single-slot memo: one render pass evaluates the same
     * tooltip rows through several consumers (frame key, trigger signature),
     * and the projection is a pure function of the rows and target language.
     */
    public static ComponentVisualProjection projectLiveComponents(
            List<Component> components, String targetLanguage) {
        if (components == liveProjectionRows
                && java.util.Objects.equals(targetLanguage, liveProjectionLanguage)) {
            return liveProjectionCached;
        }
        String source = serializeProjectionSource(components);
        ComponentVisualProjection projection =
                source == null ? null : ComponentVisualProjection.project(source, targetLanguage);
        liveProjectionRows = components;
        liveProjectionLanguage = targetLanguage;
        liveProjectionCached = projection;
        return projection;
    }

    private static String serializeComponents(List<Component> components, boolean includeHoverEvents) {
        if (components == null) {
            return null;
        }
        try {
            JsonArray array = new JsonArray();
            List<String> dynamicValues = includeHoverEvents ? null : new ArrayList<>();
            for (Component component : components) {
                String json = ComponentJsonCompat.toJson(
                        component == null ? Component.empty() : component);
                JsonElement element = JsonParser.parseString(json);
                if (!includeHoverEvents) {
                    stripHoverEvents(element);
                    element = normalizeNumbersInTree(element, dynamicValues);
                }
                array.add(element);
            }
            return array.toString();
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().warn("JSON passthrough: failed to serialize components", e);
            return null;
        }
    }

    private static boolean hasTranslatableText(List<Component> components, String targetLanguage) {
        for (Component component : components) {
            if (component != null && TranslationTextDetector.containsTranslatableText(
                    component.getString(), 1, targetLanguage)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOutgoingChatSurface(String surface) {
        return Surface.normalize(surface).startsWith("chat.outgoing");
    }
    private static String buildCacheKey(String surface, String sourceJson, String context, String role,
                                        String sourceLanguage, String targetLanguage) {
        String normalizedContext = context;
        if (context != null && !context.isBlank()) {
            normalizedContext = normalizeNumbers(context, new ArrayList<>());
        }
        String promptFingerprint = TranslationPromptPolicy.cacheFingerprint(surface);
        String semanticContext = "translation_role=" + TranslationPromptPolicy.normalizedRole(role)
                + "\ntranslation_prompt_policy=" + promptFingerprint
                + "\ntranslation_wire=component_visual_projection_v5";
        if (ComponentVisualProjection.needsLanguageVisibleCacheRevision(sourceJson)) {
            semanticContext += "\ntranslation_semantic_visibility=language_visible_v1";
        }
        normalizedContext = (normalizedContext == null || normalizedContext.isBlank())
                ? semanticContext
                : normalizedContext + "\n" + semanticContext;
        return TranslationCacheKeys.componentJsonKey(surface, sourceJson, normalizedContext,
                sourceLanguage, targetLanguage);
    }


    private static String effectiveSourceLanguage(String sourceLanguage) {
        return sourceLanguage == null || sourceLanguage.isBlank()
                ? ModConfig.SOURCE_LANGUAGE.get()
                : sourceLanguage;
    }

    private static String effectiveTargetLanguage(String targetLanguage) {
        return targetLanguage == null || targetLanguage.isBlank()
                ? ModConfig.TARGET_LANGUAGE.get()
                : targetLanguage;
    }

    private static boolean usesCurrentGlobalLanguages(String sourceLanguage, String targetLanguage) {
        return TranslationTextDetector.languagePairKey(sourceLanguage, targetLanguage)
                .equals(TranslationTextDetector.languagePairKey());
    }

    private static boolean canUseContextlessLegacyCache(String context) {
        return (context == null || context.isBlank())
                && TranslationPromptPolicy.legacyCacheCompatible();
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
        if (cacheTemplateContainsDynamicMarkers(cached)
                && !cacheTemplateMatchesSourceMarkers(sourceJson, cached)) {
            cache.remove(legacyKey);
            cache.save();
            return null;
        }
        List<Component> restored = deserializeComponents(cached, originals, null);
        if (restored == null) {
            cache.remove(legacyKey);
            cache.save();
            return null;
        }
        List<Component> visibleRestored = reattachOriginalHoverEvents(restored, originals);
        String canonical = buildCacheTemplateFromResolvedComponents(
                sourceJson, originals, visibleRestored, ModConfig.TARGET_LANGUAGE.get());
        if (canonical != null) {
            cache.putComponentJson(currentKey, canonical, sourceJson,
                    plainText(originals), plainText(visibleRestored));
        }
        cache.remove(legacyKey);
        cache.save();
        return visibleRestored;
    }

    @Nullable
    private static List<Component> restoreCachedComponents(
            String surface, String sourceJson, String context,
            String sourceLanguage, String targetLanguage,
            String cacheKey, List<Component> components) {
        List<Component> cached = restoreFromCache(cacheKey, sourceJson, components);
        // Old marker/style-wire generations stay inactive. Only the original
        // json.<surface> Component cache may migrate lazily when the current
        // prompt policy is legacy-compatible.
        if (cached == null && canUseContextlessLegacyCache(context)
                && usesCurrentGlobalLanguages(sourceLanguage, targetLanguage)) {
            cached = restoreLegacyJsonCache(surface, sourceJson, cacheKey, components);
        }
        return cached;
    }

    @Nullable
    private static List<Component> restoreFromCache(
            String cacheKey, String sourceJson, List<Component> originals) {
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
        if (!cacheTemplateMatchesSourceMarkers(sourceJson, cached)) {
            cache.remove(cacheKey);
            cache.save();
            return null;
        }
        List<Component> restored = deserializeComponents(cached, originals);
        if (restored == null) {
            cache.remove(cacheKey);
            cache.save();
            return null;
        }
        return reattachOriginalHoverEvents(restored, originals);
    }

    private static CompletableFuture<List<Component>> requestAsync(
            List<Component> components, String sourceJson, String surface,
            String role, String context, String cacheKey,
            String sourceLanguage, String targetLanguage) {
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
        TranslationLane.Lease lease = lane.begin(cacheKey, FAILURE_RETRY_MS);
        if (lease == null) {
            SimpleTranslateMod.getLogger().debug(
                    "JSON passthrough: pending or cooldown surface={} key={}", surface, cacheKey);
            return CompletableFuture.completedFuture(null);
        }

        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        long textContextRevision = TextContextMemory.revision();
        BatchItem item = new BatchItem(manager, List.copyOf(components), sourceJson, surface,
                TranslationPromptPolicy.normalizedRole(role), context == null ? "" : context,
                cacheKey, lane, lease, runtimeRevision, textContextRevision,
                sourceLanguage, targetLanguage, new CompletableFuture<>());
        if (Surface.directBatchCandidate(surface) && item.context().isBlank()) {
            return ComponentJsonBatcher.enqueue(item);
        }
        return sendSingle(item);
    }

    static CompletableFuture<List<Component>> sendSingle(BatchItem item) {
        ComponentVisualProjection projection = ComponentVisualProjection.project(
                item.sourceJson(), item.targetLanguage());
        if (projection == null || !projection.hasSlots()) {
            item.lane().finish(item.lease());
            return CompletableFuture.completedFuture(null);
        }
        String document = projection.semanticJson();
        String callerContext = item.context();
        String fullBlock = semanticPromptSourceShape(item.originals());
        if (!fullBlock.isBlank()) {
            boolean hasDynamicGaps = fullBlock.contains("<number>");
            String sourceGuidance = hasDynamicGaps
                    ? "Full ordered source text shape. Only <number> tokens mark live values owned and reinserted "
                    + "by the client; ordinary digits remain semantic request text. Translate the JSON request "
                    + "slots around each live gap, and never emit <number> or a replacement for that gap:\n"
                    : "Full ordered source text. Ordinary numbers shown here belong to the sentence and must be "
                    + "kept exactly once while translating its grammar:\n";
            callerContext = sourceGuidance + fullBlock
                    + (callerContext.isBlank() ? "" : "\nAdditional surface context:\n" + callerContext);
        }
        TextContextMemory.PromptMetadata promptMetadata = TextContextMemory.buildPromptMetadata(
                callerContext, item.surface(), item.role(), document, true,
                item.sourceLanguage(), item.targetLanguage());
        CompletableFuture<String> responseFuture = translateComponentSlotsReliably(
                item, document, projection.slotCount(), promptMetadata.json(),
                recoveryAtomicGroupSizes(item, projection));
        return responseFuture
                .thenCompose(response -> finishRequest(
                        item.originals(), item.sourceJson(), response, item.surface(), item.cacheKey(),
                        item.lane(), item.lease(),
                        item.runtimeRevision(), promptMetadata.contextRevision(),
                        item.sourceLanguage(), item.targetLanguage(), projection))
                .exceptionally(error -> {
                    item.lane().fail(item.lease(), FAILURE_RETRY_MS);
                    RecoveryPolicy.recordRejected(item.cacheKey());
                    SimpleTranslateMod.getLogger().warn(
                            "JSON passthrough: request failed surface={} key={} reason={}",
                            item.surface(), item.cacheKey(),
                            error == null ? "unknown" : error.getClass().getSimpleName());
                    return null;
                });
    }

    public static String buildUserPayload(String sourceJson, String context) {
        // Context is system-prompt metadata. The user payload is always exactly
        // one top-level JSON array in both wire modes.
        return sourceJson == null || sourceJson.isBlank() ? "[]" : sourceJson.trim();
    }

    /**
     * Pages and signs are multi-Component physical layouts whose lines form one
     * grammatical document. Other surfaces may recover between original
     * top-level Components, while nested style/icon fragments stay atomic via
     * the projection's structural groups.
     */
    private static List<Integer> recoveryAtomicGroupSizes(
            BatchItem item, ComponentVisualProjection projection) {
        return recoveryAtomicGroupSizes(
                item == null ? "" : item.surface(),
                item == null ? "" : item.context(), projection);
    }

    private static List<Integer> recoveryAtomicGroupSizes(
            String surface, String context, ComponentVisualProjection projection) {
        if (projection == null || projection.slotCount() <= 0) {
            return List.of();
        }
        return recoveryAtomicGroupSizesForTest(
                surface, context, projection.slotCount(), projection.atomicGroupSizes());
    }

    static List<Integer> recoveryAtomicGroupSizesForTest(
            String surface, String context, int slotCount, List<Integer> ordinaryAtomicGroups) {
        if (slotCount <= 0) {
            return List.of();
        }
        String normalized = Surface.normalize(surface);
        // Wynn content uses one plain BODY paragraph leaf in v4. Keep recovery
        // at semantic slot boundaries after normal whole-document retries so a
        // provider that merges NAME/BODY/CONTROL still cannot lose all dialogue.
        if (isWynnDialogueContentSurface(normalized)) {
            return java.util.Collections.nCopies(slotCount, 1);
        }
        boolean incrementalTooltipGroup = normalized.startsWith("tooltip.visible.")
                && context != null && context.contains("Incremental Component request.");
        if (normalized.startsWith("book.") || normalized.startsWith("sign.")
                || incrementalTooltipGroup) {
            return List.of(slotCount);
        }
        return ordinaryAtomicGroups == null ? List.of() : List.copyOf(ordinaryAtomicGroups);
    }

    private static boolean isWynnDialogueContentSurface(String normalizedSurface) {
        return normalizedSurface != null && normalizedSurface.startsWith(
                "hud.actionbar.wynn.dialogue.content.paragraph.");
    }

    /**
     * Translates one complete semantic document through the approved Component
     * JSON protocol. The only response contract is exact top-level count plus a
     * parseable Component at every index. A retained player name, product name,
     * technical label, or Latin phrase must never invalidate translated siblings
     * or trigger per-slot request storms.
     */
    private static CompletableFuture<String> translateComponentSlotsReliably(
            BatchItem item, String sourcePayload, int expectedSlots, String promptContext,
            List<Integer> atomicGroupSizes) {
        return translateComponentSlotsReliably(
                item, sourcePayload, expectedSlots, promptContext, atomicGroupSizes, 0);
    }

    private static CompletableFuture<String> translateComponentSlotsReliably(
            BatchItem item, String sourcePayload, int expectedSlots, String promptContext,
            List<Integer> atomicGroupSizes, int structureAttempt) {
        if (expectedSlots <= 0) {
            return CompletableFuture.completedFuture("[]");
        }

        String payload = sourcePayload;
        if (payload == null) {
            return CompletableFuture.completedFuture(null);
        }
        String attemptContext = componentStructureAttemptContext(
                promptContext, expectedSlots, structureAttempt);
        return item.manager().translateComponentJson(
                        buildUserPayload(payload, item.context()), item.surface(),
                        Math.min(MAX_COMPONENT_STRUCTURE_ATTEMPTS, structureAttempt + 1),
                        item.sourceLanguage(), item.targetLanguage(),
                        attemptContext)
                .thenCompose(response -> {
                    // Transport/provider retries already own blank responses. Only a
                    // non-empty response whose JSON/count/Component structure is
                    // invalid receives this bounded whole-document correction pass.
                    if (response == null || response.isBlank()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    JsonArray parsed = parseExactComponentArray(
                            response, expectedSlots, item.surface(), 0);
                    if (parsed != null) {
                        return CompletableFuture.completedFuture(parsed.toString());
                    }
                    int nextAttempt = structureAttempt + 1;
                    if (hasComponentStructureRetryRemaining(structureAttempt)) {
                        SimpleTranslateMod.getLogger().warn(
                                "Component JSON structural retry surface={} attempt={}/{} expectedSlots={}",
                                item.surface(), nextAttempt + 1,
                                MAX_COMPONENT_STRUCTURE_ATTEMPTS, expectedSlots);
                        return translateComponentSlotsReliably(
                                item, payload, expectedSlots, promptContext,
                                atomicGroupSizes, nextAttempt);
                    }
                    // Only after bounded full-document correction fails may the
                    // request split, and then only between original top-level
                    // Components. Nested styles and icon-adjacent grammar remain
                    // indivisible on every surface.
                    if (allowsAdaptiveComponentPartition(atomicGroupSizes, expectedSlots)) {
                        String recoveryMode = isWynnDialogueContentSurface(Surface.normalize(item.surface()))
                                ? "wynn-semantic-slot-bisection" : "atomic-group-bisection";
                        SimpleTranslateMod.getLogger().warn(
                                "Component JSON structural recovery surface={} slots={} mode={}",
                                item.surface(), expectedSlots, recoveryMode);
                        return recoverComponentSlotsByPartition(
                                item, payload, expectedSlots, promptContext, atomicGroupSizes);
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }

    /**
     * Some Component arrays are visually fragmented but grammatically atomic.
     * Splitting those arrays would preserve JSON shape while corrupting meaning,
     * so they use bounded whole-document retries and then fail closed. Ordinary
     * tooltip/menu arrays may still recover independent top-level slots.
     */
    private static boolean allowsAdaptiveComponentPartition(
            List<Integer> atomicGroupSizes, int expectedSlots) {
        return atomicGroupSizes != null && atomicGroupSizes.size() > 1
                && atomicGroupSizes.stream().allMatch(size -> size != null && size > 0)
                && atomicGroupSizes.stream().mapToInt(Integer::intValue).sum() == expectedSlots;
    }

    private static CompletableFuture<String> recoverComponentSlotsByPartition(
            BatchItem item, String sourcePayload, int expectedSlots, String promptContext,
            List<Integer> atomicGroupSizes) {
        int groupBoundary = atomicGroupBoundary(atomicGroupSizes, expectedSlots);
        if (groupBoundary <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        int leftCount = atomicGroupSizes.subList(0, groupBoundary).stream()
                .mapToInt(Integer::intValue).sum();
        int rightCount = expectedSlots - leftCount;
        List<String> partitionPayloads = bisectComponentRecoveryPayload(
                sourcePayload, expectedSlots, atomicGroupSizes);
        if (partitionPayloads.size() != 2) {
            return CompletableFuture.completedFuture(null);
        }
        List<Integer> leftGroups = List.copyOf(atomicGroupSizes.subList(0, groupBoundary));
        List<Integer> rightGroups = List.copyOf(
                atomicGroupSizes.subList(groupBoundary, atomicGroupSizes.size()));
        String leftContext = componentPartitionRecoveryContext(
                promptContext, leftCount, expectedSlots);
        String rightContext = componentPartitionRecoveryContext(
                promptContext, rightCount, expectedSlots);
        List<CompletableFuture<String>> requests = List.of(
                translateComponentSlotsReliably(
                        item, partitionPayloads.get(0), leftCount, leftContext, leftGroups, 0),
                translateComponentSlotsReliably(
                        item, partitionPayloads.get(1), rightCount, rightContext, rightGroups, 0));
        CompletableFuture<?>[] pending = requests.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(pending).thenApply(ignored -> {
            List<String> responses = new ArrayList<>(requests.size());
            for (CompletableFuture<String> request : requests) {
                responses.add(request.join());
            }
            return combineComponentPartitionResponses(
                    responses, List.of(leftCount, rightCount), item.surface());
        });
    }

    /** Bisects only at already-validated top-level Component boundaries. */
    private static List<String> bisectComponentRecoveryPayload(
            String sourcePayload, int expectedSlots) {
        return bisectComponentRecoveryPayload(
                sourcePayload, expectedSlots,
                java.util.Collections.nCopies(Math.max(0, expectedSlots), 1));
    }

    private static List<String> bisectComponentRecoveryPayload(
            String sourcePayload, int expectedSlots, List<Integer> atomicGroupSizes) {
        try {
            JsonElement parsed = JsonParser.parseString(
                    sourcePayload == null ? "" : sourcePayload.trim());
            if (expectedSlots <= 1 || !parsed.isJsonArray()
                    || parsed.getAsJsonArray().size() != expectedSlots) {
                return List.of();
            }
            JsonArray left = new JsonArray();
            JsonArray right = new JsonArray();
            int groupBoundary = atomicGroupBoundary(atomicGroupSizes, expectedSlots);
            if (groupBoundary <= 0) {
                return List.of();
            }
            int midpoint = atomicGroupSizes.subList(0, groupBoundary).stream()
                    .mapToInt(Integer::intValue).sum();
            for (int index = 0; index < expectedSlots; index++) {
                JsonElement element = parsed.getAsJsonArray().get(index);
                if (element == null || element.isJsonNull()
                        || ComponentJsonCompat.fromJson(element) == null) {
                    return List.of();
                }
                (index < midpoint ? left : right).add(element.deepCopy());
            }
            return List.of(left.toString(), right.toString());
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /** Chooses the nearest half-way boundary between original Components. */
    private static int atomicGroupBoundary(List<Integer> atomicGroupSizes, int expectedSlots) {
        if (!allowsAdaptiveComponentPartition(atomicGroupSizes, expectedSlots)) {
            return -1;
        }
        int bestBoundary = -1;
        int bestDistance = Integer.MAX_VALUE;
        int cumulative = 0;
        for (int index = 0; index < atomicGroupSizes.size() - 1; index++) {
            cumulative += atomicGroupSizes.get(index);
            int distance = Math.abs(expectedSlots - cumulative * 2);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestBoundary = index + 1;
            }
        }
        return bestBoundary;
    }

    /** Recombines only exact Component partitions in original request order. */
    private static String combineComponentPartitionResponses(
            List<String> responses, List<Integer> expectedCounts, String surface) {
        if (responses == null || expectedCounts == null || responses.size() != expectedCounts.size()) {
            return null;
        }
        JsonArray combined = new JsonArray();
        for (int index = 0; index < responses.size(); index++) {
            int expected = expectedCounts.get(index);
            JsonArray partition = parseExactComponentArray(
                    responses.get(index), expected, surface, combined.size());
            if (partition == null) {
                return null;
            }
            for (JsonElement element : partition) {
                combined.add(element.deepCopy());
            }
        }
        return combined.toString();
    }

    private static String componentPartitionRecoveryContext(
            String promptContext, int partitionTopLevelCount, int originalTopLevelCount) {
        JsonObject metadata;
        try {
            JsonElement parsed = JsonParser.parseString(
                    promptContext == null || promptContext.isBlank() ? "{}" : promptContext);
            metadata = parsed.isJsonObject()
                    ? parsed.getAsJsonObject().deepCopy() : new JsonObject();
        } catch (RuntimeException ignored) {
            metadata = new JsonObject();
        }
        metadata.addProperty("component_partition_recovery", true);
        metadata.addProperty("required_top_level_count", Math.max(0, partitionTopLevelCount));
        metadata.addProperty("source_document_top_level_count", Math.max(0, originalTopLevelCount));
        return metadata.toString();
    }

    private static boolean hasComponentStructureRetryRemaining(int completedAttempt) {
        return completedAttempt + 1 < MAX_COMPONENT_STRUCTURE_ATTEMPTS;
    }

    /**
     * Adds a machine-readable correction marker to the existing prompt metadata.
     * The user payload remains exactly the original top-level Component array.
     */
    private static String componentStructureAttemptContext(
            String promptContext, int expectedSlots, int structureAttempt) {
        if (structureAttempt <= 0) {
            return promptContext == null ? "" : promptContext;
        }
        JsonObject metadata;
        try {
            JsonElement parsed = JsonParser.parseString(
                    promptContext == null || promptContext.isBlank() ? "{}" : promptContext);
            metadata = parsed.isJsonObject() ? parsed.getAsJsonObject().deepCopy() : new JsonObject();
        } catch (RuntimeException ignored) {
            metadata = new JsonObject();
        }
        metadata.addProperty("component_structure_retry", true);
        metadata.addProperty("required_top_level_count", Math.max(0, expectedSlots));
        metadata.addProperty("structure_retry_attempt", structureAttempt);
        return metadata.toString();
    }

    @Nullable
    private static JsonArray parseExactComponentArray(
            String response, int expected, String surface, int offset) {
        try {
            JsonElement parsed = JsonParser.parseString(response == null ? "" : response.trim());
            if (!parsed.isJsonArray() || parsed.getAsJsonArray().size() != expected) {
                SimpleTranslateMod.getLogger().warn(
                        "JSON chunk rejected surface={} offset={} slotsExpected={} slotsActual={} reason=count",
                        surface, offset, expected,
                        parsed.isJsonArray() ? parsed.getAsJsonArray().size() : -1);
                return null;
            }
            JsonArray array = parsed.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                if (array.get(index) == null || array.get(index).isJsonNull()
                        || ComponentJsonCompat.fromJson(array.get(index)) == null) {
                    SimpleTranslateMod.getLogger().warn(
                            "JSON chunk rejected surface={} offset={} slot={} reason=component-parse",
                            surface, offset, offset + index);
                    return null;
                }
            }
            return array;
        } catch (RuntimeException exception) {
            SimpleTranslateMod.getLogger().warn(
                    "JSON chunk rejected surface={} offset={} slotsExpected={} reason=json-parse",
                    surface, offset, expected);
            return null;
        }
    }

    @Nullable
    private static List<Component> parseExactComponentList(
            String response, int expected, String surface, int offset) {
        JsonArray array = parseExactComponentArray(response, expected, surface, offset);
        if (array == null) {
            return null;
        }
        try {
            List<Component> components = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                Component component = ComponentJsonCompat.fromJson(element);
                if (component == null) {
                    return null;
                }
                components.add(component);
            }
            return List.copyOf(components);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static CompletableFuture<List<Component>> finishRequest(
            List<Component> originals, String sourceJson, String response,
            String surface, String cacheKey, TranslationLane lane, TranslationLane.Lease lease,
            long runtimeRevision, long textContextRevision,
            String sourceLanguage, String targetLanguage,
            ComponentVisualProjection projection) {
        try {
            if (!ModConfig.GLOBAL_ENABLED.get()
                    || !SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                    || !TextContextMemory.isRevisionCurrent(textContextRevision)) {
                lane.finish(lease);
                return CompletableFuture.completedFuture(null);
            }
            if (response == null || response.isBlank()) {
                lane.fail(lease, FAILURE_RETRY_MS);
                RecoveryPolicy.recordRejected(cacheKey);
                SimpleTranslateMod.getLogger().warn(
                        "JSON passthrough: blank response surface={} key={}", surface, cacheKey);
                return CompletableFuture.completedFuture(null);
            }

            List<Component> semanticResponse = parseExactComponentList(
                    response, projection.slotCount(), surface, 0);
            if (semanticResponse == null) {
                lane.fail(lease, FAILURE_RETRY_MS);
                RecoveryPolicy.recordRejected(cacheKey);
                SimpleTranslateMod.getLogger().warn(
                        "JSON passthrough: invalid semantic Component response surface={} key={}",
                        surface, cacheKey);
                return CompletableFuture.completedFuture(null);
            }

            DecodedProjectedResponse decoded = decodeProjectedResponse(
                    response, originals, sourceJson, surface, projection);
            if (decoded == null) {
                lane.fail(lease, FAILURE_RETRY_MS);
                RecoveryPolicy.recordRejected(cacheKey);
                SimpleTranslateMod.getLogger().warn(
                        "JSON passthrough: deserialization failed surface={} key={}", surface, cacheKey);
                return CompletableFuture.completedFuture(null);
            }

            List<Component> accepted = acceptRestored(
                    originals, sourceJson, decoded.cacheTemplate(), decoded.components(),
                    cacheKey, lane, lease,
                    runtimeRevision, textContextRevision, sourceLanguage, targetLanguage);
            return CompletableFuture.completedFuture(accepted);
        } catch (Exception e) {
            lane.fail(lease, FAILURE_RETRY_MS);
            RecoveryPolicy.recordRejected(cacheKey);
            SimpleTranslateMod.getLogger().warn(
                    "JSON passthrough: exception surface={} key={}", surface, cacheKey, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    static List<Component> acceptRestored(
            List<Component> originals, String sourceJson, String response, List<Component> restored,
            String cacheKey, TranslationLane lane, TranslationLane.Lease lease,
            long runtimeRevision, long textContextRevision,
            String sourceLanguage, String targetLanguage) {
        if (!ModConfig.GLOBAL_ENABLED.get()
                || !SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                || !TextContextMemory.isRevisionCurrent(textContextRevision)) {
            lane.finish(lease);
            return null;
        }
        List<Component> visibleRestored = reattachOriginalHoverEvents(restored, originals);
        cacheSuccessfulResponse(cacheKey, response, sourceJson, originals, visibleRestored,
                runtimeRevision, sourceLanguage, targetLanguage);
        RecoveryPolicy.recordSuccess(cacheKey);
        lane.finish(lease);
        return visibleRestored;
    }

    @Nullable
    public static List<Component> deserializeComponents(String response, List<Component> originals) {
        return deserializeComponents(response, originals, null);
    }

    /** Rebinds marker-free semantic response slots into the local source tree. */
    @Nullable
    private static DecodedProjectedResponse decodeProjectedResponse(
            String response, List<Component> originals, String sourceJson, @Nullable String surface,
            ComponentVisualProjection projection) {
        if (projection == null || response == null || response.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(response.trim());
            JsonArray rebuilt = projection.rebuildResponse(root);
            if (rebuilt == null) {
                int actual = root != null && root.isJsonArray()
                        ? root.getAsJsonArray().size() : -1;
                SimpleTranslateMod.getLogger().warn(
                        "JSON passthrough: invalid semantic component array surface={} "
                                + "slotsExpected={} slotsActual={} responseChars={}",
                        surface, projection.slotCount(), actual, response.length());
                return null;
            }
            String cacheTemplate = rebuilt.toString();
            if (!cacheTemplateMatchesSourceMarkers(sourceJson, cacheTemplate)) {
                return null;
            }
            List<Component> restored = finalizeTranslatedTree(
                    rebuilt.deepCopy(), originals, surface, response);
            return restored == null ? null
                    : new DecodedProjectedResponse(cacheTemplate, restored);
        } catch (Exception e) {
            SafeTranslate.logLimited("json-passthrough.decodeProjectedResponse",
                    "parse failed: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    private static List<Component> deserializeComponents(
            String response, List<Component> originals, @Nullable String surface) {
        try {
            JsonElement root = JsonParser.parseString(response.trim());
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
            return finalizeTranslatedTree(array, originals, surface, response);
        } catch (Exception e) {
            SafeTranslate.logLimited("json-passthrough.deserializeRoot",
                    "root parse failed: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    static List<Component> finalizeTranslatedTree(
            JsonArray array, List<Component> originals, @Nullable String surface, String response) {
        // The projection already restored the exact local source skeleton.
        // Reinsert the live values before parsing so a cache entry created for
        // "Progress 1/2" renders the current "Progress 2/2" rather than stale
        // numbers. Non-layout custom fonts may then need a default-font CJK remount.
        List<String> dynamicValues = extractDynamicValues(originals);
        if (!dynamicValues.isEmpty()) {
            restoreNumbersInTree(array, dynamicValues);
        }
        if (ComponentJsonNumberNormalizer.containsDynamicMarker(array)) {
            // Internal markers are never display text. A current response with
            // an unknown marker is rejected; an old cache entry reaches the
            // caller's existing lazy remove-and-save path.
            return null;
        }
        if (!isLayoutCriticalHudTree(array)) {
            sanitizeTranslatedFonts(array);
        }
        int expected = originals.size();
        if (array.size() != expected) {
            logDeserializationFailure(surface, "size-mismatch", originals, array.size(), response);
            return null;
        }
        List<Component> result = new ArrayList<>(expected);
        for (int i = 0; i < expected; i++) {
            JsonElement element = normalizeComponentJson(array.get(i));
            try {
                Component component = ComponentJsonCompat.fromJson(element);
                if (component == null) {
                    logDeserializationFailure(surface, "component-null", originals, array.size(), response);
                    return null;
                }
                component = ComponentJsonCompat.reattachLocalFonts(component, originals.get(i));
                result.add(component);
            } catch (Exception e) {
                SafeTranslate.logLimited("json-passthrough.deserializeElement",
                        "element {} failed: {}", i, e.getMessage());
                logDeserializationFailure(surface, "component-parse-failed", originals, array.size(), response);
                return null;
            }
        }
        return List.copyOf(result);
    }

    private static void sanitizeTranslatedFonts(JsonElement element) {
        ComponentJsonLayoutGuard.sanitizeTranslatedFonts(element);
    }

    /**
     * True when a CJK target should leave a multi-region PUA-positioned HUD tree
     * untranslated. Custom layout fonts rarely ship CJK glyphs; translating and
     * then remounting CJK onto default collapses absolute positioning.
     */
    public static boolean shouldKeepLayoutCriticalHudOriginal(String sourceJson, String targetLanguage) {
        return ComponentJsonLayoutGuard.shouldKeepLayoutCriticalHudOriginal(sourceJson, targetLanguage);
    }

    /**
     * The explicit original-only escape hatch is for actionbar coordinate
     * streams. It must not swallow a Wynn tooltip merely because the tooltip
     * happens to contain a decorative custom font or a PUA icon.
     */
    public static boolean shouldKeepLayoutCriticalHudOriginal(String surface, String sourceJson,
                                                              String targetLanguage) {
        return ComponentJsonLayoutGuard.shouldKeepLayoutCriticalHudOriginal(surface, sourceJson, targetLanguage);
    }

    /**
     * Detects HUD trees whose font metrics encode placement. A known layout
     * font (for example Wynncraft's {@code hud/selector} families) needs only
     * one private-use positioning glyph to be layout-critical. The older
     * multi-font/PUA threshold remains as a fallback for unknown font packs.
     */
    public static boolean isLayoutCriticalHudTree(JsonElement root) {
        return ComponentJsonLayoutGuard.isLayoutCriticalHudTree(root);
    }

    /**
     * In-place layout contract for layout-critical HUD trees: the translated
     * tree must mirror the source skeleton node-for-node — same array sizes,
     * same object keys, identical fonts/styles/click data — with only {@code
     * text} content (and translatable bare-string leaves) allowed to change.
     * Non-layout trees are exempt. Wrapper nodes, split siblings, or fonts
     * remounted onto {@code minecraft:default} all violate the contract.
     */
    public static boolean satisfiesInPlaceLayoutContract(String translationJson, String sourceJson) {
        return ComponentJsonLayoutGuard.satisfiesInPlaceLayoutContract(translationJson, sourceJson);
    }

    /**
     * True when a cached translation remounted CJK onto {@code minecraft:default}
     * while the source still uses layout-critical fonts — the v3 collapse pattern.
     */
    public static boolean isLayoutBrokenCustomFontTranslation(String translationJson, String sourceJson) {
        return ComponentJsonLayoutGuard.isLayoutBrokenCustomFontTranslation(translationJson, sourceJson);
    }

    /**
     * Fonts whose resource path encodes screen regions (Wynncraft HUD selector
     * fonts, etc.). Remounting visible text off these fonts breaks absolute
     * positioning even when PUA siblings keep the original font.
     */
    public static boolean isLayoutCriticalFont(@Nullable String font) {
        return ComponentJsonLayoutGuard.isLayoutCriticalFont(font);
    }

    // Logic-check anchors: layout detection and CJK font split stay named here.
    // Implementation: ComponentJsonLayoutGuard (stats.hasKnownLayoutFont && stats.privateUseGlyphs > 0)
    // and (stats.layoutFontIds.size() >= 2 && stats.privateUseGlyphs >= 4); puaLayoutFont;
    // containsProtectedFontRuns.

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
        JsonElement hover = hoverEvent(component);
        if (hover == null || !hover.isJsonObject()) {
            return;
        }
        JsonObject hoverEvent = hover.getAsJsonObject();
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
        removeHoverEvent(object);
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            keys.add(entry.getKey());
        }
        for (String key : keys) {
            stripHoverEvents(object.get(key));
        }
    }

    /** Reattaches hidden hover payloads after an external current-skeleton rebuild. */
    public static List<Component> reattachOriginalHoverEventsForRender(
            List<Component> translated, List<Component> originals) {
        return reattachOriginalHoverEvents(translated, originals);
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
            JsonElement translatedJson = JsonParser.parseString(ComponentJsonCompat.toJson(translated));
            JsonElement originalJson = JsonParser.parseString(ComponentJsonCompat.toJson(original));
            translatedJson = objectTargetForHoverCopy(translatedJson, originalJson);
            copyOriginalHoverEvents(originalJson, translatedJson);
            Component restored = ComponentJsonCompat.fromJson(translatedJson);
            return restored == null ? translated
                    : ComponentJsonCompat.reattachLocalFonts(restored, original);
        } catch (Exception e) {
            SafeTranslate.logLimited("json-passthrough.reattachHoverEvents", e);
            return translated;
        }
    }

    private static JsonElement objectTargetForHoverCopy(JsonElement translated, JsonElement original) {
        if (translated == null || translated.isJsonObject() || original == null || !original.isJsonObject()) {
            return translated;
        }
        JsonObject originalObject = original.getAsJsonObject();
        if (!hasHoverEvent(originalObject)) {
            return translated;
        }
        JsonObject wrapped = new JsonObject();
        if (translated.isJsonPrimitive()) {
            wrapped.addProperty("text", translated.getAsString());
        } else {
            wrapped.add("extra", translated.deepCopy());
        }
        return wrapped;
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
        JsonElement originalHoverEvent = hoverEvent(originalObject);
        if (originalHoverEvent != null) {
            translatedObject.add(hoverEventKey(originalObject), originalHoverEvent.deepCopy());
            removeOtherHoverEventKey(translatedObject, hoverEventKey(originalObject));
        } else {
            removeHoverEvent(translatedObject);
        }

        List<Map.Entry<String, JsonElement>> translatedEntries = new ArrayList<>(translatedObject.entrySet());
        for (Map.Entry<String, JsonElement> entry : translatedEntries) {
            String key = entry.getKey();
            if (isHoverEventKey(key)) {
                continue;
            }
            JsonElement originalChild = originalObject.get(key);
            copyOriginalHoverEvents(originalChild, entry.getValue());
        }
    }

    private static boolean hasHoverEvent(JsonObject object) {
        return hoverEvent(object) != null;
    }

    private static JsonElement hoverEvent(JsonObject object) {
        if (object == null) {
            return null;
        }
        if (object.has("hoverEvent")) {
            return object.get("hoverEvent");
        }
        if (object.has("hover_event")) {
            return object.get("hover_event");
        }
        return null;
    }

    private static String hoverEventKey(JsonObject object) {
        return object != null && object.has("hover_event") ? "hover_event" : "hoverEvent";
    }

    private static void removeHoverEvent(JsonObject object) {
        if (object == null) {
            return;
        }
        object.remove("hoverEvent");
        object.remove("hover_event");
    }

    private static void removeOtherHoverEventKey(JsonObject object, String retainedKey) {
        if (object == null) {
            return;
        }
        if (!"hoverEvent".equals(retainedKey)) {
            object.remove("hoverEvent");
        }
        if (!"hover_event".equals(retainedKey)) {
            object.remove("hover_event");
        }
    }

    private static boolean isHoverEventKey(String key) {
        return "hoverEvent".equals(key) || "hover_event".equals(key);
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
        if (usesCurrentGlobalLanguages(sourceLanguage, targetLanguage)
                && (!sourceLanguage.equals(ModConfig.SOURCE_LANGUAGE.get())
                || !targetLanguage.equals(ModConfig.TARGET_LANGUAGE.get()))) {
            return;
        }
        if (!cacheTemplateMatchesSourceMarkers(sourceJson, response)) {
            return;
        }
        cache.putComponentJson(cacheKey, response, sourceJson,
                plainText(originals), plainText(restored),
                TranslationPromptPolicy.cacheFingerprint(
                        TranslationCacheKeys.surfaceFromKey(cacheKey)));
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

    /**
     * Supplies the model with the readable source around locally retained
     * numbers, coordinates and visual glyphs.  The request itself remains the
     * strict semantic Component array; this is context metadata only.
     */
    public static String semanticPromptSourceBlock(List<Component> components) {
        String source = plainText(components);
        if (source.isBlank()) {
            return "";
        }
        StringBuilder readable = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); ) {
            int cp = source.codePointAt(index);
            int width = Character.charCount(cp);
            if (cp == '\u00a7' && index + width < source.length()) {
                index += width + Character.charCount(source.codePointAt(index + width));
                continue;
            }
            int type = Character.getType(cp);
            if (cp == '\n' || cp == '\r') {
                readable.append('\n');
            } else if (type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.PRIVATE_USE || type == Character.UNASSIGNED
                    || type == Character.SURROGATE || type == Character.OTHER_SYMBOL) {
                readable.append(' ');
            } else {
                readable.appendCodePoint(cp);
            }
            index += width;
        }
        return readable.toString()
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** Stable cache-context form that shows local value gaps without key churn. */
    public static String semanticPromptSourceShape(List<Component> components) {
        String readable = semanticPromptSourceBlock(components);
        return readable.isBlank() ? ""
                : ComponentJsonNumberNormalizer.maskPromptDynamicNumbers(readable);
    }

    private record DecodedProjectedResponse(String cacheTemplate, List<Component> components) {
    }

    static final record BatchItem(TranslationManager manager,
                             List<Component> originals,
                             String sourceJson,
                             String surface,
                             String role,
                             String context,
                             String cacheKey,
                             TranslationLane lane,
                             TranslationLane.Lease lease,
                             long runtimeRevision,
                             long textContextRevision,
                             String sourceLanguage,
                             String targetLanguage,
                             CompletableFuture<List<Component>> future) {
    }
}
