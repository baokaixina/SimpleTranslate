package com.yourname.simpletranslate.transport;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.api.TokenUsage;
import com.yourname.simpletranslate.api.TranslationDiagnostics;
import com.yourname.simpletranslate.api.TranslationRequest;
import com.yourname.simpletranslate.api.TranslationResult;
import com.yourname.simpletranslate.api.TranslationService;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.TranslationCacheKeys;

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
import java.util.concurrent.CompletionException;
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
    /** Parallelism for language-detection probes only; global translation concurrency is in {@link TranslationRequestQueue}. */
    private static final int MAX_CONCURRENT_REQUESTS = 2;
    private static final int MAX_REQUEST_ATTEMPTS = 3;
    private static final int MIN_TRANSLATION_OUTPUT_TOKENS = 128;
    private static final int MAX_TRANSLATION_OUTPUT_TOKENS = 16384;
    private static final long WARN_SLOW_FIRST_CHUNK_MS = 8000L;
    private static final long WARN_SLOW_TOTAL_MS = 30000L;
    private static final long MAX_STREAM_TOTAL_MS = 45000L;
    private static final int MODEL_ACCESS_PROBE_MAX_TOKENS = 256;
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

    public void shutdown() {
        DETECTION_EXECUTOR.shutdownNow();
        STREAM_READER_EXECUTOR.shutdownNow();
    }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
        if (request == null || request.lines().isEmpty()) {
            return CompletableFuture.completedFuture(new TranslationResult.Failed("empty-request"));
        }
        if (!isReady()) {
            return CompletableFuture.completedFuture(new TranslationResult.Failed("api-key-not-configured"));
        }
        String sourceLanguage = ModConfig.SOURCE_LANGUAGE.get();
        String targetLanguage = ModConfig.TARGET_LANGUAGE.get();
        String source = String.join("\n", request.lines());
        if (!isJsonArrayPayload(source)) {
            return CompletableFuture.completedFuture(
                    new TranslationResult.Failed("component-json-required"));
        }
        String systemPrompt = JsonPassthroughPrompts.buildSystemPrompt(
                sourceLanguage, targetLanguage, request.terms(), request.surface());
        CompletableFuture<String> future = sendRequest(systemPrompt, source,
                estimateDirectMaxTokens(source, request.maxTokenMultiplier()), request.surface());
        return future.handle((payload, error) -> {
            if (error != null) {
                String reason = error.getMessage();
                return new TranslationResult.Failed(
                        reason == null || reason.isBlank() ? error.getClass().getSimpleName() : reason);
            }
            if (payload == null || payload.isBlank()) {
                return new TranslationResult.Failed("empty-response");
            }
            return new TranslationResult.Success(payload);
        });
    }

    private static boolean isJsonArrayPayload(String source) {
        if (source == null) {
            return false;
        }
        String trimmed = source.trim();
        return trimmed.startsWith("[") && trimmed.endsWith("]");
    }

    /** Output budget follows the Component JSON array, not the optional context preface. */
    private static int estimateDirectMaxTokens(String document) {
        return estimateDirectMaxTokens(document, 1);
    }

    /** Multiplier is retained for callers that submit unusually large JSON arrays. */
    static int estimateDirectMaxTokens(String document, int multiplier) {
        String body = document == null ? "" : document;
        int contextEnd = body.indexOf("[/CONTEXT]");
        if (contextEnd >= 0) {
            body = body.substring(contextEnd + "[/CONTEXT]".length()).trim();
        }
        int elementCount = countJsonArrayElements(body);
        int charEstimate = (int) (body.length() * 2.4) + 512;
        int elementEstimate = elementCount * 192 + 384;
        int estimated = Math.max(charEstimate, elementEstimate);
        if (estimated < MIN_TRANSLATION_OUTPUT_TOKENS) {
            estimated = MIN_TRANSLATION_OUTPUT_TOKENS;
        }
        estimated = Math.min(estimated, MAX_TRANSLATION_OUTPUT_TOKENS);
        int scale = Math.max(1, multiplier);
        return Math.min(estimated * scale, MAX_TRANSLATION_OUTPUT_TOKENS);
    }

    /** Counts top-level elements in a JSON array string (for token estimation). */
    private static int countJsonArrayElements(String json) {
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            JsonElement root = JsonParser.parseString(json.trim());
            if (root.isJsonArray()) {
                return root.getAsJsonArray().size();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    public CompletableFuture<TranslationDiagnostics.ApiDetection> detectApi() {
        if (!isReady()) {
            return CompletableFuture.completedFuture(
                    new TranslationDiagnostics.ApiDetection(false, "", "", "", 0, "API key not configured"));
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

    public CompletableFuture<TranslationDiagnostics.ModelDetection> detectAvailableModels(String apiKey, String apiUrl,
            ModConfig.ApiFormat apiFormat) {
        return CompletableFuture.completedFuture(new TranslationDiagnostics.ModelDetection(
                false, ModConfig.normalizeApiUrl(apiUrl), 0, List.of(),
                "Model list detection is not supported for " + (apiFormat == null ? ModConfig.API_FORMAT.get() : apiFormat)));
    }

    public CompletableFuture<TranslationDiagnostics.ModelAccess> verifyModelAccess(String apiKey, String apiUrl,
            String modelId, ModConfig.ApiFormat apiFormat) {
        String key = sanitizeApiKey(apiKey);
        ModConfig.ApiFormat format = apiFormat == null ? ModConfig.API_FORMAT.get() : apiFormat;
        String model = format == ModConfig.ApiFormat.DEEPSEEK_CHAT
                ? ModConfig.normalizeDeepSeekModelId(modelId)
                : (modelId == null || modelId.isBlank() ? format.getDefaultModel() : modelId.trim());
        if (key.isBlank()) {
            return CompletableFuture.completedFuture(new TranslationDiagnostics.ModelAccess(
                    false, model, 0, "API key not configured"));
        }
        if (model.isBlank()) {
            return CompletableFuture.completedFuture(new TranslationDiagnostics.ModelAccess(
                    false, model, 0, "Model ID not configured"));
        }
        String urlError = ModConfig.validateApiUrl(apiUrl);
        if (!urlError.isBlank()) {
            return CompletableFuture.completedFuture(new TranslationDiagnostics.ModelAccess(
                    false, model, 0, urlError));
        }
        String endpoint = ModConfig.normalizeApiUrl(apiUrl);
        return CompletableFuture.supplyAsync(() -> probeModelAccess(endpoint, key, model, format), DETECTION_EXECUTOR);
    }

    private CompletableFuture<String> sendRequest(String systemPrompt, String userPrompt, int maxTokens,
            String surface) {
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

        String queueKey = buildQueueKey(apiUrl, model, apiFormat.name(), systemPrompt, userPrompt, maxTokens);
        return TranslationRequestQueue.submit(queueKey, surface, priorityForSurface(surface), MAX_REQUEST_ATTEMPTS,
                () -> sendRequestAsync(profiles, apiKey, model, apiFormat.name(), surface,
                        systemPrompt, userPrompt, maxTokens));
    }

    private CompletableFuture<String> sendRequestAsync(List<ApiRequestProfile> profiles, String apiKey, String model,
            String apiFormat, String surface,
            String systemPrompt, String userPrompt, int maxTokens) {
        List<ApiRequestProfile> orderedProfiles = profiles == null ? List.of() : profiles;
        return sendRequestAsync(orderedProfiles, 0, apiKey, model, apiFormat, surface,
                systemPrompt, userPrompt, maxTokens);
    }

    private CompletableFuture<String> sendRequestAsync(List<ApiRequestProfile> profiles, int index,
            String apiKey, String model, String apiFormat, String surface,
            String systemPrompt, String userPrompt, int maxTokens) {
        if (index >= profiles.size()) {
            return CompletableFuture.completedFuture(null);
        }
        ApiRequestProfile profile = profiles.get(index);
        return sendRequestWithProfileAsync(profile, apiKey, model, apiFormat, surface,
                        systemPrompt, userPrompt, maxTokens)
                .thenCompose(translated -> {
                    if (translated != null && !translated.isBlank()) {
                        lastSuccessfulProfile = profile;
                        return CompletableFuture.completedFuture(translated);
                    }
                    return sendRequestAsync(profiles, index + 1, apiKey, model, apiFormat, surface,
                            systemPrompt, userPrompt, maxTokens);
                });
    }

    private CompletableFuture<String> sendRequestWithProfileAsync(ApiRequestProfile profile, String apiKey,
            String model,
            String apiFormat, String surface,
            String systemPrompt, String userPrompt, int maxTokens) {
        if (profile == null || profile.endpointUrl() == null || profile.endpointUrl().isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        long startedAt = System.nanoTime();
        long startedAtMs = System.currentTimeMillis();
        HttpRequest request;
        try {
            request = buildRequest(profile, apiKey, model, systemPrompt, userPrompt, maxTokens);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApplyAsync(response -> handleTranslationResponse(response, startedAt, startedAtMs,
                        apiFormat, model, surface), STREAM_READER_EXECUTOR)
                .handle((translated, error) -> handleTranslationFailure(translated, error));
    }

    private String sendRequestWithProfileForProbe(ApiRequestProfile profile, String apiKey, String model,
            String systemPrompt, String userPrompt, int maxTokens) {
        try {
            return sendRequestWithProfileAsync(profile, apiKey, model, null, null,
                    systemPrompt, userPrompt, maxTokens).join();
        } catch (CompletionException e) {
            Throwable cause = unwrapCompletion(e);
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        }
    }

    private String handleTranslationResponse(HttpResponse<InputStream> response, long startedAt, long startedAtMs,
            String apiFormat, String model, String surface) {
        try {
            SimpleTranslateMod.getLogger().debug("Received DeepSeek response with status: {}",
                    response.statusCode());
            if (response.statusCode() == 200) {
                ParsedTranslationResponse parsed = parseResponse(response.body(), startedAt);
                String result = parsed.text();
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
                    recordTokenUsage(apiFormat, model, startedAtMs, surface, parsed.usage());
                    return result;
                }
                SimpleTranslateMod.getLogger().debug(
                        "Translation API returned no assistant text; skipping identical automatic retry");
                return null;
            }

            String errorBody = readErrorBody(response.body());
            if (isRetryableStatus(response.statusCode())) {
                throw new TranslationRequestQueue.RetryableTranslationException(
                        "HTTP " + response.statusCode() + (errorBody == null || errorBody.isBlank() ? "" : ": " + errorBody));
            }

            SimpleTranslateMod.getLogger().warn("DeepSeek API error: {} - {}",
                    response.statusCode(), errorBody);
            return null;
        } catch (IOException e) {
            throw new TranslationRequestQueue.RetryableTranslationException(
                    e.getMessage() == null ? "I/O error" : e.getMessage(), e);
        }
    }

    private String handleTranslationFailure(String translated, Throwable error) {
        if (error == null) {
            return translated;
        }
        Throwable cause = unwrapCompletion(error);
        if (cause instanceof TranslationRequestQueue.RetryableTranslationException retryable) {
            throw retryable;
        }
        if (cause instanceof IOException ioException) {
            throw new TranslationRequestQueue.RetryableTranslationException(
                    ioException.getMessage() == null ? "I/O error" : ioException.getMessage(), ioException);
        }
        if (cause instanceof InvalidApiResponseException invalid) {
            SimpleTranslateMod.getLogger().warn("Translation API returned an unusable response: {}",
                    invalid.getMessage());
            return null;
        }
        if (cause instanceof java.util.concurrent.CancellationException canceled) {
            throw canceled;
        }
        SimpleTranslateMod.getLogger().error("DeepSeek request failed: {}",
                cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
        return null;
    }

    private static Throwable unwrapCompletion(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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

    private String parseResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        if (responseBody.trim().startsWith("data:")) {
            return parseStreamingResponse(responseBody).text();
        }
        return parsePlainResponse(responseBody).text();
    }

    private ParsedTranslationResponse parseResponse(InputStream responseBody, long startedAt) throws IOException {
        if (responseBody == null) {
            return ParsedTranslationResponse.empty();
        }

        Future<ParsedTranslationResponse> readerTask =
                STREAM_READER_EXECUTOR.submit(() -> parseResponseBlocking(responseBody, startedAt));
        try {
            long remainingMs = Math.max(1L, MAX_STREAM_TOTAL_MS - elapsedMillis(startedAt));
            return readerTask.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            closeQuietly(responseBody);
            readerTask.cancel(true);
            SimpleTranslateMod.getLogger().warn("Translation stream exceeded {} ms; aborting this response",
                    MAX_STREAM_TOTAL_MS);
            return ParsedTranslationResponse.empty();
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

    private ParsedTranslationResponse parseResponseBlocking(InputStream responseBody, long startedAt) throws IOException {
        if (responseBody == null) {
            return ParsedTranslationResponse.empty();
        }

        StringBuilder raw = new StringBuilder();
        StringBuilder result = new StringBuilder();
        TokenUsage[] usageHolder = new TokenUsage[1];
        boolean sawData = false;
        long firstChunkMs = -1L;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                long elapsedMs = elapsedMillis(startedAt);
                if (elapsedMs > MAX_STREAM_TOTAL_MS) {
                    SimpleTranslateMod.getLogger().warn("Translation stream exceeded {} ms; aborting this response",
                            MAX_STREAM_TOTAL_MS);
                    return ParsedTranslationResponse.empty();
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

                appendStreamingChunk(result, json, usageHolder);
            }
        } catch (RuntimeException e) {
            SimpleTranslateMod.getLogger().error("Failed to parse streamed response", e);
            return ParsedTranslationResponse.empty();
        }

        if (sawData) {
            return new ParsedTranslationResponse(result.toString(), usageHolder[0]);
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

    private ParsedTranslationResponse parseStreamingResponse(String responseBody) {
        StringBuilder result = new StringBuilder();
        TokenUsage[] usageHolder = new TokenUsage[1];
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
                appendStreamingChunk(result, json, usageHolder);
            }
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to parse streamed response", e);
            return ParsedTranslationResponse.empty();
        }
        return new ParsedTranslationResponse(result.toString(), usageHolder[0]);
    }

    private ParsedTranslationResponse parsePlainResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return ParsedTranslationResponse.empty();
        }
        String trimmed = responseBody.trim();
        if (looksLikeHtml(trimmed)) {
            throw new InvalidApiResponseException(invalidApiResponseMessage(trimmed));
        }
        try {
            JsonObject response = JsonParser.parseString(trimmed).getAsJsonObject();
            String raw = extractAssistantContent(response);
            if (raw != null) {
                return new ParsedTranslationResponse(raw, extractUsageFromResponse(response));
            }
            throw new InvalidApiResponseException("API response did not contain assistant text output");
        } catch (Exception e) {
            if (e instanceof InvalidApiResponseException invalid) {
                throw invalid;
            }
            throw new InvalidApiResponseException(invalidApiResponseMessage(trimmed), e);
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

    private void appendStreamingChunk(StringBuilder result, String json, TokenUsage[] usageHolder) {
        JsonObject chunk = JsonParser.parseString(json).getAsJsonObject();
        TokenUsage usage = extractUsageFromResponse(chunk);
        if (usage != null) {
            usageHolder[0] = usage;
        }
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

    private void recordTokenUsage(String apiFormat, String model, long requestStartMs, String surface,
                                  TokenUsage usage) {
        if (!ModConfig.TOKEN_MONITOR_ENABLED.get() || usage == null
                || apiFormat == null || apiFormat.isBlank()) {
            return;
        }
        try {
            long elapsedMs = System.currentTimeMillis() - requestStartMs;
            int prompt = Math.max(0, usage.promptTokens());
            int completion = Math.max(0, usage.completionTokens());
            int total = usage.totalTokens() > 0 ? usage.totalTokens() : prompt + completion;
            if (prompt <= 0 && completion <= 0 && total <= 0) {
                return;
            }
            TokenUsageMonitor.record(new TokenUsage(
                    apiFormat, model, prompt, completion, total,
                    elapsedMs, System.currentTimeMillis(), surface));
        } catch (Exception ignored) {
        }
    }

    private TokenUsage extractUsageFromResponse(JsonObject response) {
        if (response == null) {
            return null;
        }
        if (response.has("usage") && response.get("usage").isJsonObject()) {
            TokenUsage usage = extractUsage(response.getAsJsonObject("usage"));
            if (usage != null) {
                return usage;
            }
        }
        if (response.has("usageMetadata") && response.get("usageMetadata").isJsonObject()) {
            TokenUsage usage = extractUsage(response.getAsJsonObject("usageMetadata"));
            if (usage != null) {
                return usage;
            }
        }
        return extractUsage(response);
    }

    private TokenUsage extractUsage(JsonObject usage) {
        if (usage == null) {
            return null;
        }
        try {
            int prompt = firstInt(usage,
                    "prompt_tokens", "promptTokens", "input_tokens", "inputTokens",
                    "promptTokenCount", "inputTokenCount");
            int completion = firstInt(usage,
                    "completion_tokens", "completionTokens", "output_tokens", "outputTokens",
                    "candidatesTokenCount", "completionTokenCount", "outputTokenCount");
            int thoughts = firstInt(usage, "thoughtsTokenCount", "reasoning_tokens", "reasoningTokens");
            if (thoughts > 0) {
                completion += thoughts;
            }
            int total = firstInt(usage, "total_tokens", "totalTokens", "totalTokenCount");
            if (total <= 0) {
                total = prompt + completion;
            }
            if (prompt <= 0 && completion <= 0 && total <= 0) {
                return null;
            }
            return new TokenUsage("", "", prompt, completion, total, 0, 0, "");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int firstInt(JsonObject object, String... names) {
        if (object == null || names == null) {
            return 0;
        }
        for (String name : names) {
            if (name == null || !object.has(name) || object.get(name).isJsonNull()) {
                continue;
            }
            try {
                return Math.max(0, object.get(name).getAsInt());
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private TranslationDiagnostics.ApiDetection probeApi(List<ApiRequestProfile> profiles, String systemPrompt,
            String userPrompt) {
        String apiKey = sanitizeApiKey(ModConfig.DEEPSEEK_API_KEY.get());
        ModConfig.ApiFormat apiFormat = ModConfig.API_FORMAT.get();
        String model = apiFormat == ModConfig.ApiFormat.DEEPSEEK_CHAT
                ? ModConfig.normalizeDeepSeekModelId(ModConfig.DEEPSEEK_MODEL.get())
                : ModConfig.normalizeModelId(ModConfig.DEEPSEEK_MODEL.get());
        String lastMessage = apiFormat.getDisplayName() + " endpoint did not respond successfully";
        for (ApiRequestProfile profile : profiles) {
            try {
                String result = sendRequestWithProfileForProbe(profile, apiKey, model, systemPrompt, userPrompt, 64);
                if (result != null && !result.isBlank()) {
                    return new TranslationDiagnostics.ApiDetection(true, profile.kind().name(),
                            profile.kind() == EndpointKind.GEMINI_GENERATE_CONTENT ? "API_KEY" : "BEARER",
                            profile.endpointUrl(), 200, "API responded successfully");
                }
            } catch (TranslationRequestQueue.RetryableTranslationException e) {
                lastMessage = e.getMessage() == null ? "Retryable API detection failure" : e.getMessage();
            }
        }
        return new TranslationDiagnostics.ApiDetection(false, "", "",
                ModConfig.normalizeApiUrl(ModConfig.DEEPSEEK_API_URL.get()),
                0, lastMessage);
    }

    private TranslationDiagnostics.ModelAccess probeModelAccess(String apiUrl, String apiKey, String modelId,
            ModConfig.ApiFormat apiFormat) {
        List<ApiRequestProfile> profiles = buildRequestProfiles(apiUrl, apiFormat, modelId);
        String lastMessage = "No compatible " + apiFormat.getDisplayName() + " endpoint responded";
        int lastStatus = 0;
        String probeSystemPrompt = JsonPassthroughPrompts.buildSystemPrompt(
                "en_us", ModConfig.TARGET_LANGUAGE.get(), List.of(), "manager.model_probe");
        String probeUserPrompt = "[{\"text\":\"Hello\"}]";

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

                HttpResponse<InputStream> response = httpClient.sendAsync(builder.build(),
                        HttpResponse.BodyHandlers.ofInputStream()).join();
                lastStatus = response.statusCode();
                String body = readErrorBody(response.body());
                if (response.statusCode() == 200) {
                    String translated = parseResponse(body);
                    if (isValidModelAccessProbeResponse(translated)) {
                        lastSuccessfulProfile = profile;
                        return new TranslationDiagnostics.ModelAccess(true, modelId, response.statusCode(),
                                "Model verified");
                    }
                    lastMessage = "Model responded but did not follow the translation protocol";
                    continue;
                }
                lastMessage = body == null || body.isBlank() ? ("HTTP " + response.statusCode()) : compactErrorBody(body);
            } catch (InvalidApiResponseException e) {
                lastMessage = e.getMessage() == null ? "Invalid API response" : e.getMessage();
            } catch (CompletionException e) {
                Throwable cause = unwrapCompletion(e);
                lastMessage = cause.getMessage() == null ? "Model verification failed" : cause.getMessage();
            } catch (RuntimeException e) {
                lastMessage = e.getMessage() == null ? "Model verification failed" : e.getMessage();
            }
        }

        return new TranslationDiagnostics.ModelAccess(false, modelId, lastStatus, lastMessage);
    }

    private static boolean isValidModelAccessProbeResponse(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            String trimmed = value.trim();
            if (trimmed.startsWith("```")) {
                int firstNewline = trimmed.indexOf('\n');
                int lastFence = trimmed.lastIndexOf("```");
                if (firstNewline >= 0 && lastFence > firstNewline) {
                    trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
                }
            }
            return JsonParser.parseString(trimmed).isJsonArray()
                    && JsonParser.parseString(trimmed).getAsJsonArray().size() == 1;
        } catch (Exception ignored) {
            return false;
        }
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
                    EndpointKind.CHAT_COMPLETIONS,
                    isOfficialDeepSeekEndpoint(base) || isDeepSeekReasoningModel(model)));
            case OPENAI_RESPONSES -> List.of(new ApiRequestProfile(base, resolveResponsesEndpoint(base),
                    EndpointKind.RESPONSES, false));
            case ANTHROPIC_MESSAGES -> List.of(new ApiRequestProfile(base, resolveAnthropicMessagesEndpoint(base),
                    EndpointKind.ANTHROPIC_MESSAGES, false));
            case GEMINI_GENERATE_CONTENT -> List.of(new ApiRequestProfile(base,
                    resolveGeminiGenerateContentEndpoint(base, model),
                    EndpointKind.GEMINI_GENERATE_CONTENT, false));
        };
    }

    private static boolean isOfficialDeepSeekEndpoint(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return false;
        }
        try {
            String host = URI.create(apiUrl).getHost();
            if (host == null) {
                return false;
            }
            String normalized = host.toLowerCase(java.util.Locale.ROOT);
            return normalized.equals("deepseek.com") || normalized.endsWith(".deepseek.com");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isDeepSeekReasoningModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String normalized = model.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("deepseek-")
                && (normalized.contains("reasoner")
                || normalized.contains("r1")
                || normalized.contains("v4"));
    }

    private static boolean isOpenAiReasoningModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String normalized = model.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("o1")
                || normalized.startsWith("o3")
                || normalized.startsWith("o4")
                || normalized.startsWith("gpt-5");
    }

    private static String openAiReasoningEffort(String model, boolean enabled) {
        if (enabled) {
            return "medium";
        }
        String normalized = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("gpt-5") ? "minimal" : "low";
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
            case GEMINI_GENERATE_CONTENT -> buildGeminiGenerateContentBody(model, systemPrompt, userPrompt, maxTokens);
        };
    }

    private JsonObject buildChatCompletionsBody(ApiRequestProfile profile, String model, String systemPrompt,
            String userPrompt, int maxTokens, boolean streaming) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("stream", streaming);
        boolean thinkingEnabled = profile.supportsThinking() && ModConfig.DEEPSEEK_THINKING_ENABLED.get();
        boolean openAiReasoning = !profile.supportsThinking() && isOpenAiReasoningModel(model);
        int outputTokens = Math.max(MIN_TRANSLATION_OUTPUT_TOKENS,
                Math.min(maxTokens, MAX_TRANSLATION_OUTPUT_TOKENS));
        if (thinkingEnabled) {
            outputTokens = Math.max(outputTokens, 4096);
        } else if (openAiReasoning) {
            outputTokens = Math.max(outputTokens, 2048);
        }
        requestBody.addProperty("max_tokens", Math.min(outputTokens, MAX_TRANSLATION_OUTPUT_TOKENS));

        if (!thinkingEnabled && !openAiReasoning) {
            requestBody.addProperty("temperature", 0.0);
        }

        if (profile.supportsThinking()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", thinkingEnabled ? "enabled" : "disabled");
            requestBody.add("thinking", thinking);
        } else if (openAiReasoning) {
            requestBody.addProperty("reasoning_effort",
                    openAiReasoningEffort(model, ModConfig.DEEPSEEK_THINKING_ENABLED.get()));
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
        if (streaming) {
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            requestBody.add("stream_options", streamOptions);
        }
        return requestBody;
    }

    private JsonObject buildResponsesBody(String model, String systemPrompt, String userPrompt, int maxTokens,
            boolean streaming) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("stream", streaming);
        boolean reasoningModel = isOpenAiReasoningModel(model);
        int outputTokens = Math.max(MIN_TRANSLATION_OUTPUT_TOKENS,
                Math.min(maxTokens, MAX_TRANSLATION_OUTPUT_TOKENS));
        if (reasoningModel) {
            outputTokens = Math.max(outputTokens, 2048);
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort",
                    openAiReasoningEffort(model, ModConfig.DEEPSEEK_THINKING_ENABLED.get()));
            requestBody.add("reasoning", reasoning);
        } else {
            requestBody.addProperty("temperature", 0.0);
        }
        requestBody.addProperty("max_output_tokens", Math.min(outputTokens, MAX_TRANSLATION_OUTPUT_TOKENS));
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
        boolean thinkingEnabled = ModConfig.DEEPSEEK_THINKING_ENABLED.get();
        int outputTokens = Math.max(MIN_TRANSLATION_OUTPUT_TOKENS,
                Math.min(maxTokens, MAX_TRANSLATION_OUTPUT_TOKENS));
        if (thinkingEnabled) {
            outputTokens = Math.max(outputTokens, 2048);
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            thinking.addProperty("budget_tokens", 1024);
            requestBody.add("thinking", thinking);
        } else {
            requestBody.addProperty("temperature", 0.0);
        }
        requestBody.addProperty("max_tokens", Math.min(outputTokens, MAX_TRANSLATION_OUTPUT_TOKENS));
        requestBody.addProperty("system", systemPrompt);

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);
        requestBody.add("messages", messages);
        return requestBody;
    }

    private JsonObject buildGeminiGenerateContentBody(String model, String systemPrompt, String userPrompt,
            int maxTokens) {
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
        generationConfig.addProperty("temperature", 0.0);
        int outputTokens = Math.max(MIN_TRANSLATION_OUTPUT_TOKENS,
                Math.min(maxTokens, MAX_TRANSLATION_OUTPUT_TOKENS));
        if (ModConfig.DEEPSEEK_THINKING_ENABLED.get()) {
            outputTokens = Math.max(outputTokens, 2048);
        }
        generationConfig.addProperty("maxOutputTokens", Math.min(outputTokens, MAX_TRANSLATION_OUTPUT_TOKENS));
        JsonObject thinkingConfig = new JsonObject();
        if (ModConfig.DEEPSEEK_THINKING_ENABLED.get()) {
            thinkingConfig.addProperty("thinkingBudget", 1024);
            generationConfig.add("thinkingConfig", thinkingConfig);
        } else if (model != null && model.toLowerCase(java.util.Locale.ROOT).contains("flash")) {
            thinkingConfig.addProperty("thinkingBudget", 0);
            generationConfig.add("thinkingConfig", thinkingConfig);
        }
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

    private record ParsedTranslationResponse(String text, TokenUsage usage) {
        private static ParsedTranslationResponse empty() {
            return new ParsedTranslationResponse(null, null);
        }
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

    public boolean isReady() {
        String apiKey = sanitizeApiKey(ModConfig.DEEPSEEK_API_KEY.get());
        return apiKey != null && !apiKey.isEmpty();
    }

    public String getServiceName() {
        return SERVICE_NAME;
    }
}
