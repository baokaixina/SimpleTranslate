package com.yourname.simpletranslate.translation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.util.ModelOutputSanitizer;
import com.yourname.simpletranslate.util.TranslationCacheKeys;
import com.yourname.simpletranslate.util.TranslationMapping;
import com.yourname.simpletranslate.util.TranslationMappingResult;
import com.yourname.simpletranslate.util.TranslationSlot;
import com.yourname.simpletranslate.util.TranslationTextDetector;

import java.net.URI;
import java.net.URLEncoder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek API translation service implementation.
 */
public class DeepSeekTranslationService implements TranslationService {

    private static final String SERVICE_NAME = "DeepSeek";
    private static final Pattern ALLOWED_MAPPING_PLACEHOLDER_PATTERN = Pattern.compile("@@@F\\d+@@@");
    /** Parallelism for language-detection probes only; global translation concurrency is in {@link TranslationRequestQueue}. */
    private static final int MAX_CONCURRENT_REQUESTS = 2;
    private static final int MAX_REQUEST_ATTEMPTS = 3;
    private static final int MIN_TRANSLATION_OUTPUT_TOKENS = 128;
    private static final int MAX_TRANSLATION_OUTPUT_TOKENS = 8192;
    private static final long WARN_SLOW_FIRST_CHUNK_MS = 8000L;
    private static final long WARN_SLOW_TOTAL_MS = 30000L;
    private static final long MAX_STREAM_TOTAL_MS = 45000L;
    private static final int MODEL_ACCESS_PROBE_MAX_TOKENS = 256;
    private static final Pattern DIRECT_SURFACE_PATTERN = Pattern.compile("<st-doc\\b[^>]*\\bsurface=\"([^\"]+)\"");
    private static final Pattern MAPPING_SURFACE_PATTERN = Pattern.compile("\"surface\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HTML_TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Set<String> REPORTED_INVALID_API_URLS = ConcurrentHashMap.newKeySet();
    private static final ExecutorService DETECTION_EXECUTOR = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS, runnable -> {
        Thread thread = new Thread(runnable, "SimpleTranslate-DeepSeekProbe");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService STREAM_READER_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "SimpleTranslate-StreamReader");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient httpClient;
    private final Gson gson;
    private volatile ApiRequestProfile lastSuccessfulProfile;

    /** Alias so existing references keep working after the prompt-builder split. */
    private static final class RequestMode {
        static final TranslationPrompts.RequestMode PLAIN_TEXT = TranslationPrompts.RequestMode.PLAIN_TEXT;
        static final TranslationPrompts.RequestMode PRESERVE_MARKERS = TranslationPrompts.RequestMode.PRESERVE_MARKERS;
        static final TranslationPrompts.RequestMode MAPPING_JSON = TranslationPrompts.RequestMode.MAPPING_JSON;
        static final TranslationPrompts.RequestMode DIRECT_FORMATTED = TranslationPrompts.RequestMode.DIRECT_FORMATTED;

        private RequestMode() {
        }
    }

    private enum EndpointKind {
        CHAT_COMPLETIONS,
        RESPONSES,
        ANTHROPIC_MESSAGES,
        GEMINI_GENERATE_CONTENT
    }

    public DeepSeekTranslationService() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.gson = new Gson();
    }

    @Override
    public CompletableFuture<String> translate(String text, String sourceLanguage, String targetLanguage) {
        return translateWithTerms(text, sourceLanguage, targetLanguage, List.of());
    }

