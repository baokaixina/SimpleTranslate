package com.yourname.simpletranslate.feature.sign;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

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

    public static Layout layout(Component[] components, Font font, int maxTextLineWidth) {
        Component[] safeComponents = normalizedComponents(components);
        if (font == null || maxTextLineWidth <= 0) {
            return new Layout(toVisualLines(safeComponents), 1.0F, false);
        }

        if (fitsOriginalRows(safeComponents, font, maxTextLineWidth)) {
            return new Layout(toVisualLines(safeComponents), 1.0F, false);
        }

        MutableComponent merged = mergeLines(safeComponents);
        if (merged.getString().isEmpty()) {
            return new Layout(emptyLines(), 1.0F, false);
        }

        int wrapWidth = findFourRowWrapWidth(merged, font, maxTextLineWidth);
        List<FormattedCharSequence> wrapped = font.split(merged, wrapWidth);
        if (wrapped.isEmpty()) {
            wrapped = List.of(merged.getVisualOrderText());
        }
        if (wrapped.size() > SIGN_ROWS) {
            wrapped = List.of(merged.getVisualOrderText());
        }

        FormattedCharSequence[] renderLines = emptyLines();
        int widestLine = 0;
        for (int index = 0; index < wrapped.size() && index < SIGN_ROWS; index++) {
            FormattedCharSequence line = wrapped.get(index);
            renderLines[index] = line;
            widestLine = Math.max(widestLine, font.width(line));
        }

        float scale = widestLine <= maxTextLineWidth || widestLine <= 0
                ? 1.0F
                : maxTextLineWidth / (float) widestLine;
        return new Layout(renderLines, scale, true);
    }

    private static boolean fitsOriginalRows(Component[] components, Font font, int maxTextLineWidth) {
        for (Component component : components) {
            if (component != null && font.width(component) > maxTextLineWidth) {
                return false;
            }
        }
        return true;
    }

    private static int findFourRowWrapWidth(Component merged, Font font, int minimumWidth) {
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

    private static MutableComponent mergeLines(Component[] components) {
        MutableComponent merged = Component.empty();
        String previousText = "";
        boolean hasText = false;
        for (Component component : components) {
            if (component == null || component.getString().isEmpty()) {
                continue;
            }
            String currentText = component.getString();
            if (hasText && needsSeparator(previousText, currentText)) {
                merged.append(Component.literal(" "));
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

    private static Component[] normalizedComponents(Component[] components) {
        Component[] normalized = new Component[SIGN_ROWS];
        for (int index = 0; index < SIGN_ROWS; index++) {
            normalized[index] = components != null && index < components.length && components[index] != null
                    ? components[index]
                    : Component.empty();
        }
        return normalized;
    }

    private static FormattedCharSequence[] toVisualLines(Component[] components) {
        FormattedCharSequence[] lines = emptyLines();
        for (int index = 0; index < SIGN_ROWS; index++) {
            Component component = components[index];
            lines[index] = component == null ? FormattedCharSequence.EMPTY : component.getVisualOrderText();
        }
        return lines;
    }

    private static FormattedCharSequence[] emptyLines() {
        FormattedCharSequence[] lines = new FormattedCharSequence[SIGN_ROWS];
        Arrays.fill(lines, FormattedCharSequence.EMPTY);
        return lines;
    }

    public record Layout(FormattedCharSequence[] renderLines, float scale, boolean reflowed) {
        public Layout {
            List<FormattedCharSequence> safeLines = new ArrayList<>(SIGN_ROWS);
            for (int index = 0; index < SIGN_ROWS; index++) {
                FormattedCharSequence line = renderLines != null && index < renderLines.length
                        ? renderLines[index]
                        : null;
                safeLines.add(line == null ? FormattedCharSequence.EMPTY : line);
            }
            renderLines = safeLines.toArray(FormattedCharSequence[]::new);
            scale = Float.isFinite(scale) && scale > 0.0F ? Math.min(1.0F, scale) : 1.0F;
        }

        @Override
        public FormattedCharSequence[] renderLines() {
            return Arrays.copyOf(renderLines, renderLines.length);
        }
    }
}
