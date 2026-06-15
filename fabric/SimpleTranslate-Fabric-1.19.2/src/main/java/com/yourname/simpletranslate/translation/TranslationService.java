package com.yourname.simpletranslate.translation;

import com.yourname.simpletranslate.util.TranslationMapping;
import com.yourname.simpletranslate.util.TranslationMappingResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for translation services
 * Designed to be extensible for future LLM providers
 */
public interface TranslationService {

    /**
     * Translate text from source language to target language
     * 
     * @param text           The text to translate
     * @param sourceLanguage Source language code (e.g., "en")
     * @param targetLanguage Target language code (e.g., "zh")
     * @return CompletableFuture containing the translated text
     */
    CompletableFuture<String> translate(String text, String sourceLanguage, String targetLanguage);

    /**
     * Translate text with term hints for consistency
     * 
     * @param text           The text to translate
     * @param sourceLanguage Source language code
     * @param targetLanguage Target language code
     * @param termHints      List of term pairs (original -> translation) to
     *                       maintain consistency
     * @return CompletableFuture containing the translated text
     */
    CompletableFuture<String> translateWithTerms(String text, String sourceLanguage, String targetLanguage,
            List<TermHint> termHints);

    /**
     * Translate text while preserving control markers used by legacy batch
     * pipelines.
     *
     * @param text           The marker-bearing text to translate
     * @param sourceLanguage Source language code
     * @param targetLanguage Target language code
     * @param termHints      List of term pairs to maintain consistency
     * @return CompletableFuture containing translated text with markers preserved
     */
    CompletableFuture<String> translatePreservingMarkers(String text, String sourceLanguage, String targetLanguage,
            List<TermHint> termHints);

    /**
     * Translate a reversible formatted wire document (numbered lines with
     * lightweight style tags). The model returns only translated numbered
     * lines; styling is reattached locally by the caller.
     *
     * @param surface the originating surface (for queue lanes/priorities)
     */
    CompletableFuture<String> translateFormattedDocument(String document, String surface, String sourceLanguage,
            String targetLanguage, List<TermHint> termHints);

    /**
     * Translate a structured mapping document.
     */
    CompletableFuture<TranslationMappingResult> translateMapping(TranslationMapping mapping, String sourceLanguage,
            String targetLanguage, List<TermHint> termHints);

    /**
     * Probe the current API endpoint and return the best detected compatibility profile.
     */
    CompletableFuture<ApiDetectionResult> detectApi();

    /**
     * Detect selectable model ids from the configured OpenAI-compatible endpoint.
     */
    CompletableFuture<ModelDetectionResult> detectAvailableModels(String apiKey, String apiUrl,
            com.yourname.simpletranslate.config.ModConfig.ApiFormat apiFormat);

    /**
     * Verify that one model id can be used for a minimal chat completion request.
     */
    CompletableFuture<ModelAccessResult> verifyModelAccess(String apiKey, String apiUrl, String modelId,
            com.yourname.simpletranslate.config.ModConfig.ApiFormat apiFormat);

    /**
     * Check if the service is configured and ready to use
     * 
     * @return true if ready
     */
    boolean isReady();

    /**
     * Get the name of this translation service
     * 
     * @return Service name (e.g., "DeepSeek")
     */
    String getServiceName();

    /**
     * Term hint record for translation consistency
     */
    record TermHint(String original, String translation) {
    }

    record ApiDetectionResult(
            boolean success,
            String providerMode,
            String authMode,
            String endpointUrl,
            int statusCode,
            String message) {
    }

    record ModelDetectionResult(
            boolean success,
            String endpointUrl,
            int statusCode,
            List<String> models,
            String message) {
    }

    record ModelAccessResult(
            boolean success,
            String modelId,
            int statusCode,
            String message) {
    }
}
