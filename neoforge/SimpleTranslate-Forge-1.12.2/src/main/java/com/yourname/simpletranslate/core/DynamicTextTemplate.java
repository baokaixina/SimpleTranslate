package com.yourname.simpletranslate.core;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reusable JSON-component template that replaces volatile numeric values. */
public final class DynamicTextTemplate {
    private static final int DYNAMIC_BASE = 1000;
    private static final int DYNAMIC_LIMIT = 2000;
    private static final Pattern COMPONENT_DYNAMIC_MARKER = Pattern.compile("⟦N(\\d+)⟧");
    private static final Pattern MARKER_PATTERN = Pattern.compile("⟦\\s*(\\d{1,4})\\s*⟧");

    private final ITextComponent normalized;
    private final String normalizedText;
    private final List<String> values;

    private DynamicTextTemplate(ITextComponent normalized, String normalizedText, List<String> values) {
        this.normalized = normalized;
        this.normalizedText = normalizedText;
        this.values = values;
    }

    public static DynamicTextTemplate capture(ITextComponent component) {
        if (component == null) {
            return new DynamicTextTemplate(null, "", Collections.<String>emptyList());
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, segments, new Style(), true);
        List<String> values = new ArrayList<>();
        ITextComponent normalized = com.yourname.simpletranslate.core.LegacyComponentFactory.empty();
        StringBuilder text = new StringBuilder();
        boolean changed = false;
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null || segment.text.isEmpty()) {
                continue;
            }
            String replaced = replaceNumbers(segment.text, values);
            changed |= !replaced.equals(segment.text);
            text.append(replaced);
            ITextComponent part = com.yourname.simpletranslate.core.LegacyComponentFactory.literal(replaced);
            part.setStyle(segment.style == null ? new Style() : segment.style.createShallowCopy());
            normalized.appendSibling(part);
        }
        return changed
                ? new DynamicTextTemplate(normalized, text.toString(), immutable(values))
                : new DynamicTextTemplate(component, component.getUnformattedText(), Collections.<String>emptyList());
    }

    public static DynamicTextTemplate captureText(String text) {
        if (text == null || text.isEmpty()) {
            return new DynamicTextTemplate(null, text == null ? "" : text, Collections.<String>emptyList());
        }
        List<String> values = new ArrayList<>();
        return new DynamicTextTemplate(null, replaceNumbers(text, values), immutable(values));
    }

    public ITextComponent normalized() {
        return normalized;
    }

    public String normalizedText() {
        return normalizedText;
    }

    public boolean hasValues() {
        return !values.isEmpty();
    }

    public ITextComponent restore(ITextComponent translated) {
        if (translated == null || !canRestore(translated.getUnformattedText())) {
            return null;
        }
        if (values.isEmpty()) {
            return translated;
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(translated, segments, new Style(), true);
        ITextComponent restored = com.yourname.simpletranslate.core.LegacyComponentFactory.empty();
        for (TextSegmentInfo segment : segments) {
            if (segment != null && segment.text != null && !segment.text.isEmpty()) {
                ITextComponent part = com.yourname.simpletranslate.core.LegacyComponentFactory.literal(
                        restoreTextUnchecked(segment.text));
                part.setStyle(segment.style == null ? new Style() : segment.style.createShallowCopy());
                restored.appendSibling(part);
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
        StringBuffer restored = new StringBuffer();
        while (matcher.find()) {
            int index = parseMarker(matcher.group(1)) - DYNAMIC_BASE;
            String replacement = index >= 0 && index < values.size() ? values.get(index) : matcher.group();
            matcher.appendReplacement(restored, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(restored);
        return restored.toString();
    }

    private static String replaceNumbers(String text, List<String> values) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> extracted = new ArrayList<>();
        String classified = ComponentJsonNumberNormalizer.normalizeNumbers(text, extracted);
        if (extracted.isEmpty()) {
            return text;
        }
        int available = Math.max(0, DYNAMIC_LIMIT - DYNAMIC_BASE - values.size());
        int accepted = Math.min(available, extracted.size());
        int base = values.size();
        values.addAll(extracted.subList(0, accepted));
        Matcher matcher = COMPONENT_DYNAMIC_MARKER.matcher(classified);
        StringBuffer normalized = new StringBuffer(classified.length());
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String replacement = index < accepted
                    ? marker(DYNAMIC_BASE + base + index)
                    : extracted.get(index);
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
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

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }
}
