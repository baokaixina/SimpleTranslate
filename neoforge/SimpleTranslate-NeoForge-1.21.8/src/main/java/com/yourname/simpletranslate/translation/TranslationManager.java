package com.yourname.simpletranslate.translation;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TermDictionary;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.util.ModelOutputSanitizer;
import com.yourname.simpletranslate.util.TranslationCacheKeys;
import com.yourname.simpletranslate.util.TranslationMapping;
import com.yourname.simpletranslate.util.TranslationMappingResult;
import com.yourname.simpletranslate.util.TranslationSlot;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Manages translation requests with caching and term consistency
 */
public class TranslationManager {
    private static final Pattern STYLE_PLACEHOLDER_PATTERN = Pattern.compile("@@@F(\\d+)@@@");
    private static final Pattern FROZEN_MAPPING_TOKEN_PATTERN = Pattern.compile(
            "(?iu)(?<![A-Za-z0-9_])\\d+(?:\\.\\d+)?\\s*(?:%|s|sec|secs|second|seconds)?(?![A-Za-z0-9_])");

    private final TranslationService translationService;

    public TranslationManager() {
        this.translationService = new DeepSeekTranslationService();
    }

    public CompletableFuture<TranslationMappingResult> translateMapping(TranslationMapping mapping) {
        if (mapping == null) {
            return CompletableFuture.completedFuture(new TranslationMappingResult("", Map.of()));
        }

        if (isFixedOnly(mapping)) {
            return CompletableFuture.completedFuture(fixedOnlyResult(mapping));
        }

        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist != null && blacklist.isBlacklisted(mapping.sourceText())) {
            return CompletableFuture.completedFuture(new TranslationMappingResult(mapping.taskId(), Map.of()));
        }

        if (!translationService.isReady()) {
            return CompletableFuture.completedFuture(new TranslationMappingResult(mapping.taskId(), Map.of()));
        }

        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        String cacheKey = mapping.cacheKey();
        if (cache != null && ModConfig.CACHE_ENABLED.get()) {
            Optional<String> cached = cache.get(cacheKey);
            if (cached.isPresent()) {
                String cachedValue = cached.get();
                TranslationMappingResult cachedResult = sanitizeMappingResult(mapping,
                        TranslationMappingResult.fromCacheValue(cachedValue));
                if (matchesMapping(mapping, cachedResult)) {
                    return CompletableFuture.completedFuture(cachedResult);
                }
                cache.remove(cacheKey);
                cache.save();
            }
        }

        List<TranslationService.TermHint> termHints = collectTermHints(mapping);
        String sourceLanguage = ModConfig.SOURCE_LANGUAGE.get();
        String targetLanguage = ModConfig.TARGET_LANGUAGE.get();
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        FrozenMapping frozenMapping = freezeMapping(mapping);

