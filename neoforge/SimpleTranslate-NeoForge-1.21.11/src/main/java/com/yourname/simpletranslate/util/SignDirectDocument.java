package com.yourname.simpletranslate.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reversible direct formatted document for sign groups.
 *
 * <p>Unlike the generic fixed-layout direct pipeline, this document preserves a
 * stable sign id per sign. Returned sign blocks may be reordered, but writeback
 * always uses the sign id rather than the list position.</p>
 */
public final class SignDirectDocument {
    private static final String VERSION = "sign-direct-v1";
    private static final Pattern SIGN_PATTERN = Pattern.compile("(?s)<sign\\s+([^>]*)>(.*?)</sign>");
    private static final Pattern LINE_PATTERN = Pattern.compile("(?s)<line\\s+([^>]*)>(.*?)</line>");
    private static final Pattern RUN_PATTERN = Pattern.compile("(?s)<run\\s+([^>]*)>(.*?)</run>");
    private static final Pattern ATTR_PATTERN = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");
    private static final Pattern ENGLISH_PATTERN = Pattern.compile("[A-Za-z]{2,}");
    private static final Pattern SIGN_PROTECTED_TOKEN_PATTERN = Pattern.compile(
            "(?:/[A-Za-z0-9_:@.\\-]+|@[A-Za-z0-9_]+|\\b\\d+(?:\\.\\d+)+\\b|\\b\\d+(?:\\.\\d+)?\\b|\\b[a-z]+(?:[A-Z][A-Za-z0-9_]*)+\\b)");

    private SignDirectDocument() {
    }

    public static Document fromEntries(List<Entry> entries, String surface, String role, String context) {
        return Document.fromEntries(entries, surface, role, context);
    }

    public static String requestPayloadForTest(List<Entry> entries, String surface, String role, String context) {
        return fromEntries(entries, surface, role, context).requestPayload();
    }

    public static RestoreResult restoreForTest(List<Entry> entries, String surface, String role,
                                               String context, String translatedDocument) {
        return fromEntries(entries, surface, role, context).restore(translatedDocument);
    }

    public record Entry(String signId,
                        String stateKey,
                        List<Component> components,
                        String[] sourceLines,
                        long selectionIndex,
                        boolean front) {
        public Entry {
            components = normalizeComponents(components, sourceLines);
            sourceLines = normalizeLines(sourceLines);
        }
    }

    public record RestoreResult(boolean success,
                                 Map<String, Component[]> componentsBySignId,
                                 String failureReason) {
        public static RestoreResult fail(String reason) {
            return new RestoreResult(false, Map.of(), reason == null || reason.isBlank() ? "unknown" : reason);
        }
    }

    public static final class Document {
        private final String surface;
        private final String role;
        private final String context;
        private final String document;
        private final String requestPayload;
        private final String sourceText;
        private final String layoutSignature;
        private final String styleSignature;
        private final String cacheKey;
        private final List<SignTemplate> signs;
        private final Map<String, SignTemplate> bySignId;
        private final boolean hasEnglish;

        private Document(String surface,
                         String role,
                         String context,
                         String document,
                         String requestPayload,
                         String sourceText,
                         String layoutSignature,
                         String styleSignature,
                         List<SignTemplate> signs,
                         boolean hasEnglish) {
            this.surface = surface;
            this.role = role;
            this.context = context;
            this.document = document;
            this.requestPayload = requestPayload;
            this.sourceText = sourceText;
            this.layoutSignature = layoutSignature;
            this.styleSignature = styleSignature;
            this.signs = List.copyOf(signs);
            Map<String, SignTemplate> map = new LinkedHashMap<>();
            for (SignTemplate sign : signs) {
                map.put(sign.signId(), sign);
            }
            this.bySignId = Map.copyOf(map);
            this.hasEnglish = hasEnglish;
            this.cacheKey = TranslationCacheKeys.key(surface, sourceText, context, layoutSignature, styleSignature);
        }

