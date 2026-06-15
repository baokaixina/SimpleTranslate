package com.yourname.simpletranslate.translation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.util.ModelOutputSanitizer;
import com.yourname.simpletranslate.util.TranslationCacheKeys;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class VisionOcrTranslationService implements OcrTranslationService {
    private static final int MAX_OCR_OUTPUT_TOKENS = 2048;
    private static final String ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final Gson gson = new Gson();

    private enum EndpointKind {
        CHAT_COMPLETIONS,
        RESPONSES,
        ANTHROPIC_MESSAGES,
        GEMINI_GENERATE_CONTENT
    }

    @Override
    public CompletableFuture<OcrResult> translateImage(byte[] pngBytes, String imageHash,
                                                       String sourceLanguage, String targetLanguage) {
        if (pngBytes == null || pngBytes.length == 0) {
            return CompletableFuture.completedFuture(OcrResult.failure("No screenshot data"));
        }
        ActiveProfile profile = activeProfile();
        if (!profile.ready()) {
            return CompletableFuture.completedFuture(OcrResult.failure(profile.errorMessage()));
        }
        String source = sourceLanguage == null || sourceLanguage.isBlank() ? "auto" : sourceLanguage;
        String target = targetLanguage == null || targetLanguage.isBlank() ? "zh_cn" : targetLanguage;
        String prompt = buildOcrPrompt(source, target);
        String base64 = Base64.getEncoder().encodeToString(pngBytes);
        String queueKey = "ocr:" + TranslationCacheKeys.hashSource(profile.queueIdentity()
                + "\u0001" + prompt + "\u0001" + (imageHash == null ? "" : imageHash)
                + "\u0001" + base64.length());
        return TranslationRequestQueue.submit(
                        queueKey,
                        SURFACE,
                        TranslationRequestQueue.Priority.INTERACTIVE,
                        2,
                        () -> sendOcrRequestBlocking(profile, prompt, base64))
                .thenApply(raw -> parseOcrResult(raw));
    }

    @Override
    public CompletableFuture<OcrResult> verifyAccess() {
        return translateImage(Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64),
                "one-pixel-vision-probe", "auto", ModConfig.TARGET_LANGUAGE.get());
    }

    @Override
    public boolean isReady() {
        return activeProfile().ready();
    }

    @Override
    public String describeActiveProfile() {
        ActiveProfile profile = activeProfile();
        if (!profile.ready()) {
            return profile.errorMessage();
        }
        return profile.format().getDisplayName() + " / " + profile.model();
    }

    public static String buildOcrPrompt(String sourceLanguage, String targetLanguage) {
        String target = targetLanguage == null || targetLanguage.isBlank() ? "zh_cn" : targetLanguage;
        String source = sourceLanguage == null || sourceLanguage.isBlank() ? "auto" : sourceLanguage;
        return """
                You are an OCR and translation engine for Minecraft screenshots.
                Read only visible text inside the image, in natural reading order.
                Ignore Minecraft world objects, cursor/crosshair, panels, borders, and non-text graphics.
                Source language: %s. Target language: %s.
                Return strict JSON only, with exactly:
                {"sourceText":"...","translationText":"..."}
                If the image contains no readable text, return {"sourceText":"","translationText":""}.
                Do not add markdown, comments, explanations, or extra fields.
                """.formatted(source, target);
    }

    public static JsonObject buildRequestBodyForTest(ModConfig.ApiFormat format, String model,
                                                     String prompt, String base64Png) {
        EndpointKind kind = endpointKind(format);
        return buildRequestBody(kind, model == null || model.isBlank() ? format.getDefaultModel() : model,
                prompt == null ? "" : prompt, base64Png == null ? "" : base64Png);
    }

    public static OcrResult parseOcrResultForTest(String raw) {
        return parseOcrResult(raw);
    }

    private String sendOcrRequestBlocking(ActiveProfile profile, String prompt, String base64Png) {
        try {
            JsonObject body = buildRequestBody(profile.kind(), profile.model(), prompt, base64Png);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(withApiKeyQuery(profile)))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8));
            applyAuthHeader(builder, profile);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                return extractAssistantContent(response.body());
            }
            String message = compactError(response.body());
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new TranslationRequestQueue.RetryableTranslationException(
                        "HTTP " + response.statusCode() + (message.isBlank() ? "" : ": " + message));
            }
            SimpleTranslateMod.getLogger().warn("OCR vision API error status={} message={}",
                    response.statusCode(), message);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (IOException e) {
            throw new TranslationRequestQueue.RetryableTranslationException(
                    e.getMessage() == null ? "OCR I/O error" : e.getMessage(), e);
        } catch (TranslationRequestQueue.RetryableTranslationException e) {
            throw e;
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().warn("OCR vision request failed: {}",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return "";
        }
    }

    private static JsonObject buildRequestBody(EndpointKind kind, String model, String prompt, String base64Png) {
        return switch (kind) {
            case CHAT_COMPLETIONS -> buildChatCompletionsBody(model, prompt, base64Png);
            case RESPONSES -> buildResponsesBody(model, prompt, base64Png);
            case ANTHROPIC_MESSAGES -> buildAnthropicMessagesBody(model, prompt, base64Png);
            case GEMINI_GENERATE_CONTENT -> buildGeminiGenerateContentBody(prompt, base64Png);
        };
    }

    private static JsonObject buildChatCompletionsBody(String model, String prompt, String base64Png) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("temperature", 0);
        root.addProperty("max_tokens", MAX_OCR_OUTPUT_TOKENS);

        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", prompt);
        content.add(text);
        JsonObject image = new JsonObject();
        image.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUrl(base64Png));
        imageUrl.addProperty("detail", "high");
        image.add("image_url", imageUrl);
        content.add(image);
        user.add("content", content);
        messages.add(user);
        root.add("messages", messages);
        return root;
    }

    private static JsonObject buildResponsesBody(String model, String prompt, String base64Png) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("max_output_tokens", MAX_OCR_OUTPUT_TOKENS);

        JsonArray input = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "input_text");
        text.addProperty("text", prompt);
        content.add(text);
        JsonObject image = new JsonObject();
        image.addProperty("type", "input_image");
        image.addProperty("image_url", dataUrl(base64Png));
        image.addProperty("detail", "high");
        content.add(image);
        user.add("content", content);
        input.add(user);
        root.add("input", input);
        return root;
    }

    private static JsonObject buildAnthropicMessagesBody(String model, String prompt, String base64Png) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("max_tokens", MAX_OCR_OUTPUT_TOKENS);
        root.addProperty("temperature", 0);
        root.addProperty("system", "Return strict JSON only.");

        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject image = new JsonObject();
        image.addProperty("type", "image");
        JsonObject source = new JsonObject();
        source.addProperty("type", "base64");
        source.addProperty("media_type", "image/png");
        source.addProperty("data", base64Png);
        image.add("source", source);
        content.add(image);
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", prompt);
        content.add(text);
        user.add("content", content);
        messages.add(user);
        root.add("messages", messages);
        return root;
    }

    private static JsonObject buildGeminiGenerateContentBody(String prompt, String base64Png) {
        JsonObject root = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject image = new JsonObject();
        JsonObject inlineData = new JsonObject();
        inlineData.addProperty("mimeType", "image/png");
        inlineData.addProperty("data", base64Png);
        image.add("inlineData", inlineData);
        parts.add(image);
        JsonObject text = new JsonObject();
        text.addProperty("text", prompt);
        parts.add(text);
        content.add("parts", parts);
        contents.add(content);
        root.add("contents", contents);
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0);
        generationConfig.addProperty("maxOutputTokens", MAX_OCR_OUTPUT_TOKENS);
        root.add("generationConfig", generationConfig);
        return root;
    }

    private ActiveProfile activeProfile() {
        boolean reuse = ModConfig.OCR_USE_TRANSLATION_MODEL.get();
        ModConfig.ApiFormat format = reuse ? ModConfig.API_FORMAT.get() : ModConfig.OCR_API_FORMAT.get();
        if (!isVisionFormat(format)) {
            return ActiveProfile.error(format, "OCR model format does not support image input");
        }
        String key = sanitizeApiKey(reuse ? ModConfig.DEEPSEEK_API_KEY.get() : ModConfig.OCR_API_KEY.get());
        if (key.isBlank()) {
            return ActiveProfile.error(format, "OCR API key not configured");
        }
        String url = ModConfig.normalizeApiUrl(reuse ? ModConfig.DEEPSEEK_API_URL.get() : ModConfig.OCR_API_URL.get());
        String model = reuse
                ? normalizedTranslationModel(format, ModConfig.DEEPSEEK_MODEL.get())
                : normalizedOcrModel(format, ModConfig.OCR_MODEL.get());
        if (model.isBlank()) {
            return ActiveProfile.error(format, "OCR model not configured");
        }
        EndpointKind kind = endpointKind(format);
        String endpoint = resolveEndpoint(url, kind, model);
        return new ActiveProfile(true, "", format, kind, url, endpoint, key, model);
    }

    private static boolean isVisionFormat(ModConfig.ApiFormat format) {
        return format == ModConfig.ApiFormat.OPENAI_RESPONSES
                || format == ModConfig.ApiFormat.OPENAI_CHAT_COMPAT
                || format == ModConfig.ApiFormat.ANTHROPIC_MESSAGES
                || format == ModConfig.ApiFormat.GEMINI_GENERATE_CONTENT;
    }

    private static EndpointKind endpointKind(ModConfig.ApiFormat format) {
        if (format == ModConfig.ApiFormat.OPENAI_RESPONSES) {
            return EndpointKind.RESPONSES;
        }
        if (format == ModConfig.ApiFormat.ANTHROPIC_MESSAGES) {
            return EndpointKind.ANTHROPIC_MESSAGES;
        }
        if (format == ModConfig.ApiFormat.GEMINI_GENERATE_CONTENT) {
            return EndpointKind.GEMINI_GENERATE_CONTENT;
        }
        return EndpointKind.CHAT_COMPLETIONS;
    }

    private static String normalizedTranslationModel(ModConfig.ApiFormat format, String model) {
        if (format == ModConfig.ApiFormat.DEEPSEEK_CHAT) {
            return "";
        }
        return model == null || model.isBlank() ? format.getDefaultModel() : model.trim();
    }

    private static String normalizedOcrModel(ModConfig.ApiFormat format, String model) {
        return model == null || model.isBlank() ? format.getDefaultModel() : model.trim();
    }

    private static String resolveEndpoint(String apiUrl, EndpointKind kind, String model) {
        String normalized = ModConfig.normalizeApiUrl(apiUrl);
        return switch (kind) {
            case CHAT_COMPLETIONS -> resolveChatCompletionsEndpoint(normalized);
            case RESPONSES -> resolveResponsesEndpoint(normalized);
            case ANTHROPIC_MESSAGES -> resolveAnthropicMessagesEndpoint(normalized);
            case GEMINI_GENERATE_CONTENT -> resolveGeminiGenerateContentEndpoint(normalized, model);
        };
    }

    private static String resolveChatCompletionsEndpoint(String normalized) {
        if (normalized.contains("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/v1") || normalized.endsWith("/v1/")) {
            return normalized.replaceAll("/+$", "") + "/chat/completions";
        }
        return normalized + "/chat/completions";
    }

    private static String resolveResponsesEndpoint(String normalized) {
        if (normalized.contains("/responses")) {
            return normalized;
        }
        if (normalized.endsWith("/v1") || normalized.endsWith("/v1/")) {
            return normalized.replaceAll("/+$", "") + "/responses";
        }
        return normalized + "/responses";
    }

    private static String resolveAnthropicMessagesEndpoint(String normalized) {
        if (normalized.contains("/messages")) {
            return normalized;
        }
        if (normalized.endsWith("/v1") || normalized.endsWith("/v1/")) {
            return normalized.replaceAll("/+$", "") + "/messages";
        }
        return normalized + "/v1/messages";
    }

    private static String resolveGeminiGenerateContentEndpoint(String normalized, String model) {
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

    private void applyAuthHeader(HttpRequest.Builder builder, ActiveProfile profile) {
        if (profile.kind() == EndpointKind.ANTHROPIC_MESSAGES) {
            builder.header("x-api-key", profile.apiKey());
            builder.header("anthropic-version", "2023-06-01");
        } else if (profile.kind() != EndpointKind.GEMINI_GENERATE_CONTENT) {
            builder.header("Authorization", "Bearer " + profile.apiKey());
        }
    }

    private String withApiKeyQuery(ActiveProfile profile) {
        if (profile.kind() != EndpointKind.GEMINI_GENERATE_CONTENT || profile.endpointUrl().contains("key=")) {
            return profile.endpointUrl();
        }
        return profile.endpointUrl() + (profile.endpointUrl().contains("?") ? "&" : "?")
                + "key=" + URLEncoder.encode(profile.apiKey(), StandardCharsets.UTF_8);
    }

    private static String extractAssistantContent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
            String extracted = extractAssistantContent(response);
            return extracted == null ? "" : extracted;
        } catch (Exception ignored) {
            return responseBody;
        }
    }

    private static String extractAssistantContent(JsonObject response) {
        if (response == null) {
            return "";
        }
        if (response.has("choices") && response.get("choices").isJsonArray()) {
            JsonArray choices = response.getAsJsonArray("choices");
            if (!choices.isEmpty() && choices.get(0).isJsonObject()) {
                JsonObject choice = choices.get(0).getAsJsonObject();
                if (choice.has("message") && choice.get("message").isJsonObject()) {
                    JsonObject message = choice.getAsJsonObject("message");
                    String content = textFromJsonContent(message.get("content"));
                    if (!content.isBlank()) {
                        return content;
                    }
                }
            }
        }
        if (response.has("output_text") && !response.get("output_text").isJsonNull()) {
            return response.get("output_text").getAsString();
        }
        if (response.has("output") && response.get("output").isJsonArray()) {
            String text = textFromOutputArray(response.getAsJsonArray("output"));
            if (!text.isBlank()) {
                return text;
            }
        }
        if (response.has("content") && response.get("content").isJsonArray()) {
            String text = textFromJsonContent(response.get("content"));
            if (!text.isBlank()) {
                return text;
            }
        }
        if (response.has("candidates") && response.get("candidates").isJsonArray()) {
            JsonArray candidates = response.getAsJsonArray("candidates");
            if (!candidates.isEmpty() && candidates.get(0).isJsonObject()) {
                JsonObject candidate = candidates.get(0).getAsJsonObject();
                if (candidate.has("content") && candidate.get("content").isJsonObject()) {
                    JsonObject content = candidate.getAsJsonObject("content");
                    if (content.has("parts")) {
                        return textFromJsonContent(content.get("parts"));
                    }
                }
            }
        }
        return "";
    }

    private static String textFromOutputArray(JsonArray output) {
        StringBuilder builder = new StringBuilder();
        for (JsonElement element : output) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            if (object.has("content")) {
                String content = textFromJsonContent(object.get("content"));
                if (!content.isBlank()) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(content);
                }
            }
        }
        return builder.toString();
    }

    private static String textFromJsonContent(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (!element.isJsonArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject part = item.getAsJsonObject();
            if (part.has("text") && !part.get("text").isJsonNull()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(part.get("text").getAsString());
            }
        }
        return builder.toString();
    }

    private static OcrResult parseOcrResult(String raw) {
        String cleaned = stripFences(ModelOutputSanitizer.sanitize(raw));
        if (cleaned == null || cleaned.isBlank()) {
            return OcrResult.success("", "");
        }
        String json = objectSlice(cleaned);
        if (!json.isBlank()) {
            try {
                JsonObject object = JsonParser.parseString(json).getAsJsonObject();
                String source = stringField(object, "sourceText");
                String translation = stringField(object, "translationText");
                return OcrResult.success(source, translation);
            } catch (Exception ignored) {
                // Fall through to non-JSON recovery.
            }
        }
        return OcrResult.success("", cleaned);
    }

    private static String stripFences(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("(?is)^```(?:json|text)?\\s*", "");
            text = text.replaceFirst("(?is)\\s*```$", "");
        }
        return text.trim();
    }

    private static String objectSlice(String value) {
        int start = value == null ? -1 : value.indexOf('{');
        int end = value == null ? -1 : value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return value.substring(start, end + 1);
    }

    private static String stringField(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String sanitizeApiKey(String apiKey) {
        if (apiKey == null) {
            return "";
        }
        return apiKey.trim();
    }

    private static String dataUrl(String base64Png) {
        return "data:image/png;base64," + (base64Png == null ? "" : base64Png);
    }

    private static String compactError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String compact = body.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() > 240 ? compact.substring(0, 237) + "..." : compact;
    }

    private record ActiveProfile(
            boolean ready,
            String errorMessage,
            ModConfig.ApiFormat format,
            EndpointKind kind,
            String endpointBase,
            String endpointUrl,
            String apiKey,
            String model) {
        static ActiveProfile error(ModConfig.ApiFormat format, String message) {
            return new ActiveProfile(false, message == null ? "OCR not configured" : message,
                    format == null ? ModConfig.ApiFormat.OPENAI_RESPONSES : format,
                    EndpointKind.RESPONSES, "", "", "", "");
        }

        String queueIdentity() {
            return (format == null ? "" : format.name().toLowerCase(Locale.ROOT))
                    + "|" + endpointBase + "|" + endpointUrl + "|" + model;
        }
    }
}
