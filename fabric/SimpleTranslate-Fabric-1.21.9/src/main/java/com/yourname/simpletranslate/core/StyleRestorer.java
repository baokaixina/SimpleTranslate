package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.util.DirectStatusTerms;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reattaches original styles to translated wire content with graded
 * degradation: exact tag mapping when possible, anchor heuristics when tags
 * were lost, and per-line fallback to the original text when numeric fidelity
 * fails — never whole-document rejection.
 */
public final class StyleRestorer {
    private static final Pattern BRACKETED_TOKEN_PATTERN = Pattern.compile("\\[[^\\[\\]]{1,64}]");

    private StyleRestorer() {
    }

    public record LineResult(Component component, boolean translated, boolean degraded, String canonicalContent,
                             boolean numberMismatch) {
        LineResult(Component component, boolean translated, boolean degraded, String canonicalContent) {
            this(component, translated, degraded, canonicalContent, false);
        }
    }

    /** Original-text result used when a tentative line is rejected at document level. */
    public static LineResult originalResult(TranslationDocument.LineSpec line) {
        return original(line, true);
    }

    public static LineResult restoreLine(TranslationDocument.LineSpec line, String content, String surface,
                                         boolean trusted) {
        if (line == null) {
            return new LineResult(Component.empty(), false, false, "");
        }
        if (!line.hasEditable()) {
            // Untranslatable line: ignore whatever the model produced.
            return original(line, false);
        }
        if (content == null) {
            return original(line, true, surface);
        }

        if (!line.tagged()) {
            return restorePlainLine(line, content, surface, trusted);
        }

        List<WireCodec.Piece> pieces = WireCodec.splitPieces(content);
        boolean hasTags = false;
        for (WireCodec.Piece piece : pieces) {
            if (piece.tag() > 0) {
                hasTags = true;
                break;
            }
        }
        if (!hasTags) {
            return restoreAnchoredLine(line, content, surface, trusted);
        }
        return restoreTaggedLine(line, pieces, surface, trusted);
    }

    private static LineResult restorePlainLine(TranslationDocument.LineSpec line, String content,
                                               String surface, boolean trusted) {
        String text = DirectStatusTerms.apply(line.sourceText(),
                WireCodec.unescape(WireCodec.stripTags(content)));
        text = repairUnchangedLine(line.sourceText(), text, surface);
        if (text.isBlank() && !line.sourceText().isBlank()) {
            return original(line, true, surface);
        }
        boolean mismatch = !trusted && !NumberGuard.linePasses(line.sourceText(), text);
        boolean translated = !text.equals(line.sourceText());
        Component component = Component.literal(text).withStyle(baseStyle(line));
        return new LineResult(component, translated, false, canonicalPlain(text, trusted), mismatch);
    }

    private static LineResult restoreAnchoredLine(TranslationDocument.LineSpec line, String content,
                                                  String surface, boolean trusted) {
        String text = DirectStatusTerms.apply(line.sourceText(),
                WireCodec.unescape(WireCodec.stripTags(content)));
        text = repairUnchangedLine(line.sourceText(), text, surface);
        if (text.isBlank() && !line.sourceText().isBlank()) {
            return original(line, true, surface);
        }

        // Re-attach leading/trailing non-editable runs (icons, separators) the
        // model dropped from its untagged answer, so visible symbols survive.
        List<TranslationDocument.RunSpec> leading = new ArrayList<>();
        for (TranslationDocument.RunSpec run : line.runs()) {
            if (run.editable() || run.sourceText() == null || run.sourceText().isEmpty()) {
                break;
            }
            leading.add(run);
        }
        List<TranslationDocument.RunSpec> trailing = new ArrayList<>();
        for (int i = line.runs().size() - 1; i >= leading.size(); i--) {
            TranslationDocument.RunSpec run = line.runs().get(i);
            if (run.editable() || run.sourceText() == null || run.sourceText().isEmpty()) {
                break;
            }
            trailing.add(0, run);
        }
        StringBuilder prefix = new StringBuilder();
        for (TranslationDocument.RunSpec run : leading) {
            prefix.append(run.sourceText());
        }
        StringBuilder suffix = new StringBuilder();
        for (TranslationDocument.RunSpec run : trailing) {
            suffix.append(run.sourceText());
        }
        boolean addPrefix = prefix.length() > 0 && !prefix.toString().isBlank() && !text.startsWith(prefix.toString());
        boolean addSuffix = suffix.length() > 0 && !suffix.toString().isBlank() && !text.endsWith(suffix.toString());

        String plain = (addPrefix ? prefix : "") + text + (addSuffix ? suffix : "");
        boolean mismatch = !trusted && !NumberGuard.linePasses(line.sourceText(), plain);
        boolean translated = !plain.equals(line.sourceText());
        Component body = anchoredComponent(line, text);
        Component component;
        if (addPrefix || addSuffix) {
            MutableComponent assembled = Component.empty();
            if (addPrefix) {
                for (TranslationDocument.RunSpec run : leading) {
                    assembled.append(Component.literal(run.sourceText())
                            .withStyle(run.style() == null ? Style.EMPTY : run.style()));
                }
            }
            assembled.append(body);
            if (addSuffix) {
                for (TranslationDocument.RunSpec run : trailing) {
                    assembled.append(Component.literal(run.sourceText())
                            .withStyle(run.style() == null ? Style.EMPTY : run.style()));
                }
            }
            component = assembled;
        } else {
            component = body;
        }
        // Canonical content stores the model text only; cached restores re-run
        // the same prefix/suffix reattachment for identical output.
        return new LineResult(component, translated, false, canonicalPlain(text, trusted), mismatch);
    }