        private static Document fromEntries(List<Entry> rawEntries, String rawSurface, String rawRole, String rawContext) {
            List<Entry> entries = rawEntries == null ? List.of() : rawEntries;
            String surface = rawSurface == null || rawSurface.isBlank() ? "sign.manual.group.by_id.direct" : rawSurface;
            String role = rawRole == null || rawRole.isBlank() ? "sign-manual-group-by-id" : rawRole;
            String context = rawContext == null ? "" : rawContext;

            StringBuilder doc = new StringBuilder();
            StringBuilder plain = new StringBuilder();
            StringBuilder source = new StringBuilder();
            StringBuilder layout = new StringBuilder("sign-group");
            StringBuilder style = new StringBuilder();
            List<SignTemplate> signs = new ArrayList<>();
            boolean hasEnglish = false;

            plain.append("<st-plain-context surface=\"").append(escape(surface))
                    .append("\" role=\"").append(escape(role))
                    .append("\" fixed=\"true\">");
            doc.append("<st-doc v=\"").append(VERSION).append("\" mode=\"sign-group\" surface=\"")
                    .append(escape(surface)).append("\" role=\"").append(escape(role))
                    .append("\" fixed=\"true\">");

            for (int signIndex = 0; signIndex < entries.size(); signIndex++) {
                Entry entry = entries.get(signIndex);
                if (entry == null || entry.signId() == null || entry.signId().isBlank()) {
                    continue;
                }
                List<LineTemplate> lines = new ArrayList<>(4);
                layout.append("|sign:").append(entry.signId());
                style.append("|sign:").append(entry.signId())
                        .append(":front=").append(entry.front())
                        .append(":selection=").append(entry.selectionIndex());
                if (source.length() > 0) {
                    source.append('\n');
                }
                source.append("sign ").append(entry.signId()).append('\n');

                plain.append("<sign id=\"").append(escape(entry.signId()))
                        .append("\" selection=\"").append(entry.selectionIndex()).append("\">");
                doc.append("<sign id=\"").append(escape(entry.signId()))
                        .append("\" selection=\"").append(entry.selectionIndex())
                        .append("\" front=\"").append(entry.front()).append("\">");

                for (int lineIndex = 0; lineIndex < 4; lineIndex++) {
                    Component component = entry.components().get(lineIndex);
                    String sourceLine = component == null ? "" : component.getString();
                    source.append("line ").append(lineIndex).append(": ").append(sourceLine).append('\n');
                    plain.append("<line i=\"").append(lineIndex).append("\">")
                            .append(escape(sourceLine)).append("</line>");

                    List<TextSegmentInfo> segments = new ArrayList<>();
                    ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
                    List<RunTemplate> runs = new ArrayList<>();
                    for (TextSegmentInfo segment : segments) {
                        if (segment == null || segment.text == null || segment.text.isEmpty()) {
                            continue;
                        }
                        Style runStyle = segment.style == null ? Style.EMPTY : segment.style;
                        boolean editable = containsEnglish(segment.text);
                        hasEnglish |= editable;
                        if (!runs.isEmpty()) {
                            RunTemplate previous = runs.get(runs.size() - 1);
                            if (previous.editable() == editable
                                    && previous.styleSignature().equals(SignDirectDocument.styleSignature(runStyle))) {
                                runs.set(runs.size() - 1,
                                        new RunTemplate(previous.id(), previous.sourceText() + segment.text,
                                                previous.style(), previous.editable()));
                                continue;
                            }
                        }
                        String runId = "s" + signIndex + "_l" + lineIndex + "_r" + runs.size();
                        runs.add(new RunTemplate(runId, segment.text, runStyle, editable));
                    }

                    Style baseStyle = chooseBaseStyle(runs);
                    doc.append("<line i=\"").append(lineIndex)
                            .append("\" base=\"").append(escape(SignDirectDocument.styleSignature(baseStyle))).append("\">");
                    layout.append(":line").append(lineIndex);
                    for (RunTemplate run : runs) {
                        layout.append(":").append(run.id());
                        style.append("|").append(entry.signId()).append(":").append(run.id())
                                .append("=").append(run.styleSignature());
                        doc.append("<run id=\"").append(run.id())
                                .append("\" editable=\"").append(run.editable())
                                .append("\" style=\"").append(escape(run.styleSignature()))
                                .append("\">").append(escape(run.sourceText())).append("</run>");
                    }
                    doc.append("</line>");
                    lines.add(new LineTemplate(lineIndex, runs, component == null ? "" : component.getString()));
                }
                plain.append("</sign>");
                doc.append("</sign>");
                signs.add(new SignTemplate(entry.signId(), entry.stateKey(), lines, entry.selectionIndex()));
            }
            plain.append("</st-plain-context>");
            doc.append("</st-doc>");

            String sourceText = source.toString().trim();
            String glossary = DirectStatusTerms.glossarySection(sourceText);
            String requestPayload = contextSection(context)
                    + plain
                    + (glossary.isEmpty() ? "" : "\n" + glossary)
                    + "\n" + doc;
            return new Document(surface, role, context, doc.toString(), requestPayload, sourceText,
                    layout.toString(), style.toString(), signs, hasEnglish);
        }

