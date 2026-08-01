package com.yourname.simpletranslate.api;

import com.yourname.simpletranslate.core.Surface;

import java.util.List;

public record TranslationRequest(
        String surface,
        List<String> lines,
        List<Term> terms,
        int maxTokenMultiplier,
        String sourceLanguage,
        String targetLanguage,
        String promptContext) {

    public TranslationRequest(String surface, List<String> lines, List<Term> terms, int maxTokenMultiplier) {
        this(surface, lines, terms, maxTokenMultiplier, "", "", "");
    }

    public TranslationRequest(String surface, List<String> lines, List<Term> terms, int maxTokenMultiplier,
                              String sourceLanguage, String targetLanguage) {
        this(surface, lines, terms, maxTokenMultiplier, sourceLanguage, targetLanguage, "");
    }

    public TranslationRequest {
        surface = surface == null || surface.isBlank() ? "generic" : Surface.normalize(surface);
        lines = lines == null ? List.of() : List.copyOf(lines);
        terms = terms == null ? List.of() : List.copyOf(terms);
        maxTokenMultiplier = Math.max(1, Math.min(4, maxTokenMultiplier));
        sourceLanguage = sourceLanguage == null ? "" : sourceLanguage.trim();
        targetLanguage = targetLanguage == null ? "" : targetLanguage.trim();
        promptContext = promptContext == null ? "" : promptContext.trim();
    }

    public record Term(String source, String target) {
        public Term {
            source = source == null ? "" : source;
            target = target == null ? "" : target;
        }
    }
}