    @Override
    public CompletableFuture<String> translateWithTerms(String text, String sourceLanguage, String targetLanguage,
            List<TermHint> termHints) {
        if (!isReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("DeepSeek API key not configured"));
        }
        String systemPrompt = buildSystemPrompt(sourceLanguage, targetLanguage, termHints,
                RequestMode.PLAIN_TEXT);
        return sendRequest(systemPrompt, text);
    }

    @Override
    public CompletableFuture<String> translatePreservingMarkers(String text, String sourceLanguage,
            String targetLanguage, List<TermHint> termHints) {
        if (!isReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("DeepSeek API key not configured"));
        }
        String systemPrompt = buildSystemPrompt(sourceLanguage, targetLanguage, termHints,
                RequestMode.PRESERVE_MARKERS);
        return sendRequest(systemPrompt, text);
    }

    @Override
    public CompletableFuture<String> translateFormattedDocument(String document, String surface,
            String sourceLanguage, String targetLanguage, List<TermHint> termHints) {
        if (!isReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("DeepSeek API key not configured"));
        }
        // Style/term hints live in the user payload so the system prompt stays
        // byte-stable per language pair (provider prefix caching).
        String systemPrompt = buildSystemPrompt(sourceLanguage, targetLanguage, List.of(),
                RequestMode.DIRECT_FORMATTED);
        String userPrompt = insertDirectSections(document, TranslationPrompts.directUserSections(termHints));
        return sendRequest(systemPrompt, userPrompt, estimateDirectMaxTokens(document), surface);
    }

    /** Same as {@link #translateFormattedDocument} but scales the output token budget. */
    public CompletableFuture<String> translateFormattedDocument(String document, String surface,
            String sourceLanguage, String targetLanguage, List<TermHint> termHints, int maxTokenMultiplier) {
        if (!isReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("DeepSeek API key not configured"));
        }
        String systemPrompt = buildSystemPrompt(sourceLanguage, targetLanguage, List.of(),
                RequestMode.DIRECT_FORMATTED);
        String userPrompt = insertDirectSections(document, TranslationPrompts.directUserSections(termHints));
        return sendRequest(systemPrompt, userPrompt, estimateDirectMaxTokens(document, maxTokenMultiplier), surface);
    }

    /** Inserts [STYLE]/[TERMS] sections right before the [TEXT] block. */
    private static String insertDirectSections(String document, String sections) {
        if (sections == null || sections.isBlank() || document == null) {
            return document;
        }
        int index = document.indexOf("[TEXT");
        if (index < 0) {
            return sections + document;
        }
        return document.substring(0, index) + sections + document.substring(index);
    }

    /** Output budget follows the [TEXT] block size, not the whole payload with context. */
    private static int estimateDirectMaxTokens(String document) {
        return estimateDirectMaxTokens(document, 1);
    }

    /** Output budget follows the [TEXT] block; multiplier supports partial retries. */
    static int estimateDirectMaxTokens(String document, int multiplier) {
        String body = document == null ? "" : document;
        int start = body.indexOf("[TEXT");
        int end = body.lastIndexOf("[/TEXT]");
        if (start >= 0 && end > start) {
            body = body.substring(start, end);
        }
        int lineCount = 0;
        for (String line : body.split("\\R")) {
            if (line != null && line.matches("(?s).*?\\d{1,4}\\s*[|｜].*")) {
                lineCount++;
            }
        }
        // Chinese game text often expands; multi-line tooltips need headroom per line.
        int charEstimate = (int) (body.length() * 2.4) + 512;
        int lineEstimate = lineCount * 192 + 384;
        int estimated = Math.max(charEstimate, lineEstimate);
        if (estimated < MIN_TRANSLATION_OUTPUT_TOKENS) {
            estimated = MIN_TRANSLATION_OUTPUT_TOKENS;
        }
        estimated = Math.min(estimated, MAX_TRANSLATION_OUTPUT_TOKENS);
        int scale = Math.max(1, multiplier);
        return Math.min(estimated * scale, MAX_TRANSLATION_OUTPUT_TOKENS);
    }

    @Override
    public CompletableFuture<TranslationMappingResult> translateMapping(TranslationMapping mapping,
            String sourceLanguage, String targetLanguage, List<TermHint> termHints) {
        if (!isReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("DeepSeek API key not configured"));
        }

        String systemPrompt = buildSystemPrompt(sourceLanguage, targetLanguage, termHints,
                RequestMode.MAPPING_JSON);
        String userPrompt = buildMappingUserPrompt(mapping, sourceLanguage, targetLanguage);
        return sendRequest(systemPrompt, userPrompt).thenApply(raw -> parseMappingResponse(raw, mapping));
    }

    @Override
    public CompletableFuture<TranslationService.ApiDetectionResult> detectApi() {
        if (!isReady()) {
            return CompletableFuture.completedFuture(
                    new TranslationService.ApiDetectionResult(false, "", "", "", 0, "API key not configured"));
        }

        String apiUrl = ModConfig.normalizeApiUrl(ModConfig.DEEPSEEK_API_URL.get());
        ModConfig.ApiFormat apiFormat = ModConfig.API_FORMAT.get();
        String model = apiFormat == ModConfig.ApiFormat.DEEPSEEK_CHAT
                ? ModConfig.normalizeDeepSeekModelId(ModConfig.DEEPSEEK_MODEL.get())
                : ModConfig.normalizeModelId(ModConfig.DEEPSEEK_MODEL.get());
        List<ApiRequestProfile> profiles = buildRequestProfiles(apiUrl, apiFormat, model);
        String systemPrompt = "You are a connectivity probe for a Minecraft translation client. "
                + "Respond briefly and naturally.";
        String userPrompt = "Probe";

        return CompletableFuture.supplyAsync(() -> probeApi(profiles, systemPrompt, userPrompt), DETECTION_EXECUTOR);
    }

    @Override
    public CompletableFuture<TranslationService.ModelDetectionResult> detectAvailableModels(String apiKey, String apiUrl,
            ModConfig.ApiFormat apiFormat) {
        return CompletableFuture.completedFuture(new TranslationService.ModelDetectionResult(
                false, ModConfig.normalizeApiUrl(apiUrl), 0, List.of(),
                "Model list detection is not supported for " + (apiFormat == null ? ModConfig.API_FORMAT.get() : apiFormat)));
    }

    @Override
    public CompletableFuture<TranslationService.ModelAccessResult> verifyModelAccess(String apiKey, String apiUrl,
            String modelId, ModConfig.ApiFormat apiFormat) {
        String key = sanitizeApiKey(apiKey);
        ModConfig.ApiFormat format = apiFormat == null ? ModConfig.API_FORMAT.get() : apiFormat;
        String model = format == ModConfig.ApiFormat.DEEPSEEK_CHAT
                ? ModConfig.normalizeDeepSeekModelId(modelId)
                : (modelId == null || modelId.isBlank() ? format.getDefaultModel() : modelId.trim());
        if (key.isBlank()) {
            return CompletableFuture.completedFuture(new TranslationService.ModelAccessResult(
                    false, model, 0, "API key not configured"));
        }
        if (model.isBlank()) {
            return CompletableFuture.completedFuture(new TranslationService.ModelAccessResult(
                    false, model, 0, "Model ID not configured"));
        }
        String urlError = ModConfig.validateApiUrl(apiUrl);
        if (!urlError.isBlank()) {
            return CompletableFuture.completedFuture(new TranslationService.ModelAccessResult(
                    false, model, 0, urlError));
        }
        String endpoint = ModConfig.normalizeApiUrl(apiUrl);
        return CompletableFuture.supplyAsync(() -> probeModelAccess(endpoint, key, model, format), DETECTION_EXECUTOR);
    }

    private String buildSystemPrompt(String sourceLanguage, String targetLanguage, List<TermHint> termHints,
            TranslationPrompts.RequestMode mode) {
        return TranslationPrompts.buildSystemPrompt(sourceLanguage, targetLanguage, termHints, mode);
    }


    private String buildMappingUserPrompt(TranslationMapping mapping, String sourceLanguage, String targetLanguage) {
        JsonObject root = new JsonObject();
        root.addProperty("version", "mapping-v1");
        root.addProperty("jobId", mapping.taskId());
        root.addProperty("surface", mapping.surface());
        root.addProperty("sourceLanguage", getLanguageName(sourceLanguage));
        root.addProperty("targetLanguage", getLanguageName(targetLanguage));
        root.addProperty("context", mapping.context());
        root.addProperty("layoutSignature", mapping.layoutSignature());
        root.addProperty("styleSignature", mapping.styleSignature());
        boolean flexibleLayout = isFlexibleMappingSurface(mapping.surface());
        root.addProperty("layoutMode", flexibleLayout ? "flexible-tooltip" : "fixed-layout");
        root.addProperty("instructions", flexibleLayout
                ? "Translate every unit as its own semantic style block or styled token. Use natural Minecraft localization for the target language, ignore source visual line breaks, keep every @@@F marker exactly once in the same unit, and preserve only structural tokens such as numbers, ids, commands, control codes, and placeholders."
                : "Translate each fixed-layout slot conservatively. Preserve line/slot structure, technical tokens, and every @@@F marker exactly.");

        JsonArray units = new JsonArray();
        for (TranslationSlot slot : mapping.slots()) {
            JsonObject unit = new JsonObject();
            unit.addProperty("id", slot.id());
            unit.addProperty("text", slot.text());
            unit.addProperty("role", slot.role());
            unit.addProperty("maxChars", slot.maxWidth());
            unit.addProperty("fixed", slot.fixedSlot());
            unit.addProperty("lineIndex", slot.lineIndex());
            unit.addProperty("slotIndex", slot.slotIndex());
            unit.addProperty("styleSignature", slot.styleSignature());
            unit.addProperty("tokenMask", slot.tokenMask());
            units.add(unit);
        }
        root.add("units", units);
        return root.toString();
    }

    private boolean isFlexibleMappingSurface(String surface) {
        if (surface == null) {
            return false;
        }
        String normalized = surface.toLowerCase();
        return normalized.startsWith("tooltip.")
                || normalized.startsWith("hover.")
                || normalized.startsWith("book.");
    }

    private String getLanguageName(String code) {
        return TranslationTextDetector.displayLanguageName(code);
    }

    private CompletableFuture<String> sendRequest(String systemPrompt, String userPrompt) {
        return sendRequest(systemPrompt, userPrompt, estimateMaxTokens(userPrompt));
    }

    private CompletableFuture<String> sendRequest(String systemPrompt, String userPrompt, int maxTokens) {
        return sendRequest(systemPrompt, userPrompt, maxTokens, null);
    }

    private CompletableFuture<String> sendRequest(String systemPrompt, String userPrompt, int maxTokens,
            String explicitSurface) {
        String apiKey = sanitizeApiKey(ModConfig.DEEPSEEK_API_KEY.get());
        ModConfig.ApiFormat apiFormat = ModConfig.API_FORMAT.get();
        String model = apiFormat == ModConfig.ApiFormat.DEEPSEEK_CHAT
                ? ModConfig.normalizeDeepSeekModelId(ModConfig.DEEPSEEK_MODEL.get())
                : ModConfig.normalizeModelId(ModConfig.DEEPSEEK_MODEL.get());
        String apiUrl = ModConfig.normalizeApiUrl(ModConfig.DEEPSEEK_API_URL.get());
        String urlError = ModConfig.validateApiUrl(ModConfig.DEEPSEEK_API_URL.get());
        if (!urlError.isBlank()) {
            if (REPORTED_INVALID_API_URLS.add(apiUrl + "|" + urlError)) {
                SimpleTranslateMod.getLogger().warn("Translation requests disabled for the current API URL: {}", urlError);
            }
            return CompletableFuture.completedFuture(null);
        }

        SimpleTranslateMod.getLogger().debug("Sending translation request to {} with model {} format {}", apiUrl, model, apiFormat);
        SimpleTranslateMod.getLogger().debug("Text to translate: {}", userPrompt);
        List<ApiRequestProfile> profiles = buildRequestProfiles(apiUrl, apiFormat, model);

        String surface = explicitSurface == null || explicitSurface.isBlank()
                ? detectRequestSurface(userPrompt)
                : explicitSurface;
        String queueKey = buildQueueKey(apiUrl, model, apiFormat.name(), systemPrompt, userPrompt, maxTokens);
        return TranslationRequestQueue.submit(queueKey, surface, priorityForSurface(surface), MAX_REQUEST_ATTEMPTS,
                () -> sendRequestBlocking(profiles, apiKey, model, systemPrompt, userPrompt, maxTokens));
    }

    private String sendRequestBlocking(List<ApiRequestProfile> profiles, String apiKey, String model,
            String systemPrompt, String userPrompt, int maxTokens) {
        List<ApiRequestProfile> orderedProfiles = profiles == null ? List.of() : profiles;
        for (ApiRequestProfile profile : orderedProfiles) {
            String translated = sendRequestWithProfile(profile, apiKey, model, systemPrompt, userPrompt, maxTokens);
            if (translated != null && !translated.isBlank()) {
                lastSuccessfulProfile = profile;
                return translated;
            }
        }
        return null;
    }

    private String sendRequestWithProfile(ApiRequestProfile profile, String apiKey, String model,
            String systemPrompt, String userPrompt, int maxTokens) {
        if (profile == null || profile.endpointUrl() == null || profile.endpointUrl().isBlank()) {
            return null;
        }

        long startedAt = System.nanoTime();
        try {
            HttpRequest request = buildRequest(profile, apiKey, model, systemPrompt, userPrompt, maxTokens);
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            SimpleTranslateMod.getLogger().debug("Received DeepSeek response with status: {}",
                    response.statusCode());
            if (response.statusCode() == 200) {
                String result = parseResponse(response.body(), startedAt);
                long elapsedMs = elapsedMillis(startedAt);
                if (elapsedMs > WARN_SLOW_TOTAL_MS) {
                    SimpleTranslateMod.getLogger().warn("DeepSeek translation request completed slowly in {} ms",
                            elapsedMs);
                } else {
                    SimpleTranslateMod.getLogger().debug("DeepSeek translation request completed in {} ms",
                            elapsedMs);
                }
                if (result != null && !result.isBlank()) {
                    SimpleTranslateMod.getLogger().debug("Translation result: {}", result);
                    return result;
                }
                throw new TranslationRequestQueue.RetryableTranslationException("Empty translation result from DeepSeek");
            }

            String errorBody = readErrorBody(response.body());
            if (isRetryableStatus(response.statusCode())) {
                throw new TranslationRequestQueue.RetryableTranslationException(
                        "HTTP " + response.statusCode() + (errorBody == null || errorBody.isBlank() ? "" : ": " + errorBody));
            }

            SimpleTranslateMod.getLogger().warn("DeepSeek API error: {} - {}",
                    response.statusCode(), errorBody);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SimpleTranslateMod.getLogger().error("Translation request interrupted", e);
            return null;
        } catch (IOException e) {
            throw new TranslationRequestQueue.RetryableTranslationException(
                    e.getMessage() == null ? "I/O error" : e.getMessage(), e);
        } catch (TranslationRequestQueue.RetryableTranslationException e) {
            throw e;
        } catch (InvalidApiResponseException e) {
            SimpleTranslateMod.getLogger().warn("Translation API returned an unusable response: {}", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            SimpleTranslateMod.getLogger().error("DeepSeek request failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private static String buildQueueKey(String apiUrl, String model, String apiFormat,
            String systemPrompt, String userPrompt, int maxTokens) {
        return "deepseek:" + TranslationCacheKeys.hashSource(
                (apiUrl == null ? "" : apiUrl) + "\u0001"
                        + (model == null ? "" : model) + "\u0001"
                        + (apiFormat == null ? "" : apiFormat) + "\u0001"
                        + maxTokens + "\u0001"
                        + (systemPrompt == null ? "" : systemPrompt) + "\u0001"
                        + (userPrompt == null ? "" : userPrompt));
    }

    private static String detectRequestSurface(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return "manager.raw";
        }
        Matcher direct = DIRECT_SURFACE_PATTERN.matcher(userPrompt);
        if (direct.find()) {
            return direct.group(1);
        }
        Matcher mapping = MAPPING_SURFACE_PATTERN.matcher(userPrompt);
        if (mapping.find()) {
            return mapping.group(1);
        }
        return "manager.raw";
    }

    private static TranslationRequestQueue.Priority priorityForSurface(String surface) {
        String value = surface == null ? "" : surface.toLowerCase();
        if (value.startsWith("sign.manual")) {
            return TranslationRequestQueue.Priority.MANUAL_SIGN;
        }
        if (value.startsWith("tooltip.") || value.startsWith("hover.")) {
            return TranslationRequestQueue.Priority.INTERACTIVE;
        }
        if (value.startsWith("chat.")) {
            return TranslationRequestQueue.Priority.CHAT;
        }
        if (value.startsWith("hud.title_group") || value.startsWith("hud.title.")
                || value.startsWith("hud.subtitle.") || value.startsWith("title.")) {
            return TranslationRequestQueue.Priority.TITLE_URGENT;
        }
        if (value.startsWith("hud.actionbar.") || value.startsWith("actionbar.")) {
            return TranslationRequestQueue.Priority.ACTIONBAR_URGENT;
        }
        if (value.startsWith("hud.") || value.startsWith("title.") || value.startsWith("actionbar.")
                || value.startsWith("scoreboard") || value.startsWith("bossbar.")) {
            return TranslationRequestQueue.Priority.HUD;
        }
        if (value.startsWith("book")) {
            return TranslationRequestQueue.Priority.BOOK;
        }
        if (value.contains(".fcs.")) {
            return TranslationRequestQueue.Priority.FRAGMENT;
        }
        if (value.startsWith("advancement.")) {
            return TranslationRequestQueue.Priority.ADVANCEMENT_INTERACTIVE;
        }
        return TranslationRequestQueue.Priority.NORMAL;
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static int estimateMaxTokens(String text) {
        int length = text == null ? 0 : text.length();
        int estimated = length + 512;
        if (estimated < MIN_TRANSLATION_OUTPUT_TOKENS) {
            return MIN_TRANSLATION_OUTPUT_TOKENS;
        }
        return Math.min(estimated, MAX_TRANSLATION_OUTPUT_TOKENS);
    }

    private String parseResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        if (responseBody.contains("data:")) {
            return parseStreamingResponse(responseBody);
        }
        return parsePlainResponse(responseBody);
    }

    private String parseResponse(InputStream responseBody, long startedAt) throws IOException {
        if (responseBody == null) {
            return null;
        }

        Future<String> readerTask = STREAM_READER_EXECUTOR.submit(() -> parseResponseBlocking(responseBody, startedAt));
        try {
            long remainingMs = Math.max(1L, MAX_STREAM_TOTAL_MS - elapsedMillis(startedAt));
            return readerTask.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            closeQuietly(responseBody);
            readerTask.cancel(true);
            SimpleTranslateMod.getLogger().warn("Translation stream exceeded {} ms; aborting this response",
                    MAX_STREAM_TOTAL_MS);
            return null;
        } catch (InterruptedException e) {
            closeQuietly(responseBody);
            readerTask.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Translation stream interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("Translation stream failed", cause);
        }
    }

    private String parseResponseBlocking(InputStream responseBody, long startedAt) throws IOException {
        if (responseBody == null) {
            return null;
        }

        StringBuilder raw = new StringBuilder();
        StringBuilder result = new StringBuilder();
        boolean sawData = false;
        long firstChunkMs = -1L;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                long elapsedMs = elapsedMillis(startedAt);
                if (elapsedMs > MAX_STREAM_TOTAL_MS) {
                    SimpleTranslateMod.getLogger().warn("Translation stream exceeded {} ms; aborting this response",
                            MAX_STREAM_TOTAL_MS);
                    return null;
                }
                raw.append(line).append('\n');
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }

                String json = trimmed.substring(5).trim();
                if (json.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(json)) {
                    break;
                }

                sawData = true;
                if (firstChunkMs < 0L) {
                    firstChunkMs = elapsedMillis(startedAt);
                    if (firstChunkMs > WARN_SLOW_FIRST_CHUNK_MS) {
                        SimpleTranslateMod.getLogger().warn("First translation chunk took {} ms", firstChunkMs);
                    } else {
                        SimpleTranslateMod.getLogger().debug("First translation chunk arrived in {} ms", firstChunkMs);
                    }
                }

                appendStreamingChunk(result, json);
            }
        } catch (RuntimeException e) {
            SimpleTranslateMod.getLogger().error("Failed to parse streamed response", e);
            return null;
        }

        if (sawData) {
            return ModelOutputSanitizer.sanitize(result.toString());
        }
        return parsePlainResponse(raw.toString());
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Best-effort cancellation path.
        }
    }

    private String parseStreamingResponse(String responseBody) {
        StringBuilder result = new StringBuilder();
        try {
            for (String line : responseBody.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                String json = trimmed.substring(5).trim();
                if (json.isEmpty() || "[DONE]".equals(json)) {
                    continue;
                }
                appendStreamingChunk(result, json);
            }
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to parse streamed response", e);
            return null;
        }
        return ModelOutputSanitizer.sanitize(result.toString());
    }

    private String parsePlainResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        String trimmed = responseBody.trim();
        if (looksLikeHtml(trimmed)) {
            throw new InvalidApiResponseException(invalidApiResponseMessage(trimmed));
        }
        try {
            JsonObject response = JsonParser.parseString(trimmed).getAsJsonObject();
            String raw = extractAssistantContent(response);
            if (raw != null) {
                return ModelOutputSanitizer.sanitize(raw);
            }
            throw new InvalidApiResponseException("API response did not contain assistant text output");
        } catch (Exception e) {
            if (e instanceof InvalidApiResponseException invalid) {
                throw invalid;
            }
            throw new InvalidApiResponseException(invalidApiResponseMessage(trimmed), e);
        }
    }

    private TranslationMappingResult parseMappingResponse(String response, TranslationMapping mapping) {
        String sanitized = ModelOutputSanitizer.sanitize(response);
        if (sanitized == null || sanitized.isBlank()) {
            return new TranslationMappingResult(mapping.taskId(), Map.of());
        }

        try {
            String jsonPayload = extractJsonObjectPayload(sanitized);
            if (jsonPayload == null || jsonPayload.isBlank()) {
                return new TranslationMappingResult(mapping.taskId(), Map.of());
            }
            JsonObject root = JsonParser.parseString(jsonPayload).getAsJsonObject();
            String version = root.has("version") ? root.get("version").getAsString() : "";
            String jobId = root.has("jobId") ? root.get("jobId").getAsString() : "";
            if (!"mapping-v1".equals(version) || !mapping.taskId().equals(jobId)) {
                return new TranslationMappingResult(mapping.taskId(), Map.of());
            }
            if (!root.has("translations") || !root.get("translations").isJsonArray()) {
                return new TranslationMappingResult(mapping.taskId(), Map.of());
            }

            Map<String, String> translations = new LinkedHashMap<>();
            for (var element : root.getAsJsonArray("translations")) {
                if (!element.isJsonObject()) {
                    return new TranslationMappingResult(mapping.taskId(), Map.of());
                }
                JsonObject item = element.getAsJsonObject();
                String id = item.has("id") ? item.get("id").getAsString() : "";
                String translation = item.has("translation") ? item.get("translation").getAsString() : "";
                if (id.isBlank()) {
                    return new TranslationMappingResult(mapping.taskId(), Map.of());
                }
                String cleaned = ModelOutputSanitizer.sanitize(translation);
                if (cleaned == null || cleaned.isBlank() || containsDisallowedMappingTokens(cleaned)) {
                    return new TranslationMappingResult(mapping.taskId(), Map.of());
                }
                translations.put(id, cleaned);
            }

            if (translations.size() != mapping.slots().size()) {
                return new TranslationMappingResult(mapping.taskId(), Map.of());
            }

            for (TranslationSlot slot : mapping.slots()) {
                if (!translations.containsKey(slot.id())) {
                    return new TranslationMappingResult(mapping.taskId(), Map.of());
                }
                if (slot.fixedSlot()) {
                    String normalizedSource = TranslationCacheKeys.normalizeSource(slot.text());
                    String normalizedTranslated = TranslationCacheKeys.normalizeSource(translations.get(slot.id()));
                    if (!normalizedSource.equals(normalizedTranslated)) {
                        return new TranslationMappingResult(mapping.taskId(), Map.of());
                    }
                }
            }

            return new TranslationMappingResult(mapping.taskId(), translations);
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().debug("Failed to parse mapping response", e);
            return new TranslationMappingResult(mapping.taskId(), Map.of());
        }
    }

    private String extractJsonObjectPayload(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        int start = trimmed.indexOf('{');
        if (start < 0) {
            return null;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return trimmed.substring(start, i + 1).trim();
                }
            }
        }
        return null;
    }

    private boolean containsDisallowedMappingTokens(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return containsDisallowedMappingMarker(text)
                || text.contains("```")
                || text.contains("<think>")
                || text.contains("</think>")
                || text.contains("<analysis>")
                || text.contains("</analysis>");
    }

    private boolean containsDisallowedMappingMarker(String text) {
        int index = text.indexOf("@@@");
        if (index < 0) {
            return false;
        }
        Matcher matcher = ALLOWED_MAPPING_PLACEHOLDER_PATTERN.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            String between = text.substring(cursor, matcher.start());
            if (between.contains("@@@")) {
                return true;
            }
            cursor = matcher.end();
        }
        return text.substring(cursor).contains("@@@");
    }

    private void appendStreamingChunk(StringBuilder result, String json) {
        JsonObject chunk = JsonParser.parseString(json).getAsJsonObject();
        if (chunk.has("delta") && !chunk.get("delta").isJsonNull()) {
            result.append(chunk.get("delta").getAsString());
            return;
        }
        if (chunk.has("type") && !chunk.get("type").isJsonNull()
                && "response.output_text.delta".equals(chunk.get("type").getAsString())
                && chunk.has("delta") && !chunk.get("delta").isJsonNull()) {
            result.append(chunk.get("delta").getAsString());
            return;
        }
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return;
        }
        JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
        if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
            result.append(delta.get("content").getAsString());
        }
    }

    private TranslationService.ApiDetectionResult probeApi(List<ApiRequestProfile> profiles, String systemPrompt,
            String userPrompt) {
        String apiKey = sanitizeApiKey(ModConfig.DEEPSEEK_API_KEY.get());
        ModConfig.ApiFormat apiFormat = ModConfig.API_FORMAT.get();
        String model = apiFormat == ModConfig.ApiFormat.DEEPSEEK_CHAT
                ? ModConfig.normalizeDeepSeekModelId(ModConfig.DEEPSEEK_MODEL.get())
                : ModConfig.normalizeModelId(ModConfig.DEEPSEEK_MODEL.get());
        String lastMessage = apiFormat.getDisplayName() + " endpoint did not respond successfully";
        for (ApiRequestProfile profile : profiles) {
            try {
                String result = sendRequestWithProfile(profile, apiKey, model, systemPrompt, userPrompt, 64);
                if (result != null && !result.isBlank()) {
                    return new TranslationService.ApiDetectionResult(true, profile.kind().name(),
                            profile.kind() == EndpointKind.GEMINI_GENERATE_CONTENT ? "API_KEY" : "BEARER",
                            profile.endpointUrl(), 200, "API responded successfully");
                }
            } catch (TranslationRequestQueue.RetryableTranslationException e) {
                lastMessage = e.getMessage() == null ? "Retryable API detection failure" : e.getMessage();
            }
        }
        return new TranslationService.ApiDetectionResult(false, "", "",
                ModConfig.normalizeApiUrl(ModConfig.DEEPSEEK_API_URL.get()),
                0, lastMessage);
    }

    private TranslationService.ModelAccessResult probeModelAccess(String apiUrl, String apiKey, String modelId,
            ModConfig.ApiFormat apiFormat) {
        List<ApiRequestProfile> profiles = buildRequestProfiles(apiUrl, apiFormat, modelId);
        String lastMessage = "No compatible " + apiFormat.getDisplayName() + " endpoint responded";
        int lastStatus = 0;
        String probeSystemPrompt = buildSystemPrompt("en_us", ModConfig.TARGET_LANGUAGE.get(), List.of(),
                RequestMode.DIRECT_FORMATTED);
        String probeUserPrompt = """
                <st-doc surface="model.probe.direct" role="probe">
                [TEXT lines=1]
                0|Hello
                [/TEXT]
                </st-doc>
                """;

        for (ApiRequestProfile profile : profiles) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(profile.endpointUrl()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(
                                buildRequestBody(profile, modelId, probeSystemPrompt, probeUserPrompt,
                                        MODEL_ACCESS_PROBE_MAX_TOKENS, false))))
                        .timeout(Duration.ofSeconds(30));
                applyAuthHeader(builder, profile, apiKey);

                HttpResponse<InputStream> response = httpClient.send(builder.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                lastStatus = response.statusCode();
                String body = readErrorBody(response.body());
                if (response.statusCode() == 200) {
                    String translated = parseResponse(body);
                    if (isValidModelAccessProbeResponse(translated)) {
                        lastSuccessfulProfile = profile;
                        return new TranslationService.ModelAccessResult(true, modelId, response.statusCode(),
                                "Model verified");
                    }
                    lastMessage = "Model responded but did not follow the translation protocol";
                    continue;
                }
                lastMessage = body == null || body.isBlank() ? ("HTTP " + response.statusCode()) : compactErrorBody(body);
            } catch (InvalidApiResponseException e) {
                lastMessage = e.getMessage() == null ? "Invalid API response" : e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new TranslationService.ModelAccessResult(false, modelId, 0, "Model verification interrupted");
            } catch (IOException | RuntimeException e) {
                lastMessage = e.getMessage() == null ? "Model verification failed" : e.getMessage();
            }
        }

        return new TranslationService.ModelAccessResult(false, modelId, lastStatus, lastMessage);
    }

    private static boolean isValidModelAccessProbeResponse(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String line : value.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.matches("0\\s*[|｜].+")) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeHtml(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.stripLeading().toLowerCase();
        return trimmed.startsWith("<!doctype html")
                || trimmed.startsWith("<html")
                || trimmed.contains("<title")
                || trimmed.contains("<body");
    }

    private static String invalidApiResponseMessage(String value) {
        if (value == null || value.isBlank()) {
            return "API returned an empty response";
        }
        if (looksLikeHtml(value)) {
            Matcher matcher = HTML_TITLE_PATTERN.matcher(value);
            String title = matcher.find() ? compactText(matcher.group(1), 80) : "";
            return title.isBlank()
                    ? "API endpoint returned an HTML page; check API URL and interface format"
                    : "API endpoint returned an HTML page (" + title + "); check API URL and interface format";
        }
        return "API returned non-JSON content: " + compactText(value, 120);
    }

    private static String compactErrorBody(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (looksLikeHtml(value)) {
            return invalidApiResponseMessage(value);
        }
        return compactText(value, 240);
    }

    private static String compactText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("(?is)<[^>]+>", " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        int limit = Math.max(16, maxLength);
        return compact.length() > limit ? compact.substring(0, limit - 3) + "..." : compact;
    }

    private static final class InvalidApiResponseException extends RuntimeException {
        private InvalidApiResponseException(String message) {
            super(message);
        }

        private InvalidApiResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private List<ApiRequestProfile> buildRequestProfiles(String apiUrl, ModConfig.ApiFormat apiFormat, String model) {
        ModConfig.ApiFormat format = apiFormat == null ? ModConfig.ApiFormat.DEEPSEEK_CHAT : apiFormat;
        String base = ModConfig.normalizeApiUrl(apiUrl);
        return switch (format) {
            case DEEPSEEK_CHAT -> List.of(new ApiRequestProfile(base, resolveChatEndpoint(base),
                    EndpointKind.CHAT_COMPLETIONS, true));
            case OPENAI_CHAT_COMPAT -> List.of(new ApiRequestProfile(base, resolveChatEndpoint(base),
                    EndpointKind.CHAT_COMPLETIONS, false));
            case OPENAI_RESPONSES -> List.of(new ApiRequestProfile(base, resolveResponsesEndpoint(base),
                    EndpointKind.RESPONSES, false));
            case ANTHROPIC_MESSAGES -> List.of(new ApiRequestProfile(base, resolveAnthropicMessagesEndpoint(base),
                    EndpointKind.ANTHROPIC_MESSAGES, false));
            case GEMINI_GENERATE_CONTENT -> List.of(new ApiRequestProfile(base,
                    resolveGeminiGenerateContentEndpoint(base, model),
                    EndpointKind.GEMINI_GENERATE_CONTENT, false));
        };
    }

    private String resolveChatEndpoint(String apiUrl) {
        String normalized = ModConfig.normalizeApiUrl(apiUrl);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.contains("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        if (normalized.endsWith("/v1/")) {
            return normalized + "chat/completions";
        }
        return normalized + "/chat/completions";
    }

    private String resolveResponsesEndpoint(String apiUrl) {
        String normalized = ModConfig.normalizeApiUrl(apiUrl);
        if (normalized.contains("/responses")) {
            return normalized;
        }
        if (normalized.endsWith("/v1") || normalized.endsWith("/v1/")) {
            return normalized.replaceAll("/+$", "") + "/responses";
        }
        return normalized + "/responses";
    }

    private String resolveAnthropicMessagesEndpoint(String apiUrl) {
        String normalized = ModConfig.normalizeApiUrl(apiUrl);
        if (normalized.contains("/messages")) {
            return normalized;
        }
        if (normalized.endsWith("/v1") || normalized.endsWith("/v1/")) {
            return normalized.replaceAll("/+$", "") + "/messages";
        }
        return normalized + "/v1/messages";
    }

    private String resolveGeminiGenerateContentEndpoint(String apiUrl, String model) {
        String normalized = ModConfig.normalizeApiUrl(apiUrl);
        if (normalized.contains(":generateContent")) {
            return normalized;
        }
        String encodedModel = URLEncoder.encode(model == null || model.isBlank()
                ? ModConfig.ApiFormat.GEMINI_GENERATE_CONTENT.getDefaultModel() : model, StandardCharsets.UTF_8);
        if (normalized.endsWith("/v1") || normalized.endsWith("/v1beta")) {
            return normalized + "/models/" + encodedModel + ":generateContent";
        }
        if (normalized.endsWith("/v1/") || normalized.endsWith("/v1beta/")) {
            return normalized + "models/" + encodedModel + ":generateContent";
        }
        return normalized + "/v1beta/models/" + encodedModel + ":generateContent";
    }

    private HttpRequest buildRequest(ApiRequestProfile profile, String apiKey, String model, String systemPrompt,
            String userPrompt, int maxTokens) {
        JsonObject requestBody = buildRequestBody(profile, model, systemPrompt, userPrompt, maxTokens, true);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(withApiKeyQuery(profile, apiKey)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .timeout(Duration.ofSeconds(60));

        applyAuthHeader(builder, profile, apiKey);
        return builder.build();
    }

    private JsonObject buildRequestBody(ApiRequestProfile profile, String model, String systemPrompt,
            String userPrompt, int maxTokens, boolean streaming) {
        return switch (profile.kind()) {
            case CHAT_COMPLETIONS -> buildChatCompletionsBody(profile, model, systemPrompt, userPrompt, maxTokens, streaming);
            case RESPONSES -> buildResponsesBody(model, systemPrompt, userPrompt, maxTokens, streaming);
            case ANTHROPIC_MESSAGES -> buildAnthropicMessagesBody(model, systemPrompt, userPrompt, maxTokens);
            case GEMINI_GENERATE_CONTENT -> buildGeminiGenerateContentBody(systemPrompt, userPrompt, maxTokens);
        };
    }

    private JsonObject buildChatCompletionsBody(ApiRequestProfile profile, String model, String systemPrompt,
            String userPrompt, int maxTokens, boolean streaming) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("stream", streaming);
        requestBody.addProperty("max_tokens", Math.max(MIN_TRANSLATION_OUTPUT_TOKENS,
                Math.min(maxTokens, MAX_TRANSLATION_OUTPUT_TOKENS)));

        boolean thinkingEnabled = profile.supportsThinking() && ModConfig.DEEPSEEK_THINKING_ENABLED.get();
        if (!thinkingEnabled) {
            requestBody.addProperty("temperature", 0.1);
        }

        if (profile.supportsThinking()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", thinkingEnabled ? "enabled" : "disabled");
            requestBody.add("thinking", thinking);
        }

        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", systemPrompt);
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);

        requestBody.add("messages", messages);
        return requestBody;
    }

    private JsonObject buildResponsesBody(String model, String systemPrompt, String userPrompt, int maxTokens,
            boolean streaming) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("stream", streaming);
        requestBody.addProperty("max_output_tokens", Math.max(MIN_TRANSLATION_OUTPUT_TOKENS,
                Math.min(maxTokens, MAX_TRANSLATION_OUTPUT_TOKENS)));
        requestBody.addProperty("temperature", 0.1);
        requestBody.addProperty("instructions", systemPrompt);

        JsonArray input = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("type", "input_text");
        part.addProperty("text", userPrompt);
        content.add(part);
        message.add("content", content);
        input.add(message);
        requestBody.add("input", input);
        return requestBody;
    }

    private JsonObject buildAnthropicMessagesBody(String model, String systemPrompt, String userPrompt, int maxTokens) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("max_tokens", Math.max(MIN_TRANSLATION_OUTPUT_TOKENS,
                Math.min(maxTokens, MAX_TRANSLATION_OUTPUT_TOKENS)));
        requestBody.addProperty("temperature", 0.1);
        requestBody.addProperty("system", systemPrompt);

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);
        requestBody.add("messages", messages);
        return requestBody;
    }

    private JsonObject buildGeminiGenerateContentBody(String systemPrompt, String userPrompt, int maxTokens) {
        JsonObject requestBody = new JsonObject();
        JsonObject systemInstruction = new JsonObject();
        JsonArray systemParts = new JsonArray();
        JsonObject systemPart = new JsonObject();
        systemPart.addProperty("text", systemPrompt);
        systemParts.add(systemPart);
        systemInstruction.add("parts", systemParts);
        requestBody.add("systemInstruction", systemInstruction);

        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", userPrompt);
        parts.add(part);
        content.add("parts", parts);
        contents.add(content);
        requestBody.add("contents", contents);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0.1);
        generationConfig.addProperty("maxOutputTokens", Math.max(MIN_TRANSLATION_OUTPUT_TOKENS,
                Math.min(maxTokens, MAX_TRANSLATION_OUTPUT_TOKENS)));
        requestBody.add("generationConfig", generationConfig);
        return requestBody;
    }

    private void applyAuthHeader(HttpRequest.Builder builder, ApiRequestProfile profile, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        if (profile.kind() == EndpointKind.ANTHROPIC_MESSAGES) {
            builder.header("x-api-key", apiKey);
            builder.header("anthropic-version", "2023-06-01");
        } else if (profile.kind() != EndpointKind.GEMINI_GENERATE_CONTENT) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
    }

    private String withApiKeyQuery(ApiRequestProfile profile, String apiKey) {
        if (profile.kind() != EndpointKind.GEMINI_GENERATE_CONTENT || apiKey == null || apiKey.isBlank()
                || profile.endpointUrl().contains("key=")) {
            return profile.endpointUrl();
        }
        return profile.endpointUrl() + (profile.endpointUrl().contains("?") ? "&" : "?")
                + "key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
    }

    private String extractAssistantContent(JsonObject response) {
        if (response == null) {
            return null;
        }

        if (response.has("choices") && response.get("choices").isJsonArray()) {
            JsonArray choices = response.getAsJsonArray("choices");
            if (!choices.isEmpty() && choices.get(0).isJsonObject()) {
                JsonObject choice = choices.get(0).getAsJsonObject();
                if (choice.has("message") && choice.get("message").isJsonObject()) {
                    JsonObject message = choice.getAsJsonObject("message");
                    if (message.has("content") && !message.get("content").isJsonNull()) {
                        return message.get("content").getAsString();
                    }
                }
                if (choice.has("delta") && choice.get("delta").isJsonObject()) {
                    JsonObject delta = choice.getAsJsonObject("delta");
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        return delta.get("content").getAsString();
                    }
                }
            }
        }

        if (response.has("output_text") && !response.get("output_text").isJsonNull()) {
            return response.get("output_text").getAsString();
        }

        if (response.has("output") && response.get("output").isJsonArray()) {
            JsonArray output = response.getAsJsonArray("output");
            for (var element : output) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject outputItem = element.getAsJsonObject();
                if (outputItem.has("content") && outputItem.get("content").isJsonArray()) {
                    JsonArray content = outputItem.getAsJsonArray("content");
                    for (var contentPart : content) {
                        if (!contentPart.isJsonObject()) {
                            continue;
                        }
                        JsonObject part = contentPart.getAsJsonObject();
                        if (part.has("text") && !part.get("text").isJsonNull()) {
                            return part.get("text").getAsString();
                        }
                    }
                }
            }
        }

        if (response.has("content") && response.get("content").isJsonArray()) {
            JsonArray content = response.getAsJsonArray("content");
            for (var contentPart : content) {
                if (!contentPart.isJsonObject()) {
                    continue;
                }
                JsonObject part = contentPart.getAsJsonObject();
                if (part.has("text") && !part.get("text").isJsonNull()) {
                    return part.get("text").getAsString();
                }
            }
        }

        if (response.has("candidates") && response.get("candidates").isJsonArray()) {
            JsonArray candidates = response.getAsJsonArray("candidates");
            if (!candidates.isEmpty() && candidates.get(0).isJsonObject()) {
                JsonObject candidate = candidates.get(0).getAsJsonObject();
                if (candidate.has("content") && candidate.get("content").isJsonObject()) {
                    JsonObject content = candidate.getAsJsonObject("content");
                    if (content.has("parts") && content.get("parts").isJsonArray()) {
                        JsonArray parts = content.getAsJsonArray("parts");
                        StringBuilder builder = new StringBuilder();
                        for (var partElement : parts) {
                            if (partElement.isJsonObject()) {
                                JsonObject part = partElement.getAsJsonObject();
                                if (part.has("text") && !part.get("text").isJsonNull()) {
                                    builder.append(part.get("text").getAsString());
                                }
                            }
                        }
                        if (!builder.isEmpty()) {
                            return builder.toString();
                        }
                    }
                }
            }
        }

        return null;
    }

    private record ApiRequestProfile(String endpointBase, String endpointUrl, EndpointKind kind, boolean supportsThinking) {
        private String profileKey() {
            return endpointBase + "|" + endpointUrl + "|" + kind + "|" + supportsThinking;
        }
    }

    private static String readErrorBody(InputStream body) {
        if (body == null) {
            return "";
        }
        try (body) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<failed to read error body: " + e.getMessage() + ">";
        }
    }

    private static String sanitizeApiKey(String value) {
        if (value == null) {
            return "";
        }
        String key = value.trim();
        if (key.regionMatches(true, 0, "Bearer ", 0, 7)) {
            key = key.substring(7).trim();
        }
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            key = key.substring(1, key.length() - 1).trim();
        }
        return key;
    }

    @Override
    public boolean isReady() {
        String apiKey = sanitizeApiKey(ModConfig.DEEPSEEK_API_KEY.get());
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }
}
