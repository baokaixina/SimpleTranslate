package com.yourname.simpletranslate.feature.tooltip;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.ComponentJsonCompat;
import com.yourname.simpletranslate.core.ComponentVisualProjection;
import com.yourname.simpletranslate.core.TranslationTextDetector;
import com.yourname.simpletranslate.core.TextSegmentInfo;
import com.yourname.simpletranslate.core.ComponentSegmentHelper;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.ComponentTranslationResult;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.LineTranslationMemory;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import com.yourname.simpletranslate.core.TextContextMemory;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin tooltip/hover adapter. Formatting and semantic block translation live in
 * DirectSurfaceTranslator; this class only keeps shared convenience APIs
 * used by mixins and older surfaces.
 */
public final class TooltipTranslationHelper {
    public static final String HOVER_OVERLAY_SURFACE = "hover.overlay.direct";
    public static final String HOVER_OVERLAY_ROLE = "hover-overlay-batch";
    public static final String HOVER_CONTEXT_SURFACE = "hover.context.direct";
    public static final String HOVER_CONTEXT_ROLE = "hover-block-context";
    public static final String HOVER_SEMANTIC_SURFACE = "tooltip.visible.chat_hover.component.v2";
    public static final String BOOK_SEMANTIC_SURFACE = "tooltip.visible.book_hover.component.v2";
    /** Read-only cache-migration identity; new item requests use the GUI frame surface. */
    public static final String LEGACY_ITEM_SEMANTIC_SURFACE = "tooltip.visible.item.component.v2";
    private static final String SEMANTIC_ROLE = "visible-tooltip-component";

    private static final int MAX_TRANSLATED_TOOLTIP_WIDTH = 360;
    private static final int MAX_TRANSLATED_SIGNATURES = 4096;
    private static final Set<String> PENDING_ASYNC_REFRESH_SIGNATURES = ConcurrentHashMap.newKeySet();
    /**
     * Content signature → expiry deadline (nanos) for "a translation is actively in
     * flight". Decouples the pending-translation glow from the single-shot pending
     * signal: under shortcut mode {@code requestAllowed} is only true on the trigger
     * frame, so the glow must follow this latch (set when the request starts, cleared
     * when the result is marked translated or after a safety timeout) for the whole
     * translation, exactly like hover mode keeps its glow on.
     */
    private static final java.util.Map<String, Long> ACTIVE_TRANSLATION_GLOW = new ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> SEMANTIC_PENDING_STARTED = new ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> SEMANTIC_RETRY_AFTER_NANOS = new ConcurrentHashMap<>();
    private static final long ACTIVE_TRANSLATION_GLOW_TIMEOUT_NANOS = 180_000_000_000L;
    private static final long SEMANTIC_FAILURE_RETRY_NANOS = 6_000_000_000L;
    // Render recursion markers are identity-only and bounded. Content-signature
    // markers can incorrectly classify a source-equal model response as
    // permanently translated and hide a later READY Chinese result.
    private static final IdentityMarker<List<Component>> TRANSLATED_COMPONENT_LISTS =
            new IdentityMarker<>(MAX_TRANSLATED_SIGNATURES);
    private static final IdentityMarker<Component> TRANSLATED_COMPONENTS =
            new IdentityMarker<>(MAX_TRANSLATED_SIGNATURES);

    private TooltipTranslationHelper() {
    }

    public static boolean containsEnglish(String text) {
        return containsEnglish(text, 1);
    }

    public static boolean containsEnglish(String text, int minLetters) {
        if (text == null || text.isEmpty() || isBlacklisted(text)) {
            return false;
        }
        return TranslationTextDetector.containsTranslatableText(text);
    }

    public static boolean isBlacklisted(String text) {
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        return blacklist != null && blacklist.isBlacklisted(text);
    }

    public static boolean containsBlacklistedText(String text) {
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        return blacklist != null && blacklist.containsBlacklistedEntry(text);
    }

    public static boolean isTranslationPending(List<Component> components,
                                               TooltipTranslationController.RenderContext context) {
        if (components == null || components.isEmpty()) {
            return false;
        }
        String signature = tooltipSignature(components);
        if (signature.isBlank()) {
            return false;
        }
        String semanticSignature = tooltipSemanticSignature(components);
        ComponentVisualProjection projection = JsonPassthroughPipeline.projectLiveComponents(
                components, ModConfig.TARGET_LANGUAGE.get());
        if (projection != null && projection.hasSlots()) {
            String projectedKey = semanticPendingKey(semanticSurfaceFor(context), projection);
            Long started = SEMANTIC_PENDING_STARTED.get(projectedKey);
            if (started != null) {
                if (System.nanoTime() - started <= ACTIVE_TRANSLATION_GLOW_TIMEOUT_NANOS) {
                    return true;
                }
                SEMANTIC_PENDING_STARTED.remove(projectedKey);
                PENDING_ASYNC_REFRESH_SIGNATURES.remove(projectedKey);
                deferSemanticRetry(projectedKey, System.nanoTime());
            }
        }
        return PENDING_ASYNC_REFRESH_SIGNATURES.contains(signature)
                || PENDING_ASYNC_REFRESH_SIGNATURES.contains(semanticSignature);
    }

    public static boolean isHoverTranslationPending(Component component) {
        if (component == null) {
            return false;
        }
        List<Component> lines = splitComponentByNewlines(component);
        return isTranslationPending(lines, TooltipTranslationController.RenderContext.CHAT_OVERLAY)
                || isActiveTranslationGlow(lines);
    }

    /** Marks a content as actively translating so the pending-translation glow persists. */
    private static void markActiveTranslationGlow(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + ACTIVE_TRANSLATION_GLOW_TIMEOUT_NANOS;
        String signature = tooltipSignature(components);
        String semanticSignature = tooltipSemanticSignature(components);
        if (!signature.isBlank()) {
            ACTIVE_TRANSLATION_GLOW.put(signature, deadline);
        }
        if (!semanticSignature.isBlank()) {
            ACTIVE_TRANSLATION_GLOW.put(semanticSignature, deadline);
        }
    }

