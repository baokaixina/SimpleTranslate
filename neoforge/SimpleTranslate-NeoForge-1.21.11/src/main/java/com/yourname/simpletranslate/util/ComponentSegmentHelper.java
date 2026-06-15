package com.yourname.simpletranslate.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Utility methods for extracting text/style segments from Components without
 * duplicating sibling text in translatable components.
 */
public final class ComponentSegmentHelper {

    private ComponentSegmentHelper() {
    }

    public static Style mergeStyles(Style parent, Style child) {
        if (child == null || child.isEmpty()) {
            return parent;
        }
        if (parent == null || parent.isEmpty()) {
            return child;
        }
        // Child style should override parent while inheriting missing fields.
        return child.applyTo(parent);
    }

    /**
     * Returns the direct text content of this component only (excluding sibling text).
     */
    public static String getDirectText(Component component) {
        if (component == null) {
            return "";
        }

        String literalText = literalContentText(component.getContents());
        if (literalText != null) {
            return literalText;
        }

        String full = component.getString();
        if (full.isEmpty()) {
            return full;
        }

        if (component.getSiblings().isEmpty()) {
            return full;
        }

        StringBuilder siblingsText = new StringBuilder();
        for (Component sibling : component.getSiblings()) {
            siblingsText.append(sibling.getString());
        }

        if (siblingsText.length() == 0) {
            return full;
        }

        String suffix = siblingsText.toString();
        if (full.endsWith(suffix)) {
            return full.substring(0, full.length() - suffix.length());
        }

        return full;
    }

    private static String literalContentText(Object contents) {
        if (contents == null) {
            return null;
        }
        String className = contents.getClass().getName();
        if (!className.endsWith("LiteralContents")
                && !className.endsWith("PlainTextContents$LiteralContents")) {
            return null;
        }
        try {
            Method textMethod = contents.getClass().getMethod("text");
            Object value = textMethod.invoke(contents);
            return value instanceof String text ? text : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public static void extractSegments(Component component, List<TextSegmentInfo> segments, Style parentStyle,
            boolean includeEmptyLeaf) {
        if (component == null || segments == null) {
            return;
        }

        Style mergedStyle = mergeStyles(parentStyle, component.getStyle());
        String text = getDirectText(component);

        if (!text.isEmpty()) {
            segments.add(new TextSegmentInfo(text, mergedStyle, component));
        } else if (includeEmptyLeaf && component.getSiblings().isEmpty()) {
            segments.add(new TextSegmentInfo("", mergedStyle, component));
        }

        for (Component sibling : component.getSiblings()) {
            extractSegments(sibling, segments, mergedStyle, includeEmptyLeaf);
        }
    }
}
