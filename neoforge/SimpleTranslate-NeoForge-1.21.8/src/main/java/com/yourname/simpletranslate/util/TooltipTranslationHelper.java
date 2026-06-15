package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
    public static final String HOVER_LINES_SURFACE = "hover.lines.direct";
    public static final String HOVER_LINES_ROLE = "hover-component-lines";

    private static final int MAX_TRANSLATED_TOOLTIP_WIDTH = 360;
    private static final int MAX_TRANSLATED_SIGNATURES = 4096;
    private static final Set<String> PENDING_ASYNC_REFRESH_SIGNATURES = ConcurrentHashMap.newKeySet();
    private static final Set<List<Component>> TRANSLATED_COMPONENT_LISTS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Component> TRANSLATED_COMPONENTS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<String> TRANSLATED_TOOLTIP_SIGNATURES = ConcurrentHashMap.newKeySet();

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
        if (components == null || components.isEmpty()) {
            return components;
        }
        if (isMarkedTranslatedTooltip(components)) {
            return components;
        }

        String surface = surfaceFor(context);
        String role = roleFor(context);
        String tooltipContext = buildTooltipContext(components, context);

        DirectFormattedTranslationPipeline.ComponentListResult direct =
                DirectSurfaceTranslator.translateComponents(
                        components, surface, role, false, tooltipContext);
        if (!direct.handled) {
            return components;
        }
        if (direct.components != components) {
            List<Component> translated = constrainTranslatedTooltipLines(direct.components, components);
            markTranslatedTooltip(translated);
            return translated;
        }
        scheduleAsyncRefresh(components, surface, role, tooltipContext);
        return components;
    }

    public static void prefetchComponentsBatch(List<Component> components) {
        if (components == null || components.isEmpty()
                || isMarkedTranslatedTooltip(components)
                || !anyContainsEnglish(components)) {
            return;
        }
        DirectSurfaceTranslator.translateComponents(
                components, ITEM_TOOLTIP_CONTEXT_SURFACE, ITEM_TOOLTIP_CONTEXT_ROLE, false,
                buildItemTooltipContext(components));
    }

    public static DirectFormattedTranslationPipeline.ComponentListResult getCachedComponentsBatch(List<Component> components) {
        return DirectSurfaceTranslator.getCachedComponents(
                components, ITEM_TOOLTIP_CONTEXT_SURFACE, ITEM_TOOLTIP_CONTEXT_ROLE, false,
                buildItemTooltipContext(components));
    }

    public static String buildItemTooltipContext(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("Item tooltip semantic block. Translate every numbered line using the whole block, ")
                .append("especially adjacent lore and mechanic lines, while preserving the original line slots.\n");
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

    public static Component translateComponentWithStyle(Component component) {
        if (component == null || isMarkedTranslatedTooltip(component) || !containsEnglish(component.getString())) {
            return component;
        }

        DirectFormattedTranslationPipeline.ComponentResult direct =
                DirectSurfaceTranslator.translateComponent(component, "hover.component.direct", "hover-component");
        if (direct.handled && direct.component != component) {
            markTranslatedTooltip(direct.component);
        }
        return direct.handled ? direct.component : component;
    }

    public static List<Component> translateHoverComponentLines(Component component) {
        if (component == null) {
            return List.of();
        }
        if (isMarkedTranslatedTooltip(component) || !containsEnglish(component.getString())) {
            return List.of(component);
        }

        List<Component> lines = splitComponentByNewlines(component);
        DirectFormattedTranslationPipeline.ComponentListResult direct =
                DirectSurfaceTranslator.translateComponents(
                        lines, HOVER_LINES_SURFACE, HOVER_LINES_ROLE, false);
        if (!direct.handled) {
            return List.of(component);
        }
        if (direct.components == lines) {
            scheduleAsyncRefresh(lines, HOVER_LINES_SURFACE, HOVER_LINES_ROLE, "");
            return List.of(component);
        }
        List<Component> translated = constrainTranslatedTooltipLines(direct.components, lines);
        markTranslatedTooltip(translated);
        return translated;
    }

    public static Component translateComponent(Component component) {
        if (component == null || isMarkedTranslatedTooltip(component) || !containsEnglish(component.getString())) {
            return component;
        }

        DirectFormattedTranslationPipeline.ComponentResult direct =
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
                "health", "armor", "attack", "speed", "ability", "effect", "bonus", "bonuses",
                "target", "targets", "second", "seconds", "when worn", "while worn", "on hit",
                "on use", "level", "chance", "cost")) {
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
        int checked = 0;
        int marked = 0;
        synchronized (TRANSLATED_COMPONENTS) {
            for (Component component : components) {
                if (component == null) {
                    continue;
                }
                checked++;
                if (TRANSLATED_COMPONENTS.contains(component)) {
                    marked++;
                }
            }
        }
        return checked > 0 && checked == marked;
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
        synchronized (TRANSLATED_COMPONENTS) {
            for (Component component : components) {
                if (component != null) {
                    TRANSLATED_COMPONENTS.add(component);
                }
            }
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
    }

    private static void scheduleAsyncRefresh(List<Component> components, String surface, String role,
                                             String context) {
        if (components == null || components.isEmpty()) {
            return;
        }
        String signature = tooltipSignature(components);
        if (signature.isBlank() || !PENDING_ASYNC_REFRESH_SIGNATURES.add(signature)) {
            return;
        }
        DirectSurfaceTranslator.translateComponentsAsync(components, surface, role, false, context)
                .whenComplete((result, error) -> {
                    PENDING_ASYNC_REFRESH_SIGNATURES.remove(signature);
                    if (error != null || result == null || !result.translated
                            || result.components == null || result.components.isEmpty()) {
                        return;
                    }
                    List<Component> translated = constrainTranslatedTooltipLines(result.components, components);
                    markTranslatedTooltip(translated);
                });
    }

    private static String surfaceFor(TooltipTranslationController.RenderContext context) {
        return switch (context) {
            case CHAT_OVERLAY, BOOK -> HOVER_OVERLAY_SURFACE;
            case ITEM -> ITEM_TOOLTIP_CONTEXT_SURFACE;
        };
    }

    private static String roleFor(TooltipTranslationController.RenderContext context) {
        return switch (context) {
            case CHAT_OVERLAY, BOOK -> HOVER_OVERLAY_ROLE;
            case ITEM -> ITEM_TOOLTIP_CONTEXT_ROLE;
        };
    }

    private static String buildTooltipContext(List<Component> components,
                                              TooltipTranslationController.RenderContext context) {
        return switch (context) {
            case CHAT_OVERLAY, BOOK -> buildOverlayTooltipContext(components);
            case ITEM -> buildItemTooltipContext(components);
        };
    }

    public static String buildOverlayTooltipContext(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        context.append("Overlay tooltip block rendered outside HoverEvent, e.g. chat skill details or mod UI. ")
                .append("Translate every numbered line as one coherent passage while preserving line slots.\n");
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
        if (TRANSLATED_TOOLTIP_SIGNATURES.size() >= MAX_TRANSLATED_SIGNATURES) {
            TRANSLATED_TOOLTIP_SIGNATURES.clear();
        }
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
        Font font = Minecraft.getInstance().font;
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
        Font font = Minecraft.getInstance().font;
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
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null || segment.text.isEmpty()) {
                continue;
            }
            Style style = segment.style == null ? Style.EMPTY : segment.style;
            for (int offset = 0; offset < segment.text.length();) {
                int codePoint = segment.text.codePointAt(offset);
                String piece = new String(Character.toChars(codePoint));
                int pieceWidth = font.width(piece);
                if (currentWidth > 0 && currentWidth + pieceWidth > maxWidth) {
                    result.add(current);
                    current = Component.empty();
                    currentWidth = 0;
                }
                current.append(Component.literal(piece).withStyle(style));
                currentWidth += pieceWidth;
                offset += Character.charCount(codePoint);
            }
        }

        if (!current.getString().isEmpty()) {
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
