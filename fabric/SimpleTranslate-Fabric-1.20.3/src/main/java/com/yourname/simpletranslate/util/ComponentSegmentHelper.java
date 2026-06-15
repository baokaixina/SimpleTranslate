package com.yourname.simpletranslate.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

        if (component.getContents() instanceof LiteralContents literal) {
            return literal.text();
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

    public static void extractSegments(Component component, List<TextSegmentInfo> segments, Style parentStyle,
            boolean includeEmptyLeaf) {
        if (component == null || segments == null) {
            return;
        }

        Style mergedStyle = mergeStyles(parentStyle, component.getStyle());
        if (component.getContents() instanceof TranslatableContents translatable) {
            Style visitStyle = mergedStyle == null ? Style.EMPTY : mergedStyle;
            List<TextSegmentInfo> argSegments = new ArrayList<>();
            collectTranslatableArgSegments(translatable, visitStyle, argSegments, includeEmptyLeaf);

            StringBuilder visitedText = new StringBuilder();
            List<TextSegmentInfo> visitedSegments = new ArrayList<>();
            component.visit((style, text) -> {
                if (text != null && !text.isEmpty()) {
                    visitedSegments.add(new TextSegmentInfo(text, mergeStyles(visitStyle, style), component));
                    visitedText.append(text);
                }
                return Optional.empty();
            }, visitStyle);

            String resolved = visitedText.toString();
            if (!argSegments.isEmpty() && concatSegmentTexts(argSegments).equals(resolved)) {
                segments.addAll(argSegments);
            } else {
                segments.addAll(visitedSegments);
                supplementTranslatableArgs(translatable, component, visitStyle, segments, resolved, includeEmptyLeaf);
            }
            if (includeEmptyLeaf && component.getSiblings().isEmpty() && segments.isEmpty()) {
                segments.add(new TextSegmentInfo("", visitStyle, component));
            }
            for (Component sibling : component.getSiblings()) {
                extractSegments(sibling, segments, visitStyle, includeEmptyLeaf);
            }
            return;
        }

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

    private static void collectTranslatableArgSegments(TranslatableContents translatable, Style parentStyle,
                                                     List<TextSegmentInfo> segments, boolean includeEmptyLeaf) {
        if (translatable == null || segments == null) {
            return;
        }
        Object[] args = translatable.getArgs();
        if (args == null || args.length == 0) {
            return;
        }
        for (Object arg : args) {
            if (arg instanceof Component argComponent) {
                extractSegments(argComponent, segments, parentStyle, includeEmptyLeaf);
            }
        }
    }

    private static String concatSegmentTexts(List<TextSegmentInfo> segments) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (TextSegmentInfo segment : segments) {
            if (segment != null && segment.text != null) {
                builder.append(segment.text);
            }
        }
        return builder.toString();
    }

    /**
     * Expands translatable args that were not substituted into the resolved template
     * (for example when the language key has no {@code %s} placeholders).
     */
    private static void supplementTranslatableArgs(TranslatableContents translatable, Component component,
                                                   Style parentStyle, List<TextSegmentInfo> segments,
                                                   String visitedText, boolean includeEmptyLeaf) {
        if (translatable == null || segments == null) {
            return;
        }
        Object[] args = translatable.getArgs();
        if (args == null || args.length == 0) {
            return;
        }
        String resolved = visitedText == null ? "" : visitedText;
        for (Object arg : args) {
            if (arg instanceof Component argComponent) {
                String argText = argComponent.getString();
                if (argText == null || argText.isEmpty()) {
                    continue;
                }
                if (resolved.contains(argText)) {
                    if (!hasStyledArgRepresentation(segments, argComponent)) {
                        removeUnstyledArgText(segments, argText);
                        extractSegments(argComponent, segments, parentStyle, includeEmptyLeaf);
                    }
                    continue;
                }
                extractSegments(argComponent, segments, parentStyle, includeEmptyLeaf);
            } else if (arg != null) {
                String argText = arg.toString();
                if (argText.isEmpty() || resolved.contains(argText)) {
                    continue;
                }
                segments.add(new TextSegmentInfo(argText, parentStyle == null ? Style.EMPTY : parentStyle, component));
            }
        }
    }

    private static boolean hasStyledArgRepresentation(List<TextSegmentInfo> segments, Component argComponent) {
        if (segments == null || segments.isEmpty() || argComponent == null) {
            return false;
        }
        List<TextSegmentInfo> argSegments = new ArrayList<>();
        extractSegments(argComponent, argSegments, Style.EMPTY, false);
        if (argSegments.isEmpty()) {
            return false;
        }
        int cursor = 0;
        for (TextSegmentInfo argSegment : argSegments) {
            if (argSegment == null || argSegment.text == null || argSegment.text.isEmpty()) {
                continue;
            }
            boolean matched = false;
            while (cursor < segments.size()) {
                TextSegmentInfo segment = segments.get(cursor);
                cursor++;
                if (segment == null || segment.text == null || !segment.text.equals(argSegment.text)) {
                    continue;
                }
                if (stylesEquivalent(segment.style, argSegment.style)) {
                    matched = true;
                    break;
                }
                return false;
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static void removeUnstyledArgText(List<TextSegmentInfo> segments, String argText) {
        if (segments == null || argText == null || argText.isEmpty()) {
            return;
        }
        for (int i = segments.size() - 1; i >= 0; i--) {
            TextSegmentInfo segment = segments.get(i);
            if (segment != null && argText.equals(segment.text)) {
                segments.remove(i);
            }
        }
    }

    private static boolean stylesEquivalent(Style left, Style right) {
        Style effectiveLeft = left == null ? Style.EMPTY : left;
        Style effectiveRight = right == null ? Style.EMPTY : right;
        return effectiveLeft.equals(effectiveRight);
    }
}
