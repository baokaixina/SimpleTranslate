package com.yourname.simpletranslate.feature.hud;

import com.yourname.simpletranslate.core.ComponentSegmentHelper;
import com.yourname.simpletranslate.core.ProtectedTextRuns;
import com.yourname.simpletranslate.core.ActiveFontManager;
import com.yourname.simpletranslate.core.TextSegmentInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Render-only layout preservation for resource-pack actionbars whose private-use
 * glyphs encode absolute screen positions. It never changes a translation
 * request, cache entry, or response acceptance decision.
 */
public final class ActionbarLayoutRenderer {
    private static final float ADVANCE_EPSILON = 0.01F;
    // Smaller horizontal glyphs become visibly crowded at Minecraft's UI scale.
    // This path cannot reflow fixed PUA anchors, so an unsafe fit keeps the
    // complete original actionbar instead of drawing an unreadable translation.
    private static final float MIN_READABLE_SCALE_X = 0.75F;

    private ActionbarLayoutRenderer() {
    }

    /**
     * Compiles a source/translation pair without measuring a client font. A
     * {@code null} result means the translated tree cannot be safely rendered
     * against the source anchors and callers must keep the original actionbar.
     */
    @Nullable
    public static Plan compile(@Nullable Component originalOverlay,
                               @Nullable HudTextSupport.ActionbarTemplate sourceTemplate,
                               @Nullable Component translatedTemplate) {
        if (originalOverlay == null || sourceTemplate == null || translatedTemplate == null
                || sourceTemplate.leaves().isEmpty()) {
            return null;
        }

        List<TextSegmentInfo> translatedLeaves = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(translatedTemplate, translatedLeaves, Style.EMPTY, false);
        if (translatedLeaves.size() != sourceTemplate.leaves().size()) {
            return null;
        }

        List<Span> spans = new ArrayList<>();
        for (int index = 0; index < sourceTemplate.leaves().size(); index++) {
            HudTextSupport.ActionbarLeaf sourceLeaf = sourceTemplate.leaves().get(index);
            TextSegmentInfo translatedLeaf = translatedLeaves.get(index);
            String translatedText = translatedLeaf == null ? null : translatedLeaf.text;
            if (translatedText == null) {
                return null;
            }

            if (sourceLeaf.isVariable()) {
                // Variables are emitted as their own marker leaves. The model may
                // not move, merge, or translate them; current values are restored
                // locally by HudTextSupport before this render path is reached.
                if (!sourceLeaf.templateText().equals(translatedText)) {
                    return null;
                }
                appendOriginalRuns(spans, sourceLeaf.sourceText(), sourceLeaf.style());
                continue;
            }

            List<ProtectedTextRuns.Run> sourceRuns =
                    ProtectedTextRuns.split(sourceLeaf.sourceText());
            List<ProtectedTextRuns.Run> translatedRuns =
                    ProtectedTextRuns.split(translatedText);
            if (sourceRuns.size() != translatedRuns.size()) {
                return null;
            }
            for (int runIndex = 0; runIndex < sourceRuns.size(); runIndex++) {
                ProtectedTextRuns.Run sourceRun = sourceRuns.get(runIndex);
                ProtectedTextRuns.Run translatedRun = translatedRuns.get(runIndex);
                if (sourceRun.protectedRun() != translatedRun.protectedRun()) {
                    return null;
                }
                if (sourceRun.protectedRun() && !sourceRun.text().equals(translatedRun.text())) {
                    return null;
                }
                spans.add(new Span(sourceRun.text(),
                        sourceRun.protectedRun() ? sourceRun.text() : translatedRun.text(),
                        sourceLeaf.style(), sourceRun.protectedRun()));
            }
        }
        return spans.isEmpty() ? null : new Plan(originalOverlay, spans);
    }

    private static void appendOriginalRuns(List<Span> target, String text, Style style) {
        for (ProtectedTextRuns.Run run : ProtectedTextRuns.split(text)) {
            target.add(new Span(run.text(), run.text(), style, run.protectedRun()));
        }
    }

    /** Width seam used by both the renderer and deterministic offline fixtures. */
    @FunctionalInterface
    public interface WidthMeasurer {
        float width(Component component);
    }

    /** Immutable source/translation pairing. */
    public static final class Plan {
        private final Component originalOverlay;
        private final List<Span> spans;

        private Plan(Component originalOverlay, List<Span> spans) {
            this.originalOverlay = originalOverlay;
            this.spans = List.copyOf(spans);
        }

        public Component originalOverlay() {
            return originalOverlay;
        }

        @Nullable private Font cachedLayoutFont;
        private long cachedLayoutRevision = -1L;
        @Nullable private Layout cachedLayout;

