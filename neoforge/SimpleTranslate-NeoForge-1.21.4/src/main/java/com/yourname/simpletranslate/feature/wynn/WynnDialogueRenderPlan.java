package com.yourname.simpletranslate.feature.wynn;

import com.yourname.simpletranslate.core.ActiveFontManager;
import com.yourname.simpletranslate.core.SafeTranslate;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Render-only Wynn dialogue plan. The original stream supplies frame geometry
 * and advances; only verified semantic glyph quads are hidden and replaced by
 * default-font lines in their measured source regions.
 */
public final class WynnDialogueRenderPlan {
    private static final float EPSILON = 0.01F;
    private final Component sourceActionbar;
    private final List<WynnDialogueProjection.GlyphEvent> events;
    private final WynnDialogueProjection.EventSequence sourceSequence;
    private final List<TranslatedSlot> translatedSlots;
    private final java.util.BitSet dockedReplayOrdinals;
    @Nullable private CachedMetrics cachedMetrics;

    WynnDialogueRenderPlan(Component sourceActionbar,
                           List<WynnDialogueProjection.GlyphEvent> events,
                           List<TranslatedSlot> translatedSlots) {
        this.sourceActionbar = sourceActionbar;
        this.events = events == null ? List.of() : List.copyOf(events);
        this.sourceSequence = new WynnDialogueProjection.EventSequence(this.events);
        this.translatedSlots = translatedSlots == null ? List.of() : List.copyOf(translatedSlots);
        this.dockedReplayOrdinals = new java.util.BitSet(this.events.size());
    }

    public Component sourceActionbar() {
        return sourceActionbar;
    }

    public List<TranslatedSlot> translatedSlots() {
        return translatedSlots;
    }