        public String surface() {
            return surface;
        }

        public String requestPayload() {
            return requestPayload;
        }

        public String cacheKey() {
            return cacheKey;
        }

        public String sourceText() {
            return sourceText;
        }

        public String context() {
            return context;
        }

        public String layoutSignature() {
            return layoutSignature;
        }

        public String styleSignature() {
            return styleSignature;
        }

        public boolean hasEnglish() {
            return hasEnglish;
        }

        public String sourceSummary() {
            String summary = sourceText == null ? "" : sourceText.replace('\n', ' ').replace('\r', ' ').trim();
            return summary.length() > 160 ? summary.substring(0, 157) + "..." : summary;
        }

        public RestoreResult restore(String translatedDocument) {
            String payload = extractDocumentPayload(translatedDocument);
            if (payload == null) {
                return RestoreResult.fail("missing-st-doc");
            }
            int rootEnd = payload.indexOf('>');
            if (rootEnd < 0) {
                return RestoreResult.fail("missing-root-end");
            }
            Map<String, String> rootAttrs = attrs(payload.substring(0, rootEnd));
            if (!VERSION.equals(rootAttrs.get("v"))) {
                return RestoreResult.fail("root-version expected=" + VERSION + " actual=" + rootAttrs.get("v"));
            }
            if (!"sign-group".equals(rootAttrs.get("mode"))) {
                return RestoreResult.fail("root-mode actual=" + rootAttrs.get("mode"));
            }
            if (!surface.equals(rootAttrs.get("surface"))) {
                return RestoreResult.fail("root-surface actual=" + rootAttrs.get("surface"));
            }
            if (!role.equals(rootAttrs.get("role"))) {
                return RestoreResult.fail("root-role actual=" + rootAttrs.get("role"));
            }

            Matcher signMatcher = SIGN_PATTERN.matcher(payload);
            Map<String, Component[]> restored = new LinkedHashMap<>();
            Set<String> seen = new HashSet<>();
            while (signMatcher.find()) {
                Map<String, String> signAttrs = attrs(signMatcher.group(1));
                String signId = signAttrs.get("id");
                if (signId == null || signId.isBlank()) {
                    return RestoreResult.fail("sign-missing-id");
                }
                SignTemplate expected = bySignId.get(signId);
                if (expected == null) {
                    return RestoreResult.fail("sign-unknown-id id=" + signId);
                }
                if (!seen.add(signId)) {
                    return RestoreResult.fail("sign-duplicate-id id=" + signId);
                }
                SignRestore signRestore = restoreSign(expected, signMatcher.group(2));
                if (signRestore == null) {
                    return RestoreResult.fail(validationFailureForSign(expected, signMatcher.group(2)));
                }
                restored.put(signId, signRestore.lines());
            }

            for (SignTemplate sign : signs) {
                if (!seen.contains(sign.signId())) {
                    return RestoreResult.fail("sign-missing-id id=" + sign.signId());
                }
            }
            if (restored.size() != signs.size()) {
                return RestoreResult.fail("sign-count expected=" + signs.size() + " actual=" + restored.size());
            }
            return new RestoreResult(true, Map.copyOf(restored), "");
        }

        private SignRestore restoreSign(SignTemplate expected, String body) {
            Component[] restored = new Component[4];
            Matcher lineMatcher = LINE_PATTERN.matcher(body == null ? "" : body);
            int expectedLine = 0;
            List<String> restoredRunTexts = new ArrayList<>();
            while (lineMatcher.find()) {
                Map<String, String> lineAttrs = attrs(lineMatcher.group(1));
                int lineIndex;
                try {
                    lineIndex = Integer.parseInt(lineAttrs.getOrDefault("i", ""));
                } catch (NumberFormatException e) {
                    return null;
                }
                if (lineIndex != expectedLine || lineIndex < 0 || lineIndex >= 4) {
                    return null;
                }
                LineTemplate lineTemplate = expected.lines().get(lineIndex);
                MutableComponent line = Component.empty();
                Matcher runMatcher = RUN_PATTERN.matcher(lineMatcher.group(2));
                int expectedRun = 0;
                while (runMatcher.find()) {
                    if (expectedRun >= lineTemplate.runs().size()) {
                        return null;
                    }
                    RunTemplate run = lineTemplate.runs().get(expectedRun++);
                    Map<String, String> runAttrs = attrs(runMatcher.group(1));
                    if (!run.id().equals(runAttrs.get("id"))) {
                        return null;
                    }
                    String translated = DirectStatusTerms.apply(run.sourceText(), unescape(runMatcher.group(2)));
                    String text = run.editable() ? translated : run.sourceText();
                    restoredRunTexts.add(text);
                    line.append(Component.literal(text).withStyle(run.style()));
                }
                if (expectedRun != lineTemplate.runs().size()) {
                    return null;
                }
                restored[lineIndex] = line;
                expectedLine++;
            }
            if (expectedLine != 4) {
                return null;
            }
            if (fixedSignTokenFailure(expected, restoredRunTexts) != null) {
                return null;
            }
            for (int i = 0; i < restored.length; i++) {
                if (restored[i] == null) {
                    restored[i] = Component.empty();
                }
            }
            return new SignRestore(restored);
        }

