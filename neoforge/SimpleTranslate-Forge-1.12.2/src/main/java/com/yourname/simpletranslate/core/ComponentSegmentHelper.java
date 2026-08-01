package com.yourname.simpletranslate.core;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;

import java.util.List;

/** Segment extraction adapted to Minecraft 1.12.2's mutable Component API. */
public final class ComponentSegmentHelper {
    private static final int MAX_SEGMENT_DEPTH = 256;
    private ComponentSegmentHelper() {}

    public static Style mergeStyles(Style parent, Style child) {
        if (child == null) return parent == null ? new Style() : parent.createShallowCopy();
        Style result = child.createShallowCopy();
        if (parent != null) result.setParentStyle(parent);
        return result;
    }

    /** Direct component text excluding siblings. */
    public static String getDirectText(ITextComponent component) {
        if (component == null) return "";
        if (component instanceof TextComponentString) {
            String text = ((TextComponentString) component).getText();
            return text == null ? "" : text;
        }
        try {
            String text = component.getUnformattedComponentText();
            return text == null ? "" : text;
        } catch (Throwable error) {
            SafeTranslate.logLimited("component-segments.direct", error);
            return "";
        }
    }

    public static void extractSegments(ITextComponent component, List<TextSegmentInfo> segments,
                                       Style parentStyle, boolean includeEmptyLeaf) {
        extractSegments(component, segments, parentStyle, includeEmptyLeaf, 0);
    }

    private static void extractSegments(ITextComponent component, List<TextSegmentInfo> segments,
                                        Style parentStyle, boolean includeEmptyLeaf, int depth) {
        if (component == null || segments == null || depth > MAX_SEGMENT_DEPTH) return;
        ITextComponent safe = ComponentRenderSafety.sanitize(component);
        Style merged = mergeStyles(parentStyle, safe.getStyle());
        String direct = getDirectText(safe);
        if (!direct.isEmpty() || (includeEmptyLeaf && safe.getSiblings().isEmpty())) {
            segments.add(new TextSegmentInfo(direct, merged, safe));
        }
        for (ITextComponent sibling : safe.getSiblings()) {
            extractSegments(sibling, segments, merged, includeEmptyLeaf, depth + 1);
        }
    }
}
