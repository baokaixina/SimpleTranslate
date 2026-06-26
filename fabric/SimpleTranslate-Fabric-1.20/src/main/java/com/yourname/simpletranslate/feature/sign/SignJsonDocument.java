package com.yourname.simpletranslate.feature.sign;

import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import com.yourname.simpletranslate.core.TranslationTextDetector;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Map;

/** Positional four-line sign mapping backed by the shared component JSON format. */
public final class SignJsonDocument {
    private SignJsonDocument() {
    }

    public static Document fromEntries(List<Entry> entries, String surface, String role, String context) {
        return Document.fromEntries(entries, surface, role, context, false);
    }

    public static Document fromCompactEntries(List<Entry> entries, String surface, String role, String context) {
        return Document.fromEntries(entries, surface, role, context, true);
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
            return new RestoreResult(false, Map.of(),
                    reason == null || reason.isBlank() ? "unknown" : reason);
        }
    }

    public static final class Document {
        private final String surface;
        private final String role;
        private final String context;
        private final List<Component> components;
        private final List<SignSlot> signs;
        private final String sourceText;
        private final String sourceJson;
        private final String requestPayload;
        private final String cacheKey;

        private Document(String surface, String role, String context, List<Component> components,
                         List<SignSlot> signs, String sourceText) {
            this.surface = surface;
            this.role = role;
            this.context = context;
            this.components = List.copyOf(components);
            this.signs = List.copyOf(signs);
            this.sourceText = sourceText;
            this.sourceJson = JsonPassthroughPipeline.serializeComponents(this.components);
            this.requestPayload = JsonPassthroughPipeline.buildUserPayload(this.sourceJson, context);
            this.cacheKey = TranslationCacheKeys.componentJsonKey(surface, this.sourceJson, context);
        }

        private static Document fromEntries(List<Entry> rawEntries, String rawSurface, String rawRole,
                                            String rawContext, boolean compactSigns) {
            List<Entry> entries = rawEntries == null ? List.of() : rawEntries;
            String surface = rawSurface == null || rawSurface.isBlank()
                    ? "sign.manual.group.by_id.direct" : rawSurface;
            String role = rawRole == null || rawRole.isBlank()
                    ? "sign-manual-group-by-id" : rawRole;
            String context = rawContext == null ? "" : rawContext;
            List<Component> flat = new ArrayList<>();
            List<SignSlot> slots = new ArrayList<>();
            StringBuilder source = new StringBuilder();
            for (Entry entry : entries) {
                if (entry == null || entry.signId() == null || entry.signId().isBlank()) {
                    continue;
                }
                int firstLine = flat.size();
                int componentCount;
                if (compactSigns) {
                    flat.add(mergeSignComponents(entry.components()));
                    componentCount = 1;
                } else {
                    flat.addAll(entry.components());
                    componentCount = 4;
                }
                slots.add(new SignSlot(entry.signId(), firstLine, componentCount, entry.components()));
                if (source.length() > 0) {
                    source.append('\n');
                }
                for (int i = 0; i < 4; i++) {
                    if (i > 0) {
                        source.append('\n');
                    }
                    source.append(entry.components().get(i).getString());
                }
            }
            return new Document(surface, role, buildContext(context, slots, compactSigns), flat, slots,
                    source.toString());
        }

        private static String buildContext(String context, List<SignSlot> slots, boolean compactSigns) {
            StringBuilder note = new StringBuilder(context == null ? "" : context.trim());
            if (!slots.isEmpty()) {
                if (note.length() > 0) {
                    note.append('\n');
                }
                if (compactSigns) {
                    note.append("Each top-level component is one complete sign. Keep every sign as exactly one top-level component.");
                } else {
                    note.append("Each sign has exactly four ordered component entries. Keep each sign's wording within its four entries.");
                }
            }
            return note.toString();
        }

        public String surface() {
            return surface;
        }

        public String role() {
            return role;
        }

        public String requestPayload() {
            return requestPayload;
        }

        public String sourceJson() {
            return sourceJson;
        }

        public List<Component> components() {
            return components;
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

        public boolean hasEnglish() {
            for (Component component : components) {
                if (component != null
                        && TranslationTextDetector.containsTranslatableText(component.getString())) {
                    return true;
                }
            }
            return false;
        }

        public String sourceSummary() {
            String summary = sourceText.replace('\n', ' ').replace('\r', ' ').trim();
            return summary.length() > 160 ? summary.substring(0, 157) + "..." : summary;
        }

        public RestoreResult restore(String response) {
            List<Component> translated = JsonPassthroughPipeline.deserializeComponents(response, components);
            return restoreComponents(translated);
        }

        public RestoreResult restoreComponents(List<Component> translated) {
            if (translated == null || translated.isEmpty()) {
                return RestoreResult.fail("component-json-invalid");
            }
            Map<String, Component[]> bySign = new LinkedHashMap<>();
            for (SignSlot slot : signs) {
                Component[] lines = new Component[4];
                int idx = slot.firstLine();
                if (slot.componentCount() == 1) {
                    Component merged = idx < translated.size() ? translated.get(idx) : Component.empty();
                    lines = splitMergedComponentToLines(merged, slot.sourceComponents());
                } else {
                    for (int i = 0; i < 4; i++) {
                        int srcIdx = idx + i;
                        lines[i] = srcIdx < translated.size() ? translated.get(srcIdx) : Component.empty();
                    }
                }
                bySign.put(slot.signId(), lines);
            }
            return new RestoreResult(true, Map.copyOf(bySign), "");
        }
    }

    private static Component mergeSignComponents(List<Component> components) {
        MutableComponent merged = Component.empty();
        boolean hasText = false;
        for (Component component : components) {
            if (component == null || component.getString().isBlank()) {
                continue;
            }
            if (hasText) {
                merged.append(Component.literal(" "));
            }
            merged.append(component.copy());
            hasText = true;
        }
        return merged;
    }

    /**
     * Splits a translated merged component back into 4 sign lines.
     *
     * <p>In compact mode, 4 sign lines were merged into one Component (separated
     * by spaces) before translation. The model translated the text inside the
     * merged component. This method extracts the translated text from the
     * component's segment tree and distributes it across 4 lines, using the
     * original 4 lines' styles as fallback.</p>
     *
     * <p>If the translated text contains explicit {@code \n} separators (from the
     * original lines), they are used to split. Otherwise the text is split by
     * the same space-separators that {@link #mergeSignComponents} inserted.</p>
     */
    private static Component[] splitMergedComponentToLines(Component merged, List<Component> originalLines) {
        Component[] result = new Component[4];
        for (int i = 0; i < 4; i++) {
            result[i] = Component.empty();
        }
        if (merged == null) {
            return result;
        }

        String translatedText = merged.getString();
        if (translatedText == null || translatedText.isBlank()) {
            return result;
        }

        String[] parts = translatedText.split("\n", 4);
        if (parts.length < 2) {
            parts = translatedText.split("(?<=\\.)\\s+|\\s+(?= )", 4);
        }

        for (int i = 0; i < 4 && i < parts.length; i++) {
            String part = parts[i] == null ? "" : parts[i].trim();
            if (part.isEmpty()) {
                continue;
            }
            Style style = i < originalLines.size() && originalLines.get(i) != null
                    ? originalLines.get(i).getStyle() : Style.EMPTY;
            result[i] = Component.literal(part).withStyle(style);
        }

        if (result[0].getString().isBlank() && !translatedText.isBlank()) {
            result[0] = Component.literal(translatedText.trim())
                    .withStyle(merged.getStyle() == null ? Style.EMPTY : merged.getStyle());
        }
        return result;
    }

    private static List<Component> normalizeComponents(List<Component> components, String[] fallbackLines) {
        List<Component> normalized = new ArrayList<>(4);
        String[] lines = normalizeLines(fallbackLines);
        for (int i = 0; i < 4; i++) {
            Component component = components != null && i < components.size() ? components.get(i) : null;
            normalized.add(component == null ? Component.literal(lines[i]) : component);
        }
        return List.copyOf(normalized);
    }

    private static String[] normalizeLines(String[] sourceLines) {
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = sourceLines != null && i < sourceLines.length && sourceLines[i] != null
                    ? sourceLines[i] : "";
        }
        return lines;
    }

    private record SignSlot(String signId, int firstLine, int componentCount, List<Component> sourceComponents) {
    }
}
