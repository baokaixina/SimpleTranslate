package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.util.ComponentSegmentHelper;
import com.yourname.simpletranslate.util.DirectStatusTerms;
import com.yourname.simpletranslate.util.TextSegmentInfo;
import com.yourname.simpletranslate.util.TranslationCacheKeys;
import com.yourname.simpletranslate.util.TranslationTask;
import com.yourname.simpletranslate.util.TranslationTextDetector;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reversible template for the minimal-echo wire protocol.
 *
 * <p>Each source component becomes one numbered line. Lines containing more
 * than one distinct style emit lightweight numbered tags so the model can
 * reorder styled fragments; all other lines travel as plain text. Styles are
 * always reattached from the original {@link Style} objects at restore time —
 * the model never echoes structural metadata.</p>
 */
public final class TranslationDocument {
    private static final int MAX_CONTEXT_CHARS = 4000;

    public record RunSpec(int tag, String sourceText, Style style, boolean editable) {
        public String styleSignature() {
            return StyleSignatures.of(style);
        }
    }

    public record LineSpec(int index,
                           List<RunSpec> runs,
                           Style baseStyle,
                           String sourceText,
                           boolean tagged,
                           boolean hasEditable,
                           String sourceWire) {
    }

    /** Result of restoring a model response or cached payload. */
    public record RestoreOutcome(List<Component> components,
                                 String canonicalPayload,
                                 int translatedLines,
                                 int degradedLines,
                                 int residualLines) {

        /** Lines that should have been translated but kept their source text. */
        public boolean isPartial() {
            return degradedLines > 0 || residualLines > 0;
        }
    }

    private final String surface;
    private final String role;
    private final String context;
    private final List<LineSpec> lines;
    private final String sourceText;
    private final String layoutSignature;
    private final String styleSignature;
    private final boolean fixedLayout;
    private final boolean hasEnglish;
    private final String textBlock;
    private final String requestPayload;

    private TranslationDocument(String surface, String role, String context, List<LineSpec> lines,
                                String sourceText, String layoutSignature, String styleSignature,
                                boolean fixedLayout, boolean hasEnglish, String textBlock, String requestPayload) {
        this.surface = surface;
        this.role = role;
        this.context = context;
        this.lines = lines;
        this.sourceText = sourceText;
        this.layoutSignature = layoutSignature;
        this.styleSignature = styleSignature;
        this.fixedLayout = fixedLayout;
        this.hasEnglish = hasEnglish;
        this.textBlock = textBlock;
        this.requestPayload = requestPayload;
    }

    public static TranslationDocument fromComponents(List<Component> components, String surface, String role,
                                                     boolean fixedLayout, String context) {
        List<Component> safeComponents = components == null ? List.of() : components;
        String effectiveSurface = surface == null || surface.isBlank() ? "generic.direct" : surface;
        String effectiveRole = role == null || role.isBlank() ? "component" : role;
        String effectiveContext = context == null ? "" : context;

        List<LineSpec> lines = new ArrayList<>(safeComponents.size());
        List<String> encodedLines = new ArrayList<>(safeComponents.size());
        StringBuilder source = new StringBuilder();
        StringBuilder layout = new StringBuilder("w1");
        StringBuilder style = new StringBuilder();
        boolean hasEnglish = false;

        for (int i = 0; i < safeComponents.size(); i++) {
            Component component = safeComponents.get(i);
            LineSpec line = buildLine(component, i, effectiveSurface);
            lines.add(line);
            encodedLines.add(line.sourceWire());
            hasEnglish |= line.hasEditable();

            if (source.length() > 0) {
                source.append('\n');
            }
            source.append(line.sourceText());
            layout.append("|L").append(i).append(line.tagged() ? ":t" + line.runs().size() : ":p");
            for (RunSpec run : line.runs()) {
                style.append('|').append(i).append('.').append(run.tag()).append('=').append(run.styleSignature());
            }
        }

        String textBlock = WireCodec.textBlock(encodedLines);
        String requestPayload = contextSection(effectiveContext)
                + noteSection(effectiveSurface, effectiveRole)
                + normalizedSection(lines)
                + WireCodec.section("GLOSSARY", DirectStatusTerms.plainGlossary(source.toString()))
                + textBlock;
        return new TranslationDocument(effectiveSurface, effectiveRole, effectiveContext, List.copyOf(lines),
                source.toString(), layout.toString(), style.toString(), fixedLayout, hasEnglish,
                textBlock, requestPayload);
    }

