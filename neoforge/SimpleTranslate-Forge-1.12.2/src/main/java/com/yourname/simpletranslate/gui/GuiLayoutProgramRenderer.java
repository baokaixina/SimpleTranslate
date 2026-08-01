package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.core.ProtectedTextRuns;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Legacy 1.12 renderer for resource-pack strings that use PUA glyphs as a
 * small layout program. Every replacement span keeps the source cursor
 * advance so translated text cannot move later icons or rows.
 */
public final class GuiLayoutProgramRenderer {
    private static final int MAX_DETECTION_CACHE = 64;
    private static final float MIN_READABLE_SCALE_X = 0.75F;
    private static final ThreadLocal<Boolean> REPLAYING = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() { return Boolean.FALSE; }
    };
    private static final Map<String, Boolean> DETECTION_CACHE =
            new LinkedHashMap<String, Boolean>(80, 0.75F, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_DETECTION_CACHE;
                }
            };

    private GuiLayoutProgramRenderer() { }

    public static boolean isReplaying() { return REPLAYING.get().booleanValue(); }

    public static boolean isLayoutProgram(String source) {
        if (source == null || source.isEmpty()) return false;
        synchronized (DETECTION_CACHE) {
            Boolean cached = DETECTION_CACHE.get(source);
            if (cached != null) return cached.booleanValue();
        }
        boolean result = ProtectedTextRuns.containsLayoutCodepoint(source);
        synchronized (DETECTION_CACHE) { DETECTION_CACHE.put(source, Boolean.valueOf(result)); }
        return result;
    }

    /** Pure structural seam used by build-only regression checks. */
    public static boolean hasCompatibleVisualRuns(String source, String translated) {
        List<Span> spans = compile(source, translated);
        return spans != null && !spans.isEmpty();
    }

    /** Mirrors the modern guard: negative advances are valid only for protected glyph runs. */
    public static boolean acceptsMeasuredAdvances(float sourceAdvance, float renderedAdvance,
                                                   boolean protectedRun) {
        return !Float.isNaN(sourceAdvance) && !Float.isInfinite(sourceAdvance)
                && !Float.isNaN(renderedAdvance) && !Float.isInfinite(renderedAdvance)
                && (protectedRun || renderedAdvance >= 0.0F);
    }

    /**
     * Replays one exact private FontRenderer#renderString pass. The caller
     * supplies the pass' shadow flag; this method applies its colour transform
     * and invokes public drawString with shadow disabled to avoid duplicating
     * the outer shadow/normal pair.
     */
    public static Integer renderText(FontRenderer font, String source, String translated,
                                     float x, float y, int color, boolean shadowPass) {
        if (font == null || source == null || translated == null || source.equals(translated)
                || !isLayoutProgram(source)) return null;
        List<Span> spans = compile(source, translated);
        if (spans == null || spans.isEmpty()) return null;

        int wholeAdvance = rawWidth(font, source);
        int sourceCursor = 0;
        int passColor = shadowPass
                ? ((color & 0xFCFCFC) >> 2 | color & 0xFF000000)
                : color;
        String formatting = "";
        for (Span span : spans) {
            if (span.kind == Kind.FORMAT) {
                formatting += span.source;
                continue;
            }
            String renderedText = span.kind == Kind.PROTECTED ? span.source : span.translated;
            String styledSource = formatting + span.source;
            String styledRendered = formatting + renderedText;
            int sourceAdvance = rawWidth(font, styledSource);
            int renderedAdvance = rawWidth(font, styledRendered);
            if (!acceptsMeasuredAdvances(sourceAdvance, renderedAdvance, span.kind == Kind.PROTECTED)) {
                return null;
            }
            float scaleX = span.kind == Kind.TEXT && sourceAdvance > 0 && renderedAdvance > sourceAdvance
                    ? Math.max(MIN_READABLE_SCALE_X, (float) sourceAdvance / (float) renderedAdvance)
                    : 1.0F;
            if (!renderedText.isEmpty()) {
                GlStateManager.pushMatrix();
                try {
                    GlStateManager.translate(x + sourceCursor, y, 0.0F);
                    if (scaleX != 1.0F) GlStateManager.scale(scaleX, 1.0F, 1.0F);
                    REPLAYING.set(Boolean.TRUE);
                    font.drawString(styledRendered, 0.0F, 0.0F, passColor, false);
                } finally {
                    REPLAYING.set(Boolean.FALSE);
                    GlStateManager.popMatrix();
                }
            }
            sourceCursor += sourceAdvance;
        }
        if (Math.abs(wholeAdvance - sourceCursor) > 1) return null;
        return Integer.valueOf((int) (x + wholeAdvance));
    }

    /** Replays a normal translated private render pass without recursive capture. */
    public static Integer renderPlainPass(FontRenderer font, String translated,
                                          float x, float y, int color, boolean shadowPass) {
        if (font == null || translated == null) return null;
        int passColor = shadowPass
                ? ((color & 0xFCFCFC) >> 2 | color & 0xFF000000)
                : color;
        REPLAYING.set(Boolean.TRUE);
        try {
            return Integer.valueOf(font.drawString(translated, x, y, passColor, false));
        } finally {
            REPLAYING.set(Boolean.FALSE);
        }
    }

    public static void clearLocalState() {
        REPLAYING.remove();
        synchronized (DETECTION_CACHE) { DETECTION_CACHE.clear(); }
    }

    private static int rawWidth(FontRenderer font, String text) {
        boolean previous = isReplaying();
        REPLAYING.set(Boolean.TRUE);
        try {
            return font.getStringWidth(text == null ? "" : text);
        } finally {
            REPLAYING.set(Boolean.valueOf(previous));
        }
    }

    private static List<Span> compile(String source, String translated) {
        List<Token> originals = tokenize(source);
        List<Token> replacements = tokenize(translated);
        if (originals.size() != replacements.size()) return null;
        List<Span> spans = new ArrayList<Span>(originals.size());
        for (int index = 0; index < originals.size(); index++) {
            Token original = originals.get(index);
            Token replacement = replacements.get(index);
            if (original.kind != replacement.kind) return null;
            if (original.kind != Kind.TEXT && !original.text.equals(replacement.text)) return null;
            spans.add(new Span(original.text, replacement.text, original.kind));
        }
        return spans;
    }

    private static List<Token> tokenize(String value) {
        List<Token> result = new ArrayList<Token>();
        if (value == null || value.isEmpty()) return result;
        StringBuilder current = new StringBuilder();
        Kind currentKind = null;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            int consume = Character.charCount(codePoint);
            Kind kind;
            if (codePoint == '\u00a7' && index + 1 < value.length()) {
                kind = Kind.FORMAT;
                consume = 1 + Character.charCount(value.codePointAt(index + 1));
            } else {
                kind = ProtectedTextRuns.containsLayoutCodepoint(
                        value.substring(index, index + consume)) ? Kind.PROTECTED : Kind.TEXT;
            }
            if (current.length() > 0 && kind != currentKind) {
                result.add(new Token(current.toString(), currentKind));
                current.setLength(0);
            }
            currentKind = kind;
            current.append(value, index, index + consume);
            index += consume;
            if (kind == Kind.FORMAT) {
                result.add(new Token(current.toString(), currentKind));
                current.setLength(0);
                currentKind = null;
            }
        }
        if (current.length() > 0) result.add(new Token(current.toString(), currentKind));
        return result;
    }

    private enum Kind { FORMAT, PROTECTED, TEXT }

    private static final class Token {
        private final String text;
        private final Kind kind;
        private Token(String text, Kind kind) { this.text = text; this.kind = kind; }
    }

    private static final class Span {
        private final String source;
        private final String translated;
        private final Kind kind;
        private Span(String source, String translated, Kind kind) {
            this.source = source;
            this.translated = translated;
            this.kind = kind;
        }
    }
}
