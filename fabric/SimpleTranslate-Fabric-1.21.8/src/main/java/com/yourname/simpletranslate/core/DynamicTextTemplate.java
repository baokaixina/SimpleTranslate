package com.yourname.simpletranslate.core;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reusable JSON-component template that replaces volatile numeric values. */
public final class DynamicTextTemplate {
    private static final int DYNAMIC_BASE = 1000;
    private static final int DYNAMIC_LIMIT = 2000;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:[.,:]\\d+)*%?");
    private static final Pattern MARKER_PATTERN = Pattern.compile("⟦\\s*(\\d{1,4})\\s*⟧");

    private final Component normalized;
    private final String normalizedText;
    private final List<String> values;

    private DynamicTextTemplate(Component normalized, String normalizedText, List<String> values) {
        this.normalized = normalized;
        this.normalizedText = normalizedText;
        this.values = values;
    }

    public static DynamicTextTemplate capture(Component component) {
        if (component == null) {
            return new DynamicTextTemplate(null, "", List.of());
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
        List<String> values = new ArrayList<>();
        MutableComponent normalized = Component.empty();
        StringBuilder text = new StringBuilder();
        boolean changed = false;
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null || segment.text.isEmpty()) {
                continue;
            }
            String replaced = replaceNumbers(segment.text, values);
            changed |= !replaced.equals(segment.text);
            text.append(replaced);
            normalized.append(Component.literal(replaced)
                    .withStyle(segment.style == null ? Style.EMPTY : segment.style));
        }
        return changed
                ? new DynamicTextTemplate(normalized, text.toString(), List.copyOf(values))
                : new DynamicTextTemplate(component, component.getString(), List.of());
    }

    public static DynamicTextTemplate captureText(String text) {
        if (text == null || text.isEmpty()) {
            return new DynamicTextTemplate(null, text == null ? "" : text, List.of());
        }
        List<String> values = new ArrayList<>();
        return new DynamicTextTemplate(null, replaceNumbers(text, values), List.copyOf(values));
    }

    public Component normalized() {
        return normalized;
    }

    public String normalizedText() {
        return normalizedText;
    }

    public boolean hasValues() {
        return !values.isEmpty();
    }

    public Component restore(Component translated) {
        if (translated == null || !canRestore(translated.getString())) {
            return null;
        }
        if (values.isEmpty()) {
            return translated;
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(translated, segments, Style.EMPTY, true);
        MutableComponent restored = Component.empty();
        for (TextSegmentInfo segment : segments) {
            if (segment != null && segment.text != null && !segment.text.isEmpty()) {
                restored.append(Component.literal(restoreTextUnchecked(segment.text))
                        .withStyle(segment.style == null ? Style.EMPTY : segment.style));
            }
        }
        return restored;
    }

    public String restoreText(String translated) {
        if (!canRestore(translated)) {
            return null;
        }
        return values.isEmpty() ? translated : restoreTextUnchecked(translated);
    }

    private boolean canRestore(String translated) {
        if (translated == null) {
            return false;
        }
        if (values.isEmpty()) {
            return true;
        }
        Map<Integer, Integer> counts = markerCounts(translated);
        if (counts.size() != values.size()) {
            return false;
        }
        for (int i = 0; i < values.size(); i++) {
            if (counts.getOrDefault(DYNAMIC_BASE + i, 0) != 1) {
                return false;
            }
        }
        return true;
    }

    private String restoreTextUnchecked(String translated) {
        Matcher matcher = MARKER_PATTERN.matcher(translated == null ? "" : translated);
        StringBuilder restored = new StringBuilder();
        while (matcher.find()) {
            int index = parseMarker(matcher.group(1)) - DYNAMIC_BASE;
            String replacement = index >= 0 && index < values.size() ? values.get(index) : matcher.group();
            matcher.appendReplacement(restored, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(restored);
        return restored.toString();
    }

    private static String replaceNumbers(String text, List<String> values) {
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        StringBuilder replaced = new StringBuilder();
        while (matcher.find() && values.size() < DYNAMIC_LIMIT - DYNAMIC_BASE) {
            String marker = marker(DYNAMIC_BASE + values.size());
            values.add(matcher.group());
            matcher.appendReplacement(replaced, Matcher.quoteReplacement(marker));
        }
        matcher.appendTail(replaced);
        return replaced.toString();
    }

    private static Map<Integer, Integer> markerCounts(String text) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        Matcher matcher = MARKER_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            int marker = parseMarker(matcher.group(1));
            if (marker >= DYNAMIC_BASE && marker < DYNAMIC_LIMIT) {
                counts.merge(marker, 1, Integer::sum);
            }
        }
        return counts;
    }

    public static String marker(int index) {
        return "⟦" + index + "⟧";
    }

    private static int parseMarker(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
