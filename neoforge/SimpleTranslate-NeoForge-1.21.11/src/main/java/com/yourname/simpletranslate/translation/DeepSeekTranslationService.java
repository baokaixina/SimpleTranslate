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
    private static final int MAX_CONCURRENT_REQUESTS = 2;
    private static final int MAX_REQUEST_ATTEMPTS = 3;
    private static final int MIN_TRANSLATION_OUTPUT_TOKENS = 128;
    private static final int MAX_TRANSLATION_OUTPUT_TOKENS = 8192;
    private static final long WARN_SLOW_FIRST_CHUNK_MS = 8000L;
    private static final long WARN_SLOW_TOTAL_MS = 30000L;
    private static final long MAX_STREAM_TOTAL_MS = 45000L;
    private static final Pattern DIRECT_SURFACE_PATTERN = Pattern.compile("<st-doc\\b[^>]*\\bsurface=\"([^\"]+)\"");
    private static final Pattern MAPPING_SURFACE_PATTERN = Pattern.compile("\"surface\"\\s*:\\s*\"([^\"]+)\"");
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

    private enum RequestMode {
        PLAIN_TEXT,
        PRESERVE_MARKERS,
        MAPPING_JSON,
        DIRECT_FORMATTED
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
    public CompletableFuture<String> translateFormattedDocument(String document, String sourceLanguage,
            String targetLanguage, List<TermHint> termHints) {
        if (!isReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("DeepSeek API key not configured"));
        }
        String systemPrompt = buildSystemPrompt(sourceLanguage, targetLanguage, termHints,
                RequestMode.DIRECT_FORMATTED);
        return sendRequest(systemPrompt, document, estimateMaxTokens(document));
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
        String endpoint = ModConfig.normalizeApiUrl(apiUrl);
        return CompletableFuture.supplyAsync(() -> probeModelAccess(endpoint, key, model, format), DETECTION_EXECUTOR);
    }

    private String buildSystemPrompt(String sourceLanguage, String targetLanguage, List<TermHint> termHints,
            RequestMode mode) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a professional translator for Minecraft game content. ");
        String sourceCode = TranslationTextDetector.canonicalLanguageCode(sourceLanguage);
        if ("auto".equals(sourceCode)) {
            prompt.append("Auto-detect the source language and translate the following text to ");
        } else {
            prompt.append("Translate the following text from ").append(getLanguageName(sourceLanguage)).append(" to ");
        }
        prompt.append(getLanguageName(targetLanguage)).append(". ");
        prompt.append("Minecraft formatting, colors, styles, and events are restored by the client code. ");
        prompt.append("Only output the translated text without any explanation or additional content. ");
        prompt.append("Do not echo the source text or produce bilingual output unless the source itself must stay unchanged. ");
        prompt.append("For Minecraft UI and item tooltip text, translate every visible natural-language word or phrase into the target language; do not append, repeat, or keep the source beside the translation. ");
        prompt.append("Do not leave UI labels such as Abbreviations, Hover on their names, Objectives, Enemies, Mana, Damage, Ability, Used, or fullwidth Latin status text unchanged. ");
        prompt.append("Keep only player names, namespace IDs, commands, formatting codes, numbers, placeholders, and symbols unchanged when they are not natural-language text. ");
        prompt.append("Use fluent game-localization wording in the target language. Mechanics should be short, precise, and natural; lore should read naturally. ");
        prompt.append("Stay faithful to the source facts: do not invent lore, targets, triggers, ranges, or effects. ");
        prompt.append("Keep important item type nouns explicit in names: translate Charm and Amulet as 护符, Staff as 法杖, Blueprint as 蓝图, Ballista as 弩炮, Guardian/Warden as 守卫, and do not compress them away. ");
        prompt.append("Translate natural-language text inside existing brackets while preserving the bracket characters and count, for example [Root] -> [缠绕], [Burn] -> [燃烧], [Phase] -> [相位], [Click to TP] -> [点击传送], and [Right-Click] -> [右键]. ");
        prompt.append("In tooltip mechanics, a leading phrase like 'For 10s,' is a duration ('持续 10s'), not a range. Phrases like 'every 0.8s' are intervals. ");
        prompt.append("In game status text, 'without [Status]' means the target does not currently have that status; never translate it as 'will not trigger [Status]'. ");
        prompt.append("Do not add quotation marks, brackets, punctuation, or explanatory wording unless they already exist in the source text or are represented by a frozen placeholder. ");
        prompt.append("If the text contains player names or coordinates, keep them unchanged. ");
        if (mode == RequestMode.PRESERVE_MARKERS) {
            prompt.append("Never modify control markers/tokens such as §§§, @@@F<number>@@@, @@@CTX<number>@@@, @@@TTS<number>@@@, @@@S<number>@@@, @@@S<signIndex>L<lineIndex>@@@, @@@S_END@@@, ");
            prompt.append("§§§PAGE§§§, §§§TITLE§§§, or bracketed marker tags. ");
            prompt.append("@@@F<number>@@@ markers are frozen style placeholders; keep every one exactly. ");
            prompt.append("Keep marker order/count and line breaks unchanged. ");
            prompt.append("When @@@TTS<number>@@@ markers are present, preserve every marker exactly, translate only the text after each marker until the next marker, and never merge, remove, reorder, or move text across those markers. ");
        }
        if (mode == RequestMode.MAPPING_JSON) {
            prompt.append("When the user prompt is a structured mapping document, output JSON only and do not wrap the answer in markdown or code fences. ");
            prompt.append("The JSON shape must be {\"version\":\"mapping-v1\",\"jobId\":\"...\",\"translations\":[{\"id\":\"...\",\"translation\":\"...\"}]}. ");
            prompt.append("Return one translation entry for every unit id from the input. Do not change ids or order. ");
            prompt.append("@@@F<number>@@@ markers are style placeholders owned by the client; every marker in a unit must appear exactly once in that unit's translation. ");
            prompt.append("For layoutMode=flexible-tooltip, each unit may be a semantic style block or an inline styled token. Translate each unit naturally as game localization, not word-by-word. You may change word order and line breaks, but every @@@F marker inside that same unit must appear exactly once. ");
            prompt.append("Do not translate a semantic block by following the source's visual line breaks; translate the block's meaning as a complete title, lore paragraph, operation hint, stat row, or mechanic sentence according to its role/tokenMask. ");
            prompt.append("For layoutMode=fixed-layout, preserve slot boundaries and line structure as instructed by the unit metadata. ");
        }
        if (mode == RequestMode.DIRECT_FORMATTED) {
            prompt.append("The user prompt is a reversible SimpleTranslate formatted document, optionally preceded by <st-context>, <st-plain-context>, and <st-normalized-plain-context> sections. ");
            prompt.append("If the user prompt is wrapped in <st-batch>, return one <st-batch> with the same root attributes and every <st-item id=\"...\"> exactly once; each item's content must follow the same <st-doc> rules independently. ");
            prompt.append("Never move text, tags, or ids between <st-item> entries. ");
            prompt.append("Use <st-context> as surrounding context for signs, books, chat, and tooltips, but never return it. ");
            prompt.append("For surface=\"chat.context.direct\", <st-context> lists chat history from oldest to newest and marks exactly one [target] line. Use [previous] lines only to understand references and tone; translate only the current <st-doc> target message, keep player names and chat prefixes stable, and never return previous context lines. ");
            prompt.append("For surface=\"chat.context.direct\" or \"chat.message.segment.direct\" with mode=\"line-text\", the current <st-doc> contains exactly one target chat line; return that same one <line> only, or a single plain translated target line if tag output would be malformed. ");
            prompt.append("Use <st-plain-context> only to understand the complete visible sentence across styled runs; never return it. ");
            prompt.append("For mode=\"free\" tooltips and hover text, translate from the complete plain line context, not from isolated styled fragments; do not copy source-language words back as fallback when a phrase spans split <g> runs. ");
            prompt.append("For surface=\"tooltip.item_context.direct\", <st-context> is the complete numbered item tooltip block with line roles such as title, lore, mechanic, usage, hotkey, empty, and separator. Translate each returned <line> using the entire item tooltip block as context, so multi-line lore and mechanics read consistently across adjacent lines. ");
            prompt.append("For surface=\"tooltip.item_context.direct\", do not treat lore or mechanic lines as isolated sentences. In mode=\"line-text\", preserve the exact <st-doc> line count, line indexes, and per-line boundaries, translate only the plain text inside each <line>, and do not create <g>, <run>, or any other nested tags. ");
            prompt.append("For surface=\"hud.title_group.component.direct\", <st-context> may contain recent title/subtitle command history, including original lines whose translated text is still [pending]. Use those prior originals and translations to keep fast subtitle-like title commands semantically continuous; never repeat or return the history. ");
            prompt.append("For surface=\"hud.actionbar.component.direct\", <st-context> may contain recent title/actionbar caption history. Use it to keep consecutive actionbar subtitle fragments coherent, but keep numeric placeholder groups like @@0@@ exactly once each and do not invent or remove counters. ");
            prompt.append("For surface=\"hud.history.caption_batch.direct\", translate the batch as ordered HUD caption subtitles. Treat all current <st-plain-context> lines as consecutive fragments of one subtitle passage, not isolated sentences. When a sentence spans multiple lines, understand the whole sentence first, then distribute the target wording across the same line indexes in natural reading order. <st-context> contains only captions before the current batch; use it for continuity, but do not let it change line count, line order, tag ids, or the exact @@0@@-style placeholders in the current <st-doc>. ");
            prompt.append("For surface=\"text_display.component.direct\", preserve every <g id> style slot exactly once so colored in-world labels, hotkeys, and decorative title letters keep their original colors and formatting. Translate short instruction/label phrases inside their own slots; keep already-localized hotkeys and stylized proper-name/logo text unchanged when translating it would break the visual title. ");
            prompt.append("Avoid bilingual output: translate natural-language source text to the target language unless it is already target-language text or a structural token represented by tags, numbers, ids, coordinates, or player names. ");
            prompt.append("Use <st-normalized-plain-context> to understand fullwidth or compatibility characters such as ＡＴＴＥＭＰＴＩＮＧ, but keep returned tag structure based on <st-doc>. Never return the normalized context section. ");
            prompt.append("If a <st-glossary> section is present, obey every source -> target term exactly for matching visible UI/status words, and never return the glossary section. ");
            prompt.append("Return the <st-doc> document format only; do not output JSON, markdown, code fences, explanations, or bilingual text. ");
            prompt.append("Preserve the <st-doc> root attributes, every <line> tag, every line index, and the line count exactly. ");
            prompt.append("If <st-doc mode=\"sign-group\"> contains <sign id=\"...\"> blocks, preserve every sign id exactly once. You may reorder sign blocks only if every id remains complete and unique; never move a line, run, command, number, gamerule, or sentence fragment from one sign id into another. ");
            prompt.append("For sign-group documents, every sign must still contain exactly four <line> elements and every line must keep its original <run id> elements in place. ");
            prompt.append("For surface=\"sign.auto.single.direct\", the one sign's four lines are a single semantic sign message. Understand the full four-line block before translating any line; wrapped phrases, menu labels, instructions, and lore must read consistently across adjacent lines. ");
            prompt.append("For surface=\"sign.auto.single.direct\", do not translate each visual line as an isolated sentence. Keep the exact four line indexes and run ids, but distribute the target-language wording across those same line slots in natural display order. ");
            prompt.append("If mode=\"line-text\", never add, remove, reorder, split, or merge <line> elements; translate only the text content of each <line> and keep empty, separator, numeric, icon-only, and pure hotkey/control-token lines unchanged when they have no natural-language meaning. ");
            prompt.append("If mode=\"fixed\", never add, remove, reorder, split, or merge <run> elements; translate only editable=\"true\" run text in place and copy editable=\"false\" run text exactly. ");
            prompt.append("If mode=\"free\", translate the full line naturally and you may move <g id=\"...\"> tags inside the same line to match target-language word order. ");
            prompt.append("For mode=\"free\", <g> tags normally contain only id attributes. Every original <g id> from that line must appear exactly once in the returned same line, ids must be unchanged, and no new <g> ids may be created. ");
            prompt.append("For mode=\"free\", text outside <g> tags is allowed for natural grammar and will use the line's base style; put styled words, numbers, key terms, and moved tokens inside their original <g id> tags. Do not add style/editable attributes to <g> tags. ");
            prompt.append("Translate bracketed UI labels and title-case system labels such as [Animate Heroes], [Enemies], Abbreviations, and Score Leaderboard; do not treat them as protected player names. ");
            prompt.append("If an editable tag contains only a short English UI/status phrase, translate that phrase instead of copying it unchanged. ");
            prompt.append("When source words are split across adjacent styled runs, translate according to the complete plain context instead of preserving English order. ");
            prompt.append("Example: 'draining <g id=\"a\">15</g><g id=\"b\"> Mana</g> every <g id=\"c\">0.4</g>' should become '每 <g id=\"c\">0.4</g> 秒消耗 <g id=\"a\">15</g> 点 <g id=\"b\">法力</g>', not '每 15 秒消耗 0.4 法力'. ");
            prompt.append("Do not change XML entities except as needed for valid escaped text. ");
            prompt.append("Keep colors, styles, click events, hover events, icons, numbers, ids, and player names represented by tags/attributes untouched; translate all visible English text inside editable tags. ");
        }

        String stylePrompt = ModConfig.TRANSLATION_STYLE_PROMPT.get();
        if (stylePrompt != null) {
            stylePrompt = stylePrompt.trim();
            if (!stylePrompt.isEmpty()) {
                prompt.append("\n\nFollow this custom translation style instruction strictly: ");
                prompt.append(stylePrompt);
                prompt.append(" ");
                prompt.append("Apply it to tone and wording, but never break formatting, control markers, or line structure.");
            }
        }

        if (!termHints.isEmpty()) {
            prompt.append("\n\nUse these term translations for consistency:\n");
            for (TermHint hint : termHints) {
                prompt.append("- \"").append(hint.original()).append("\" -> \"").append(hint.translation())
                        .append("\"\n");
            }
        }

        return prompt.toString();
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
        String apiKey = sanitizeApiKey(ModConfig.DEEPSEEK_API_KEY.get());
        ModConfig.ApiFormat apiFormat = ModConfig.API_FORMAT.get();
        String model = apiFormat == ModConfig.ApiFormat.DEEPSEEK_CHAT
                ? ModConfig.normalizeDeepSeekModelId(ModConfig.DEEPSEEK_MODEL.get())
                : ModConfig.normalizeModelId(ModConfig.DEEPSEEK_MODEL.get());
        String apiUrl = ModConfig.normalizeApiUrl(ModConfig.DEEPSEEK_API_URL.get());

        SimpleTranslateMod.getLogger().debug("Sending translation request to {} with model {} format {}", apiUrl, model, apiFormat);
        SimpleTranslateMod.getLogger().debug("Text to translate: {}", userPrompt);
        List<ApiRequestProfile> profiles = buildRequestProfiles(apiUrl, apiFormat, model);

        String surface = detectRequestSurface(userPrompt);
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
                SimpleTranslateMod.getLogger().warn("Empty translation result from DeepSeek");
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SimpleTranslateMod.getLogger().error("Translation request interrupted", e);
            return null;
        } catch (IOException e) {
            throw new TranslationRequestQueue.RetryableTranslationException(
                    e.getMessage() == null ? "I/O error" : e.getMessage(), e);
        } catch (TranslationRequestQueue.RetryableTranslationException e) {
            throw e;
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
        try {
            JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
            String raw = extractAssistantContent(response);
            if (raw != null) {
                return ModelOutputSanitizer.sanitize(raw);
            }
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to parse non-stream response: {}", responseBody, e);
        }
        return null;
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

        for (ApiRequestProfile profile : profiles) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(profile.endpointUrl()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(
                                buildRequestBody(profile, modelId, "Connectivity probe.", "Hi", 8, false))))
                        .timeout(Duration.ofSeconds(30));
                applyAuthHeader(builder, profile, apiKey);

                HttpResponse<InputStream> response = httpClient.send(builder.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                lastStatus = response.statusCode();
                String body = readErrorBody(response.body());
                if (response.statusCode() == 200) {
                    lastSuccessfulProfile = profile;
                    return new TranslationService.ModelAccessResult(true, modelId, response.statusCode(),
                            "Model verified");
                }
                lastMessage = body == null || body.isBlank() ? ("HTTP " + response.statusCode()) : body;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new TranslationService.ModelAccessResult(false, modelId, 0, "Model verification interrupted");
            } catch (IOException | RuntimeException e) {
                lastMessage = e.getMessage() == null ? "Model verification failed" : e.getMessage();
            }
        }

        return new TranslationService.ModelAccessResult(false, modelId, lastStatus, lastMessage);
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

