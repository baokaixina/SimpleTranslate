package com.yourname.simpletranslate.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Freezes volatile numeric tokens (scores, timers, percentages) into stable
 * {@code @@N@@} placeholders so dynamic HUD text shares one cache entry and one
 * LLM request per template instead of one per value change.
 */
public final class NumberTemplate {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:[.,:]\\d+)*%?");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("@@(\\d+)@@");

    private final Component normalized;
    private final String normalizedText;
    private final List<String> values;

    private NumberTemplate(Component normalized, String normalizedText, List<String> values) {
        this.normalized = normalized;
        this.normalizedText = normalizedText;
        this.values = values;
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

    public static NumberTemplate capture(Component component) {
        if (component == null) {
            return new NumberTemplate(null, "", List.of());
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);

        List<String> values = new ArrayList<>();
        MutableComponent normalized = Component.empty();
        StringBuilder normalizedText = new StringBuilder();
        boolean changed = false;
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null || segment.text.isEmpty()) {
                continue;
            }
            String replaced = replaceNumbers(segment.text, values);
            changed |= !replaced.equals(segment.text);
            normalizedText.append(replaced);
            normalized.append(Component.literal(replaced)
                    .withStyle(segment.style == null ? Style.EMPTY : segment.style));
        }
        if (!changed) {
            return new NumberTemplate(component, component.getString(), List.of());
        }
        return new NumberTemplate(normalized, normalizedText.toString(), values);
    }

    public static NumberTemplate captureText(String text) {
        if (text == null || text.isEmpty()) {
            return new NumberTemplate(null, text == null ? "" : text, List.of());
        }
        List<String> values = new ArrayList<>();
        String replaced = replaceNumbers(text, values);
        return new NumberTemplate(null, replaced, values);
    }

    public Component restore(Component translated) {
        if (translated == null) {
            return null;
        }
        if (values.isEmpty()) {
            return translated;
        }
        if (!canRestore(translated)) {
            return null;
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(translated, segments, Style.EMPTY, true);
        MutableComponent restored = Component.empty();
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null || segment.text.isEmpty()) {
                continue;
            }
            restored.append(Component.literal(restoreText(segment.text))
                    .withStyle(segment.style == null ? Style.EMPTY : segment.style));
        }
        return restored;
    }

    public boolean canRestore(Component translated) {
        if (translated == null) {
            return false;
        }
        return canRestoreText(translated.getString());
    }

    public boolean canRestoreText(String translated) {
        if (values.isEmpty()) {
            return true;
        }
        if (translated == null || translated.indexOf("@@") < 0) {
            return false;
        }

        int[] counts = new int[values.size()];
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(translated);
        while (matcher.find()) {
            int index;
            try {
                index = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return false;
            }
            if (index < 0 || index >= counts.length) {
                return false;
            }
            counts[index]++;
        }

        for (int count : counts) {
            if (count != 1) {
                return false;
            }
        }
        return true;
    }

    public String restoreText(String translated) {
        if (translated == null) {
            return null;
        }
        if (values.isEmpty()) {
            return translated;
        }
        if (!canRestoreText(translated)) {
            return null;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(translated);
        StringBuilder restored = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            restored.append(translated, cursor, matcher.start());
            int index;
            try {
                index = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                index = -1;
            }
            if (index >= 0 && index < values.size()) {
                restored.append(values.get(index));
            } else {
                restored.append(matcher.group());
            }
            cursor = matcher.end();
        }
        restored.append(translated, cursor, translated.length());
        return restored.toString();
    }

    private static String replaceNumbers(String text, List<String> values) {
        if (text.indexOf("@@") >= 0) {
            return text;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        StringBuilder replaced = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            replaced.append(text, cursor, matcher.start());
            replaced.append("@@").append(values.size()).append("@@");
            values.add(matcher.group());
            cursor = matcher.end();
        }
        replaced.append(text, cursor, text.length());
        return replaced.toString();
    }
}