    private static void clearActiveTranslationGlow(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return;
        }
        ACTIVE_TRANSLATION_GLOW.remove(tooltipSignature(components));
        ACTIVE_TRANSLATION_GLOW.remove(tooltipSemanticSignature(components));
    }

    private static boolean isActiveTranslationGlow(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return false;
        }
        return activeGlowAlive(tooltipSignature(components)) || activeGlowAlive(tooltipSemanticSignature(components));
    }

    private static boolean activeGlowAlive(String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        Long deadline = ACTIVE_TRANSLATION_GLOW.get(signature);
        if (deadline == null) {
            return false;
        }
        if (System.nanoTime() > deadline) {
            ACTIVE_TRANSLATION_GLOW.remove(signature);
            return false;
        }
        return true;
    }

    public static List<Component> splitHoverComponentLinesForRender(Component component) {
        if (component == null) {
            return List.of();
        }
        return wrapPendingHoverLines(splitComponentByNewlines(component));
    }

    public static List<Component> splitHoverComponentsLinesForRender(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return components == null ? List.of() : components;
        }
        List<Component> lines = new ArrayList<>();
        int sourceSize = components.size();
        for (Component component : components) {
            lines.addAll(splitComponentByNewlines(component));
        }
        List<Component> wrapped = wrapPendingHoverLines(lines);
        return wrapped.size() != sourceSize || !wrapped.equals(components) ? wrapped : components;
    }

    private static List<Component> wrapPendingHoverLines(List<Component> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines == null ? List.of() : lines;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft == null ? null : minecraft.font;
        if (font == null) {
            return lines;
        }

        int maxWidth = pendingHoverTooltipMaxWidth();
        List<Component> wrapped = new ArrayList<>();
        boolean changed = false;
        for (Component line : lines) {
            if (line == null || font.width(line) <= maxWidth) {
                wrapped.add(line);
                continue;
            }
            List<Component> split = wrapStyledTooltipComponent(line, maxWidth, font);
            wrapped.addAll(split);
            changed |= split.size() != 1 || split.get(0) != line;
        }
        return changed ? wrapped : lines;
    }

    private static int pendingHoverTooltipMaxWidth() {
        Minecraft minecraft = Minecraft.getInstance();
        int screenLimit = minecraft == null || minecraft.getWindow() == null
                ? MAX_TRANSLATED_TOOLTIP_WIDTH
                : Math.max(120, minecraft.getWindow().getGuiScaledWidth() - 80);
        return Math.max(120, Math.min(MAX_TRANSLATED_TOOLTIP_WIDTH, screenLimit));
    }

    public static Component translateComponentWithStyle(Component component) {
        return SafeTranslate.guard(() -> {
            if (component == null || isMarkedTranslatedTooltip(component) || !containsEnglish(component.getString())) {
                return component;
            }
            ComponentTranslationResult direct =
                    DirectSurfaceTranslator.translateComponent(component, "hover.component.direct", "hover-component");
            if (direct.handled && direct.translated && direct.component != null) {
                markTranslatedTooltip(direct.component);
                return direct.component;
            }
            return component;
        }, component, "tooltip.translateComponentWithStyle");
    }

    public static List<Component> translateHoverComponentLines(Component component) {
        return translateHoverComponentLines(component, true);
    }

    private static Component lastHoverLinesSource;
    private static boolean lastHoverLinesAllowRequest;
    private static long lastHoverLinesRevision = -1L;
    private static List<Component> lastHoverLinesResult;

    public static List<Component> translateHoverComponentLines(Component component, boolean allowRequest) {
        if (component != null) {
            long revision = TooltipSemanticResultStore.revision();
            if (component == lastHoverLinesSource && allowRequest == lastHoverLinesAllowRequest
                    && revision == lastHoverLinesRevision) {
                return lastHoverLinesResult;
            }
            List<Component> result = translateHoverComponentLinesInner(component, allowRequest);
            lastHoverLinesSource = component;
            lastHoverLinesAllowRequest = allowRequest;
            lastHoverLinesRevision = revision;
            lastHoverLinesResult = result;
            return result;
        }
        return translateHoverComponentLinesInner(component, allowRequest);
    }

    private static List<Component> translateHoverComponentLinesInner(Component component, boolean allowRequest) {
        List<Component> fallback = component == null ? List.of() : List.of(component);
        return SafeTranslate.guard(() -> {
            if (component == null) {
                return List.of();
            }
            if (isMarkedTranslatedTooltip(component) || !containsEnglish(component.getString())) {
                return List.of(component);
            }

            List<Component> lines = splitComponentByNewlines(component);
            List<Component> translated = translateSemanticProjection(
                    lines, TooltipTranslationController.RenderContext.CHAT_OVERLAY, allowRequest);
            // Preserve the caller's identity contract on a cache miss. The
            // hover mixin uses this exact singleton to let vanilla draw the
            // original while the pending glow is armed.
            return translated == lines ? List.of(component) : translated;
        }, fallback, "tooltip.translateHoverComponentLines");
    }

    public static Component translateComponent(Component component) {
        if (component == null || isMarkedTranslatedTooltip(component) || !containsEnglish(component.getString())) {
            return component;
        }
        return SafeTranslate.guard(() -> translateComponentImpl(component), component, "tooltip.translateComponent");
    }

    private static Component translateComponentImpl(Component component) {
        ComponentTranslationResult direct =
                DirectSurfaceTranslator.translateComponent(component, "manager.component.direct", "component");
        if (direct.handled && direct.translated && direct.component != null) {
            markTranslatedTooltip(direct.component);
            return direct.component;
        }
        return component;
    }

    public static boolean anyContainsEnglish(List<Component> components) {
        if (components == null) {
            return false;
        }
        for (Component component : components) {
            if (component != null && containsEnglish(component.getString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasVisibleTextChange(Component source, Component translated) {
        if (translated == null) {
            return false;
        }
        if (source == null) {
            return true;
        }
        return !TranslationCacheKeys.normalizeSemanticSource(source.getString())
                .equals(TranslationCacheKeys.normalizeSemanticSource(translated.getString()));
    }

    private static boolean hasVisibleTextChange(List<Component> source, List<Component> translated) {
        if (translated == null) {
            return false;
        }
        if (source == null) {
            return true;
        }
        return !normalizedVisibleText(source).equals(normalizedVisibleText(translated));
    }

    private static String normalizedVisibleText(List<Component> components) {
        StringBuilder visible = new StringBuilder();
        for (Component component : components) {
            if (component != null) {
                visible.append(component.getString());
            }
            visible.append(' ');
        }
        return TranslationCacheKeys.normalizeSemanticSource(visible.toString());
    }

    private static String classifyTooltipLine(int index, String text) {
        if (text == null || text.isBlank()) {
            return "empty";
        }
        String normalized = text.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        String compact = lower.replaceAll("[^a-z0-9+:%/\\- ]", " ").replaceAll("\\s+", " ").trim();
        if (index == 0) {
            return "title";
        }
        if (normalized.replaceAll("[\\s§0-9a-fk-orA-FK-OR]", "").matches("[-=_*~•·.]+")) {
            return "separator";
        }
        if (compact.contains("shift") || compact.contains("ctrl") || compact.contains("control")
                || compact.contains("alt") || compact.contains("space") || compact.contains("delete")
                || compact.contains("right-click") || compact.contains("left-click")
                || compact.contains("click") || compact.startsWith("press ")) {
            return "hotkey";
        }
        if (compact.startsWith("used for ") || compact.startsWith("used to ")
                || compact.startsWith("use for ") || compact.startsWith("use to ")
                || compact.startsWith("place ") || compact.startsWith("hold ")
                || compact.contains(" special trade")) {
            return "usage";
        }
        if (compact.contains(":") || compact.matches(".*\\d.*")
                || containsAny(compact, "damage", "mana", "cooldown", "duration", "range", "radius",
                "health", "armor", "toughness", "resistance", "attack", "speed", "ability", "effect",
                "bonus", "bonuses", "cast", "target", "targets", "second", "seconds", "when worn",
                "while worn", "on hit", "on use", "level", "chance", "cost")) {
            return "mechanic";
        }
        return "lore";
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMarkedTranslatedTooltip(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return false;
        }
        return TRANSLATED_COMPONENT_LISTS.contains(components);
    }

    public static boolean isMarkedTranslatedTooltip(Component component) {
        if (component == null) {
            return false;
        }
        return TRANSLATED_COMPONENTS.contains(component);
    }

    public static void markTranslatedTooltip(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return;
        }
        TRANSLATED_COMPONENT_LISTS.add(components);
        // The GUI frame sees individual draw Components rather than the
        // tooltip list. Mark each result as well so K cannot recollect it.
        for (Component component : components) {
            markTranslatedTooltip(component);
        }
    }

    public static void markTranslatedTooltip(Component component) {
        if (component == null) {
            return;
        }
        TRANSLATED_COMPONENTS.add(component);
    }

    public static void clearPendingCache() {
        TRANSLATED_COMPONENT_LISTS.clear();
        TRANSLATED_COMPONENTS.clear();
        PENDING_ASYNC_REFRESH_SIGNATURES.clear();
        ACTIVE_TRANSLATION_GLOW.clear();
        SEMANTIC_PENDING_STARTED.clear();
        SEMANTIC_RETRY_AFTER_NANOS.clear();
        TooltipSemanticResultStore.clear();
    }

    /**
     * Optional Iceberg / Legendary Tooltips gather path: translates already
     * gathered tooltip lines through the same semantic projection pipeline as
     * the vanilla item tooltip frame. Returns the input list unchanged when
     * nothing may translate yet (cache miss before a request is allowed).
     */
    public static List<Component> translateGatheredTooltipLines(
            List<Component> components,
            TooltipTranslationController.RenderContext context,
            boolean allowRequest) {
        if (components == null || components.isEmpty()) {
            return components;
        }
        return SafeTranslate.guard(() -> translateSemanticProjection(components, context, allowRequest),
                components, "tooltip.translateGatheredTooltipLines");
    }

    private static List<Component> translateSemanticProjection(
            List<Component> components,
            TooltipTranslationController.RenderContext context,
            boolean allowRequest) {
        ComponentVisualProjection projection = JsonPassthroughPipeline.projectLiveComponents(
                components, ModConfig.TARGET_LANGUAGE.get());
        if (projection == null || !projection.hasSlots()) {
            return components;
        }
        List<Component> semantic = projection.semanticComponents();
        String surface = semanticSurfaceFor(context);
        // Cache/prompt identity must follow the stable semantic projection, not
        // raw resource-pack glyph streams that may animate between frames.
        String stableContext = semanticContext(context, semantic);
        String readyKey = semanticPendingKey(surface, projection);
        String reuseScope = semanticReuseScope(context, projection);

        // Promote a completed asynchronous result directly on the next render
        // frame. Store semantic Components rather than the rebuilt tooltip so
        // this frame's live numbers, icons and spacing remain authoritative.
        List<Component> ready = TooltipSemanticResultStore.get(readyKey);
        if (ready != null) {
            if (ready.size() == projection.slotCount()) {
                List<Component> rebuilt = rebuildSemanticResult(projection, ready, components);
                if (rebuilt != null) {
                    clearActiveTranslationGlow(components);
                    return rebuilt;
                }
            }
            TooltipSemanticResultStore.remove(readyKey);
        }

        ComponentListTranslationResult cached = DirectSurfaceTranslator.getCachedComponents(
                components, surface, SEMANTIC_ROLE, false, stableContext);
        if (!cached.handled) {
            return components;
        }
        if (cached.translated && cached.components != null && !cached.components.isEmpty()) {
            List<Component> readySemantic = translatedSemanticComponents(projection, cached.components);
            if (readySemantic != null && !readySemantic.isEmpty()) {
                TooltipSemanticResultStore.put(readyKey, readySemantic);
                recordScopedSemanticTranslations(
                        projection, readySemantic, surface, reuseScope, false);
            }
            List<Component> translated = constrainTranslatedTooltipLines(cached.components, components);
            if (translated != null && !translated.isEmpty()) {
                markTranslatedTooltip(translated);
                clearActiveTranslationGlow(components);
                return translated;
            }
        }
        // Lazy, read-only bridge for pre-unified semantic_paragraph entries. It
        // never drives a new model request: while the existing Chinese result is
        // displayed, the unified path below seeds its own current cache. Retired
        // tooltip.visible.component.v1 results are intentionally incompatible:
        // that generation could omit short custom-font words such as "an".
        List<Component> legacy = tryLegacySemanticCacheCandidate(
                components, projection, surface, readyKey, reuseScope);
        if (legacy != null && !legacy.isEmpty()) {
            JsonPassthroughPipeline.cacheResolvedComponents(
                    components, legacy, surface, SEMANTIC_ROLE, stableContext,
                    SimpleTranslateMod.getRuntimeRevision());
            legacy = constrainTranslatedTooltipLines(legacy, components);
            markTranslatedTooltip(legacy);
            return legacy;
        }

        SemanticDeltaPlan delta = planSemanticDelta(
                projection, surface, reuseScope,
                ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
        if (delta.fullyResolved()) {
            List<Component> readySemantic = delta.resolvedComponents();
            TooltipSemanticResultStore.put(readyKey, readySemantic);
            List<Component> cacheable = projection.rebuildComponentList(readySemantic);
            if (cacheable != null && !cacheable.isEmpty()) {
                JsonPassthroughPipeline.cacheResolvedComponents(
                        components, cacheable, surface, SEMANTIC_ROLE, stableContext,
                        SimpleTranslateMod.getRuntimeRevision());
            }
            List<Component> rebuilt = rebuildSemanticResult(projection, readySemantic, components);
            if (rebuilt != null) {
                clearActiveTranslationGlow(components);
                return rebuilt;
            }
            TooltipSemanticResultStore.remove(readyKey);
        }
        if (allowRequest) {
            scheduleSemanticRefresh(components, projection, surface, stableContext, delta);
        }
        return components;
    }

    private static List<Component> tryLegacySemanticCacheCandidate(
            List<Component> components, ComponentVisualProjection projection,
            String surface, String readyKey, String reuseScope) {
        return tryLegacySemanticCacheCandidate(
                SimpleTranslateMod.getTranslationCache(), components, projection,
                surface, readyKey, reuseScope);
    }

    private static List<Component> tryLegacySemanticCacheCandidate(
            TranslationCache cache, List<Component> components, ComponentVisualProjection projection,
            String surface, String readyKey, String reuseScope) {
        if (cache == null || projection == null || !projection.hasSlots()) {
            return null;
        }
        String sourceText = projection.semanticComponents().stream()
                .map(Component::getString).reduce("", (left, right) ->
                        left.isEmpty() ? right : left + '\n' + right);
        if (sourceText.isBlank()) {
            return null;
        }
        String exactKey = TranslationCacheKeys.componentJsonKey(
                surface, projection.semanticJson(), "",
                ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
        for (TranslationCache.SemanticCacheCandidate candidate
                : cache.getSemanticBySource(sourceText, exactKey)) {
            String candidateSurface = TranslationCacheKeys.surfaceFromKey(candidate.sourceKey());
            if (!isCompatibleLegacyTooltipSurface(candidateSurface)) {
                continue;
            }
            List<Component> semantic = parseExactSemanticCandidate(
                    candidate.payload(), projection.slotCount());
            if (semantic == null) {
                continue;
            }
            List<Component> rebuilt = projection.rebuildComponentList(semantic);
            if (rebuilt == null || rebuilt.isEmpty()) {
                continue;
            }
            TooltipSemanticResultStore.put(readyKey, semantic);
            recordScopedSemanticTranslations(
                    projection, semantic, surface, reuseScope, false);
            return JsonPassthroughPipeline.reattachOriginalHoverEventsForRender(
                    rebuilt, components);
        }
        return null;
    }

    private static boolean isCompatibleLegacyTooltipSurface(String surface) {
        String normalized = surface == null ? "" : surface.toLowerCase(Locale.ROOT);
        return normalized.startsWith("tooltip.item_context.semantic_paragraph.")
                || normalized.startsWith("hover.context.semantic_paragraph.")
                || normalized.startsWith("hover.overlay.semantic_paragraph.");
    }

    private static List<Component> parseExactSemanticCandidate(String payload, int expected) {
        if (payload == null || payload.isBlank() || expected <= 0) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(payload);
            if (!root.isJsonArray() || root.getAsJsonArray().size() != expected) {
                return null;
            }
            List<Component> parsed = new ArrayList<>(expected);
            for (JsonElement element : root.getAsJsonArray()) {
                Component component = ComponentJsonCompat.fromJson(element);
                if (component == null) {
                    return null;
                }
                parsed.add(component);
            }
            return List.copyOf(parsed);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void scheduleSemanticRefresh(List<Component> source,
                                                 ComponentVisualProjection projection,
                                                 String surface,
                                                 String stableContext,
                                                 SemanticDeltaPlan delta) {
        String pendingKey = semanticPendingKey(surface, projection);
        long now = System.nanoTime();
        if (pendingKey.isBlank() || semanticRetryBlocked(pendingKey, now)
                || !PENDING_ASYNC_REFRESH_SIGNATURES.add(pendingKey)) {
            return;
        }
        if (delta == null || delta.missingComponents().isEmpty()) {
            PENDING_ASYNC_REFRESH_SIGNATURES.remove(pendingKey);
            return;
        }
        long startedAt = now;
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        List<Component> sourceSnapshot = List.copyOf(source);
        SEMANTIC_PENDING_STARTED.put(pendingKey, startedAt);
        markActiveTranslationGlow(source);
        String requestContext = semanticDeltaRequestContext(stableContext, delta);
        DirectSurfaceTranslator.translateComponentsAsync(
                        delta.missingComponents(), surface, SEMANTIC_ROLE, false, requestContext)
                .whenComplete((result, error) -> {
                    // A request may have timed out locally and been retried.
                    // Its late completion must not disarm the newer request's
                    // pending latch or glow.
                    if (!SEMANTIC_PENDING_STARTED.remove(pendingKey, startedAt)) {
                        return;
                    }
                    PENDING_ASYNC_REFRESH_SIGNATURES.remove(pendingKey);
                    if (!SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)) {
                        clearActiveTranslationGlow(sourceSnapshot);
                        return;
                    }
                    if (error != null || result == null || !result.handled || !result.translated
                            || result.components == null
                            || result.components.size() != delta.missingIndexes().size()) {
                        deferSemanticRetry(pendingKey, System.nanoTime());
                        clearActiveTranslationGlow(sourceSnapshot);
                        return;
                    }
                    List<Component> readySemantic = mergeSemanticDelta(
                            delta, result.components, surface,
                            ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
                    if (readySemantic == null || readySemantic.isEmpty()) {
                        deferSemanticRetry(pendingKey, System.nanoTime());
                        clearActiveTranslationGlow(sourceSnapshot);
                        return;
                    }
                    SEMANTIC_RETRY_AFTER_NANOS.remove(pendingKey);
                    recordScopedSemanticTranslations(
                            projection, readySemantic, surface, delta.reuseScope(), true);
                    TooltipSemanticResultStore.put(pendingKey, readySemantic);
                    List<Component> cacheable = projection.rebuildComponentList(readySemantic);
                    if (cacheable != null && !cacheable.isEmpty()) {
                        JsonPassthroughPipeline.cacheResolvedComponents(
                                sourceSnapshot, cacheable, surface, SEMANTIC_ROLE,
                                stableContext, runtimeRevision);
                    }
                    // Do not measure fonts or mutate render markers on the HTTP
                    // completion thread. The next visible frame consumes READY,
                    // rebuilds against its current skeleton, and clears the glow.
                });
    }

    private static String semanticReuseScope(
            TooltipTranslationController.RenderContext context,
            ComponentVisualProjection projection) {
        if (projection == null || !projection.hasSlots() || projection.slots().isEmpty()) {
            return "";
        }
        String anchor = projection.slots().get(0).sourceText();
        if (anchor == null || anchor.isBlank()) {
            return "";
        }
        return "tooltip-semantic-delta-v1/"
                + context.name().toLowerCase(Locale.ROOT) + '/'
                + TranslationCacheKeys.hashSource(anchor);
    }

    private static SemanticDeltaPlan planSemanticDelta(
            ComponentVisualProjection projection, String surface, String reuseScope,
            String sourceLanguage, String targetLanguage) {
        if (projection == null || !projection.hasSlots()) {
            return new SemanticDeltaPlan("", List.of(), List.of(), List.of(), List.of(), List.of());
        }
        List<String> sources = projection.slots().stream()
                .map(ComponentVisualProjection.SemanticSlot::sourceText)
                .toList();
        List<Component> semantic = projection.semanticComponents();
        List<Component> resolved = new ArrayList<>(Collections.nCopies(sources.size(), null));
        List<Integer> missingIndexes = new ArrayList<>();
        List<Component> missingComponents = new ArrayList<>();
        LineTranslationMemory memory = scopedSemanticMemory(reuseScope);
        boolean allowShared = ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.get();
        if (memory != null) {
            // Cache-editor changes are deliberate terminology. Promote only
            // explicit player edits; automatic full-block answers remain scoped
            // to the item lineage below and can never leak across tooltips.
            Map<Integer, TextContextMemory.ExactTranslation> edited =
                    TextContextMemory.lookupExactTranslations(
                            sources, sourceLanguage, targetLanguage);
            for (Map.Entry<Integer, TextContextMemory.ExactTranslation> entry : edited.entrySet()) {
                int index = entry.getKey();
                TextContextMemory.ExactTranslation translation = entry.getValue();
                if (index >= 0 && index < sources.size() && translation != null
                        && translation.editedByPlayer()
                        && translation.translation() != null
                        && !translation.translation().isBlank()) {
                    memory.recordPlayerEdited(
                            sources.get(index), sourceLanguage, targetLanguage,
                            translation.translation(), translation.sharedImported());
                }
            }
        }
        // Incremental reuse is atomic per original top-level Component, not per
        // projected leaf. If one colour/font/icon-adjacent fragment changed,
        // request every sibling from that original line together so articles,
        // prepositions and target-language word order retain their context.
        int cursor = 0;
        for (int groupSize : projection.atomicGroupSizes()) {
            int end = Math.min(sources.size(), cursor + groupSize);
            List<String> rememberedGroup = new ArrayList<>(Math.max(0, end - cursor));
            boolean complete = memory != null;
            for (int index = cursor; index < end; index++) {
                String remembered = memory == null ? null : memory.lookupScoped(
                        sources.get(index), sourceLanguage, targetLanguage,
                        surface, SEMANTIC_ROLE, reuseScope, allowShared);
                rememberedGroup.add(remembered);
                if (remembered == null) {
                    complete = false;
                }
            }
            for (int index = cursor; index < end; index++) {
                if (complete) {
                    resolved.set(index, com.yourname.simpletranslate.core.LegacyComponentFactory.literal(rememberedGroup.get(index - cursor)));
                } else {
                    missingIndexes.add(index);
                    missingComponents.add(semantic.get(index));
                }
            }
            cursor = end;
        }
        // Defensive fail-closed coverage if a future projection reports an
        // incomplete grouping plan.
        while (cursor < sources.size()) {
            missingIndexes.add(cursor);
            missingComponents.add(semantic.get(cursor));
            cursor++;
        }
        return new SemanticDeltaPlan(
                reuseScope, sources, resolved, missingIndexes, missingComponents,
                projection.atomicGroupSizes());
    }

    private static List<Component> mergeSemanticDelta(
            SemanticDeltaPlan delta, List<Component> requested,
            String surface, String sourceLanguage, String targetLanguage) {
        if (delta == null || requested == null
                || requested.size() != delta.missingIndexes().size()) {
            return null;
        }
        List<Component> merged = new ArrayList<>(delta.resolved());
        for (int index = 0; index < delta.missingIndexes().size(); index++) {
            Component component = requested.get(index);
            if (component == null) {
                return null;
            }
            merged.set(delta.missingIndexes().get(index), com.yourname.simpletranslate.core.LegacyComponentFactory.literal(component.getString()));
        }

        // A player correction or an accepted sibling request may have arrived
        // while this request was in flight. Re-read the scoped memory before the
        // atomic full-tooltip merge so the newest authoritative value wins.
        LineTranslationMemory memory = scopedSemanticMemory(delta.reuseScope());
        if (memory != null) {
            boolean allowShared = ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.get();
            int cursor = 0;
            for (int groupSize : delta.atomicGroupSizes()) {
                int end = Math.min(delta.sourceTexts().size(), cursor + groupSize);
                List<String> latestGroup = new ArrayList<>(Math.max(0, end - cursor));
                boolean complete = true;
                for (int index = cursor; index < end; index++) {
                    String latest = memory.lookupScoped(
                            delta.sourceTexts().get(index), sourceLanguage, targetLanguage,
                            surface, SEMANTIC_ROLE, delta.reuseScope(), allowShared);
                    latestGroup.add(latest);
                    if (latest == null) {
                        complete = false;
                    }
                }
                if (complete) {
                    for (int index = cursor; index < end; index++) {
                        merged.set(index, com.yourname.simpletranslate.core.LegacyComponentFactory.literal(latestGroup.get(index - cursor)));
                    }
                }
                cursor = end;
            }
        }
        return merged.stream().anyMatch(java.util.Objects::isNull)
                ? null : List.copyOf(merged);
    }

    private static LineTranslationMemory scopedSemanticMemory(String reuseScope) {
        if (!ModConfig.CACHE_ENABLED.get() || reuseScope == null || reuseScope.isBlank()
                || SimpleTranslateMod.getCurrentWorldId() == null) {
            return null;
        }
        return SimpleTranslateMod.getLineTranslationMemory();
    }

    private static void recordScopedSemanticTranslations(
            ComponentVisualProjection projection, List<Component> translatedSemantic,
            String surface, String reuseScope, boolean flush) {
        if (projection == null || translatedSemantic == null
                || translatedSemantic.size() != projection.slotCount()) {
            return;
        }
        LineTranslationMemory memory = scopedSemanticMemory(reuseScope);
        if (memory == null) {
            return;
        }
        List<String> sources = projection.slots().stream()
                .map(ComponentVisualProjection.SemanticSlot::sourceText)
                .toList();
        List<String> translations = translatedSemantic.stream()
                .map(component -> component == null ? null : component.getString())
                .toList();
        int recorded = memory.recordScoped(
                sources, translations,
                ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get(),
                surface, SEMANTIC_ROLE, reuseScope, false);
        if (flush && recorded > 0) {
            memory.flush();
        }
    }

    private static String semanticDeltaRequestContext(
            String stableContext, SemanticDeltaPlan delta) {
        StringBuilder context = new StringBuilder(stableContext == null ? "" : stableContext);
        if (!context.isEmpty()) {
            context.append('\n');
        }
        context.append("Incremental Component request. The user array contains only changed or newly seen semantic entries from the complete tooltip above. ")
                .append("Translate only those entries in their request order and return exactly ")
                .append(delta == null ? 0 : delta.missingIndexes().size())
                .append(" top-level Component entries. Unchanged entries are rebound locally.");
        if (delta != null) {
            int appended = 0;
            for (int index = 0; index < delta.sourceTexts().size() && appended < 16; index++) {
                Component translated = delta.resolved().get(index);
                if (translated == null || translated.getString().isBlank()) {
                    continue;
                }
                if (appended++ == 0) {
                    context.append("\nAccepted unchanged sibling terminology (context data only):\n");
                }
                context.append(delta.sourceTexts().get(index))
                        .append(" => ")
                        .append(translated.getString())
                        .append('\n');
            }
        }
        return context.toString().stripTrailing();
    }

    private static List<Component> rebuildSemanticResult(
            ComponentVisualProjection projection, List<Component> semantic,
            List<Component> originals) {
        List<Component> rebuilt = projection == null ? null
                : projection.rebuildComponentList(semantic);
        if (rebuilt == null || rebuilt.isEmpty()) {
            return null;
        }
        rebuilt = JsonPassthroughPipeline.reattachOriginalHoverEventsForRender(
                rebuilt, originals);
        rebuilt = constrainTranslatedTooltipLines(rebuilt, originals);
        if (rebuilt == null || rebuilt.isEmpty()) {
            return null;
        }
        markTranslatedTooltip(rebuilt);
        return rebuilt;
    }

    private record SemanticDeltaPlan(
            String reuseScope,
            List<String> sourceTexts,
            List<Component> resolved,
            List<Integer> missingIndexes,
            List<Component> missingComponents,
            List<Integer> atomicGroupSizes) {
        private SemanticDeltaPlan {
            reuseScope = reuseScope == null ? "" : reuseScope;
            sourceTexts = List.copyOf(sourceTexts == null ? List.of() : sourceTexts);
            // List.copyOf rejects null; unresolved ordinals deliberately use null.
            resolved = Collections.unmodifiableList(new ArrayList<>(
                    resolved == null ? List.of() : resolved));
            missingIndexes = List.copyOf(missingIndexes == null ? List.of() : missingIndexes);
            missingComponents = List.copyOf(
                    missingComponents == null ? List.of() : missingComponents);
            atomicGroupSizes = List.copyOf(
                    atomicGroupSizes == null ? List.of() : atomicGroupSizes);
        }

        private boolean fullyResolved() {
            return !sourceTexts.isEmpty() && missingIndexes.isEmpty()
                    && resolved.size() == sourceTexts.size()
                    && resolved.stream().noneMatch(java.util.Objects::isNull);
        }

        private List<Component> resolvedComponents() {
            return fullyResolved() ? List.copyOf(resolved) : null;
        }
    }

    private static boolean semanticRetryBlocked(String pendingKey, long nowNanos) {
        if (pendingKey == null || pendingKey.isBlank()) {
            return false;
        }
        Long retryAfter = SEMANTIC_RETRY_AFTER_NANOS.get(pendingKey);
        if (retryAfter == null) {
            return false;
        }
        if (nowNanos >= retryAfter) {
            SEMANTIC_RETRY_AFTER_NANOS.remove(pendingKey, retryAfter);
            return false;
        }
        return true;
    }

    private static void deferSemanticRetry(String pendingKey, long nowNanos) {
        if (pendingKey != null && !pendingKey.isBlank()) {
            SEMANTIC_RETRY_AFTER_NANOS.put(
                    pendingKey, nowNanos + SEMANTIC_FAILURE_RETRY_NANOS);
        }
    }

    private static String semanticSurfaceFor(TooltipTranslationController.RenderContext context) {
        return context == TooltipTranslationController.RenderContext.BOOK
                ? BOOK_SEMANTIC_SURFACE
                : HOVER_SEMANTIC_SURFACE;
    }

    private static String semanticContext(TooltipTranslationController.RenderContext context,
                                          List<Component> sourceComponents) {
        String sourceShape = JsonPassthroughPipeline.semanticPromptSourceShape(sourceComponents);
        String base = "Visible Component tooltip v1 (" + context.name().toLowerCase(Locale.ROOT) + "). "
                + "Translate every Component entry in order and return exactly the same top-level array length. "
                + "The shared Component projection retains icons, progress bars, styles, spacing and dynamic values locally. "
                + "Translate the Component entries supplied in the user array as one coherent part of this tooltip. "
                + "The user array may contain only entries whose source text changed; the complete ordered source shape below remains authoritative context.";
        return sourceShape.isBlank()
                ? base
                : base + "\nStable readable source shape (dynamic numbers are <number>):\n" + sourceShape;
    }

    private static String semanticPendingKey(String surface, ComponentVisualProjection projection) {
        String semanticJson = projection == null ? null : projection.semanticJson();
        if (semanticJson == null || semanticJson.isBlank()) {
            return "";
        }
        return "visible-component:" + surface + '\u001f'
                + SimpleTranslateMod.getRuntimeRevision() + '\u001f'
                + TranslationTextDetector.languagePairKey() + '\u001f'
                + TranslationCacheKeys.hashSource(semanticJson);
    }

    private static List<Component> translatedSemanticComponents(
            ComponentVisualProjection projection, List<Component> restored) {
        if (projection == null || restored == null || restored.isEmpty()) {
            return null;
        }
        String restoredJson = JsonPassthroughPipeline.serializeProjectionSource(restored);
        if (restoredJson == null || restoredJson.isBlank()) {
            return null;
        }
        try {
            JsonElement restoredRoot = JsonParser.parseString(restoredJson);
            List<String> translated = projection.alignedTranslatedSlotTexts(restoredRoot);
            if (translated == null || translated.size() != projection.slotCount()) {
                return null;
            }
            return translated.stream().map(text -> (Component) com.yourname.simpletranslate.core.LegacyComponentFactory.literal(text)).toList();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String buildOverlayTooltipContext(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        context.append("Overlay tooltip block rendered outside HoverEvent, e.g. chat skill details or mod UI. ")
                .append("Translate every component entry as one coherent passage while preserving line slots. ")
                .append("Do not leave English mechanic tails such as 'in Anvils', 'when worn', or 'on hit' untranslated. ")
                .append("Do not duplicate the same Chinese phrase across adjacent continuation lines.\n");
        for (int i = 0; i < components.size(); i++) {
            Component component = components.get(i);
            String text = component == null ? "" : component.getString();
            context.append("line ")
                    .append(i)
                    .append(" [")
                    .append(classifyTooltipLine(i, text))
                    .append("]: ")
                    .append(text == null ? "" : text)
                    .append('\n');
        }
        return context.toString().trim();
    }

    public static String buildHoverTooltipContext(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        context.append("Chat HoverEvent tooltip block. All component entries are one tooltip shown together, ")
                .append("often with a title, separators, skill metadata, mechanics, and lore. ")
                .append("Understand the whole block before translating any line. ")
                .append("Translate or naturally transliterate skill titles and invented item/skill names; they are not player names. ")
                .append("Translate every natural English sentence, mechanic phrase, heading, and lore line. ")
                .append("Keep commands, key names, numeric values, icons, cooldowns, mana costs, and genuine identifiers unchanged. ")
                .append("Preserve each source line slot exactly once and do not display a partially translated block. ")
                .append("Do not leave English continuation lines untranslated and do not duplicate Chinese phrases to fill wrapped slots.\n");
        for (int i = 0; i < components.size(); i++) {
            Component component = components.get(i);
            String text = component == null ? "" : component.getString();
            context.append("line ")
                    .append(i)
                    .append(" [")
                    .append(classifyTooltipLine(i, text))
                    .append("]: ")
                    .append(text == null ? "" : text)
                    .append('\n');
        }
        return context.toString().trim();
    }

    private static String tooltipSignature(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return "";
        }
        StringBuilder signature = new StringBuilder();
        for (Component component : components) {
            signature.append('\u001e').append(componentSignature(component));
        }
        return signature.toString();
    }

    private static String tooltipSemanticSignature(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return "";
        }
        StringBuilder signature = new StringBuilder();
        for (Component component : components) {
            signature.append('\u001e');
            if (component != null) {
                signature.append(TranslationCacheKeys.normalizeSemanticSource(component.getString()));
            }
        }
        return signature.toString();
    }

    private static String componentSignature(Component component) {
        if (component == null) {
            return "<null>";
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
        if (segments.isEmpty()) {
            return component.getString();
        }
        StringBuilder signature = new StringBuilder();
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null) {
                continue;
            }
            Style style = segment.style == null ? Style.EMPTY : segment.style;
            signature.append('\u001f')
                    .append(segment.text)
                    .append('\u001d')
                    .append(style);
        }
        return signature.toString();
    }

    private static List<Component> constrainTranslatedTooltipLines(List<Component> translated, List<Component> originals) {
        if (translated == null || translated.isEmpty()) {
            return translated;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft == null ? null : minecraft.font;
        if (font == null) {
            return translated;
        }

        int maxWidth = translatedTooltipMaxWidth(originals);
        List<Component> wrapped = new ArrayList<>();
        boolean changed = false;
        for (Component component : translated) {
            if (component == null || font.width(component) <= maxWidth) {
                wrapped.add(component);
                continue;
            }
            List<Component> split = wrapStyledTooltipComponent(component, maxWidth, font);
            wrapped.addAll(split);
            changed |= split.size() != 1 || split.get(0) != component;
        }
        return changed ? wrapped : translated;
    }

    private static int translatedTooltipMaxWidth(List<Component> originals) {
        int originalWidth = getMaxTooltipLineWidth(originals);
        return Math.max(120, Math.min(MAX_TRANSLATED_TOOLTIP_WIDTH, originalWidth));
    }

    private static int getMaxTooltipLineWidth(List<Component> components) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft == null ? null : minecraft.font;
        if (font == null || components == null || components.isEmpty()) {
            return 180;
        }

        int maxWidth = 80;
        for (Component component : components) {
            if (component != null) {
                maxWidth = Math.max(maxWidth, font.width(component));
            }
        }
        return Math.max(80, maxWidth);
    }

    private static List<Component> wrapStyledTooltipComponent(Component component, int maxWidth, Font font) {
        if (component == null) {
            return List.of();
        }
        if (font == null || maxWidth <= 0) {
            return List.of(component);
        }

        try {
            List<TextSegmentInfo> segments = new ArrayList<>();
            ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
            if (segments.isEmpty()) {
                return List.of(component);
            }

            List<Component> result = new ArrayList<>();
            MutableComponent current = com.yourname.simpletranslate.core.LegacyComponentFactory.empty();
            float currentWidth = 0.0F;
            boolean hasText = false;
            for (TextSegmentInfo segment : segments) {
                if (segment == null || segment.text == null || segment.text.isEmpty()) {
                    continue;
                }
                Style style = segment.style == null ? Style.EMPTY : segment.style;
                for (int offset = 0; offset < segment.text.length();) {
                    int codePoint = segment.text.codePointAt(offset);
                    String piece = new String(Character.toChars(codePoint));
                    float pieceWidth = font.getSplitter().stringWidth(FormattedText.of(piece, style));
                    // Resource-pack spacing providers can deliberately expose negative
                    // advances. Moving such a glyph to a new tooltip line changes its
                    // positioning semantics, so an unsafe metric keeps the complete
                    // source component instead of emitting a partially wrapped result.
                    if (!Float.isFinite(pieceWidth) || pieceWidth < 0.0F || pieceWidth > maxWidth) {
                        return List.of(component);
                    }
                    float candidateWidth = currentWidth + pieceWidth;
                    if (!Float.isFinite(candidateWidth)) {
                        return List.of(component);
                    }
                    if (hasText && candidateWidth > maxWidth) {
                        result.add(current);
                        current = com.yourname.simpletranslate.core.LegacyComponentFactory.empty();
                        currentWidth = 0.0F;
                        hasText = false;
                    }
                    current.append(com.yourname.simpletranslate.core.LegacyComponentFactory.literal(piece).withStyle(style));
                    currentWidth += pieceWidth;
                    hasText = true;
                    offset += Character.charCount(codePoint);
                }
            }

            if (hasText) {
                result.add(current);
            }
            return result.isEmpty() ? List.of(component) : result;
        } catch (RuntimeException ignored) {
            return List.of(component);
        }
    }

    private static List<Component> splitComponentByNewlines(Component component) {
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
        List<Component> lines = new ArrayList<>();
        MutableComponent current = com.yourname.simpletranslate.core.LegacyComponentFactory.empty();
        boolean appendedAnything = false;

        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null) {
                continue;
            }
            Style style = segment.style == null ? Style.EMPTY : segment.style;
            String text = segment.text.replace("\r\n", "\n").replace('\r', '\n');
            int start = 0;
            for (int i = 0; i <= text.length(); i++) {
                if (i == text.length() || text.charAt(i) == '\n') {
                    if (i > start) {
                        current.append(com.yourname.simpletranslate.core.LegacyComponentFactory.literal(text.substring(start, i)).withStyle(style));
                        appendedAnything = true;
                    }
                    if (i < text.length()) {
                        lines.add(current);
                        current = com.yourname.simpletranslate.core.LegacyComponentFactory.empty();
                        appendedAnything = false;
                    }
                    start = i + 1;
                }
            }
        }

        if (appendedAnything || lines.isEmpty()) {
            lines.add(current);
        }
        return lines;
    }

    private static final class IdentityMarker<T> {
        private final int maximumSize;
        private final IdentityHashMap<T, Boolean> entries = new IdentityHashMap<>();
        private final ArrayDeque<T> insertionOrder = new ArrayDeque<>();

        private IdentityMarker(int maximumSize) {
            this.maximumSize = Math.max(1, maximumSize);
        }

        private synchronized boolean contains(T value) {
            return value != null && entries.containsKey(value);
        }

        private synchronized void add(T value) {
            if (value == null || entries.put(value, Boolean.TRUE) != null) {
                return;
            }
            insertionOrder.addLast(value);
            while (entries.size() > maximumSize) {
                T oldest = insertionOrder.pollFirst();
                if (oldest == null) {
                    break;
                }
                entries.remove(oldest);
            }
        }

        private synchronized void clear() {
            entries.clear();
            insertionOrder.clear();
        }
    }

}
