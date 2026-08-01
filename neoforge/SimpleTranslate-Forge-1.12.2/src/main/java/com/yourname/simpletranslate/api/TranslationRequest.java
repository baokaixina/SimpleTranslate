package com.yourname.simpletranslate.api;

import com.yourname.simpletranslate.core.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable Java-8 form of the complete baseline Component-JSON request. */
public final class TranslationRequest {
    private final String surface;
    private final List<String> lines;
    private final List<Term> terms;
    private final int maxTokenMultiplier;
    private final String sourceLanguage;
    private final String targetLanguage;
    private final String promptContext;

    /** Compatibility constructor used by the original 1.12.2 adapters. */
    public TranslationRequest(String surface, List<String> componentJson,
                              String sourceLanguage, String targetLanguage) {
        this(surface, componentJson, Collections.<Term>emptyList(), 1,
                sourceLanguage, targetLanguage, "");
    }

    public TranslationRequest(String surface, List<String> lines, List<Term> terms,
                              int maxTokenMultiplier) {
        this(surface, lines, terms, maxTokenMultiplier, "", "", "");
    }

    public TranslationRequest(String surface, List<String> lines, List<Term> terms,
                              int maxTokenMultiplier, String sourceLanguage,
                              String targetLanguage) {
        this(surface, lines, terms, maxTokenMultiplier, sourceLanguage, targetLanguage, "");
    }

    public TranslationRequest(String surface, List<String> lines, List<Term> terms,
                              int maxTokenMultiplier, String sourceLanguage,
                              String targetLanguage, String promptContext) {
        this.surface = surface == null || surface.trim().isEmpty()
                ? "generic" : Surface.normalize(surface);
        this.lines = immutableCopy(lines);
        this.terms = terms == null
                ? Collections.<Term>emptyList()
                : Collections.unmodifiableList(new ArrayList<Term>(terms));
        this.maxTokenMultiplier = Math.max(1, Math.min(4, maxTokenMultiplier));
        this.sourceLanguage = sourceLanguage == null ? "" : sourceLanguage.trim();
        this.targetLanguage = targetLanguage == null ? "" : targetLanguage.trim();
        this.promptContext = promptContext == null ? "" : promptContext.trim();
    }

    private static List<String> immutableCopy(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public String getSurface() { return surface; }
    public List<String> getComponentJson() { return lines; }
    public String getSourceLanguage() { return sourceLanguage; }
    public String getTargetLanguage() { return targetLanguage; }

    // Baseline-style accessors retained so donor product classes need only
    // syntax/API adaptation, not a second request abstraction.
    public String surface() { return surface; }
    public List<String> lines() { return lines; }
    public List<Term> terms() { return terms; }
    public int maxTokenMultiplier() { return maxTokenMultiplier; }
    public String sourceLanguage() { return sourceLanguage; }
    public String targetLanguage() { return targetLanguage; }
    public String promptContext() { return promptContext; }

    public static final class Term {
        private final String source;
        private final String target;

        public Term(String source, String target) {
            this.source = source == null ? "" : source;
            this.target = target == null ? "" : target;
        }

        public String source() { return source; }
        public String target() { return target; }
        public String getSource() { return source; }
        public String getTarget() { return target; }
    }
}