        private String validationFailureForSign(SignTemplate expected, String body) {
            Matcher lineMatcher = LINE_PATTERN.matcher(body == null ? "" : body);
            int expectedLine = 0;
            List<String> restoredRunTexts = new ArrayList<>();
            while (lineMatcher.find()) {
                Map<String, String> lineAttrs = attrs(lineMatcher.group(1));
                int lineIndex;
                try {
                    lineIndex = Integer.parseInt(lineAttrs.getOrDefault("i", ""));
                } catch (NumberFormatException e) {
                    return "line-index-invalid signId=" + expected.signId();
                }
                if (lineIndex != expectedLine || lineIndex < 0 || lineIndex >= 4) {
                    return "line-index signId=" + expected.signId() + " expected=" + expectedLine + " actual=" + lineIndex;
                }
                LineTemplate lineTemplate = expected.lines().get(lineIndex);
                Matcher runMatcher = RUN_PATTERN.matcher(lineMatcher.group(2));
                int expectedRun = 0;
                while (runMatcher.find()) {
                    if (expectedRun >= lineTemplate.runs().size()) {
                        return "run-extra signId=" + expected.signId() + " line=" + lineIndex;
                    }
                    RunTemplate run = lineTemplate.runs().get(expectedRun++);
                    Map<String, String> runAttrs = attrs(runMatcher.group(1));
                    String actualId = runAttrs.get("id");
                    if (!run.id().equals(actualId)) {
                        return "run-id signId=" + expected.signId() + " line=" + lineIndex
                                + " expected=" + run.id() + " actual=" + actualId;
                    }
                    String translated = DirectStatusTerms.apply(run.sourceText(), unescape(runMatcher.group(2)));
                    restoredRunTexts.add(run.editable() ? translated : run.sourceText());
                }
                if (expectedRun != lineTemplate.runs().size()) {
                    return "run-count signId=" + expected.signId() + " line=" + lineIndex
                            + " expected=" + lineTemplate.runs().size() + " actual=" + expectedRun;
                }
                expectedLine++;
            }
            if (expectedLine != 4) {
                return "line-count signId=" + expected.signId() + " expected=4 actual=" + expectedLine;
            }
            String tokenFailure = fixedSignTokenFailure(expected, restoredRunTexts);
            if (tokenFailure != null) {
                return tokenFailure + " signId=" + expected.signId();
            }
            return "line-count signId=" + expected.signId() + " expected=4 actual=" + expectedLine;
        }

        private String fixedSignTokenFailure(SignTemplate sign, List<String> translatedRuns) {
            if (sign == null || translatedRuns == null) {
                return null;
            }
            Map<String, Integer> sourceTokens = signProtectedTokenCounts(sign);
            Map<String, Integer> translatedTokens = new LinkedHashMap<>();
            for (String translated : translatedRuns) {
                mergeTokenCounts(translatedTokens, protectedTokenCounts(translated));
            }
            for (Map.Entry<String, Integer> entry : sourceTokens.entrySet()) {
                String token = entry.getKey();
                int expectedCount = entry.getValue();
                int actualCount = translatedTokens.getOrDefault(token, 0);
                if (actualCount < expectedCount) {
                    return "sign-token-missing token=" + token;
                }
                if (actualCount > expectedCount) {
                    return "sign-token-duplicate token=" + token;
                }
            }
            if (translatedTokens.isEmpty()) {
                return null;
            }
            Set<String> documentTokens = allSignProtectedTokens();
            for (String token : translatedTokens.keySet()) {
                if (!sourceTokens.containsKey(token) && documentTokens.contains(token)) {
                    return "sign-token-moved-from-other-sign token=" + token;
                }
            }
            return null;
        }

