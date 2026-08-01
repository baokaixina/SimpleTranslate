package com.yourname.simpletranslate.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.CacheKey;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.transport.TranslationPromptPolicy;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retrieves small, scope-local translation examples for every model-backed request.
 *
 * <p>The active {@link TranslationCache} already represents exactly one server
 * or save. This class deliberately refuses to read it while the client is in
 * the global/main-menu scope, so examples never leak between worlds. The
 * returned JSON is system-prompt metadata; it is never prepended to the user
 * payload and never participates in a translation cache key.</p>
 */
public final class TextContextMemory {
    public static final int MAX_EXAMPLES = 12;
    public static final int MAX_EXAMPLE_CODE_POINTS = 3000;

    private static final int MAX_CALLER_CONTEXT_CODE_POINTS = 2000;
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern LEGACY_DIALOGUE_FONT = Pattern.compile(
            "(?i)hud/dialogue/text/(?:nameplate|control)");
    private static final Gson GSON = new Gson();
    private static final AtomicLong REVISION = new AtomicLong();
    private static final int MAX_EXACT_MEMO_ENTRIES = 4096;
    private static TranslationCache exactMemoCache;
    private static long exactMemoCacheRevision = -1L;
    private static String exactMemoLanguagePair = "";
    private static boolean exactMemoAllowShared;
    private static String exactMemoProfileFingerprint = "";
    private static final LinkedHashMap<String, Optional<ExactTranslation>> EXACT_MEMO = new LinkedHashMap<>();
    private static long exactFullScanCount;

    private TextContextMemory() {
    }

    /** Independent from the global runtime revision: changing only context settings does not reset HUD state. */
    public static long revision() {
        return REVISION.get();
    }

    public static long settingsChanged() {
        return REVISION.incrementAndGet();
    }

    public static boolean isRevisionCurrent(long revision) {
        return revision < 0L || REVISION.get() == revision;
    }

