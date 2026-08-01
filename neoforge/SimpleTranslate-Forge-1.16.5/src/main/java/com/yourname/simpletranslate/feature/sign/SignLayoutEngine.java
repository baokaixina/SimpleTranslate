package com.yourname.simpletranslate.feature.sign;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.IReorderingProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds a four-row render-only layout without changing cached component JSON.
 */
public final class SignLayoutEngine {

    private static final int SIGN_ROWS = 4;

    private SignLayoutEngine() {
    }

    public static Layout layout(ITextComponent[] components, FontRenderer font, int maxTextLineWidth) {
        ITextComponent[] safeComponents = normalizedComponents(components);
        if (font == null || maxTextLineWidth <= 0) {
            return new Layout(toVisualLines(safeComponents), 1.0F, false);
        }

        if (fitsOriginalRows(safeComponents, font, maxTextLineWidth)) {
            return new Layout(toVisualLines(safeComponents), 1.0F, false);
        }

        IFormattableTextComponent merged = mergeLines(safeComponents);
        if (merged.getString().isEmpty()) {
            return new Layout(emptyLines(), 1.0F, false);
        }

        int wrapWidth = findFourRowWrapWidth(merged, font, maxTextLineWidth);
        List<IReorderingProcessor> wrapped = font.split(merged, wrapWidth);
        if (wrapped.isEmpty()) {
            wrapped = List.of(merged.getVisualOrderText());
        }
        if (wrapped.size() > SIGN_ROWS) {
            wrapped = List.of(merged.getVisualOrderText());
        }

        IReorderingProcessor[] renderLines = emptyLines();
        int widestLine = 0;
        for (int index = 0; index < wrapped.size() && index < SIGN_ROWS; index++) {
            IReorderingProcessor line = wrapped.get(index);
            renderLines[index] = line;
            widestLine = Math.max(widestLine, font.width(line));
        }

        float scale = widestLine <= maxTextLineWidth || widestLine <= 0
                ? 1.0F
                : maxTextLineWidth / (float) widestLine;
        return new Layout(renderLines, scale, true);
    }

    private static boolean fitsOriginalRows(ITextComponent[] components, FontRenderer font, int maxTextLineWidth) {
        for (ITextComponent component : components) {
            if (component != null && font.width(component) > maxTextLineWidth) {
                return false;
            }
        }
        return true;
    }

    private static int findFourRowWrapWidth(ITextComponent merged, FontRenderer font, int minimumWidth) {
        int low = Math.max(1, minimumWidth);
        int high = Math.max(low, font.width(merged));
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (font.split(merged, middle).size() <= SIGN_ROWS) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    private static IFormattableTextComponent mergeLines(ITextComponent[] components) {
        IFormattableTextComponent merged = com.yourname.simpletranslate.core.LegacyComponentFactory.empty();
        String previousText = "";
        boolean hasText = false;
        for (ITextComponent component : components) {
            if (component == null || component.getString().isEmpty()) {
                continue;
            }
            String currentText = component.getString();
            if (hasText && needsSeparator(previousText, currentText)) {
                merged.append(com.yourname.simpletranslate.core.LegacyComponentFactory.literal(" "));
            }
            merged.append(component.copy());
            previousText = currentText;
            hasText = true;
        }
        return merged;
    }

    private static boolean needsSeparator(String previous, String current) {
        if (previous == null || previous.isEmpty() || current == null || current.isEmpty()) {
            return false;
        }
        int left = previous.codePointBefore(previous.length());
        int right = current.codePointAt(0);
        if (Character.isWhitespace(left) || Character.isWhitespace(right)) {
            return false;
        }
        if (isCjk(left) || isCjk(right)) {
            return false;
        }
        return !isOpeningPunctuation(left) && !isClosingPunctuation(right);
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static boolean isOpeningPunctuation(int codePoint) {
        return "([{<《「『【（［｛".indexOf(codePoint) >= 0;
    }

    private static boolean isClosingPunctuation(int codePoint) {
        return ".,!?;:)]}>，。！？；：、》」』】）］｝".indexOf(codePoint) >= 0;
    }

    private static ITextComponent[] normalizedComponents(ITextComponent[] components) {
        ITextComponent[] normalized = new ITextComponent[SIGN_ROWS];
        for (int index = 0; index < SIGN_ROWS; index++) {
            normalized[index] = components != null && index < components.length && components[index] != null
                    ? components[index]
                    : com.yourname.simpletranslate.core.LegacyComponentFactory.empty();
        }
        return normalized;
    }

    private static IReorderingProcessor[] toVisualLines(ITextComponent[] components) {
        IReorderingProcessor[] lines = emptyLines();
        for (int index = 0; index < SIGN_ROWS; index++) {
            ITextComponent component = components[index];
            lines[index] = component == null ? IReorderingProcessor.EMPTY : component.getVisualOrderText();
        }
        return lines;
    }

    private static IReorderingProcessor[] emptyLines() {
        IReorderingProcessor[] lines = new IReorderingProcessor[SIGN_ROWS];
        Arrays.fill(lines, IReorderingProcessor.EMPTY);
        return lines;
    }

    public record Layout(IReorderingProcessor[] renderLines, float scale, boolean reflowed) {
        public Layout {
            List<IReorderingProcessor> safeLines = new ArrayList<>(SIGN_ROWS);
            for (int index = 0; index < SIGN_ROWS; index++) {
                IReorderingProcessor line = renderLines != null && index < renderLines.length
                        ? renderLines[index]
                        : null;
                safeLines.add(line == null ? IReorderingProcessor.EMPTY : line);
            }
            renderLines = safeLines.toArray(IReorderingProcessor[]::new);
            scale = Float.isFinite(scale) && scale > 0.0F ? Math.min(1.0F, scale) : 1.0F;
        }

        @Override
        public IReorderingProcessor[] renderLines() {
            return Arrays.copyOf(renderLines, renderLines.length);
        }
    }
}
