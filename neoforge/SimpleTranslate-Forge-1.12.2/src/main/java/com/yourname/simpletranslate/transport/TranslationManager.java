package com.yourname.simpletranslate.transport;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.api.TranslationDiagnostics;
import com.yourname.simpletranslate.api.TranslationRequest;
import com.yourname.simpletranslate.cache.TermDictionary;
import com.yourname.simpletranslate.cache.TranslationBlacklist;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.LegacyComponentFactory;
import com.yourname.simpletranslate.core.TextContextMemory;
import com.yourname.simpletranslate.translation.TranslationEngine;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Baseline facade backed by the Java-8 provider implementation. */
public final class TranslationManager {
    private final TranslationEngine engine;

    public TranslationManager(TranslationEngine engine) {
        this.engine = engine;
    }

    public CompletableFuture<TranslationResult> translate(final String text) {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return CompletableFuture.completedFuture(
                    new TranslationResult(text, null, false, "Translation is disabled"));
        }
        return translateRaw(text).thenApply(new java.util.function.Function<String, TranslationResult>() {
            @Override public TranslationResult apply(String translated) {
                if (blank(translated)) return new TranslationResult(text, null, false, "Translation failed");
                TranslationBlacklist blacklist = engine.getTranslationBlacklist();
                if (blacklist != null && blacklist.containsBlacklistedEntry(translated)) {
                    return new TranslationResult(text, null, false, "Translation is blacklisted");
                }
                TermDictionary dictionary = engine.getTermDictionary();
                if (dictionary != null && ModConfig.TERM_AUTO_DETECT_ENABLED.get()) {
                    dictionary.analyzeAndRecordTerms(text);
                }
                return new TranslationResult(text, translated, true, null);
            }
        });
    }

    public boolean isReady() { return engine != null && engine.isConfigured(); }

    public CompletableFuture<TranslationDiagnostics.ApiDetection> detectApi() {
        return engine.verifyApiAccess().thenApply(
                new java.util.function.Function<TranslationEngine.ApiCheckResult, TranslationDiagnostics.ApiDetection>() {
                    @Override public TranslationDiagnostics.ApiDetection apply(TranslationEngine.ApiCheckResult result) {
                        boolean success = result != null && result.isAvailable();
                        return new TranslationDiagnostics.ApiDetection(success, "openai_chat", "bearer",
                                engine.getEndpoint(), success ? 200 : 0,
                                result == null ? "request_failed" : result.getStatus());
                    }
                });
    }

    public CompletableFuture<TranslationDiagnostics.ModelDetection> detectAvailableModels(
            String apiKey, String apiUrl, ModConfig.ApiFormat apiFormat) {
        return engine.detectAvailableModels(apiKey, apiUrl, apiFormat);
    }

    public CompletableFuture<TranslationDiagnostics.ModelAccess> verifyModelAccess(
            String apiKey, String apiUrl, String modelId, ModConfig.ApiFormat apiFormat) {
        return engine.verifyModelAccess(apiKey, apiUrl, modelId, apiFormat);
    }

    public CompletableFuture<String> translateRaw(String text) {
        return translateRaw(text, "manager.raw", "manager-raw", "", "");
    }

    public CompletableFuture<String> translateRaw(String text, String surface, String role,
                                                  String sourceLanguage, String targetLanguage) {
        final String source = text == null ? "" : text;
        if (blank(source)) return CompletableFuture.completedFuture(source);
        if (!ModConfig.GLOBAL_ENABLED.get() || !isReady()) return CompletableFuture.completedFuture(null);
        final TranslationBlacklist blacklist = engine.getTranslationBlacklist();
        if (blacklist != null && blacklist.isBlacklisted(source)) return CompletableFuture.completedFuture(null);
        return DirectSurfaceTranslator.translateComponentsAsync(
                Collections.singletonList(LegacyComponentFactory.literal(source)), surface, role,
                false, "", sourceLanguage, targetLanguage).thenApply(
                new java.util.function.Function<com.yourname.simpletranslate.core.ComponentListTranslationResult, String>() {
                    @Override public String apply(com.yourname.simpletranslate.core.ComponentListTranslationResult result) {
                        if (result == null || !result.translated || result.components == null
                                || result.components.size() != 1) return null;
                        String translated = result.components.get(0).getUnformattedText();
                        return blank(translated) || (blacklist != null
                                && blacklist.containsBlacklistedEntry(translated)) ? null : translated;
                    }
                });
    }

    public CompletableFuture<String> translateComponentJson(String document, String surface) {
        return translateComponentJson(document, surface, 1);
    }
    public CompletableFuture<String> translateComponentJson(String document, String surface, int maxTokenMultiplier) {
        return translateComponentJson(document, surface, maxTokenMultiplier, "", "", "");
    }
    public CompletableFuture<String> translateComponentJson(String document, String surface, int maxTokenMultiplier,
                                                            String sourceLanguage, String targetLanguage) {
        return translateComponentJson(document, surface, maxTokenMultiplier, sourceLanguage, targetLanguage, "");
    }
    public CompletableFuture<String> translateComponentJson(String document, String surface, int maxTokenMultiplier,
                                                            String sourceLanguageOverride, String targetLanguageOverride,
                                                            String promptContext) {
        final String source = document == null ? "" : document;
        if (blank(source) || !ModConfig.GLOBAL_ENABLED.get() || !isReady()) {
            return CompletableFuture.completedFuture(null);
        }
        String sourceLanguage = blank(sourceLanguageOverride)
                ? ModConfig.SOURCE_LANGUAGE.get() : sourceLanguageOverride;
        String targetLanguage = blank(targetLanguageOverride)
                ? ModConfig.TARGET_LANGUAGE.get() : targetLanguageOverride;
        String context = promptContext;
        if (blank(context)) {
            context = TextContextMemory.buildPromptMetadata("", surface, "game-text", source,
                    true, sourceLanguage, targetLanguage).json();
        }
        return engine.translateRawComponentDocument(source, surface, maxTokenMultiplier,
                sourceLanguage, targetLanguage, context, collectTermHints(source));
    }

    public String getServiceName() { return "OpenAI-compatible Component JSON"; }
    public void shutdown() { engine.shutdown(); }

    private List<TranslationRequest.Term> collectTermHints(String text) {
        TermDictionary dictionary = engine.getTermDictionary();
        return dictionary == null || blank(text) ? Collections.<TranslationRequest.Term>emptyList()
                : dictionary.matchTermsInText(text);
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    public static final class TranslationResult {
        public final String original;
        public final String translated;
        public final boolean success;
        public final String error;
        public TranslationResult(String original, String translated, boolean success, String error) {
            this.original = original;
            this.translated = translated;
            this.success = success;
            this.error = error;
        }
    }
}