    /**
     * Creates JSON-escaped system-prompt metadata. Historical examples are
     * considered for both wire modes and only after a cache miss.
     */
    public static PromptMetadata buildPromptMetadata(
            String callerContext, String surface, String textSegmentPayload, boolean includeHistory) {
        return buildPromptMetadata(callerContext, surface, "game-text", textSegmentPayload, includeHistory,
                ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
    }

    public static PromptMetadata buildPromptMetadata(
            String callerContext, String surface, String role, String payload, boolean includeHistory) {
        return buildPromptMetadata(callerContext, surface, role, payload, includeHistory,
                ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
    }

    public static PromptMetadata buildPromptMetadata(
            String callerContext, String surface, String role, String payload, boolean includeHistory,
            String sourceLanguage, String targetLanguage) {
        // Every model cache-miss request is revision-bound, including
        // requests made while history is disabled. If the user enables or
        // changes context before that response arrives, the old response must
        // not populate the stable cache and suppress a contextual retry.
        long capturedRevision = includeHistory ? revision() : -1L;
        String stableCallerContext = trimCodePoints(
                ComponentJsonNumberNormalizer.maskPromptDynamicNumbers(callerContext),
                MAX_CALLER_CONTEXT_CODE_POINTS);
        boolean historyEnabled = includeHistory
                && TranslationPromptPolicy.smartContextEnabled(surface)
                && SimpleTranslateMod.getCurrentWorldId() != null;
        List<Example> examples = historyEnabled
                ? retrieve(surface, extractQueryText(payload), sourceLanguage, targetLanguage)
                : List.of();

        JsonObject metadata = new JsonObject();
        metadata.addProperty("schema", "simple_translate_prompt_context_v2");
        metadata.addProperty("surface", Surface.normalize(surface));
        metadata.addProperty("surface_role", TranslationPromptPolicy.normalizedRole(role));
        if (historyEnabled) {
            String scope = SimpleTranslateMod.getCurrentWorldId();
            metadata.addProperty("scope_kind", scope != null && scope.startsWith("server_")
                    ? "multiplayer_server" : "local_save");
            metadata.addProperty("scope", scope == null ? "" : scope);
        }
        if (!stableCallerContext.isBlank()) {
            metadata.addProperty("caller_context", stableCallerContext);
        }
        if (!examples.isEmpty()) {
            JsonArray array = new JsonArray();
            for (Example example : examples) {
                JsonObject item = new JsonObject();
                item.addProperty("source",
                        ComponentJsonNumberNormalizer.maskPromptDynamicNumbers(example.source()));
                item.addProperty("translation",
                        ComponentJsonNumberNormalizer.maskPromptDynamicNumbers(example.translation()));
                item.addProperty("surface", example.surface());
                item.addProperty("player_edited", example.editedByPlayer());
                array.add(item);
            }
            metadata.add("translation_examples", array);
        }
        return new PromptMetadata(GSON.toJson(metadata), capturedRevision, examples.size());
    }

    static List<Example> retrieve(String requestSurface, String queryText) {
        return retrieve(requestSurface, queryText,
                ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
    }

    static List<Example> retrieve(String requestSurface, String queryText,
                                  String sourceLanguage, String targetLanguage) {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null || queryText == null || queryText.isBlank()) {
            return List.of();
        }

        String normalizedQuery = normalize(queryText);
        Set<String> queryTokens = tokens(normalizedQuery);
        Set<String> queryBigrams = cjkBigrams(normalizedQuery);
        List<ScoredExample> candidates = new ArrayList<>();
        for (TranslationCache.CacheViewEntry entry : cache.getEntries().values()) {
            if (!eligible(entry, sourceLanguage, targetLanguage)) {
                continue;
            }
            String source = normalizeDisplay(entry.sourceText());
            String translation = normalizeDisplay(entry.translationText());
            if (source.isBlank() || translation.isBlank() || normalize(source).equals(normalize(translation))) {
                continue;
            }
            double score = score(normalizedQuery, queryTokens, queryBigrams, requestSurface, entry, source);
            if (score <= 0.0D) {
                continue;
            }
            candidates.add(new ScoredExample(
                    new Example(source, translation, Surface.normalize(entry.surface()), entry.editedByPlayer()),
                    score,
                    entry.lastUsedAt()));
        }

        candidates.sort(Comparator
                .comparingDouble(ScoredExample::score).reversed()
                .thenComparing(Comparator.comparingLong(ScoredExample::lastUsedAt).reversed())
                .thenComparing(candidate -> candidate.example().source()));
        List<Example> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int usedCodePoints = 0;
        for (ScoredExample candidate : candidates) {
            Example example = candidate.example();
            String identity = normalize(example.source()) + "\u0000" + normalize(example.translation());
            if (!seen.add(identity)) {
                continue;
            }
            int pairLength = codePointLength(example.source()) + codePointLength(example.translation());
            if (pairLength <= 0 || pairLength > MAX_EXAMPLE_CODE_POINTS - usedCodePoints) {
                continue;
            }
            result.add(example);
            usedCodePoints += pairLength;
            if (result.size() >= MAX_EXAMPLES) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /**
     * Looks up exact, scope-local semantic strings for incremental Component slots.
     *
     * <p>This is deliberately independent of surface and Component style: an
     * NPC name already translated on a scoreboard can be reused in a dialogue
     * or tooltip.  Only the active world's {@link TranslationCache} is read,
     * and only plain natural-language slots are eligible.  Layout plans,
     * private-use glyphs, controls and placeholder-bearing strings are never
     * reused.</p>
     *
     * @return slot index to translated semantic text
     */
    public static Map<Integer, ExactTranslation> lookupExactTranslations(List<String> sourceSlots) {
        return lookupExactTranslations(sourceSlots,
                ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
    }

    public static Map<Integer, ExactTranslation> lookupExactTranslations(
            List<String> sourceSlots, String targetLanguage) {
        return lookupExactTranslations(sourceSlots, ModConfig.SOURCE_LANGUAGE.get(), targetLanguage);
    }

    public static synchronized Map<Integer, ExactTranslation> lookupExactTranslations(
            List<String> sourceSlots, String sourceLanguage, String targetLanguage) {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null || SimpleTranslateMod.getCurrentWorldId() == null
                || sourceSlots == null || sourceSlots.isEmpty()) {
            return Map.of();
        }

        Set<String> wanted = new HashSet<>();
        for (String source : sourceSlots) {
            if (eligibleExactSemantic(source, targetLanguage)) {
                wanted.add(normalize(source));
            }
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }

        long cacheRevision = cache.contentRevision();
        String languagePair = TranslationTextDetector.languagePairKey(sourceLanguage, targetLanguage);
        boolean allowShared = ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.get();
        String profileFingerprint = TranslationPromptPolicy.runtimeFingerprint();
        if (cache != exactMemoCache || cacheRevision != exactMemoCacheRevision
                || !languagePair.equals(exactMemoLanguagePair)
                || allowShared != exactMemoAllowShared
                || !profileFingerprint.equals(exactMemoProfileFingerprint)) {
            exactMemoCache = cache;
            exactMemoCacheRevision = cacheRevision;
            exactMemoLanguagePair = languagePair;
            exactMemoAllowShared = allowShared;
            exactMemoProfileFingerprint = profileFingerprint;
            EXACT_MEMO.clear();
        }

        boolean needsScan = wanted.stream().anyMatch(source -> !EXACT_MEMO.containsKey(source));
        if (!needsScan) {
            return exactMemoResult(sourceSlots, targetLanguage);
        }

        Map<String, ExactCandidate> best = new LinkedHashMap<>();
        exactFullScanCount++;
        for (TranslationCache.CacheViewEntry entry : cache.getEntries().values()) {
            if (!eligibleExactCacheEntry(entry, sourceLanguage, targetLanguage)) {
                continue;
            }
            String sourcePayload = entry.sourcePayload() == null ? "" : entry.sourcePayload();
            String translatedPayload = entry.translation() == null ? "" : entry.translation();
            if (sourcePayload.isBlank() || translatedPayload.isBlank()) {
                // Shared-cache packets intentionally carry display text and a
                // translated Component payload, but not the original Component
                // skeleton. They can still provide one exact semantic example
                // when the display metadata is one unambiguous phrase. This is
                // context retrieval only; translation requests/responses remain
                // exclusively Component JSON.
                String semanticSource = entry.sourceText();
                String semanticTranslation = entry.translationText();
                if (eligibleExactSemantic(semanticSource, targetLanguage)
                        && eligibleExactSemanticTranslation(semanticTranslation, targetLanguage)) {
                    String normalizedSource = normalize(semanticSource);
                    if (wanted.contains(normalizedSource)
                            && !normalizedSource.equals(normalize(semanticTranslation))) {
                        ExactCandidate candidate = new ExactCandidate(
                                semanticTranslation, entry.editedByPlayer(),
                                entry.sharedImported(), entry.lastUsedAt());
                        ExactCandidate previous = best.get(normalizedSource);
                        if (previous == null || candidate.betterThan(previous)) {
                            best.put(normalizedSource, candidate);
                        }
                    }
                }
                continue;
            }
            try {
                ComponentVisualProjection source = ComponentVisualProjection.project(
                        JsonParser.parseString(sourcePayload), targetLanguage);
                if (source == null || !source.hasSlots()) {
                    continue;
                }
                List<String> sourceTexts = source.slots().stream()
                        .map(ComponentVisualProjection.SemanticSlot::sourceText).toList();
                List<String> translatedTexts = source.alignedTranslatedSlotTexts(
                        JsonParser.parseString(translatedPayload));
                if (translatedTexts == null || translatedTexts.size() != sourceTexts.size()) {
                    continue;
                }
                for (int index = 0; index < sourceTexts.size(); index++) {
                    String semanticSource = sourceTexts.get(index);
                    String semanticTranslation = translatedTexts.get(index);
                    if (!eligibleExactSemantic(semanticSource, targetLanguage)
                            || !eligibleExactSemanticTranslation(semanticTranslation, targetLanguage)) {
                        continue;
                    }
                    String normalizedSource = normalize(semanticSource);
                    if (!wanted.contains(normalizedSource)
                            || normalizedSource.equals(normalize(semanticTranslation))) {
                        continue;
                    }
                    ExactCandidate candidate = new ExactCandidate(
                            semanticTranslation, entry.editedByPlayer(), entry.sharedImported(), entry.lastUsedAt());
                    ExactCandidate previous = best.get(normalizedSource);
                    if (previous == null || candidate.betterThan(previous)) {
                        best.put(normalizedSource, candidate);
                    }
                }
            } catch (Exception ignored) {
                // A corrupt or legacy cache record is not semantic memory.
            }
        }

        if (EXACT_MEMO.size() + wanted.size() > MAX_EXACT_MEMO_ENTRIES) {
            EXACT_MEMO.clear();
        }
        for (String normalizedSource : wanted) {
            ExactCandidate candidate = best.get(normalizedSource);
            EXACT_MEMO.put(normalizedSource, candidate == null
                    ? Optional.empty()
                    : Optional.of(new ExactTranslation(candidate.translation(), candidate.editedByPlayer(),
                    candidate.sharedImported())));
        }
        return exactMemoResult(sourceSlots, targetLanguage);
    }

    private static Map<Integer, ExactTranslation> exactMemoResult(
            List<String> sourceSlots, String targetLanguage) {
        Map<Integer, ExactTranslation> result = new LinkedHashMap<>();
        for (int index = 0; index < sourceSlots.size(); index++) {
            String source = sourceSlots.get(index);
            if (!eligibleExactSemantic(source, targetLanguage)) {
                continue;
            }
            Optional<ExactTranslation> candidate = EXACT_MEMO.getOrDefault(
                    normalize(source), Optional.empty());
            if (candidate.isPresent()) {
                result.put(index, candidate.get());
            }
        }
        return Map.copyOf(result);
    }

    private static boolean eligibleExactCacheEntry(TranslationCache.CacheViewEntry entry,
                                                   String sourceLanguage, String targetLanguage) {
        if (entry == null || entry.sourcePayload() == null || entry.translation() == null) {
            return false;
        }
        if (entry.sharedImported() && !ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.get()) {
            return false;
        }
        if (!matchesCurrentPromptPolicy(entry)) {
            return false;
        }
        String surface = Surface.normalize(entry.surface());
        if (isExpiredSurface(surface)) {
            return false;
        }
        if (!matchesLanguagePair(entry, sourceLanguage, targetLanguage)) {
            return false;
        }
        return !containsPrivateUseOrUnsafeControl(entry.sourceText())
                && !containsPrivateUseOrUnsafeControl(entry.translationText());
    }

    private static boolean eligibleExactSemantic(String text, String targetLanguage) {
        return text != null && !text.isBlank()
                && !text.contains("⟦") && !text.contains("⟧")
                && !containsPrivateUseOrUnsafeControl(text)
                && TranslationTextDetector.containsTranslatableText(text, 1, targetLanguage);
    }

    private static boolean eligibleExactSemanticTranslation(String text, String targetLanguage) {
        return text != null && !text.isBlank()
                && !text.contains("⟦") && !text.contains("⟧")
                && !containsPrivateUseOrUnsafeControl(text)
                && TranslationTextDetector.hasLanguageSignal(text, targetLanguage);
    }

    private static boolean eligible(TranslationCache.CacheViewEntry entry,
                                    String sourceLanguage, String targetLanguage) {
        if (entry == null || entry.sourceText() == null || entry.translationText() == null) {
            return false;
        }
        if (entry.sharedImported() && !ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.get()) {
            return false;
        }
        if (!matchesCurrentPromptPolicy(entry)) {
            return false;
        }
        if (!matchesLanguagePair(entry, sourceLanguage, targetLanguage)) {
            return false;
        }
        String surface = Surface.normalize(entry.surface());
        if (!isSurfaceEnabled(surface) || isExpiredSurface(surface)) {
            return false;
        }
        String sourcePayload = entry.sourcePayload() == null ? "" : entry.sourcePayload();
        if (LEGACY_DIALOGUE_FONT.matcher(sourcePayload).find()) {
            return false;
        }
        return !containsPrivateUseOrUnsafeControl(entry.sourceText())
                && !containsPrivateUseOrUnsafeControl(entry.translationText());
    }

    private static boolean isExpiredSurface(String surface) {
        return surface.contains(".semantic_paragraph.v1");
    }

    /** Surface source filter shared by retrieval and request injection. */
    public static boolean isSurfaceEnabled(String rawSurface) {
        String surface = Surface.normalize(rawSurface);
        if (surface.startsWith("chat.outgoing")) {
            return ModConfig.API_TEXT_CONTEXT_SENT_CHAT.get();
        }
        if (surface.startsWith("chat.")) {
            return ModConfig.API_TEXT_CONTEXT_RECEIVED_CHAT.get();
        }
        if (surface.startsWith("tooltip.item_context")) {
            return ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP.get();
        }
        if (surface.startsWith("tooltip.visible.item.")) {
            return ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP.get();
        }
        if (surface.startsWith("tooltip.visible.book_hover.")) {
            return ModConfig.API_TEXT_CONTEXT_BOOK.get();
        }
        if (surface.startsWith("tooltip.visible.chat_hover.")) {
            return ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP.get();
        }
        if (surface.startsWith("hover.overlay") || surface.startsWith("tooltip.book")) {
            return ModConfig.API_TEXT_CONTEXT_BOOK.get();
        }
        if (surface.startsWith("hover.") || surface.startsWith("tooltip.chat")) {
            return ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP.get();
        }
        if (surface.startsWith("book")) {
            return ModConfig.API_TEXT_CONTEXT_BOOK.get();
        }
        if (surface.startsWith("sign.")) {
            return ModConfig.API_TEXT_CONTEXT_SIGN.get();
        }
        if (surface.startsWith("hud.title") || surface.startsWith("hud.subtitle")
                || surface.startsWith("hud.actionbar") || surface.startsWith("title.")
                || surface.startsWith("actionbar.")) {
            return ModConfig.API_TEXT_CONTEXT_HUD_CAPTIONS.get();
        }
        if (surface.startsWith("scoreboard") || surface.startsWith("bossbar.")
                || surface.startsWith("advancement.")) {
            return ModConfig.API_TEXT_CONTEXT_HUD_PROGRESS.get();
        }
        if (surface.startsWith("entity.")) {
            return ModConfig.API_TEXT_CONTEXT_ENTITY_NAME.get();
        }
        if (surface.startsWith("text_display.")) {
            return ModConfig.API_TEXT_CONTEXT_TEXT_DISPLAY.get();
        }
        // New and custom model-backed surfaces inherit smart context by default.
        // The master toggle remains the single explicit way to disable it.
        return true;
    }

    private static boolean matchesCurrentPromptPolicy(TranslationCache.CacheViewEntry entry) {
        if (entry != null && entry.editedByPlayer()) {
            return true;
        }
        if (entry == null || entry.promptFingerprint() == null || entry.promptFingerprint().isBlank()) {
            return false;
        }
        return entry.promptFingerprint().equals(
                TranslationPromptPolicy.cacheFingerprint(entry.surface()));
    }

    private static boolean matchesLanguagePair(TranslationCache.CacheViewEntry entry,
                                               String sourceLanguage, String targetLanguage) {
        if (entry == null || entry.key() == null) {
            return false;
        }
        String expectedLanguageSuffix = ":lang=" + CacheKey.hash(
                TranslationTextDetector.languagePairKey(sourceLanguage, targetLanguage));
        return entry.key().endsWith(expectedLanguageSuffix);
    }

    private static double score(String normalizedQuery, Set<String> queryTokens, Set<String> queryBigrams,
                                String requestSurface, TranslationCache.CacheViewEntry entry, String source) {
        String normalizedSource = normalize(source);
        boolean exact = normalizedSource.equals(normalizedQuery);
        Set<String> sourceTokens = tokens(normalizedSource);
        Set<String> sourceBigrams = cjkBigrams(normalizedSource);
        int tokenOverlap = overlap(queryTokens, sourceTokens);
        int bigramOverlap = overlap(queryBigrams, sourceBigrams);
        boolean sameSurface = Surface.normalize(requestSurface).equals(Surface.normalize(entry.surface()));
        boolean sameSurfaceClass = Surface.classify(requestSurface) == Surface.classify(entry.surface());
        // Recency and edit provenance are tie-breakers, not relevance by
        // themselves. Without this gate, an unrelated but recently used cache
        // entry from another UI could leak into every prompt on the scope.
        if (!exact && tokenOverlap == 0 && bigramOverlap == 0
                && !sameSurface && !sameSurfaceClass) {
            return 0.0D;
        }
        double score = exact ? 20_000.0D : 0.0D;
        score += tokenOverlap * 180.0D;
        score += bigramOverlap * 120.0D;
        if (sameSurface) {
            score += 900.0D;
        } else if (sameSurfaceClass) {
            score += 350.0D;
        }
        if (entry.editedByPlayer()) {
            score += 1_500.0D;
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - Math.max(entry.lastUsedAt(), entry.createdAt()));
        score += Math.max(0.0D, 240.0D - ageMs / 3_600_000.0D);
        return score;
    }

    private static int overlap(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int count = 0;
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = smaller == left ? right : left;
        for (String value : smaller) {
            if (larger.contains(value)) {
                count++;
            }
        }
        return count;
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        Matcher matcher = WORD_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (!token.isBlank()) {
                result.add(token);
            }
        }
        return result;
    }

    private static Set<String> cjkBigrams(String text) {
        List<Integer> cjk = new ArrayList<>();
        if (text != null) {
            text.codePoints().filter(TextContextMemory::isCjk).forEach(cjk::add);
        }
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < cjk.size(); i++) {
            result.add(new String(new int[]{cjk.get(i), cjk.get(i + 1)}, 0, 2));
        }
        return result;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static String extractQueryText(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonElement parsed = JsonParser.parseString(payload);
            if (!parsed.isJsonArray()) {
                return "";
            }
            StringBuilder result = new StringBuilder();
            collectQueryText(parsed, result);
            return result.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void collectQueryText(JsonElement element, StringBuilder result) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                appendQueryText(result, element.getAsString());
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectQueryText(child, result);
            }
            return;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement text = object.get("text");
        if (text != null && text.isJsonPrimitive() && text.getAsJsonPrimitive().isString()) {
            appendQueryText(result, text.getAsString());
        }
        JsonElement extra = object.get("extra");
        if (extra != null) {
            collectQueryText(extra, result);
        }
        JsonElement with = object.get("with");
        if (with != null) {
            collectQueryText(with, result);
        }
    }

    private static void appendQueryText(StringBuilder result, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!result.isEmpty()) {
            result.append('\n');
        }
        result.append(value);
    }

    private static boolean containsPrivateUseOrUnsafeControl(String text) {
        if (text == null) {
            return false;
        }
        return text.codePoints().anyMatch(codePoint ->
                (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                        || (codePoint >= 0xC0000 && codePoint <= 0xDFFFF)
                        || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                        || (codePoint >= 0x100000 && codePoint <= 0x10FFFD)
                        || (Character.isISOControl(codePoint)
                        && codePoint != '\n' && codePoint != '\r' && codePoint != '\t'));
    }

    private static String normalizeDisplay(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int codePointLength(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    private static String trimCodePoints(String value, int maxCodePoints) {
        if (value == null || value.isBlank() || maxCodePoints <= 0) {
            return "";
        }
        String trimmed = value.trim();
        int count = codePointLength(trimmed);
        if (count <= maxCodePoints) {
            return trimmed;
        }
        int end = trimmed.offsetByCodePoints(0, maxCodePoints);
        return trimmed.substring(0, end);
    }

    public record PromptMetadata(String json, long contextRevision, int exampleCount) {
        private static final PromptMetadata EMPTY = new PromptMetadata("", -1L, 0);

        public PromptMetadata {
            json = json == null ? "" : json;
        }
    }

    public record Example(String source, String translation, String surface, boolean editedByPlayer) {
    }

    public record ExactTranslation(String translation, boolean editedByPlayer, boolean sharedImported) {
    }

    private record ScoredExample(Example example, double score, long lastUsedAt) {
    }

    private record ExactCandidate(String translation, boolean editedByPlayer,
                                  boolean sharedImported, long lastUsedAt) {
        boolean betterThan(ExactCandidate other) {
            if (editedByPlayer != other.editedByPlayer) {
                return editedByPlayer;
            }
            if (sharedImported != other.sharedImported) {
                return !sharedImported;
            }
            return lastUsedAt > other.lastUsedAt;
        }
    }
}
