package com.yourname.simpletranslate.feature.tooltip;
import com.yourname.simpletranslate.core.TranslationTextDetector;
import com.yourname.simpletranslate.core.TextSegmentInfo;
import com.yourname.simpletranslate.core.ComponentSegmentHelper;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.ComponentTranslationResult;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin tooltip/hover adapter. Formatting and semantic block translation live in
 * DirectSurfaceTranslator; this class only keeps shared convenience APIs
 * used by mixins and older surfaces.
 */
public final class TooltipTranslationHelper {
    public static final String ITEM_TOOLTIP_CONTEXT_SURFACE = "tooltip.item_context.direct";
    public static final String ITEM_TOOLTIP_CONTEXT_ROLE = "tooltip-block-context";
    public static final String HOVER_OVERLAY_SURFACE = "hover.overlay.direct";
    public static final String HOVER_OVERLAY_ROLE = "hover-overlay-batch";
    public static final String HOVER_CONTEXT_SURFACE = "hover.context.direct";
    public static final String HOVER_CONTEXT_ROLE = "hover-block-context";

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
    private static final long ACTIVE_TRANSLATION_GLOW_TIMEOUT_NANOS = 180_000_000_000L;
    private static final Set<List<Component>> TRANSLATED_COMPONENT_LISTS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Component> TRANSLATED_COMPONENTS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<String> TRANSLATED_TOOLTIP_SIGNATURES =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<>(128, 0.75f, true) {
                        private static final long serialVersionUID = 1L;
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                            return size() > MAX_TRANSLATED_SIGNATURES;
                        }
                    }));

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

    public static List<Component> translateComponentsBatch(List<Component> components) {
        return translateRenderedTooltip(components, TooltipTranslationController.RenderContext.ITEM);
    }

    public static List<Component> translateRenderedTooltip(List<Component> components,
                                                           TooltipTranslationController.RenderContext context) {
        return translateRenderedTooltip(components, context, true);
    }

    public static List<Component> translateRenderedTooltip(List<Component> components,
                                                           TooltipTranslationController.RenderContext context,
                                                           boolean allowRequest) {
        if (components == null || components.isEmpty()) {
            return components;
        }
        return SafeTranslate.guard(
                () -> translateRenderedTooltipImpl(components, context, allowRequest),
                components,
                "tooltip.translateRenderedTooltip");
    }

    private static List<Component> translateRenderedTooltipImpl(List<Component> components,
                                                           TooltipTranslationController.RenderContext context,
                                                           boolean allowRequest) {
        if (components == null || components.isEmpty()) {
            return components;
        }
        if (isMarkedTranslatedTooltip(components)) {
            return components;
        }
        if (context == TooltipTranslationController.RenderContext.ITEM) {
            List<Component> translated = translateItemTooltipSnapshot(components, !allowRequest);
            if (translated != components) {
                markTranslatedTooltip(translated);
                return translated;
            }
            return components;
        }

        String surface = surfaceFor(context);
        String role = roleFor(context);
        String tooltipContext = buildTooltipContext(components, context);

        ComponentListTranslationResult direct =
                DirectSurfaceTranslator.getCachedComponents(
                components, surface, role, false, tooltipContext);
        if (!direct.handled) {
            return components;
        }
        if (direct.components != components) {
            List<Component> translated = constrainTranslatedTooltipLines(direct.components, components);
            markTranslatedTooltip(translated);
            return translated;
        }
        if (allowRequest) {
            scheduleAsyncRefresh(components, surface, role, tooltipContext);
        }
        return components;
    }

    public static void prefetchComponentsBatch(List<Component> components) {
        if (components == null || components.isEmpty()
                || isMarkedTranslatedTooltip(components)
                || !anyContainsEnglish(components)) {
            return;
        }
        translateItemTooltipSnapshot(components, false);
    }

    public static ComponentListTranslationResult getCachedComponentsBatch(List<Component> components) {
        return SafeTranslate.guard(() -> {
            List<Component> translated = translateItemTooltipSnapshot(components, true);
            return new ComponentListTranslationResult(
                    translated == components ? components : translated,
                    anyContainsEnglish(components),
                    translated != components);
        }, new ComponentListTranslationResult(components, false, false),
                "tooltip.getCachedComponentsBatch");
    }

    public static String buildItemTooltipContext(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("Atomic item tooltip snapshot. Translate the complete tooltip in this one response. ")
                .append("Use all component entries together for meaning, but return the same number of entries in order. ")
                .append("Translate or naturally transliterate item titles and invented item names; they are not player names. ")
                .append("Do not leave English lore, headings, mechanic tails, equipment labels, or attribute names untranslated. ")
                .append("Keep only identifiers, commands, key names, abbreviations, numbers, and genuine player names unchanged. ")
                .append("Combat abbreviations may stay Latin, but translate their surrounding words, e.g. AOE Damage -> AOE 伤害 and Attack: Fireball -> 攻击：火球. ")
                .append("Do not repeat the same Chinese words to fill wrapped line slots; split the unique Chinese wording across all source line slots.\n");
        for (int i = 0; i < components.size(); i++) {
            Component component = components.get(i);
            String text = component == null ? "" : component.getString();
            context.append("line ")
                    .append(i)
                    .append(" [")
                    .append(classifyItemTooltipLine(i, text))
                    .append("]: ")
                    .append(text == null ? "" : text)
                    .append('\n');
        }
        return context.toString().trim();
    }

    private static List<Component> translateItemTooltipSnapshot(List<Component> components, boolean cachedOnly) {
        if (components == null || components.isEmpty() || !anyContainsEnglish(components)) {
            return components;
        }
        String context = buildItemTooltipContext(components);
        ComponentListTranslationResult direct = cachedOnly
                ? DirectSurfaceTranslator.getCachedComponents(
                components, ITEM_TOOLTIP_CONTEXT_SURFACE, ITEM_TOOLTIP_CONTEXT_ROLE, false, context)
                : DirectSurfaceTranslator.getCachedComponents(
                components, ITEM_TOOLTIP_CONTEXT_SURFACE, ITEM_TOOLTIP_CONTEXT_ROLE, false, context);
        if (direct.handled && direct.translated && direct.components != null
                && direct.components != components && isCompleteItemTooltipSnapshot(components, direct.components)) {
            return padOrTruncate(direct.components, components.size());
        }
        if (!cachedOnly) {
            scheduleItemTooltipSnapshotRequest(components, context);
        }
        return components;
    }

    private static List<Component> padOrTruncate(List<Component> translated, int expectedSize) {
        if (translated == null) {
            return null;
        }
        if (translated.size() == expectedSize) {
            return translated;
        }
        List<Component> result = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            result.add(i < translated.size() ? translated.get(i) : Component.empty());
        }
        return List.copyOf(result);
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
        if (context == TooltipTranslationController.RenderContext.ITEM
                && (PENDING_ASYNC_REFRESH_SIGNATURES.contains("item-snapshot:" + signature)
                || PENDING_ASYNC_REFRESH_SIGNATURES.contains("item-snapshot:" + semanticSignature))) {
            return true;
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
            changed = true;
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

    private static void scheduleItemTooltipSnapshotRequest(List<Component> components, String context) {
        String signature = tooltipSignature(components);
        String semanticSignature = tooltipSemanticSignature(components);
        String pendingKey = "item-snapshot:" + signature;
        String semanticPendingKey = "item-snapshot:" + semanticSignature;
        if (signature.isBlank() || semanticSignature.isBlank()
                || (!PENDING_ASYNC_REFRESH_SIGNATURES.add(pendingKey)
                && !PENDING_ASYNC_REFRESH_SIGNATURES.add(semanticPendingKey))) {
            return;
        }
        PENDING_ASYNC_REFRESH_SIGNATURES.add(semanticPendingKey);
        DirectSurfaceTranslator.translateComponentsAsync(
                components, ITEM_TOOLTIP_CONTEXT_SURFACE, ITEM_TOOLTIP_CONTEXT_ROLE, false, context)
                .whenComplete((result, error) -> {
                    PENDING_ASYNC_REFRESH_SIGNATURES.remove(pendingKey);
                    PENDING_ASYNC_REFRESH_SIGNATURES.remove(semanticPendingKey);
                    if (error == null && result != null && result.translated
                            && result.components != null && result.components != components
                            && result.components.size() == components.size()
                            && isCompleteItemTooltipSnapshot(components, result.components)) {
                        return;
                    }
                    if (error == null && result != null && result.translated
                            && result.components != null && result.components != components) {
                        invalidateItemTooltipSnapshotCache(components, context);
                    }
                });
    }

    private static void invalidateItemTooltipSnapshotCache(List<Component> components, String context) {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null || components == null || components.isEmpty()) {
            return;
        }
        try {
            String sourceJson = JsonPassthroughPipeline.serializeComponents(components);
            cache.remove(TranslationCacheKeys.componentJsonKey(ITEM_TOOLTIP_CONTEXT_SURFACE, sourceJson, context));
            cache.save();
        } catch (Exception ignored) {
        }
    }

    private static boolean isCompleteItemTooltipSnapshot(List<Component> source, List<Component> translated) {
        if (source == null || translated == null || source.size() != translated.size()) {
            return false;
        }
        // Component JSON is accepted atomically; only null entries are unusable.
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) != null && translated.get(i) == null) {
                return false;
            }
        }
        return true;
    }

    public static Component translateComponentWithStyle(Component component) {
        return SafeTranslate.guard(() -> {
            if (component == null || isMarkedTranslatedTooltip(component) || !containsEnglish(component.getString())) {
                return component;
            }
            ComponentTranslationResult direct =
                    DirectSurfaceTranslator.translateComponent(component, "hover.component.direct", "hover-component");
            if (direct.handled && direct.component != component) {
                markTranslatedTooltip(direct.component);
            }
            return direct.handled ? direct.component : component;
        }, component, "tooltip.translateComponentWithStyle");
    }

    public static List<Component> translateHoverComponentLines(Component component) {
        return translateHoverComponentLines(component, true);
    }

    public static List<Component> translateHoverComponentLines(Component component, boolean allowRequest) {
        List<Component> fallback = component == null ? List.of() : List.of(component);
        return SafeTranslate.guard(() -> {
            if (component == null) {
                return List.of();
            }
            if (isMarkedTranslatedTooltip(component) || !containsEnglish(component.getString())) {
                return List.of(component);
            }

            List<Component> lines = splitComponentByNewlines(component);
            String context = buildHoverTooltipContext(lines);
            ComponentListTranslationResult direct =
                    DirectSurfaceTranslator.getCachedComponents(
                    lines, HOVER_CONTEXT_SURFACE, HOVER_CONTEXT_ROLE, false, context);
            if (!direct.handled) {
                return List.of(component);
            }
            if (direct.components == lines) {
                if (allowRequest) {
                    scheduleAsyncRefresh(lines, HOVER_CONTEXT_SURFACE, HOVER_CONTEXT_ROLE, context);
                }
                return List.of(component);
            }
            List<Component> translated = constrainTranslatedTooltipLines(direct.components, lines);
            markTranslatedTooltip(translated);
            return translated;
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
        if (direct.handled && direct.component != component) {
            markTranslatedTooltip(direct.component);
        }
        return direct.handled ? direct.component : component;
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

    private static String classifyItemTooltipLine(int index, String text) {
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
        synchronized (TRANSLATED_COMPONENT_LISTS) {
            if (TRANSLATED_COMPONENT_LISTS.contains(components)) {
                return true;
            }
        }
        if (TRANSLATED_TOOLTIP_SIGNATURES.contains(tooltipSignature(components))) {
            return true;
        }
        return false;
    }

    public static boolean isMarkedTranslatedTooltip(Component component) {
        if (component == null) {
            return false;
        }
        synchronized (TRANSLATED_COMPONENTS) {
            if (TRANSLATED_COMPONENTS.contains(component)) {
                return true;
            }
        }
        return TRANSLATED_TOOLTIP_SIGNATURES.contains(componentSignature(component));
    }

    public static void markTranslatedTooltip(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return;
        }
        synchronized (TRANSLATED_COMPONENT_LISTS) {
            TRANSLATED_COMPONENT_LISTS.add(components);
        }
        addTranslatedSignature(tooltipSignature(components));
    }

    public static void markTranslatedTooltip(Component component) {
        if (component == null) {
            return;
        }
        synchronized (TRANSLATED_COMPONENTS) {
            TRANSLATED_COMPONENTS.add(component);
        }
        addTranslatedSignature(componentSignature(component));
    }

    public static void clearPendingCache() {
        synchronized (TRANSLATED_COMPONENT_LISTS) {
            TRANSLATED_COMPONENT_LISTS.clear();
        }
        synchronized (TRANSLATED_COMPONENTS) {
            TRANSLATED_COMPONENTS.clear();
        }
        TRANSLATED_TOOLTIP_SIGNATURES.clear();
        PENDING_ASYNC_REFRESH_SIGNATURES.clear();
        ACTIVE_TRANSLATION_GLOW.clear();
    }

    private static void scheduleAsyncRefresh(List<Component> components, String surface, String role,
                                             String context) {
        if (components == null || components.isEmpty()) {
            return;
        }
        String signature = tooltipSignature(components);
        String semanticSignature = tooltipSemanticSignature(components);
        if (signature.isBlank() || semanticSignature.isBlank()
                || (!PENDING_ASYNC_REFRESH_SIGNATURES.add(signature)
                && !PENDING_ASYNC_REFRESH_SIGNATURES.add(semanticSignature))) {
            return;
        }
        PENDING_ASYNC_REFRESH_SIGNATURES.add(semanticSignature);
        // Keep the pending-translation glow alive for the whole in-flight window so
        // shortcut mode (where requestAllowed is true for only one frame) matches
        // hover mode. Cleared once the result is marked translated or on timeout.
        markActiveTranslationGlow(components);
        DirectSurfaceTranslator.translateComponentsAsync(components, surface, role, false, context)
                .whenComplete((result, error) -> {
                    PENDING_ASYNC_REFRESH_SIGNATURES.remove(signature);
                    PENDING_ASYNC_REFRESH_SIGNATURES.remove(semanticSignature);
                    if (error != null || result == null || !result.translated
                            || result.components == null || result.components.isEmpty()) {
                        clearActiveTranslationGlow(components);
                        return;
                    }
                    List<Component> translated = constrainTranslatedTooltipLines(result.components, components);
                    markTranslatedTooltip(translated);
                    clearActiveTranslationGlow(components);
                });
    }

    private static String surfaceFor(TooltipTranslationController.RenderContext context) {
        return switch (context) {
            case CHAT_OVERLAY -> HOVER_CONTEXT_SURFACE;
            case BOOK -> HOVER_OVERLAY_SURFACE;
            case ITEM -> ITEM_TOOLTIP_CONTEXT_SURFACE;
        };
    }

    private static String roleFor(TooltipTranslationController.RenderContext context) {
        return switch (context) {
            case CHAT_OVERLAY -> HOVER_CONTEXT_ROLE;
            case BOOK -> HOVER_OVERLAY_ROLE;
            case ITEM -> ITEM_TOOLTIP_CONTEXT_ROLE;
        };
    }

    private static String buildTooltipContext(List<Component> components,
                                              TooltipTranslationController.RenderContext context) {
        return switch (context) {
            case CHAT_OVERLAY -> buildHoverTooltipContext(components);
            case BOOK -> buildOverlayTooltipContext(components);
            case ITEM -> buildItemTooltipContext(components);
        };
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
                    .append(classifyItemTooltipLine(i, text))
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
                    .append(classifyItemTooltipLine(i, text))
                    .append("]: ")
                    .append(text == null ? "" : text)
                    .append('\n');
        }
        return context.toString().trim();
    }

    private static void addTranslatedSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return;
        }
        TRANSLATED_TOOLTIP_SIGNATURES.add(signature);
        TRANSLATED_TOOLTIP_SIGNATURES.add(signature);
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
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
        if (segments.isEmpty()) {
            return List.of(component);
        }

        List<Component> result = new ArrayList<>();
        MutableComponent current = Component.empty();
        int currentWidth = 0;
        boolean hasText = false;
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null || segment.text.isEmpty()) {
                continue;
            }
            Style style = segment.style == null ? Style.EMPTY : segment.style;
            for (int offset = 0; offset < segment.text.length();) {
                int codePoint = segment.text.codePointAt(offset);
                String piece = new String(Character.toChars(codePoint));
                int pieceWidth = font.width(piece);
                if (hasText && currentWidth + pieceWidth > maxWidth) {
                    result.add(current);
                    current = Component.empty();
                    currentWidth = 0;
                    hasText = false;
                }
                current.append(Component.literal(piece).withStyle(style));
                currentWidth += pieceWidth;
                hasText = true;
                offset += Character.charCount(codePoint);
            }
        }

        if (hasText) {
            result.add(current);
        }
        return result.isEmpty() ? List.of(component) : result;
    }

    private static List<Component> splitComponentByNewlines(Component component) {
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
        List<Component> lines = new ArrayList<>();
        MutableComponent current = Component.empty();
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
                        current.append(Component.literal(text.substring(start, i)).withStyle(style));
                        appendedAnything = true;
                    }
                    if (i < text.length()) {
                        lines.add(current);
                        current = Component.empty();
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

}