        return translationService.translateMapping(frozenMapping.requestMapping(), sourceLanguage, targetLanguage, termHints)
                .thenApply(frozenMapping::restore)
                .thenApply(result -> sanitizeMappingResult(mapping, result))
                .thenApply(result -> {
                    if (cache != null && ModConfig.CACHE_ENABLED.get()
                            && cache == SimpleTranslateMod.getTranslationCache()
                            && SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                            && sourceLanguageMatches(sourceLanguage)
                            && targetLanguageMatches(targetLanguage)
                            && result != null && result.translations() != null && !result.translations().isEmpty()
                            && matchesMapping(mapping, result)) {
                        String translated = result.toCacheValue();
                        if (translated != null && !translated.isBlank()) {
                            cache.put(cacheKey, translated, mapping.sourceText(), TranslationCache.displayTextFromValue(translated));
                            cache.save();
                        }
                    }
                    return result;
                });
    }

    /**
     * Translate text, using cache if available
     * 
     * @param text The text to translate
     * @return CompletableFuture with the translation result
     */
    public CompletableFuture<TranslationResult> translate(String text) {
        return translateRaw(text).thenApply(translated -> {
            if (translated == null || translated.isBlank()) {
                return new TranslationResult(text, null, false, "Translation failed");
            }
            var blacklist = SimpleTranslateMod.getTranslationBlacklist();
            if (blacklist != null && blacklist.containsBlacklistedEntry(translated)) {
                return new TranslationResult(text, null, false, "Translation is blacklisted");
            }
            TermDictionary termDict = SimpleTranslateMod.getTermDictionary();
            if (termDict != null && ModConfig.TERM_AUTO_DETECT_ENABLED.get()) {
                termDict.analyzeAndRecordTerms(text);
            }
            return new TranslationResult(text, translated, true, null);
        });
    }

    /**
     * Check if the translation service is ready
     */
    public boolean isReady() {
        return translationService.isReady();
    }

    public CompletableFuture<TranslationService.ApiDetectionResult> detectApi() {
        return translationService.detectApi();
    }

    public CompletableFuture<TranslationService.ModelDetectionResult> detectAvailableModels(String apiKey, String apiUrl,
            ModConfig.ApiFormat apiFormat) {
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.completedFuture(new TranslationService.ModelDetectionResult(
                    false, "", 0, List.of(), "API key not configured"));
        }
        return translationService.detectAvailableModels(apiKey, apiUrl, apiFormat);
    }

    public CompletableFuture<TranslationService.ModelAccessResult> verifyModelAccess(String apiKey, String apiUrl,
            String modelId, ModConfig.ApiFormat apiFormat) {
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.completedFuture(new TranslationService.ModelAccessResult(
                    false, modelId, 0, "API key not configured"));
        }
        if (modelId == null || modelId.isBlank()) {
            return CompletableFuture.completedFuture(new TranslationService.ModelAccessResult(
                    false, "", 0, "Model ID not configured"));
        }
        return translationService.verifyModelAccess(apiKey, apiUrl, modelId, apiFormat);
    }

    /**
     * Translate plain raw text using the raw protocol. This deliberately does not
     * route through TranslationMapping, because legacy callers expect the direct
     * translated text rather than mapping JSON.
     *
     * @param text The text to translate (may contain separators for batch)
     * @return CompletableFuture with the raw translated string
     */
    public CompletableFuture<String> translateRaw(String text) {
        return translateRawInternal(text, false);
    }


    public CompletableFuture<String> translateFormattedDocument(String document) {
        return translateFormattedDocument(document, "");
    }

    public CompletableFuture<String> translateFormattedDocument(String document, String surface) {
        return translateFormattedDocument(document, surface, 1);
    }

    public CompletableFuture<String> translateFormattedDocument(String document, String surface, int maxTokenMultiplier) {
        String source = document == null ? "" : document;
        if (source.isBlank()) {
            return CompletableFuture.completedFuture(source);
        }
        if (!translationService.isReady()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<String> future;
        if (translationService instanceof DeepSeekTranslationService deepSeek) {
            future = deepSeek.translateFormattedDocument(
                    source,
                    surface,
                    ModConfig.SOURCE_LANGUAGE.get(),
                    ModConfig.TARGET_LANGUAGE.get(),
                    collectTermHints(source),
                    maxTokenMultiplier);
        } else {
            future = translationService.translateFormattedDocument(
                    source,
                    surface,
                    ModConfig.SOURCE_LANGUAGE.get(),
                    ModConfig.TARGET_LANGUAGE.get(),
                    collectTermHints(source));
        }
        return future.handle((translated, error) -> {
            if (error != null) {
                SimpleTranslateMod.getLogger().warn("Direct formatted translation failed: {}",
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                return null;
            }
            return ModelOutputSanitizer.sanitize(translated);
        });
    }

    /**
     * Get the current translation service name
     */
    public String getServiceName() {
        return translationService.getServiceName();
    }

    private List<TranslationService.TermHint> collectTermHints(TranslationMapping mapping) {
        TermDictionary termDict = SimpleTranslateMod.getTermDictionary();
        if (termDict == null || mapping == null) {
            return List.of();
        }

        StringBuilder text = new StringBuilder();
        if (mapping.sourceText() != null) {
            text.append(mapping.sourceText()).append('\n');
        }
        for (var slot : mapping.slots()) {
            if (slot != null && slot.text() != null) {
                text.append(slot.text()).append('\n');
            }
        }

        String haystack = text.toString();
        return termDict.getAllTerms().entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .filter(entry -> haystack.contains(entry.getKey()))
                .map(entry -> new TranslationService.TermHint(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    private CompletableFuture<String> translateRawInternal(String text, boolean preserveMarkers) {
        String source = text == null ? "" : text;
        if (source.isBlank()) {
            return CompletableFuture.completedFuture(source);
        }

        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist != null && blacklist.isBlacklisted(source)) {
            return CompletableFuture.completedFuture(null);
        }

        if (!translationService.isReady()) {
            return CompletableFuture.completedFuture(null);
        }

        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        String surface = preserveMarkers ? "manager.marker" : "manager.raw";
        String cacheKey = TranslationCacheKeys.key(surface, source);
        if (cache != null && ModConfig.CACHE_ENABLED.get()) {
            Optional<String> cached = cache.get(cacheKey);
            if (cached.isPresent()) {
                String cachedValue = cached.get();
                if (cachedValue != null && !cachedValue.isBlank()) {
                    return CompletableFuture.completedFuture(cachedValue);
                }
                cache.remove(cacheKey);
                cache.save();
            }
        }

        List<TranslationService.TermHint> termHints = collectTermHints(source);
        String sourceLanguage = ModConfig.SOURCE_LANGUAGE.get();
        String targetLanguage = ModConfig.TARGET_LANGUAGE.get();
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        CompletableFuture<String> request = preserveMarkers
                ? translationService.translatePreservingMarkers(source, sourceLanguage, targetLanguage, termHints)
                : translationService.translateWithTerms(source, sourceLanguage, targetLanguage, termHints);

        return request.handle((translated, error) -> {
            if (error != null) {
                SimpleTranslateMod.getLogger().warn("{} translation failed: {}",
                        preserveMarkers ? "Marker-preserving raw" : "Raw",
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                return null;
            }
            String cleaned = preserveMarkers
                    ? ModelOutputSanitizer.sanitize(translated)
                    : ModelOutputSanitizer.sanitizeWithOriginal(translated, source);
            if (cleaned == null || cleaned.isBlank()) {
                return null;
            }
            if (blacklist != null && blacklist.containsBlacklistedEntry(cleaned)) {
                return null;
            }
            if (cache != null && ModConfig.CACHE_ENABLED.get()
                    && cache == SimpleTranslateMod.getTranslationCache()
                    && SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                    && sourceLanguageMatches(sourceLanguage)
                    && targetLanguageMatches(targetLanguage)) {
                cache.put(cacheKey, cleaned, source, cleaned);
                cache.save();
            }
            return cleaned;
        });
    }

    private static boolean sourceLanguageMatches(String expected) {
        String current = ModConfig.SOURCE_LANGUAGE.get();
        return expected == null ? current == null : expected.equals(current);
    }

    private static boolean targetLanguageMatches(String expected) {
        String current = ModConfig.TARGET_LANGUAGE.get();
        return expected == null ? current == null : expected.equals(current);
    }

    private List<TranslationService.TermHint> collectTermHints(String text) {
        TermDictionary termDict = SimpleTranslateMod.getTermDictionary();
        if (termDict == null || text == null || text.isBlank()) {
            return List.of();
        }
        return termDict.getAllTerms().entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .filter(entry -> text.contains(entry.getKey()))
                .map(entry -> new TranslationService.TermHint(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    private TranslationMappingResult sanitizeMappingResult(TranslationMapping mapping, TranslationMappingResult result) {
        if (mapping == null || result == null) {
            return new TranslationMappingResult(mapping == null ? "" : mapping.taskId(), Map.of());
        }

        Map<String, String> sanitized = new LinkedHashMap<>();
        for (TranslationSlot slot : mapping.slots()) {
            if (slot == null) {
                continue;
            }
            String translated = result.translationFor(slot.id());
            if (translated == null) {
                continue;
            }
            if (slot.fixedSlot()) {
                String source = ModelOutputSanitizer.sanitize(slot.text());
                String target = ModelOutputSanitizer.sanitize(translated);
                if (source != null && target != null && source.equals(target)) {
                    sanitized.put(slot.id(), slot.text());
                }
                continue;
            }
            String cleaned = ModelOutputSanitizer.sanitize(translated);
            if (cleaned == null || cleaned.isBlank()) {
                continue;
            }
            if (!preservesStylePlaceholders(slot.text(), cleaned)) {
                continue;
            }
            var blacklist = SimpleTranslateMod.getTranslationBlacklist();
            if (blacklist != null && blacklist.containsBlacklistedEntry(cleaned)) {
                continue;
            }
            sanitized.put(slot.id(), cleaned);
        }
        return new TranslationMappingResult(mapping.taskId(), sanitized);
    }

    private String firstTranslatedValue(TranslationMappingResult result) {
        if (result == null || result.translations().isEmpty()) {
            return null;
        }
        return result.translations().values().iterator().next();
    }

    private boolean matchesMapping(TranslationMapping mapping, TranslationMappingResult result) {
        if (mapping == null || result == null || result.translations() == null || result.translations().isEmpty()) {
            return false;
        }
        if (result.translations().size() != mapping.slots().size()) {
            return false;
        }
        for (TranslationSlot slot : mapping.slots()) {
            if (slot == null || !result.translations().containsKey(slot.id())) {
                return false;
            }
            if (slot.fixedSlot()) {
                String translated = result.translationFor(slot.id());
                if (translated == null) {
                    return false;
                }
                String source = ModelOutputSanitizer.sanitize(slot.text());
                String target = ModelOutputSanitizer.sanitize(translated);
                if (source == null || target == null || !source.equals(target)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isFixedOnly(TranslationMapping mapping) {
        if (mapping == null || mapping.slots().isEmpty()) {
            return false;
        }
        for (TranslationSlot slot : mapping.slots()) {
            if (slot != null && !slot.fixedSlot()) {
                return false;
            }
        }
        return true;
    }

    private static TranslationMappingResult fixedOnlyResult(TranslationMapping mapping) {
        Map<String, String> translations = new LinkedHashMap<>();
        for (TranslationSlot slot : mapping.slots()) {
            if (slot != null && !slot.id().isBlank()) {
                translations.put(slot.id(), slot.text());
            }
        }
        return new TranslationMappingResult(mapping.taskId(), translations);
    }

    private static FrozenMapping freezeMapping(TranslationMapping mapping) {
        List<TranslationSlot> requestSlots = new ArrayList<>();
        Map<String, Map<String, String>> replacements = new LinkedHashMap<>();
        Map<String, String> fixedTranslations = new LinkedHashMap<>();
        boolean changed = false;
        for (TranslationSlot slot : mapping.slots()) {
            if (slot == null) {
                continue;
            }
            if (slot.fixedSlot()) {
                fixedTranslations.put(slot.id(), slot.text());
                changed = true;
                continue;
            }
            FrozenText frozen = freezeMappingText(slot.text());
            requestSlots.add(slot.withText(frozen.text()));
            if (!frozen.replacements().isEmpty()) {
                replacements.put(slot.id(), frozen.replacements());
                changed = true;
            }
        }
        if (!changed) {
            return new FrozenMapping(mapping, Map.of(), Map.of());
        }
        TranslationMapping requestMapping = new TranslationMapping(
                mapping.taskId(),
                mapping.surface(),
                mapping.sourceText(),
                mapping.context(),
                mapping.layoutSignature(),
                mapping.styleSignature(),
                mapping.createdAt(),
                requestSlots);
        return new FrozenMapping(requestMapping, replacements, fixedTranslations);
    }

    private static FrozenText freezeMappingText(String text) {
        if (text == null || text.isBlank()) {
            return new FrozenText(text == null ? "" : text, Map.of());
        }

        List<int[]> existingPlaceholders = placeholderRanges(text);
        Matcher matcher = FROZEN_MAPPING_TOKEN_PATTERN.matcher(text);
        StringBuilder frozen = new StringBuilder();
        Map<String, String> replacements = new LinkedHashMap<>();
        int cursor = 0;
        int nextPlaceholderIndex = nextPlaceholderIndex(text);
        while (matcher.find()) {
            if (isInsideRange(matcher.start(), existingPlaceholders)) {
                continue;
            }
            String token = matcher.group();
            if (token == null || token.isBlank()) {
                continue;
            }
            frozen.append(text, cursor, matcher.start());
            String placeholder = "@@@F" + nextPlaceholderIndex++ + "@@@";
            frozen.append(placeholder);
            replacements.put(placeholder, token);
            cursor = matcher.end();
        }

        if (replacements.isEmpty()) {
            return new FrozenText(text, Map.of());
        }
        frozen.append(text, cursor, text.length());
        return new FrozenText(frozen.toString(), replacements);
    }

    private static List<int[]> placeholderRanges(String text) {
        List<int[]> ranges = new ArrayList<>();
        Matcher matcher = STYLE_PLACEHOLDER_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            ranges.add(new int[] { matcher.start(), matcher.end() });
        }
        return ranges;
    }

    private static int nextPlaceholderIndex(String text) {
        int next = 0;
        Matcher matcher = STYLE_PLACEHOLDER_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            try {
                next = Math.max(next, Integer.parseInt(matcher.group(1)) + 1);
            } catch (NumberFormatException ignored) {
                // Keep scanning; malformed marker numbers are ignored here.
            }
        }
        return next;
    }

    private static boolean isInsideRange(int index, List<int[]> ranges) {
        for (int[] range : ranges) {
            if (index >= range[0] && index < range[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean preservesStylePlaceholders(String source, String translated) {
        Map<String, Integer> sourceCounts = countPlaceholders(source);
        if (sourceCounts.isEmpty()) {
            return true;
        }
        Map<String, Integer> translatedCounts = countPlaceholders(translated);
        for (Map.Entry<String, Integer> entry : sourceCounts.entrySet()) {
            if (!entry.getValue().equals(translatedCounts.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Integer> countPlaceholders(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher matcher = STYLE_PLACEHOLDER_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            counts.merge(matcher.group(), 1, Integer::sum);
        }
        return counts;
    }

    private record FrozenText(String text, Map<String, String> replacements) {
    }

    private record FrozenMapping(TranslationMapping requestMapping,
                                 Map<String, Map<String, String>> replacements,
                                 Map<String, String> fixedTranslations) {
        TranslationMappingResult restore(TranslationMappingResult result) {
            if ((result == null || result.translations().isEmpty())
                    && (fixedTranslations == null || fixedTranslations.isEmpty())) {
                return result;
            }
            Map<String, String> restored = new LinkedHashMap<>();
            if (fixedTranslations != null) {
                restored.putAll(fixedTranslations);
            }
            if (result != null && result.translations() != null) {
                for (Map.Entry<String, String> entry : result.translations().entrySet()) {
                    String value = entry.getValue();
                    Map<String, String> slotReplacements = replacements.get(entry.getKey());
                    if (slotReplacements != null && value != null) {
                        for (Map.Entry<String, String> replacement : slotReplacements.entrySet()) {
                            value = value.replace(replacement.getKey(), replacement.getValue());
                        }
                    }
                    restored.put(entry.getKey(), value);
                }
            }
            String taskId = result == null ? requestMapping.taskId() : result.taskId();
            return new TranslationMappingResult(taskId, restored);
        }
    }

    /**
     * Result of a translation request
     */
    public record TranslationResult(
            String original,
            String translated,
            boolean success,
            String error) {
    }
}



