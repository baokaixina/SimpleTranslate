package com.yourname.simpletranslate.transport;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.api.TranslationDiagnostics;
import com.yourname.simpletranslate.api.TranslationRequest;
import com.yourname.simpletranslate.cache.TermDictionary;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Translation facade. Every game-text request is wrapped as Component JSON.
 */
public final class TranslationManager {
    private final com.yourname.simpletranslate.api.TranslationService translationService;
    private final DeepSeekTranslationService deepSeekService;

    public TranslationManager() {
        this.deepSeekService = new DeepSeekTranslationService();
        this.translationService = deepSeekService;
    }

    public CompletableFuture<TranslationResult> translate(String text) {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return CompletableFuture.completedFuture(
                    new TranslationResult(text, null, false, "Translation is disabled"));
        }
        return translateRaw(text).thenApply(translated -> {
            if (translated == null || translated.isBlank()) {
                return new TranslationResult(text, null, false, "Translation failed");
            }
            var blacklist = SimpleTranslateMod.getTranslationBlacklist();
            if (blacklist != null && blacklist.containsBlacklistedEntry(translated)) {
                return new TranslationResult(text, null, false, "Translation is blacklisted");
            }
            TermDictionary dictionary = SimpleTranslateMod.getTermDictionary();
            if (dictionary != null && ModConfig.TERM_AUTO_DETECT_ENABLED.get()) {
                dictionary.analyzeAndRecordTerms(text);
            }
            return new TranslationResult(text, translated, true, null);
        });
    }

    public boolean isReady() {
        return deepSeekService.isReady();
    }

    public CompletableFuture<TranslationDiagnostics.ApiDetection> detectApi() {
        return deepSeekService.detectApi();
    }

    public CompletableFuture<TranslationDiagnostics.ModelDetection> detectAvailableModels(
            String apiKey, String apiUrl, ModConfig.ApiFormat apiFormat) {
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.completedFuture(new TranslationDiagnostics.ModelDetection(
                    false, "", 0, List.of(), "API key not configured"));
        }
        return deepSeekService.detectAvailableModels(apiKey, apiUrl, apiFormat);
    }

    public CompletableFuture<TranslationDiagnostics.ModelAccess> verifyModelAccess(
            String apiKey, String apiUrl, String modelId, ModConfig.ApiFormat apiFormat) {
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.completedFuture(new TranslationDiagnostics.ModelAccess(
                    false, modelId, 0, "API key not configured"));
        }
        if (modelId == null || modelId.isBlank()) {
            return CompletableFuture.completedFuture(new TranslationDiagnostics.ModelAccess(
                    false, "", 0, "Model ID not configured"));
        }
        return deepSeekService.verifyModelAccess(apiKey, apiUrl, modelId, apiFormat);
    }

    public CompletableFuture<String> translateRaw(String text) {
        String source = text == null ? "" : text;
        if (source.isBlank()) {
            return CompletableFuture.completedFuture(source);
        }
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return CompletableFuture.completedFuture(null);
        }

        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist != null && blacklist.isBlacklisted(source)) {
            return CompletableFuture.completedFuture(null);
        }
        if (!deepSeekService.isReady()) {
            return CompletableFuture.completedFuture(null);
        }
        return DirectSurfaceTranslator.translateComponentsAsync(
                        List.of(Component.literal(source)), "manager.raw", "manager-raw", false, "")
                .thenApply(result -> {
                    if (result == null || !result.translated
                            || result.components == null || result.components.size() != 1) {
                        return null;
                    }
                    String translated = result.components.get(0).getString();
                    if (translated == null || translated.isBlank()
                            || (blacklist != null && blacklist.containsBlacklistedEntry(translated))) {
                        return null;
                    }
                    return translated;
                });
    }

    public CompletableFuture<String> translateComponentJson(String document, String surface) {
        return translateComponentJson(document, surface, 1);
    }

    public CompletableFuture<String> translateComponentJson(
            String document, String surface, int maxTokenMultiplier) {
        String source = document == null ? "" : document;
        if (source.isBlank()) {
            return CompletableFuture.completedFuture(source);
        }
        if (!ModConfig.GLOBAL_ENABLED.get() || !deepSeekService.isReady()) {
            return CompletableFuture.completedFuture(null);
        }

        String sourceLanguage = ModConfig.SOURCE_LANGUAGE.get();
        String targetLanguage = ModConfig.TARGET_LANGUAGE.get();
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        TranslationRequest request = new TranslationRequest(
                surface, List.of(source), collectTermHints(source), maxTokenMultiplier);
        return translationService.translate(request).handle((result, error) -> {
            if (!requestStillCurrent(runtimeRevision, sourceLanguage, targetLanguage)) {
                return null;
            }
            if (error != null) {
                SimpleTranslateMod.getLogger().warn("Component JSON translation failed: {}",
                        error.getMessage() == null
                                ? error.getClass().getSimpleName()
                                : error.getMessage());
                return null;
            }
            return payloadOf(result);
        });
    }

    public String getServiceName() {
        return deepSeekService.getServiceName();
    }

    public void shutdown() {
        deepSeekService.shutdown();
    }

    private List<TranslationRequest.Term> collectTermHints(String text) {
        TermDictionary dictionary = SimpleTranslateMod.getTermDictionary();
        if (dictionary == null || text == null || text.isBlank()) {
            return List.of();
        }
        return dictionary.matchTermsInText(text);
    }

    private static String payloadOf(com.yourname.simpletranslate.api.TranslationResult result) {
        if (result instanceof com.yourname.simpletranslate.api.TranslationResult.Success success) {
            return success.payload();
        }
        return null;
    }

    private static boolean requestStillCurrent(
            long runtimeRevision, String sourceLanguage, String targetLanguage) {
        return ModConfig.GLOBAL_ENABLED.get()
                && SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                && equalsNullable(sourceLanguage, ModConfig.SOURCE_LANGUAGE.get())
                && equalsNullable(targetLanguage, ModConfig.TARGET_LANGUAGE.get());
    }

    private static boolean equalsNullable(String expected, String current) {
        return expected == null ? current == null : expected.equals(current);
    }

    public record TranslationResult(
            String original,
            String translated,
            boolean success,
            String error) {
    }
}