    private static String canonicalPlain(String text, boolean trusted) {
        return (trusted ? "*" : "") + WireCodec.escape(text);
    }

    private static LineResult restoreTaggedLine(TranslationDocument.LineSpec line, List<WireCodec.Piece> pieces,
                                                String surface, boolean trusted) {
        Map<Integer, TranslationDocument.RunSpec> byTag = new LinkedHashMap<>();
        Map<Integer, Boolean> seen = new LinkedHashMap<>();
        for (TranslationDocument.RunSpec run : line.runs()) {
            byTag.put(run.tag(), run);
            seen.put(run.tag(), false);
        }

        MutableComponent component = Component.empty();
        StringBuilder plain = new StringBuilder();
        StringBuilder canonical = new StringBuilder();
        Style base = baseStyle(line);
        for (WireCodec.Piece piece : pieces) {
            TranslationDocument.RunSpec run = piece.tag() > 0 ? byTag.get(piece.tag()) : null;
            if (run != null && !seen.getOrDefault(piece.tag(), false)) {
                seen.put(piece.tag(), true);
                String text = run.editable()
                        ? DirectStatusTerms.apply(run.sourceText(), WireCodec.unescape(piece.text()))
                        : run.sourceText();
                if (run.editable()) {
                    text = repairUnchangedLine(run.sourceText(), text, surface);
                }
                if (!text.isEmpty()) {
                    component.append(Component.literal(text).withStyle(run.style() == null ? Style.EMPTY : run.style()));
                    plain.append(text);
                    canonical.append(WireCodec.openTag(piece.tag()))
                            .append(WireCodec.escape(text))
                            .append(WireCodec.closeTag(piece.tag()));
                }
                continue;
            }
            String text = DirectStatusTerms.apply(line.sourceText(),
                    WireCodec.unescape(WireCodec.stripTags(piece.text())));
            if (!text.isEmpty()) {
                component.append(Component.literal(text).withStyle(base));
                plain.append(text);
                canonical.append(WireCodec.escape(text));
            }
        }

        // Re-append dropped non-editable runs so symbols/markers are never lost.
        for (TranslationDocument.RunSpec run : line.runs()) {
            if (seen.getOrDefault(run.tag(), false) || run.editable()) {
                continue;
            }
            String text = run.sourceText();
            if (text != null && !text.isEmpty()) {
                component.append(Component.literal(text).withStyle(run.style() == null ? Style.EMPTY : run.style()));
                plain.append(text);
                canonical.append(WireCodec.openTag(run.tag()))
                        .append(WireCodec.escape(text))
                        .append(WireCodec.closeTag(run.tag()));
            }
        }

        String plainText = plain.toString();
        if (plainText.isBlank() && !line.sourceText().isBlank()) {
            return original(line, true, surface);
        }
        boolean mismatch = !trusted && !NumberGuard.linePasses(line.sourceText(), plainText);
        boolean translated = !plainText.equals(line.sourceText());
        return new LineResult(component, translated, false, (trusted ? "*" : "") + canonical, mismatch);
    }

    private static LineResult original(TranslationDocument.LineSpec line, boolean degraded) {
        return new LineResult(originalComponent(line), false, degraded, line.sourceWire());
    }

    private static LineResult original(TranslationDocument.LineSpec line, boolean degraded, String surface) {
        String repaired = DirectStatusTerms.repairUntranslatedLine(line.sourceText(), surface);
        if (repaired != null && !repaired.equals(line.sourceText()) && NumberGuard.linePasses(line.sourceText(), repaired)) {
            return new LineResult(Component.literal(repaired).withStyle(baseStyle(line)), true, false,
                    WireCodec.escape(repaired));
        }
        return original(line, degraded);
    }

    private static String repairUnchangedLine(String source, String text, String surface) {
        if (source == null || text == null || !text.equals(source)) {
            return text;
        }
        String repaired = DirectStatusTerms.repairUntranslatedLine(source, surface);
        if (repaired == null || repaired.equals(source) || !NumberGuard.linePasses(source, repaired)) {
            return text;
        }
        return repaired;
    }

