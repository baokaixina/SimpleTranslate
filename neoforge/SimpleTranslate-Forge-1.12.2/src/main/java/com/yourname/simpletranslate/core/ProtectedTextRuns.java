package com.yourname.simpletranslate.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits legacy rendered text into translatable text and client-owned visual
 * runs. Formatting pairs and private-use glyphs are render metadata, never a
 * translation wire format.
 */
public final class ProtectedTextRuns {
    private ProtectedTextRuns() { }

    public static final class Run {
        private final String text;
        private final boolean protectedRun;

        private Run(String text, boolean protectedRun) {
            this.text = text == null ? "" : text;
            this.protectedRun = protectedRun;
        }

        public String text() { return text; }
        public boolean protectedRun() { return protectedRun; }
    }

    public static List<Run> split(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        List<Run> runs = new ArrayList<Run>();
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
        if (current.length() > 0) runs.add(new Run(current.toString(), currentProtected));
        return Collections.unmodifiableList(runs);
    }

    public static boolean containsLayoutCodepoint(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            if (isPrivateUseCodepoint(codePoint)) return true;
            index += Character.charCount(codePoint);
        }
        return false;
    }

    static boolean isProtectedCodepoint(int codePoint) {
        return codePoint < 0x20 || codePoint == 0x7F || isPrivateUseCodepoint(codePoint);
    }

    private static boolean isPrivateUseCodepoint(int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xC0000 && codePoint <= 0xDFFFF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }
}