        private Map<String, Integer> signProtectedTokenCounts(SignTemplate sign) {
            Map<String, Integer> tokens = new LinkedHashMap<>();
            for (LineTemplate line : sign.lines()) {
                for (RunTemplate run : line.runs()) {
                    mergeTokenCounts(tokens, protectedTokenCounts(run.sourceText()));
                }
            }
            return tokens;
        }

        private void mergeTokenCounts(Map<String, Integer> target, Map<String, Integer> source) {
            for (Map.Entry<String, Integer> entry : source.entrySet()) {
                target.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }

        private Set<String> allSignProtectedTokens() {
            Set<String> tokens = new HashSet<>();
            for (SignTemplate sign : signs) {
                for (LineTemplate line : sign.lines()) {
                    for (RunTemplate run : line.runs()) {
                        tokens.addAll(signProtectedTokens(run.sourceText()));
                    }
                }
            }
            return tokens;
        }
    }

    private static List<Component> normalizeComponents(List<Component> components, String[] fallbackLines) {
        List<Component> normalized = new ArrayList<>(4);
        String[] lines = normalizeLines(fallbackLines);
        for (int i = 0; i < 4; i++) {
            Component component = components != null && i < components.size() ? components.get(i) : null;
            if (component == null) {
                component = lines[i].isBlank() ? Component.empty() : Component.literal(lines[i]);
            }
            normalized.add(component);
        }
        return List.copyOf(normalized);
    }

    private static String[] normalizeLines(String[] sourceLines) {
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = sourceLines != null && i < sourceLines.length && sourceLines[i] != null ? sourceLines[i] : "";
        }
        return lines;
    }

    private static String extractDocumentPayload(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf("<st-doc");
        int end = trimmed.lastIndexOf("</st-doc>");
        if (start < 0 || end < start) {
            return null;
        }
        end += "</st-doc>".length();
        return trimmed.substring(start, end).trim();
    }

    private static String contextSection(String context) {
        if (context == null || context.isBlank()) {
            return "";
        }
        return "<st-context>" + escape(context.trim()) + "</st-context>\n";
    }

    private static Map<String, String> attrs(String raw) {
        Map<String, String> attrs = new LinkedHashMap<>();
        Matcher matcher = ATTR_PATTERN.matcher(raw == null ? "" : raw);
        while (matcher.find()) {
            attrs.put(matcher.group(1), unescape(matcher.group(2)));
        }
        return attrs;
    }

    private static boolean containsEnglish(String text) {
        return TranslationTextDetector.containsTranslatableText(text);
    }

    private static Set<String> signProtectedTokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = SIGN_PROTECTED_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (token != null && !token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static Map<String, Integer> protectedTokenCounts(String text) {
        Map<String, Integer> tokens = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = SIGN_PROTECTED_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (token != null && !token.isBlank()) {
                tokens.merge(token, 1, Integer::sum);
            }
        }
        return tokens;
    }

    private static Style chooseBaseStyle(List<RunTemplate> runs) {
        Style fallback = Style.EMPTY;
        int fallbackLength = -1;
        for (RunTemplate run : runs) {
            int length = run.sourceText() == null ? 0 : run.sourceText().length();
            if (length > fallbackLength) {
                fallback = run.style();
                fallbackLength = length;
            }
        }
        return fallback == null ? Style.EMPTY : fallback;
    }

    private static String styleSignature(Style style) {
        Style effective = style == null ? Style.EMPTY : style;
        return "color=" + (effective.getColor() == null ? "" : effective.getColor())
                + ";bold=" + effective.isBold()
                + ";italic=" + effective.isItalic()
                + ";underlined=" + effective.isUnderlined()
                + ";strikethrough=" + effective.isStrikethrough()
                + ";obfuscated=" + effective.isObfuscated()
                + ";click=" + (effective.getClickEvent() != null)
                + ";hover=" + (effective.getHoverEvent() != null);
    }

    private static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String unescape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&quot;", "\"")
                .replace("&gt;", ">")
                .replace("&lt;", "<")
                .replace("&amp;", "&");
    }

    private record SignTemplate(String signId, String stateKey, List<LineTemplate> lines, long selectionIndex) {
    }

    private record LineTemplate(int index, List<RunTemplate> runs, String sourceText) {
    }

    private record RunTemplate(String id, String sourceText, Style style, boolean editable) {
        private String styleSignature() {
            return SignDirectDocument.styleSignature(style);
        }
    }

    private record SignRestore(Component[] lines) {
    }
}