    public static Component originalComponent(TranslationDocument.LineSpec line) {
        if (line.runs().isEmpty()) {
            if (line.sourceText() == null || line.sourceText().isEmpty()) {
                return Component.empty();
            }
            return Component.literal(line.sourceText()).withStyle(baseStyle(line));
        }
        MutableComponent component = Component.empty();
        for (TranslationDocument.RunSpec run : line.runs()) {
            component.append(Component.literal(run.sourceText() == null ? "" : run.sourceText())
                    .withStyle(run.style() == null ? Style.EMPTY : run.style()));
        }
        return component;
    }

    private static Style baseStyle(TranslationDocument.LineSpec line) {
        return line.baseStyle() == null ? Style.EMPTY : line.baseStyle();
    }

    /**
     * Approximate styling for a multi-style line whose tags were lost: exact
     * source tokens (numbers, bracketed labels, short copied words) keep their
     * original styles, everything else uses the line base style.
     */
    private static Component anchoredComponent(TranslationDocument.LineSpec line, String text) {
        String value = text == null ? "" : text;
        Style base = baseStyle(line);
        List<StyledRange> ranges = anchorRanges(line, value, base);
        if (ranges.isEmpty()) {
            return Component.literal(value).withStyle(base);
        }
        ranges.sort((left, right) -> Integer.compare(left.start(), right.start()));
        MutableComponent component = Component.empty();
        int cursor = 0;
        for (StyledRange range : ranges) {
            if (range.start() < cursor || range.start() >= range.end() || range.end() > value.length()) {
                continue;
            }
            if (range.start() > cursor) {
                component.append(Component.literal(value.substring(cursor, range.start())).withStyle(base));
            }
            component.append(Component.literal(value.substring(range.start(), range.end())).withStyle(range.style()));
            cursor = range.end();
        }
        if (cursor < value.length()) {
            component.append(Component.literal(value.substring(cursor)).withStyle(base));
        }
        return component;
    }

    private static List<StyledRange> anchorRanges(TranslationDocument.LineSpec line, String translatedText, Style base) {
        if (translatedText == null || translatedText.isEmpty()) {
            return List.of();
        }
        String baseSignature = StyleSignatures.of(base);
        List<StyledRange> ranges = new ArrayList<>();
        List<Style> sourceBracketStyles = new ArrayList<>();
        for (TranslationDocument.RunSpec run : line.runs()) {
            if (run == null || run.sourceText() == null || run.sourceText().isBlank() || run.style() == null) {
                continue;
            }
            String source = run.sourceText().trim();
            Matcher bracketMatcher = BRACKETED_TOKEN_PATTERN.matcher(source);
            while (bracketMatcher.find()) {
                sourceBracketStyles.add(run.style());
            }
            if (run.styleSignature().equals(baseSignature)) {
                continue;
            }
            if (source.length() >= 2 && shouldCarryStyle(source)) {
                addExactStyledRanges(translatedText, source, run.style(), ranges);
            }
        }
        if (!sourceBracketStyles.isEmpty()) {
            List<StyledRange> translatedBrackets = new ArrayList<>();
            Matcher matcher = BRACKETED_TOKEN_PATTERN.matcher(translatedText);
            while (matcher.find()) {
                translatedBrackets.add(new StyledRange(matcher.start(), matcher.end(), base));
            }
            if (sourceBracketStyles.size() == translatedBrackets.size()) {
                for (int i = 0; i < sourceBracketStyles.size(); i++) {
                    StyledRange bracket = translatedBrackets.get(i);
                    addStyledRange(ranges, bracket.start(), bracket.end(), sourceBracketStyles.get(i));
                }
            }
        }
        return ranges;
    }

    private static boolean shouldCarryStyle(String source) {
        return BRACKETED_TOKEN_PATTERN.matcher(source.trim()).matches()
                || source.matches("[+-]?\\d+(?:\\.\\d+)?(?:/[+-]?\\d+(?:\\.\\d+)?)*%?")
                || source.matches("[A-Za-z][A-Za-z0-9_.'\\-]*(?:,?\\s+[A-Za-z][A-Za-z0-9_.'\\-]*){0,3}");
    }

    private static void addExactStyledRanges(String translatedText, String source, Style style,
                                             List<StyledRange> ranges) {
        int cursor = 0;
        while (cursor <= translatedText.length() - source.length()) {
            int index = indexOfIgnoreCase(translatedText, source, cursor);
            if (index < 0) {
                return;
            }
            addStyledRange(ranges, index, index + source.length(), style);
            cursor = index + Math.max(1, source.length());
        }
    }

    private static int indexOfIgnoreCase(String text, String needle, int fromIndex) {
        if (text == null || needle == null || needle.isEmpty()) {
            return -1;
        }
        int max = text.length() - needle.length();
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    private static void addStyledRange(List<StyledRange> ranges, int start, int end, Style style) {
        if (start < 0 || end <= start || style == null) {
            return;
        }
        for (StyledRange range : ranges) {
            if (start < range.end() && end > range.start()) {
                return;
            }
        }
        ranges.add(new StyledRange(start, end, style));
    }

    private record StyledRange(int start, int end, Style style) {
    }
}