        /** Font-bound layout cached by (font, font resource revision). */
        @Nullable
        public Layout layout(Font font) {
            if (font == null) {
                return null;
            }
            long revision = ActiveFontManager.resourceRevision();
            if (font == this.cachedLayoutFont && revision == this.cachedLayoutRevision) {
                return this.cachedLayout;
            }
            Layout layout = layout(component -> font.getSplitter().stringWidth(component));
            this.cachedLayoutFont = font;
            this.cachedLayoutRevision = revision;
            this.cachedLayout = layout;
            return layout;
        }

        /**
         * Resolves source advances and per-span compression. {@code null}
         * preserves the original actionbar rather than risking a shifted frame.
         */
        @Nullable
        public Layout layout(WidthMeasurer widths) {
            if (widths == null) {
                return null;
            }
            float sourceCursor = 0.0F;
            List<PositionedSpan> positioned = new ArrayList<>(spans.size());
            for (Span span : spans) {
                Component source = styled(span.sourceText(), span.style());
                Component rendered = styled(span.renderedText(), span.protectedSpan()
                        ? span.style() : translatedTextStyle(span.style()));
                float sourceAdvance = widths.width(source);
                float renderedAdvance = widths.width(rendered);
                if (!Float.isFinite(sourceAdvance) || !Float.isFinite(renderedAdvance)
                        || renderedAdvance < 0.0F) {
                    return null;
                }

                float scaleX = 1.0F;
                if (!span.protectedSpan()) {
                    if (sourceAdvance <= 0.0F) {
                        return null;
                    }
                    if (renderedAdvance > sourceAdvance) {
                        scaleX = sourceAdvance / renderedAdvance;
                    }
                    if (!Float.isFinite(scaleX) || scaleX < MIN_READABLE_SCALE_X) {
                        return null;
                    }
                }
                positioned.add(new PositionedSpan(source, rendered, span.protectedSpan(), sourceCursor,
                        sourceAdvance, renderedAdvance, scaleX));
                sourceCursor += sourceAdvance;
            }

            float originalAdvance = widths.width(originalOverlay);
            if (!Float.isFinite(originalAdvance)
                    || Math.abs(originalAdvance - sourceCursor) > ADVANCE_EPSILON) {
                return null;
            }
            return new Layout(originalAdvance, List.copyOf(positioned));
        }

        /**
         * Draws one source-sized backdrop followed by individually anchored
         * source/translated spans. The caller already supplies the vanilla
         * source-width x coordinate through the paired width wrapper.
         */
        public boolean render(GuiGraphicsExtractor graphics, Font font,
                              int x, int y, int width, int color) {
            if (graphics == null || font == null || width != font.width(originalOverlay)) {
                return false;
            }
            Layout layout = layout(font);
            if (layout == null) {
                return false;
            }

            // Reuse vanilla's exact backdrop implementation without drawing the
            // full translated stream before its fixed-anchor spans.
            graphics.textWithBackdrop(font, Component.empty(), x, y, width, color);
            for (PositionedSpan span : layout.spans()) {
                if (span.rendered().getString().isEmpty()) {
                    continue;
                }
                graphics.pose().pushMatrix();
                try {
                    graphics.pose().translate(x + span.x(), y);
                    if (!span.protectedSpan() && span.scaleX() != 1.0F) {
                        graphics.pose().scale(span.scaleX(), 1.0F);
                    }
                    graphics.text(font, span.rendered(), 0, 0, color, true);
                } finally {
                    graphics.pose().popMatrix();
                }
            }
            return true;
        }
    }

    /** Pure layout data exposed for fixtures; rendering remains client-only. */
    public record Layout(float sourceAdvance, List<PositionedSpan> spans) {
    }

    /** One source-positioned draw operation. */
    public record PositionedSpan(Component source, Component rendered, boolean protectedSpan,
                                 float x, float sourceAdvance, float renderedAdvance, float scaleX) {
    }

    private record Span(String sourceText, String renderedText, Style style, boolean protectedSpan) {
    }

    private static Component styled(String text, Style style) {
        return Component.literal(text == null ? "" : text)
                .withStyle(style == null ? Style.EMPTY : style);
    }

    /**
     * Resource-pack bitmap fonts own protected icon/positioning spans only.
     * Semantic translations use the mod-owned CJK fallback while retaining the
     * source colour and decorations, preventing tiny, missing, or overlapping
     * Chinese glyphs in generic tutorial/actionbar overlays.
     */
    private static Style translatedTextStyle(Style source) {
        Style safe = source == null ? Style.EMPTY : source;
        return safe.withFont(new FontDescription.Resource(ActiveFontManager.CJK_FALLBACK_FONT));
    }
}