    /**
     * Draws frame/portrait/positioning glyphs only (never the replaced English
     * prose), then overlays default-font Chinese. English is not drawn-and-masked
     * anymore: any Font path that ignored the glyph-mask mixin left residual
     * Latin under CJK. Skipping those events entirely is the reliable fix.
     */
    public boolean render(GuiGraphics graphics, Font font,
                          int x, int y, int width, int color) {
        if (graphics == null || font == null) {
            return false;
        }
        CachedMetrics metrics = resolveMetrics(font);
        if (metrics == null) {
            logRenderFailure("metrics-null", width, -1, Float.NaN);
            return false;
        }
        if (metrics.layout() == null) {
            logRenderFailure("layout-empty", width, metrics.sourceWidth(),
                    metrics.sourceAdvance());
            return false;
        }
        List<SourceReplayRun> sourceReplay = metrics.replayRuns();
        if (sourceReplay == null) {
            logRenderFailure("unmasked-run-preflight-failed", width, metrics.sourceWidth(),
                    metrics.sourceAdvance());
            return false;
        }
        graphics.drawStringWithBackdrop(font, Component.empty(), x, y, width, color);
        drawUnmaskedSourceRuns(graphics, font, sourceReplay, x, y, color);
        for (PositionedLine line : metrics.layout().lines()) {
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(x + line.x(), y + line.y(), 0.0D);
                graphics.drawString(font, line.text(), 0, 0, color, true);
            } finally {
                graphics.pose().popPose();
            }
        }
        return true;
    }

    /**
     * Walks the original glyph stream once. Masked (translated) prose is only
     * counted for advance so later frame PUA stays aligned; it is never submitted
     * to Font.drawString. Unmasked runs keep their cumulative X from the full
     * stream and are drawn as short sequences.
     */
    @Nullable
    private List<SourceReplayRun> prepareUnmaskedSourceRuns(
            Font font, java.util.BitSet acceptedMask) {
        if (font == null || acceptedMask == null) {
            return null;
        }
        if (events.isEmpty()) return List.of();
        List<SourceReplayRun> result = new ArrayList<>();
        float cursor = 0.0F;
        int index = 0;
        while (index < events.size()) {
            if (isSourceGlyphSuppressed(index, acceptedMask)) {
                float advance = font.getSplitter().stringWidth(
                        sourceSequence.slice(index, index + 1));
                if (!Float.isFinite(advance)) {
                    return null;
                }
                cursor += advance;
                if (!Float.isFinite(cursor)) return null;
                index++;
                continue;
            }
            int start = index;
            float runX = cursor;
            while (index < events.size()
                    && !isSourceGlyphSuppressed(index, acceptedMask)) {
                float advance = font.getSplitter().stringWidth(
                        sourceSequence.slice(index, index + 1));
                if (!Float.isFinite(advance)) {
                    return null;
                }
                cursor += advance;
                if (!Float.isFinite(cursor)) return null;
                index++;
            }
            List<WynnDialogueProjection.GlyphEvent> runEvents = events.subList(start, index);
            WynnDialogueProjection.EventSequence run =
                    new WynnDialogueProjection.EventSequence(runEvents);
            result.add(new SourceReplayRun(run, runX));
        }
        return List.copyOf(result);
    }

    /** Only explicitly committed prose ordinals and flow-docked icons may be hidden. */
    private boolean isSourceGlyphSuppressed(int ordinal, java.util.BitSet acceptedMask) {
        return ordinal >= 0 && ordinal < events.size()
                && (acceptedMask != null && acceptedMask.get(ordinal)
                || this.dockedReplayOrdinals.get(ordinal));
    }

    private static void drawUnmaskedSourceRuns(
            GuiGraphics graphics, Font font, List<SourceReplayRun> sourceReplay,
            int x, int y, int color) {
        for (SourceReplayRun run : sourceReplay) {
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(x + run.x(), y, 0.0D);
                graphics.drawString(font, run.sequence(), 0, 0, color, true);
            } finally {
                graphics.pose().popPose();
            }
        }
    }

    @Nullable
    public Layout resolveLayout(@Nullable Font font) {
        CachedMetrics metrics = resolveMetrics(font);
        return metrics == null ? null : metrics.layout();
    }

    @Nullable
    private CachedMetrics resolveMetrics(@Nullable Font font) {
        if (font == null) {
            return null;
        }
        long revision = ActiveFontManager.resourceRevision();
        CachedMetrics cached = this.cachedMetrics;
        if (cached != null && cached.font() == font && cached.resourceRevision() == revision) {
            return cached;
        }
        Layout layout = computeLayout(font);
        List<SourceReplayRun> replayRuns = layout == null ? null
                : prepareUnmaskedSourceRuns(font, layout.acceptedMask());
        CachedMetrics computed = new CachedMetrics(font, revision, layout, replayRuns,
                font.width(sourceActionbar),
                font.getSplitter().stringWidth(sourceSequence));
        this.cachedMetrics = computed;
        return computed;
    }

    @Nullable
    private Layout computeLayout(Font font) {
        this.dockedReplayOrdinals.clear();
        List<PositionedLine> result = new ArrayList<>();
        java.util.BitSet acceptedMask = new java.util.BitSet(events.size());
        for (TranslatedSlot translatedSlot : translatedSlots) {
            if (!translatedSlot.sourceVisible()) {
                // The server has already supplied this option so it can be
                // translated and cached, but the current dialogue phase keeps
                // its original glyphs hidden/off-screen. Never reveal it via
                // the default-font overlay ahead of Wynn's own choice phase.
                continue;
            }
            List<PositionedLine> measured = measureSlot(font, translatedSlot);
            if (!commitMeasuredSlot(result, acceptedMask, translatedSlot, measured)) {
                // Keep only this slot on Wynn's original glyph stream. A name
                // or option with unusual metrics must never throw away a valid
                // BODY/CONTROL translation from the same atomic response.
                continue;
            }
        }
        if (acceptedMask.isEmpty()) {
            return null;
        }
        return new Layout(List.copyOf(result), acceptedMask);
    }

    /** Measures one semantic slot transactionally; no shared mask is touched here. */
    @Nullable
    private List<PositionedLine> measureSlot(Font font, TranslatedSlot translatedSlot) {
        WynnDialogueProjection.SemanticSlot sourceSlot = translatedSlot.source();
        List<WynnDialogueProjection.LineRegion> regions = sourceSlot.regions();
        if (regions.isEmpty()) {
            return measurementFailure(translatedSlot, "no-source-regions");
        }
        if (sourceSlot.kind() == WynnDialogueProjection.SemanticKind.BODY) {
            List<PositionedLine> body = measureBodyParagraph(font, translatedSlot);
            if (body == null || body.isEmpty()) {
                return measurementFailure(translatedSlot, "body-paragraph-layout-unavailable");
            }
            if (!hasNonOverlappingRows(font, body)) {
                return measurementFailure(translatedSlot, "translated-body-paragraph-overlap");
            }
            return body;
        }

        List<RegionLayout> regionLayouts = new ArrayList<>(regions.size());
        for (WynnDialogueProjection.LineRegion region : regions) {
            RegionLayout regionLayout = regionLayout(font, region);
            if (regionLayout == null) {
                return measurementFailure(translatedSlot,
                        "source-region-bounds-unavailable:" + region.line());
            }
            regionLayouts.add(regionLayout);
        }

        // NAME, CONTROL and OPTION are fixed single-line UI slots. Their source
        // text width is not the container width, so longer translated names are
        // measured once instead of inheriting BODY wrapping rules.
        FormattedCharSequence line = translatedSlot.component().getVisualOrderText();
        float translatedWidth = font.getSplitter().stringWidth(line);
        Bounds targetBounds = bounds(font, line);
        if (!finitePositive(translatedWidth) || targetBounds == null) {
            return measurementFailure(translatedSlot, "fixed-slot-bounds-unavailable");
        }
        float occupiedWidth = occupiedWidth(translatedWidth, targetBounds);
        if (!finitePositive(occupiedWidth)) {
            return measurementFailure(translatedSlot, "fixed-slot-pixel-width-unavailable");
        }
        RegionLayout region = regionLayouts.getFirst();
        float absoluteLeft = region.absoluteLeft();
        float drawX = sourceSlot.kind() == WynnDialogueProjection.SemanticKind.NAME
                || sourceSlot.kind() == WynnDialogueProjection.SemanticKind.CONTROL
                ? centeredDrawX(absoluteLeft, region.budget(), targetBounds.left(), translatedWidth)
                : alignedDrawX(absoluteLeft, region.budget(), targetBounds.left(),
                translatedWidth, sourceSlot.regions().getFirst().inlineIconAfter());
        float drawY = region.bounds().top() - targetBounds.top();
        if (!Float.isFinite(drawX) || !Float.isFinite(drawY)) {
            return measurementFailure(translatedSlot, "fixed-slot-position-unavailable");
        }
        return List.of(new PositionedLine(sourceSlot.kind(), 0, line,
                drawX, drawY, region.budget(), occupiedWidth));
    }

    /**
     * Lays a translated BODY as ordinary paragraph flow across native Wynn text
     * rows. Source BODY runs are anchors for masking only: inheriting their
     * x-coordinates would split Chinese at English visual fragments and make it
     * unreadable. Frame, portrait, PUA, and control glyphs remain source-replay;
     * a successfully docked inline icon instead reserves its advance inside the
     * translated flow and is drawn directly before its keyword span. Any
     * unmeasurable blocker rejects this whole BODY transaction.
     */
    @Nullable
    private List<PositionedLine> measureBodyParagraph(Font font, TranslatedSlot translatedSlot) {
        if (font == null || translatedSlot.bodyMaskOrdinals().isEmpty()) {
            return null;
        }
        WynnDialogueProjection.SemanticSlot sourceSlot = translatedSlot.source();
        List<WynnDialogueProjection.LineRegion> regions = sourceSlot.regions();
        if (regions.isEmpty()) {
            return null;
        }
        List<RegionLayout> measured = new ArrayList<>(regions.size());
        for (WynnDialogueProjection.LineRegion region : regions) {
            RegionLayout layout = regionLayout(font, region);
            if (layout == null) {
                return null;
            }
            measured.add(layout);
        }
        List<DockMeasurement> docks = resolveDocks(font, translatedSlot.dockedIcons());
        java.util.BitSet dockedOrdinals = new java.util.BitSet(events.size());
        for (DockMeasurement dock : docks) {
            dockedOrdinals.set(dock.icon().ordinal());
        }
        List<BodyRow> rows = buildBodyRows(font, regions, measured, dockedOrdinals);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        SafeTranslate.logLimited("wynn.dialogue.body-paragraph-flow",
                "Wynn dialogue BODY paragraph flow rows={} maskOrdinals={}",
                rows.size(), translatedSlot.bodyMaskOrdinals().size());
        WrappedBodyParagraph wrapped = wrapBodyParagraph(font, translatedSlot.component(), rows, docks);
        if (wrapped == null || wrapped.lines().isEmpty() || wrapped.lines().size() > rows.size()) {
            return null;
        }
        List<PositionedLine> result = new ArrayList<>();
        for (int index = 0; index < wrapped.lines().size(); index++) {
            WrappedLine line = wrapped.lines().get(index);
            BodyRow row = rows.get(index);
            float runningLeft = row.left();
            int pieceStart = line.start();
            boolean rowHasContent = false;
            for (DockMeasurement dock : line.docks()) {
                if (dock.icon().charIndex() > pieceStart) {
                    TextPiece piece = buildTextPiece(font, wrapped.spans(), pieceStart,
                            dock.icon().charIndex(), row, runningLeft);
                    if (piece == null) {
                        return null;
                    }
                    result.add(piece.line());
                    runningLeft = piece.nextLeft();
                    rowHasContent = true;
                }
                // The docked source glyph keeps its own font and vertical slot;
                // only its x is recomputed so it sits before its keyword span.
                result.add(new PositionedLine(WynnDialogueProjection.SemanticKind.BODY,
                        row.rowIndex(),
                        sourceSequence.slice(dock.icon().ordinal(), dock.icon().ordinal() + 1),
                        runningLeft - dock.bounds().left(), 0.0F, row.budget(), dock.advance()));
                this.dockedReplayOrdinals.set(dock.icon().ordinal());
                runningLeft += dock.advance();
                pieceStart = dock.icon().charIndex();
                rowHasContent = true;
            }
            if (line.end() > pieceStart) {
                TextPiece piece = buildTextPiece(font, wrapped.spans(), pieceStart, line.end(),
                        row, runningLeft);
                if (piece == null) {
                    return null;
                }
                result.add(piece.line());
                runningLeft = piece.nextLeft();
                rowHasContent = true;
            }
            if (!rowHasContent || runningLeft > row.right() + EPSILON) {
                return null;
            }
        }
        return List.copyOf(result);
    }

    @Nullable
    private TextPiece buildTextPiece(Font font, List<OverlaySpan> spans, int start, int end,
                                     BodyRow row, float left) {
        FormattedCharSequence sequence = sliceOverlaySpans(spans, start, end).getVisualOrderText();
        float advance = font.getSplitter().stringWidth(sequence);
        Bounds pieceBounds = bounds(font, sequence);
        float occupied = occupiedWidth(advance, pieceBounds);
        if (pieceBounds == null || !Float.isFinite(advance) || advance <= 0.0F
                || !Float.isFinite(occupied) || occupied <= 0.0F) {
            return null;
        }
        float drawX = left - pieceBounds.left();
        float drawY = row.referenceBounds().top() - pieceBounds.top();
        if (!Float.isFinite(drawX) || !Float.isFinite(drawY)) {
            return null;
        }
        return new TextPiece(new PositionedLine(WynnDialogueProjection.SemanticKind.BODY,
                row.rowIndex(), sequence, drawX, drawY, row.budget(), occupied), left + advance);
    }

    /** Measures every dockable icon; unmeasurable ones stay fixed source blockers. */
    private List<DockMeasurement> resolveDocks(Font font,
                                               List<WynnDialogueProjection.DockedIcon> dockedIcons) {
        if (dockedIcons == null || dockedIcons.isEmpty()) {
            return List.of();
        }
        List<DockMeasurement> result = new ArrayList<>();
        for (WynnDialogueProjection.DockedIcon icon : dockedIcons) {
            if (icon == null || icon.ordinal() < 0 || icon.ordinal() >= events.size()) {
                continue;
            }
            WynnDialogueProjection.EventSequence glyph =
                    sourceSequence.slice(icon.ordinal(), icon.ordinal() + 1);
            float advance = font.getSplitter().stringWidth(glyph);
            Bounds iconBounds = bounds(font, glyph);
            if (!Float.isFinite(advance) || advance <= 0.0F || iconBounds == null) {
                continue;
            }
            result.add(new DockMeasurement(icon, advance, iconBounds));
        }
        result.sort(java.util.Comparator.comparingInt(measurement -> measurement.icon().charIndex()));
        return List.copyOf(result);
    }

    static List<String> wrapParagraphForTest(String text, int... rowWidths) {
        if (text == null || rowWidths == null || rowWidths.length == 0) {
            return List.of();
        }
        text = text.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        int cursor = 0;
        for (int width : rowWidths) {
            while (cursor < text.length() && Character.isWhitespace(text.codePointAt(cursor))) {
                cursor += Character.charCount(text.codePointAt(cursor));
            }
            if (cursor >= text.length()) {
                break;
            }
            if (width <= 0) {
                return List.of();
            }
            int scan = cursor;
            int fittingEnd = cursor;
            int lastWordBreak = -1;
            boolean committed = false;
            while (scan < text.length()) {
                int codePoint = text.codePointAt(scan);
                int next = scan + Character.charCount(codePoint);
                if (next - cursor > width) {
                    int end = Character.isWhitespace(codePoint) ? fittingEnd
                            : lastWordBreak > cursor ? lastWordBreak : fittingEnd;
                    if (end <= cursor) {
                        return List.of();
                    }
                    result.add(text.substring(cursor, end).stripTrailing());
                    cursor = end;
                    committed = true;
                    break;
                }
                fittingEnd = next;
                if (Character.isWhitespace(codePoint)) {
                    lastWordBreak = scan;
                } else if (next < text.length() && isSoftWrapBreakpoint(text, next, cursor)) {
                    lastWordBreak = next;
                }
                scan = next;
            }
            if (!committed) {
                result.add(text.substring(cursor, fittingEnd).stripTrailing());
                cursor = fittingEnd;
            }
        }
        while (cursor < text.length() && Character.isWhitespace(text.codePointAt(cursor))) {
            cursor += Character.charCount(text.codePointAt(cursor));
        }
        return cursor == text.length() ? List.copyOf(result) : List.of();
    }

    @Nullable
    private static WrappedBodyParagraph wrapBodyParagraph(
            Font font, Component paragraph, List<BodyRow> rows, List<DockMeasurement> docks) {
        if (font == null || paragraph == null || rows == null || rows.isEmpty()) {
            return null;
        }
        NormalizedOverlay normalized = normalizeOverlay(paragraph);
        String text = normalized.text();
        if (text.isEmpty()) {
            return null;
        }
        List<OverlaySpan> spans = normalized.spans();
        List<WrappedLine> result = new ArrayList<>();
        int cursor = 0;
        for (BodyRow row : rows) {
            while (cursor < text.length() && Character.isWhitespace(text.codePointAt(cursor))) {
                cursor += Character.charCount(text.codePointAt(cursor));
            }
            if (cursor >= text.length()) {
                break;
            }
            if (!finitePositive(row.budget())) {
                return null;
            }
            int scan = cursor;
            int fittingEnd = cursor;
            int lastWordBreak = -1;
            boolean committed = false;
            while (scan < text.length()) {
                int codePoint = text.codePointAt(scan);
                int next = scan + Character.charCount(codePoint);
                FormattedCharSequence candidate = sliceOverlaySpans(spans, cursor, next)
                        .getVisualOrderText();
                float width = font.getSplitter().stringWidth(candidate)
                        + dockAdvanceBetween(docks, cursor, next);
                if (!Float.isFinite(width)) {
                    return null;
                }
                if (width > row.budget() + EPSILON) {
                    int end = Character.isWhitespace(codePoint) ? fittingEnd
                            : lastWordBreak > cursor ? lastWordBreak : fittingEnd;
                    int strippedEnd = stripTrailingWhitespace(text, cursor, end);
                    if (strippedEnd <= cursor) {
                        return null;
                    }
                    result.add(new WrappedLine(cursor, strippedEnd,
                            docksBetween(docks, cursor, strippedEnd)));
                    cursor = end;
                    committed = true;
                    break;
                }
                fittingEnd = next;
                if (Character.isWhitespace(codePoint)) {
                    lastWordBreak = scan;
                } else if (next < text.length() && isSoftWrapBreakpoint(text, next, cursor)) {
                    lastWordBreak = next;
                }
                scan = next;
            }
            if (!committed) {
                int strippedEnd = stripTrailingWhitespace(text, cursor, fittingEnd);
                if (strippedEnd <= cursor) {
                    return null;
                }
                result.add(new WrappedLine(cursor, strippedEnd,
                        docksBetween(docks, cursor, strippedEnd)));
                cursor = fittingEnd;
            }
        }
        while (cursor < text.length() && Character.isWhitespace(text.codePointAt(cursor))) {
            cursor += Character.charCount(text.codePointAt(cursor));
        }
        return cursor == text.length()
                ? new WrappedBodyParagraph(spans, List.copyOf(result)) : null;
    }

    /** Total advance of docked icons whose anchor index falls inside [start, end). */
    private static float dockAdvanceBetween(List<DockMeasurement> docks, int start, int end) {
        float total = 0.0F;
        for (DockMeasurement dock : docks) {
            if (dock.icon().charIndex() >= start && dock.icon().charIndex() < end) {
                total += dock.advance();
            }
        }
        return total;
    }

    private static List<DockMeasurement> docksBetween(List<DockMeasurement> docks, int start,
                                                      int end) {
        List<DockMeasurement> result = new ArrayList<>();
        for (DockMeasurement dock : docks) {
            if (dock.icon().charIndex() >= start && dock.icon().charIndex() < end) {
                result.add(dock);
            }
        }
        return List.copyOf(result);
    }

    /** One docked inline icon with its measured source-glyph geometry. */
    private record DockMeasurement(WynnDialogueProjection.DockedIcon icon, float advance,
                                   Bounds bounds) {
    }

    /** One wrapped BODY row: text range plus the docked icons inside it. */
    private record WrappedLine(int start, int end, List<DockMeasurement> docks) {
        private WrappedLine {
            docks = docks == null ? List.of() : List.copyOf(docks);
        }
    }

    /** A wrapped BODY paragraph: normalized spans plus one entry per used row. */
    private record WrappedBodyParagraph(List<OverlaySpan> spans, List<WrappedLine> lines) {
        private WrappedBodyParagraph {
            spans = spans == null ? List.of() : List.copyOf(spans);
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** One laid-out text piece and the x where the next piece starts. */
    private record TextPiece(PositionedLine line, float nextLeft) {
    }

    /**
     * Collapses model whitespace once so wrap offsets and span styles stay
     * aligned: every span covers the exact slice of the normalized paragraph
     * that carries its overlay style (colour/decorations from the source run,
     * CJK overlay font).
     */
    static NormalizedOverlay normalizeOverlay(Component paragraph) {
        List<OverlaySpan> raw = new ArrayList<>();
        collectOverlaySpans(paragraph, Style.EMPTY, raw);
        StringBuilder text = new StringBuilder();
        List<OverlaySpan> spans = new ArrayList<>();
        boolean pendingSpace = false;
        Style pendingSpaceStyle = null;
        for (OverlaySpan span : raw) {
            String value = span.text();
            int index = 0;
            while (index < value.length()) {
                int codePoint = value.codePointAt(index);
                int next = index + Character.charCount(codePoint);
                if (Character.isWhitespace(codePoint)) {
                    if (!text.isEmpty()) {
                        pendingSpace = true;
                        if (pendingSpaceStyle == null) {
                            pendingSpaceStyle = span.style();
                        }
                    }
                } else {
                    if (pendingSpace) {
                        appendOverlaySpan(spans, " ",
                                pendingSpaceStyle == null ? span.style() : pendingSpaceStyle);
                        text.append(' ');
                        pendingSpace = false;
                        pendingSpaceStyle = null;
                    }
                    String glyph = value.substring(index, next);
                    appendOverlaySpan(spans, glyph, span.style());
                    text.append(glyph);
                }
                index = next;
            }
        }
        return new NormalizedOverlay(text.toString(), List.copyOf(spans));
    }

    private static void collectOverlaySpans(Component component, Style inherited,
                                            List<OverlaySpan> target) {
        Style effective = component.getStyle().applyTo(inherited);
        if (component.getContents() instanceof PlainTextContents.LiteralContents literal) {
            if (!literal.text().isEmpty()) {
                appendOverlaySpan(target, literal.text(), effective);
            }
        }
        for (Component sibling : component.getSiblings()) {
            collectOverlaySpans(sibling, effective, target);
        }
    }

    private static void appendOverlaySpan(List<OverlaySpan> spans, String value, Style style) {
        Style safeStyle = style == null ? Style.EMPTY : style;
        if (!spans.isEmpty() && Objects.equals(spans.getLast().style(), safeStyle)) {
            OverlaySpan last = spans.removeLast();
            spans.add(new OverlaySpan(last.text() + value, last.style()));
            return;
        }
        spans.add(new OverlaySpan(value, safeStyle));
    }

    /** Cuts [start, end) out of the normalized span list, keeping each span's style. */
    static MutableComponent sliceOverlaySpans(List<OverlaySpan> spans, int start, int end) {
        MutableComponent line = Component.empty();
        int offset = 0;
        for (OverlaySpan span : spans) {
            int spanStart = offset;
            int spanEnd = spanStart + span.text().length();
            offset = spanEnd;
            if (spanStart >= end) {
                break;
            }
            int cutStart = Math.max(start, spanStart);
            int cutEnd = Math.min(end, spanEnd);
            if (cutStart < cutEnd) {
                line.append(Component.literal(
                        span.text().substring(cutStart - spanStart, cutEnd - spanStart))
                        .withStyle(span.style()));
            }
        }
        return line;
    }

    private static int stripTrailingWhitespace(String text, int start, int end) {
        int cursor = end;
        while (cursor > start) {
            int codePoint = text.codePointBefore(cursor);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            cursor -= Character.charCount(codePoint);
        }
        return cursor;
    }

    /**
     * CJK-aware soft wrap opportunities: a break may occur at any Han /
     * kana / hangul boundary, so translated CJK prose fills each native row
     * instead of stopping at the paragraph's only ASCII space (which left
     * most of an icon-shortened row blank). Latin words stay atomic, and
     * closing punctuation never starts a translated row.
     */
    private static boolean isSoftWrapBreakpoint(String text, int scan, int cursor) {
        if (scan <= cursor || scan >= text.length()) {
            return false;
        }
        int previous = text.codePointBefore(scan);
        int current = text.codePointAt(scan);
        if (isCjkClosingPunctuation(current) || isCjkOpeningPunctuation(previous)) {
            return false;
        }
        return isCjkWrapChar(previous) || isCjkWrapChar(current);
    }

    private static boolean isCjkWrapChar(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }

    private static boolean isCjkClosingPunctuation(int codePoint) {
        return switch (codePoint) {
            case 0x3001, 0x3002, 0xFF0C, 0xFF01, 0xFF1F, 0xFF1B, 0xFF1A,
                    0xFF09, 0x3015, 0x300B, 0x300D, 0x300F, 0x3011,
                    0xFF5D, 0xFF60, 0x2026, 0x2014 -> true;
            default -> false;
        };
    }

    private static boolean isCjkOpeningPunctuation(int codePoint) {
        return switch (codePoint) {
            case 0xFF08, 0x3014, 0x300A, 0x300C, 0x300E, 0x3010,
                    0xFF5B, 0xFF5F -> true;
            default -> false;
        };
    }

    /** One styled slice of a normalized BODY overlay paragraph. */
    record OverlaySpan(String text, Style style) {
        OverlaySpan {
            text = text == null ? "" : text;
            style = style == null ? Style.EMPTY : style;
        }
    }

    /** A whitespace-normalized BODY overlay plus the spans that style it. */
    record NormalizedOverlay(String text, List<OverlaySpan> spans) {
        NormalizedOverlay {
            text = text == null ? "" : text;
            spans = spans == null ? List.of() : List.copyOf(spans);
        }
    }

    /**
     * Builds exactly one translated text row for every physical Wynn BODY row.
     * The retired renderer split a row into several independent lanes around
     * local positioning glyphs; an imperfect visual classification could then
     * emit two Chinese draw calls on the same baseline. This representation has
     * no such state: a physical row can own zero or one draw call only.
     */
    @Nullable
    private List<BodyRow> buildBodyRows(
            Font font,
            List<WynnDialogueProjection.LineRegion> regions,
            List<RegionLayout> measured,
            java.util.BitSet dockedOrdinals) {
        if (font == null || regions == null || measured == null || regions.isEmpty()
                || regions.size() != measured.size()) {
            return null;
        }
        float sharedLeft = Float.MAX_VALUE;
        float sharedRight = -Float.MAX_VALUE;
        for (RegionLayout layout : measured) {
            sharedLeft = Math.min(sharedLeft, layout.absoluteLeft());
            sharedRight = Math.max(sharedRight, layout.absoluteRight());
        }
        if (!Float.isFinite(sharedLeft) || !Float.isFinite(sharedRight)
                || !finitePositive(sharedRight - sharedLeft)) {
            return null;
        }

        List<BodyRow> rows = new ArrayList<>();
        int index = 0;
        int previousRowIndex = -1;
        while (index < regions.size()) {
            int rowStart = index;
            WynnDialogueProjection.DialogueLine line = regions.get(index).line();
            do {
                index++;
            } while (index < regions.size() && regions.get(index).line() == line);

            int rowIndex = line.bodyRowIndex();
            if (rowIndex < 0 || rowIndex <= previousRowIndex) {
                return null;
            }
            previousRowIndex = rowIndex;

            Bounds referenceBounds = measured.get(rowStart).bounds();
            java.util.BitSet visualOrdinals = new java.util.BitSet(events.size());
            for (int regionIndex = rowStart; regionIndex < index; regionIndex++) {
                RegionLayout layout = measured.get(regionIndex);
                referenceBounds = referenceBounds.union(layout.bounds());
                WynnDialogueProjection.LineRegion region = regions.get(regionIndex);
                if (!collectVisualOrdinals(visualOrdinals, region.visualBeforeOrdinals())
                        || !collectVisualOrdinals(visualOrdinals, region.visualAfterOrdinals())) {
                    return null;
                }
            }
            // Docked icons reserve their advance inside the translated flow, so
            // they no longer block text as fixed obstacles on this row.
            visualOrdinals.andNot(dockedOrdinals);

            HorizontalSpan span = largestSafeRowSpan(
                    font, sharedLeft, sharedRight, referenceBounds, visualOrdinals);
            if (span == null || !finitePositive(span.width())) {
                return null;
            }
            rows.add(new BodyRow(rowIndex, span.left(), span.right(), referenceBounds));
        }

        // Missing BODY rows have no source glyph that proves their baseline.
        // Even a stable resource-pack family may alter its frame geometry after
        // reload, so never extrapolate a translated row from neighbouring prose.
        return rows.isEmpty() ? null : List.copyOf(rows);
    }

    private boolean collectVisualOrdinals(java.util.BitSet target, List<Integer> ordinals) {
        if (target == null || ordinals == null) return false;
        for (int ordinal : ordinals) {
            if (ordinal < 0 || ordinal >= events.size()) return false;
            target.set(ordinal);
        }
        return true;
    }

    /**
     * Keeps visible local icons in the original stream and chooses one contiguous
     * text span beside them. Invisible space-provider PUA have no bounds and are
     * ignored. If the sentence cannot fit in these one-pass rows, the complete
     * BODY falls back to Wynn's source text instead of drawing competing pieces.
     */
    @Nullable
    private HorizontalSpan largestSafeRowSpan(Font font, float rowLeft, float rowRight,
                                              Bounds rowBounds,
                                              java.util.BitSet visualOrdinals) {
        List<Bounds> blockers = new ArrayList<>();
        for (int ordinal = visualOrdinals.nextSetBit(0); ordinal >= 0;
             ordinal = visualOrdinals.nextSetBit(ordinal + 1)) {
            Bounds absolute;
            try {
                absolute = absoluteGlyphBounds(font, ordinal);
            } catch (Throwable ignored) {
                return null;
            }
            if (absolute == null
                    || absolute.bottom() <= rowBounds.top() + EPSILON
                    || absolute.top() >= rowBounds.bottom() - EPSILON
                    || absolute.right() <= rowLeft + EPSILON
                    || absolute.left() >= rowRight - EPSILON) {
                continue;
            }
            blockers.add(absolute);
        }
        if (blockers.isEmpty()) {
            return new HorizontalSpan(rowLeft, rowRight);
        }
        blockers.sort(java.util.Comparator.comparingDouble(Bounds::left));

        HorizontalSpan best = null;
        float cursor = rowLeft;
        for (Bounds blocker : blockers) {
            float blockedLeft = Math.max(rowLeft, blocker.left());
            float blockedRight = Math.min(rowRight, blocker.right());
            if (blockedRight <= cursor + EPSILON) continue;
            if (blockedLeft > cursor + EPSILON) {
                best = wider(best, new HorizontalSpan(cursor, blockedLeft));
            }
            cursor = Math.max(cursor, blockedRight);
            if (cursor >= rowRight - EPSILON) break;
        }
        if (cursor < rowRight - EPSILON) {
            best = wider(best, new HorizontalSpan(cursor, rowRight));
        }
        return best;
    }

    @Nullable
    private Bounds absoluteGlyphBounds(Font font, int ordinal) {
        if (font == null || ordinal < 0 || ordinal >= events.size()) return null;
        Bounds local = bounds(font, sourceSequence.slice(ordinal, ordinal + 1), false);
        if (local == null) return null;
        float prefix = font.getSplitter().stringWidth(sourceSequence.slice(0, ordinal));
        return Float.isFinite(prefix) ? local.translate(prefix, 0.0F) : null;
    }

    private static HorizontalSpan wider(@Nullable HorizontalSpan current,
                                        HorizontalSpan candidate) {
        if (candidate == null || !finitePositive(candidate.width())) return current;
        return current == null || candidate.width() > current.width() ? candidate : current;
    }


    private static boolean hasNonOverlappingRows(Font font, List<PositionedLine> lines) {
        int activeRow = -1;
        float previousRowTextBottom = -Float.MAX_VALUE;
        for (int index = 0; index < lines.size(); index++) {
            PositionedLine line = lines.get(index);
            if (line.text() instanceof WynnDialogueProjection.EventSequence) {
                // Docked source icons keep trusted pack geometry and are placed
                // beside translated text by this layout; they skip the proof.
                continue;
            }
            Bounds glyphBounds = bounds(font, line.text(), false);
            if (glyphBounds == null) return false;
            float left = line.x() + glyphBounds.left();
            float right = left + line.translatedWidth();
            float top = line.y() + glyphBounds.top();
            float bottom = line.y() + glyphBounds.bottom();
            if (!Float.isFinite(left) || !Float.isFinite(right) || right <= left
                    || !Float.isFinite(top) || !Float.isFinite(bottom) || bottom <= top) {
                return false;
            }
            if (line.lineIndex() < activeRow) return false;
            // A paragraph-flow BODY layout owns at most one overlay line per
            // native row. A duplicate row would indicate a corrupted layout.
            for (int earlier = 0; earlier < index; earlier++) {
                PositionedLine previous = lines.get(earlier);
                if (previous.text() instanceof WynnDialogueProjection.EventSequence
                        || previous.lineIndex() != line.lineIndex()) continue;
                Bounds previousBounds = bounds(font, previous.text(), false);
                if (previousBounds == null) return false;
                float previousLeft = previous.x() + previousBounds.left();
                float previousRight = previousLeft + previous.translatedWidth();
                float previousTop = previous.y() + previousBounds.top();
                float previousBottom = previous.y() + previousBounds.bottom();
                if (left < previousRight - EPSILON && right > previousLeft + EPSILON
                        && top < previousBottom - EPSILON && bottom > previousTop + EPSILON) {
                    return false;
                }
            }
            if (line.lineIndex() != activeRow) {
                if (previousRowTextBottom > -Float.MAX_VALUE
                        && top < previousRowTextBottom - EPSILON) {
                    return false;
                }
                activeRow = line.lineIndex();
                previousRowTextBottom = bottom;
            } else {
                previousRowTextBottom = Math.max(previousRowTextBottom, bottom);
            }
        }
        return true;
    }

    private boolean commitMeasuredSlot(List<PositionedLine> result,
                                       java.util.BitSet acceptedMask,
                                              TranslatedSlot translatedSlot,
                                              @Nullable List<PositionedLine> measured) {
        if (measured == null || measured.isEmpty()) {
            return false;
        }
        java.util.BitSet candidateMask = new java.util.BitSet();
        if (translatedSlot.source().kind() == WynnDialogueProjection.SemanticKind.BODY) {
            if (translatedSlot.bodyMaskOrdinals().isEmpty()) return false;
            for (int ordinal : translatedSlot.bodyMaskOrdinals()) {
                if (!isMaskableNaturalLanguageOrdinal(ordinal, translatedSlot.source())
                        || candidateMask.get(ordinal)) {
                    return false;
                }
                candidateMask.set(ordinal);
            }
        } else {
            for (WynnDialogueProjection.LineRegion region : translatedSlot.source().regions()) {
                for (int ordinal : region.maskOrdinals()) {
                    if (!isMaskableNaturalLanguageOrdinal(ordinal, region) || candidateMask.get(ordinal)) {
                        return false;
                    }
                    candidateMask.set(ordinal);
                }
            }
        }
        // One source glyph may have exactly one overlay owner. BitSet would
        // silently de-duplicate a repeated mask while both translated slots
        // remained in the draw list, producing visible stacked text.
        if (candidateMask.isEmpty() || acceptedMask.intersects(candidateMask)) {
            return false;
        }
        result.addAll(measured);
        acceptedMask.or(candidateMask);
        return true;
    }

    private boolean isMaskableNaturalLanguageOrdinal(int ordinal,
                                                     WynnDialogueProjection.SemanticSlot sourceSlot) {
        if (sourceSlot == null || ordinal < 0 || ordinal >= events.size()) {
            return false;
        }
        for (WynnDialogueProjection.LineRegion region : sourceSlot.regions()) {
            if (region.maskOrdinals().contains(ordinal)) {
                return isMaskableNaturalLanguageOrdinal(ordinal, region);
            }
        }
        return false;
    }

    private boolean isMaskableNaturalLanguageOrdinal(int ordinal,
                                                     WynnDialogueProjection.LineRegion region) {
        if (ordinal < 0 || ordinal >= events.size() || region == null
                || region.visualBeforeOrdinals().contains(ordinal)
                || region.visualAfterOrdinals().contains(ordinal)) {
            return false;
        }
        WynnDialogueProjection.GlyphEvent event = events.get(ordinal);
        int codePoint = event.codePoint();
        return WynnDialogueProjection.isOverlayMaskableCodePoint(codePoint);
    }

    @Nullable
    private RegionLayout regionLayout(Font font, WynnDialogueProjection.LineRegion region) {
        WynnDialogueProjection.EventSequence prefix = sourceSequence.slice(0, region.startOrdinal());
        WynnDialogueProjection.EventSequence source = sourceSequence.slice(
                region.startOrdinal(), region.endOrdinal());
        float prefixAdvance = font.getSplitter().stringWidth(prefix);
        float advance = font.getSplitter().stringWidth(source);
        Bounds sourceBounds = bounds(font, source);
        if (sourceBounds == null) {
            // Some composed resource-pack fonts do not expose a union rectangle
            // for a multi-glyph sequence even though their individual glyphs are
            // renderable. Re-measuring the exact original events is a geometry-
            // preserving fallback: no guessed coordinates, scaling, or default-
            // font metrics enter the source anchor.
            sourceBounds = boundsByEvent(font, region);
        }
        float visibleWidth = sourceBounds == null ? 0.0F : sourceBounds.right() - sourceBounds.left();
        float budget = Math.max(advance, visibleWidth);
        if (!Float.isFinite(prefixAdvance) || sourceBounds == null || !finitePositive(budget)) {
            return null;
        }
        return new RegionLayout(prefixAdvance, budget, sourceBounds);
    }

    @Nullable
    private Bounds boundsByEvent(Font font, WynnDialogueProjection.LineRegion region) {
        Bounds result = null;
        java.util.BitSet included = new java.util.BitSet(events.size());
        for (int ordinal : region.maskOrdinals()) {
            if (ordinal >= 0 && ordinal < events.size()) included.set(ordinal);
        }
        float cursor = 0.0F;
        for (int ordinal = region.startOrdinal(); ordinal < region.endOrdinal(); ordinal++) {
            WynnDialogueProjection.EventSequence event = sourceSequence.slice(ordinal, ordinal + 1);
            Bounds current = bounds(font, event);
            if (included.get(ordinal) && current != null) {
                Bounds shifted = current.translate(cursor, 0.0F);
                result = result == null ? shifted : result.union(shifted);
            }
            float advance = font.getSplitter().stringWidth(event);
            if (!Float.isFinite(advance)) return null;
            cursor += advance;
        }
        return result;
    }

    @Nullable
    private static List<PositionedLine> measurementFailure(
            TranslatedSlot slot, String reason) {
        WynnDialogueProjection.SemanticSlot source = slot.source();
        SafeTranslate.logLimited("wynn.dialogue.measure." + reason,
                "Wynn dialogue slot measurement rejected reason={} kind={} regions={} sourceHash={}; keeping this source slot",
                reason, source.kind(), source.regions().size(), shortSourceHash(source.sourceText()));
        return null;
    }

    private static String shortSourceHash(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return "empty";
        }
        return Integer.toUnsignedString(sourceText.hashCode(), 36);
    }

    private static void logRenderFailure(String reason, int suppliedWidth, int sourceWidth,
                                         float sourceAdvance) {
        SafeTranslate.logLimited("wynn.dialogue.render." + reason,
                "Wynn dialogue render plan rejected reason={} suppliedWidth={} sourceWidth={} sourceAdvance={}; keeping original HUD text",
                reason, suppliedWidth, sourceWidth, sourceAdvance);
    }

    @Nullable
    private static Bounds bounds(Font font, FormattedCharSequence sequence) {
        return bounds(font, sequence, true);
    }

    @Nullable
    private static Bounds bounds(Font font, FormattedCharSequence sequence, boolean shadow) {
        ScreenRectangle rectangle = com.yourname.simpletranslate.core.PreparedBoundsCompat.bounds(font, sequence, shadow);
        if (rectangle == null || rectangle.right() <= rectangle.left()
                || rectangle.bottom() <= rectangle.top()) {
            return null;
        }
        return new Bounds(rectangle.left(), rectangle.top(), rectangle.right(), rectangle.bottom());
    }

    /**
     * Pure geometry seam. A fixed icon may shorten the available row, but it
     * must never push a shorter translation toward the icon and create a large
     * leading indent. All translated prose remains left-aligned.
     */
    public static float alignedDrawX(float absoluteLeft, float budget, float targetBoundsLeft,
                                     float translatedWidth, boolean inlineIconAfter) {
        return absoluteLeft - targetBoundsLeft;
    }

    /** Keeps centered one-line labels centered without changing their native glyph size. */
    public static float centeredDrawX(float absoluteLeft, float sourceWidth,
                                      float targetBoundsLeft, float translatedWidth) {
        if (!Float.isFinite(absoluteLeft) || !Float.isFinite(sourceWidth)
                || !Float.isFinite(targetBoundsLeft) || !Float.isFinite(translatedWidth)) {
            return Float.NaN;
        }
        return absoluteLeft + (sourceWidth - translatedWidth) * 0.5F - targetBoundsLeft;
    }

    public record TranslatedSlot(WynnDialogueProjection.SemanticSlot source, Component component,
                                 boolean sourceVisible,
                                 List<Integer> bodyMaskOrdinals,
                                 List<WynnDialogueProjection.DockedIcon> dockedIcons) {
        public TranslatedSlot {
            source = Objects.requireNonNull(source, "source");
            component = Objects.requireNonNull(component, "component");
            bodyMaskOrdinals = bodyMaskOrdinals == null ? List.of() : List.copyOf(bodyMaskOrdinals);
            dockedIcons = dockedIcons == null ? List.of() : List.copyOf(dockedIcons);
        }

        public TranslatedSlot(WynnDialogueProjection.SemanticSlot source, Component component,
                              boolean sourceVisible) {
            this(source, component, sourceVisible, List.of(), List.of());
        }

        public TranslatedSlot(WynnDialogueProjection.SemanticSlot source, Component component,
                              boolean sourceVisible, List<Integer> bodyMaskOrdinals) {
            this(source, component, sourceVisible, bodyMaskOrdinals, List.of());
        }
    }

    public record PositionedLine(WynnDialogueProjection.SemanticKind kind, int lineIndex,
                                 FormattedCharSequence text, float x, float y,
                                 float sourceWidth, float translatedWidth) {
    }

    public record Layout(List<PositionedLine> lines, java.util.BitSet acceptedMask) {
        public Layout {
            lines = lines == null ? List.of() : List.copyOf(lines);
            acceptedMask = acceptedMask == null
                    ? new java.util.BitSet() : (java.util.BitSet) acceptedMask.clone();
        }

        @Override
        public java.util.BitSet acceptedMask() {
            return (java.util.BitSet) acceptedMask.clone();
        }
    }

    private record CachedMetrics(Font font, long resourceRevision,
                                  @Nullable Layout layout, @Nullable List<SourceReplayRun> replayRuns,
                                  int sourceWidth,
                                  float sourceAdvance) {
    }

    private record SourceReplayRun(FormattedCharSequence sequence, float x) {
    }

    private record RegionLayout(float prefixAdvance, float budget, Bounds bounds) {
        private float absoluteLeft() {
            return prefixAdvance + bounds.left();
        }

        private float absoluteRight() {
            return absoluteLeft() + budget;
        }
    }

    private record Bounds(float left, float top, float right, float bottom) {
        private Bounds union(Bounds other) {
            return new Bounds(Math.min(left, other.left), Math.min(top, other.top),
                    Math.max(right, other.right), Math.max(bottom, other.bottom));
        }

        private Bounds translate(float x, float y) {
            return new Bounds(left + x, top + y, right + x, bottom + y);
        }
    }

    private record BodyRow(int rowIndex, float left, float right, Bounds referenceBounds) {
        private float budget() {
            return right - left;
        }
    }

    private record HorizontalSpan(float left, float right) {
        private float width() {
            return right - left;
        }
    }

    private static boolean finitePositive(float value) {
        return Float.isFinite(value) && value > 0.0F;
    }

    private static float occupiedWidth(float advance, @Nullable Bounds bounds) {
        if (!Float.isFinite(advance) || bounds == null) return Float.NaN;
        float leftBearing = Math.min(0.0F, bounds.left());
        float rightExtent = Math.max(Math.max(0.0F, advance), bounds.right());
        float occupied = rightExtent - leftBearing;
        return Float.isFinite(occupied) ? occupied : Float.NaN;
    }

}