    private static LineSpec buildLine(Component component, int index, String surface) {
        String lineText = component == null ? "" : component.getString();
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);

        List<RunSpec> merged = new ArrayList<>();
        boolean hasEditable = false;
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null || segment.text.isEmpty()) {
                continue;
            }
            Style runStyle = segment.style == null ? Style.EMPTY : segment.style;
            boolean editable = EditableText.isEditableRun(segment.text, runStyle, surface);
            hasEditable |= editable;
            if (!merged.isEmpty()) {
                RunSpec previous = merged.get(merged.size() - 1);
                if (previous.editable() == editable && Objects.equals(previous.style(), runStyle)) {
                    merged.set(merged.size() - 1,
                            new RunSpec(0, previous.sourceText() + segment.text, previous.style(), previous.editable()));
                    continue;
                }
            }
            merged.add(new RunSpec(0, segment.text, runStyle, editable));
        }

        Set<String> distinctStyles = new LinkedHashSet<>();
        for (RunSpec run : merged) {
            distinctStyles.add(run.styleSignature());
        }
        boolean tagged = distinctStyles.size() > 1 && !WireCodec.containsTagLikeText(lineText);

        List<RunSpec> runs = new ArrayList<>(merged.size());
        StringBuilder wire = new StringBuilder();
        if (tagged) {
            int nextTag = 1;
            for (RunSpec run : merged) {
                RunSpec taggedRun = new RunSpec(nextTag, run.sourceText(), run.style(), run.editable());
                runs.add(taggedRun);
                wire.append(WireCodec.openTag(nextTag))
                        .append(WireCodec.escape(run.sourceText()))
                        .append(WireCodec.closeTag(nextTag));
                nextTag++;
            }
        } else {
            runs.addAll(merged);
            wire.append(WireCodec.escape(lineText));
        }

        Style baseStyle = chooseBaseStyle(merged, segments, surface);
        return new LineSpec(index, List.copyOf(runs), baseStyle, lineText, tagged, hasEditable, wire.toString());
    }

    private static Style chooseBaseStyle(List<RunSpec> runs, List<TextSegmentInfo> segments, String surface) {
        for (RunSpec run : runs) {
            if (run.editable()) {
                return run.style() == null ? Style.EMPTY : run.style();
            }
        }
        Style fallback = Style.EMPTY;
        int fallbackLength = -1;
        for (RunSpec run : runs) {
            int length = run.sourceText() == null ? 0 : run.sourceText().length();
            if (length > fallbackLength) {
                fallback = run.style();
                fallbackLength = length;
            }
        }
        if (fallbackLength >= 0) {
            return fallback == null ? Style.EMPTY : fallback;
        }
        for (TextSegmentInfo segment : segments) {
            if (segment != null && segment.style != null) {
                return segment.style;
            }
        }
        return Style.EMPTY;
    }

    private static String contextSection(String context) {
        if (context == null || context.isBlank()) {
            return "";
        }
        String normalized = context.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() > MAX_CONTEXT_CHARS) {
            int head = MAX_CONTEXT_CHARS / 2;
            int tail = MAX_CONTEXT_CHARS - head;
            normalized = normalized.substring(0, head)
                    + "\n...[context truncated]...\n"
                    + normalized.substring(normalized.length() - tail);
        }
        return WireCodec.section("CONTEXT", normalized);
    }

    private static String noteSection(String surface, String role) {
        String effectiveSurface = surface == null ? "" : surface.toLowerCase(Locale.ROOT);
        String note = null;
        if (effectiveSurface.startsWith("tooltip.item_context")) {
            note = "All lines form one item tooltip (title, lore, mechanics). Long sentences are hard-wrapped across "
                    + "consecutive lines: translate the whole sentence and distribute it across those same line numbers; "
                    + "every source-language line must come back translated.";
        } else if (effectiveSurface.startsWith("chat.context.batch")) {
            note = "Lines are consecutive chat messages of one passage or list. Understand the whole passage first, "
                    + "then distribute natural wording across the same line numbers.";
        } else if (effectiveSurface.startsWith("hover.overlay")) {
            note = "Lines are one overlay tooltip block (chat skill details, mod UI). Translate as one coherent passage "
                    + "and distribute natural wording across the same line numbers.";
        } else if (effectiveSurface.startsWith("hud.history.caption_batch")) {
            note = "Lines are consecutive HUD subtitle fragments of one passage. Understand the whole passage first, "
                    + "then distribute natural wording across the same line numbers.";
        } else if (effectiveSurface.startsWith("hud.title_group")) {
            note = "Lines are a title and its subtitle shown together; translate them as one coherent pair.";
        } else if (effectiveSurface.startsWith("chat.context") || effectiveSurface.startsWith("chat.message.segment")) {
            note = "Translate only the [TEXT] line(s); [CONTEXT] chat history is reference only.";
        }
        return note == null ? "" : WireCodec.section("NOTE", note);
    }

    private static String normalizedSection(List<LineSpec> lines) {
        StringBuilder builder = new StringBuilder();
        for (LineSpec line : lines) {
            String normalized = TranslationTextDetector.normalizeForDetection(line.sourceText());
            String trimmedSource = line.sourceText() == null ? "" : line.sourceText().trim();
            if (!normalized.isBlank() && !normalized.equals(trimmedSource)) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line.index()).append('|').append(WireCodec.escape(normalized));
            }
        }
        return builder.length() == 0 ? "" : WireCodec.section("NORMALIZED", builder.toString());
    }

    public TranslationDocument withSignatures(String layout, String style) {
        String effectiveLayout = layout == null || layout.isBlank()
                ? layoutSignature : layoutSignature + "|" + layout;
        String effectiveStyle = style == null || style.isBlank()
                ? styleSignature : styleSignature + "|" + style;
        return new TranslationDocument(surface, role, context, lines, sourceText, effectiveLayout, effectiveStyle,
                fixedLayout, hasEnglish, textBlock, requestPayload);
    }

    public String surface() {
        return surface;
    }

    public String role() {
        return role;
    }

    public String context() {
        return context;
    }

    public List<LineSpec> lines() {
        return lines;
    }

    public String sourceText() {
        return sourceText;
    }

    public String layoutSignature() {
        return layoutSignature;
    }

    public String styleSignature() {
        return styleSignature;
    }

    public boolean fixedLayout() {
        return fixedLayout;
    }

    public boolean hasEnglish() {
        return hasEnglish;
    }

    /** The [TEXT] block only (wire source document). */
    public String document() {
        return textBlock;
    }

    /** Canonical cache payload for this document's current line wire text. */
    public String canonicalPayloadFromSourceWire() {
        StringBuilder canonical = new StringBuilder(WireCodec.PAYLOAD_MARKER);
        for (LineSpec line : lines) {
            canonical.append('\n').append(line.index()).append('|').append(line.sourceWire());
        }
        return canonical.toString();
    }

    public String requestPayload() {
        return requestPayload;
    }

    public String cacheKey() {
        return TranslationCacheKeys.key(surface, sourceText, context, layoutSignature, styleSignature);
    }

    public String cacheKeySummary() {
        String key = cacheKey();
        return key == null || key.isBlank() ? "none" : Integer.toHexString(key.hashCode());
    }

    public String sourceSummary() {
        String summary = sourceText == null ? "" : sourceText.replace('\n', ' ').replace('\r', ' ').trim();
        return summary.length() > 120 ? summary.substring(0, 117) + "..." : summary;
    }

    public TranslationTask task() {
        return TranslationTask.create(surface, sourceText, context, layoutSignature, styleSignature, List.of());
    }

    /**
     * Restores a model response or cached payload into styled components with
     * graded per-line degradation. Returns {@code null} only when nothing was
     * usable (counts as a rejection).
     */
    @Nullable
    public RestoreOutcome restore(String response) {
        Map<Integer, String> parsed = WireCodec.parseResponse(response, lines.size());
        if (parsed == null) {
            return null;
        }
        boolean canonicalPayload = WireCodec.isCanonicalPayload(response);
        List<StyleRestorer.LineResult> results = new ArrayList<>(lines.size());
        boolean anyNumberMismatch = false;
        for (LineSpec line : lines) {
            String content = parsed.get(line.index());
            boolean trusted = false;
            if (canonicalPayload && content != null && content.startsWith("*")) {
                trusted = true;
                content = content.substring(1);
            }
            StyleRestorer.LineResult result = StyleRestorer.restoreLine(line, content, surface, trusted);
            results.add(result);
            anyNumberMismatch |= result.numberMismatch();
        }

        // Wrapped sentences legitimately move numbers between adjacent lines.
        // Per-line mismatches are accepted when the whole document still carries
        // exactly the source numbers; otherwise only the offending lines revert.
        if (anyNumberMismatch) {
            StringBuilder joinedSource = new StringBuilder();
            StringBuilder joinedRestored = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                joinedSource.append(lines.get(i).sourceText()).append('\n');
                joinedRestored.append(results.get(i).component().getString()).append('\n');
            }
            if (!NumberGuard.linePasses(joinedSource.toString(), joinedRestored.toString())) {
                for (int i = 0; i < results.size(); i++) {
                    if (results.get(i).numberMismatch()) {
                        results.set(i, StyleRestorer.originalResult(lines.get(i)));
                    }
                }
            }
        }

        List<Component> components = new ArrayList<>(lines.size());
        StringBuilder canonical = new StringBuilder(WireCodec.PAYLOAD_MARKER);
        int translated = 0;
        int degraded = 0;
        int residual = 0;
        int editableLines = 0;
        for (int i = 0; i < lines.size(); i++) {
            LineSpec line = lines.get(i);
            StyleRestorer.LineResult result = results.get(i);
            if (line.hasEditable()) {
                editableLines++;
            }
            components.add(result.component());
            canonical.append('\n').append(line.index()).append('|').append(result.canonicalContent());
            if (result.translated()) {
                translated++;
            }
            if (result.degraded() && line.hasEditable()) {
                degraded++;
            } else if (line.hasEditable() && !result.translated()) {
                // Editable line came back unchanged: possibly a lazy model answer.
                residual++;
            }
        }
        if (editableLines > 0 && translated == 0 && degraded >= editableLines) {
            return null;
        }
        return new RestoreOutcome(List.copyOf(components), canonical.toString(), translated, degraded, residual);
    }

    /** Compact diagnostics string describing why {@link #restore} returned null. */
    public String failureSummary(String response) {
        Map<Integer, String> parsed = WireCodec.parseResponse(response, lines.size());
        if (parsed == null) {
            return "missing-document";
        }
        int missing = 0;
        int guarded = 0;
        for (LineSpec line : lines) {
            if (!line.hasEditable()) {
                continue;
            }
            String content = parsed.get(line.index());
            if (content == null) {
                missing++;
                continue;
            }
            StyleRestorer.LineResult result = StyleRestorer.restoreLine(line, content, surface, false);
            if (result.degraded()) {
                guarded++;
            }
        }
        return "all-lines-degraded missing=" + missing + " guarded=" + guarded + " total=" + lines.size();
    }
}
