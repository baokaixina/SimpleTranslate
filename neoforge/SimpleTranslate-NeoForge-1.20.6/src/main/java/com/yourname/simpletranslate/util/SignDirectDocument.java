package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.core.TranslationDocument;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sign-group document on the shared minimal-echo wire protocol.
 *
 * <p>Each sign contributes four consecutive numbered lines (global index =
 * signIndex * 4 + lineIndex), so sign identity is positional and immune to
 * model reordering. Protected tokens (commands, versions, selectors) are
 * validated per sign; a violated sign degrades to its original lines instead
 * of rejecting the whole group.</p>
 */
public final class SignDirectDocument {
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
                                String failureReason,
                                String canonicalPayload,
                                int degradedSigns,
                                boolean partialDocument) {
        public RestoreResult(boolean success, Map<String, Component[]> componentsBySignId, String failureReason) {
            this(success, componentsBySignId, failureReason, "", 0, false);
        }

        public RestoreResult(boolean success, Map<String, Component[]> componentsBySignId, String failureReason,
                             String canonicalPayload, int degradedSigns) {
            this(success, componentsBySignId, failureReason, canonicalPayload, degradedSigns, false);
        }

        public static RestoreResult fail(String reason) {
            return new RestoreResult(false, Map.of(), reason == null || reason.isBlank() ? "unknown" : reason);
        }

        /** True when every translatable sign survived validation (safe to cache). */
        public boolean fullyTranslated() {
            return success && degradedSigns == 0 && !partialDocument;
        }
    }

    public static final class Document {
        private final TranslationDocument inner;
        private final List<SignSlot> signs;
        private final String sourceText;
        private final String cacheKey;

        private Document(TranslationDocument inner, List<SignSlot> signs, String sourceText) {
            this.inner = inner;
            this.signs = List.copyOf(signs);
            this.sourceText = sourceText;
            this.cacheKey = TranslationCacheKeys.key(inner.surface(), sourceText, inner.context(),
                    inner.layoutSignature(), inner.styleSignature());
        }

        private static Document fromEntries(List<Entry> rawEntries, String rawSurface, String rawRole,
                                            String rawContext) {
            List<Entry> entries = rawEntries == null ? List.of() : rawEntries;
            String surface = rawSurface == null || rawSurface.isBlank() ? "sign.manual.group.by_id.direct" : rawSurface;
            String role = rawRole == null || rawRole.isBlank() ? "sign-manual-group-by-id" : rawRole;
            String context = rawContext == null ? "" : rawContext;

            List<Component> flat = new ArrayList<>();
            List<SignSlot> signs = new ArrayList<>();
            StringBuilder source = new StringBuilder();
            StringBuilder extraLayout = new StringBuilder("sign-group");
            StringBuilder extraStyle = new StringBuilder();
            StringBuilder signNote = new StringBuilder();
            for (Entry entry : entries) {
                if (entry == null || entry.signId() == null || entry.signId().isBlank()) {
                    continue;
                }
                int firstLine = flat.size();
                for (int lineIndex = 0; lineIndex < 4; lineIndex++) {
                    flat.add(entry.components().get(lineIndex));
                }
                signs.add(new SignSlot(entry.signId(), entry.stateKey(), firstLine, entry.components()));
                extraLayout.append("|sign:").append(entry.signId());
                extraStyle.append("|sign:").append(entry.signId())
                        .append(":front=").append(entry.front())
                        .append(":selection=").append(entry.selectionIndex());
                if (source.length() > 0) {
                    source.append('\n');
                }
                source.append("sign ").append(entry.signId()).append('\n');
                for (int lineIndex = 0; lineIndex < 4; lineIndex++) {
                    Component component = entry.components().get(lineIndex);
                    source.append("line ").append(lineIndex).append(": ")
                            .append(component == null ? "" : component.getString()).append('\n');
                }
                if (signNote.length() > 0) {
                    signNote.append(' ');
                }
                signNote.append("Lines ").append(firstLine).append('-').append(firstLine + 3)
                        .append(" are one sign.");
            }
            String fullContext = buildSignContext(context, signNote.toString());
            TranslationDocument inner = TranslationDocument
                    .fromComponents(flat, surface, role, true, fullContext)
                    .withSignatures(extraLayout.toString(), extraStyle.toString());
            return new Document(inner, signs, source.toString().trim());
        }

        private static String buildSignContext(String context, String signNote) {
            String trimmed = context == null ? "" : context.trim();
            if (signNote == null || signNote.isBlank()) {
                return trimmed;
            }
            String note = "Each sign has exactly 4 short lines; keep each sign's wording within its own 4 lines. "
                    + signNote;
            return trimmed.isBlank() ? note : trimmed + "\n" + note;
        }

        public String surface() {
            return inner.surface();
        }

        public String requestPayload() {
            return inner.requestPayload();
        }

        public String cacheKey() {
            return cacheKey;
        }

        public String sourceText() {
            return sourceText;
        }

        public String context() {
            return inner.context();
        }

        public String layoutSignature() {
            return inner.layoutSignature();
        }

        public String styleSignature() {
            return inner.styleSignature();
        }

        public boolean hasEnglish() {
            return inner.hasEnglish();
        }

        public String sourceSummary() {
            String summary = sourceText == null ? "" : sourceText.replace('\n', ' ').replace('\r', ' ').trim();
            return summary.length() > 160 ? summary.substring(0, 157) + "..." : summary;
        }

        public RestoreResult restore(String translatedDocument) {
            TranslationDocument.RestoreOutcome outcome = inner.restore(translatedDocument);
            if (outcome == null) {
                return RestoreResult.fail(inner.failureSummary(translatedDocument));
            }
            List<Component> components = outcome.components();

            Set<String> documentTokens = new HashSet<>();
            for (SignSlot sign : signs) {
                documentTokens.addAll(protectedTokenCounts(sourcePlainText(sign)).keySet());
            }

            Map<String, Component[]> restored = new LinkedHashMap<>();
            int translatableSigns = 0;
            int degradedSigns = 0;
            boolean anyTranslated = false;
            for (SignSlot sign : signs) {
                Component[] lines = new Component[4];
                StringBuilder translatedPlain = new StringBuilder();
                boolean changed = false;
                for (int i = 0; i < 4; i++) {
                    int globalIndex = sign.firstLine() + i;
                    Component line = globalIndex < components.size() ? components.get(globalIndex) : Component.empty();
                    lines[i] = line;
                    String text = line == null ? "" : line.getString();
                    translatedPlain.append(text).append('\n');
                    String sourceLine = sign.sourceComponents().get(i) == null
                            ? "" : sign.sourceComponents().get(i).getString();
                    changed |= !text.equals(sourceLine);
                }
                boolean translatable = signHasEnglish(sign);
                if (translatable) {
                    translatableSigns++;
                }
                String violation = translatable
                        ? signTokenViolation(sign, translatedPlain.toString(), documentTokens)
                        : null;
                if (violation != null) {
                    degradedSigns++;
                    lines = originalLines(sign);
                } else if (changed) {
                    anyTranslated = true;
                }
                restored.put(sign.signId(), lines);
            }
            if (translatableSigns > 0 && !anyTranslated && degradedSigns >= translatableSigns) {
                return RestoreResult.fail("sign-token-validation degradedSigns=" + degradedSigns);
            }
            boolean partialDocument = outcome.isPartial();
            return new RestoreResult(true, Map.copyOf(restored), "", outcome.canonicalPayload(),
                    degradedSigns, partialDocument);
        }

        private boolean signHasEnglish(SignSlot sign) {
            for (Component component : sign.sourceComponents()) {
                if (component != null && TranslationTextDetector.containsTranslatableText(component.getString())) {
                    return true;
                }
            }
            return false;
        }

        private String sourcePlainText(SignSlot sign) {
            StringBuilder builder = new StringBuilder();
            for (Component component : sign.sourceComponents()) {
                builder.append(component == null ? "" : component.getString()).append('\n');
            }
            return builder.toString();
        }

        private String signTokenViolation(SignSlot sign, String translatedPlain, Set<String> documentTokens) {
            Map<String, Integer> sourceTokens = protectedTokenCounts(sourcePlainText(sign));
            Map<String, Integer> translatedTokens = protectedTokenCounts(translatedPlain);
            for (Map.Entry<String, Integer> entry : sourceTokens.entrySet()) {
                int actual = translatedTokens.getOrDefault(entry.getKey(), 0);
                if (actual < entry.getValue()) {
                    return "sign-token-missing token=" + entry.getKey();
                }
                if (actual > entry.getValue()) {
                    return "sign-token-duplicate token=" + entry.getKey();
                }
            }
            for (String token : translatedTokens.keySet()) {
                if (!sourceTokens.containsKey(token) && documentTokens.contains(token)) {
                    return "sign-token-moved-from-other-sign token=" + token;
                }
            }
            return null;
        }

        private Component[] originalLines(SignSlot sign) {
            Component[] lines = new Component[4];
            for (int i = 0; i < 4; i++) {
                Component component = sign.sourceComponents().get(i);
                lines[i] = component == null ? Component.empty() : component;
            }
            return lines;
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

    private record SignSlot(String signId, String stateKey, int firstLine, List<Component> sourceComponents) {
    }
}
