package com.yourname.simpletranslate.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits resource-pack text into visible text and client-owned visual/control
 * runs. HUD and GUI layout renderers use this classifier only to preserve font
 * metric glyphs and legacy formatting; it is not a translation wire protocol.
 */
public final class ProtectedTextRuns {
    private ProtectedTextRuns() {
    }

    public record Run(String text, boolean protectedRun) {
        public Run {
            text = text == null ? "" : text;
        }
    }

    public static List<Run> split(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Run> runs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean currentProtected = false;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int consume = Character.charCount(codePoint);
            boolean protectedRun;
            if (codePoint == '\u00a7' && index + 1 < text.length()) {
                protectedRun = true;
                consume = 1 + Character.charCount(text.codePointAt(index + 1));
            } else {
                protectedRun = isProtectedCodepoint(codePoint);
            }
            if (current.length() > 0 && protectedRun != currentProtected) {
                runs.add(new Run(current.toString(), currentProtected));
                current.setLength(0);
            }
            currentProtected = protectedRun;
            current.append(text, index, index + consume);
            index += consume;
        }
        if (current.length() > 0) {
            runs.add(new Run(current.toString(), currentProtected));
        }
        return List.copyOf(runs);
    }

    static boolean isProtectedCodepoint(int codePoint) {
        if (codePoint < 0x20 || codePoint == 0x7F) {
            return true;
        }
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xC0000 && codePoint <= 0xDFFFF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }
}
