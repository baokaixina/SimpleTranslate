package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.TranslationManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct formatted translation pipeline.
 *
 * <p>The model receives a reversible XML-like document. Fixed layout surfaces
 * preserve line/run tags in place, while flexible tooltip-style surfaces may
 * move style groups within a line. Client code trusts only the original Style
 * objects; returned attributes are validated for shape but never used for
 * rendering.</p>
 */
public final class DirectFormattedTranslationPipeline {
    private static final Pattern LINE_PATTERN = Pattern.compile("(?s)<line\\s+([^>]*)>(.*?)</line>");
    private static final Pattern RUN_PATTERN = Pattern.compile("(?s)<run\\s+([^>]*)>(.*?)</run>");
    private static final Pattern GROUP_PATTERN = Pattern.compile("(?s)<g\\s+([^>]*)>(.*?)</g>");
    private static final Pattern ATTR_PATTERN = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");
    private static final Pattern KNOWN_DIRECT_TAG_PATTERN = Pattern.compile(
            "(?is)</?\\s*(?:st-doc|st-context|st-plain-context|st-normalized-plain-context|st-glossary|line|g|run)\\b[^>]*>");
    private static final Pattern ASCII_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern BRACKETED_TOKEN_PATTERN = Pattern.compile("\\[[^\\[\\]]{1,64}]");
    private static final Pattern SIGN_PROTECTED_TOKEN_PATTERN = Pattern.compile(
            "(?:/[A-Za-z0-9_:@.\\-]+|@[A-Za-z0-9_]+|\\b\\d+(?:\\.\\d+)+\\b|\\b\\d+(?:\\.\\d+)?\\b|\\b[a-z]+(?:[A-Z][A-Za-z0-9_]*)+\\b)");
    private static final Set<String> TRANSLATABLE_SINGLE_WORDS = Set.of(
            "ability", "abbreviations", "activation", "allies", "ally", "amulet", "armor",
            "attack", "atk", "bleed", "blueprint", "book", "boss", "buff", "burn",
            "cast", "casting", "charm", "click", "complete", "completed", "cooldown",
            "damage", "debuff", "disabled", "duration", "enabled", "enemies", "enemy",
            "failed", "freeze", "freezing", "heal", "health", "hoe", "invulnerable",
            "loading", "loaded", "magic", "mana", "physical", "radius", "range",
            "ready", "relic", "resistance", "ring", "root", "score", "self", "shred",
            "siege", "silence", "slot", "slow", "speed", "staff", "stats", "stun",
            "summon", "target", "targets", "tooltip", "used"
    );
    private static final long FAILURE_RETRY_MS = 6000L;
    private static final long DIAGNOSTIC_REPEAT_MS = 15_000L;
    private static final int MAX_DIRECT_CONTEXT_CHARS = 7000;
    private static final int MAX_QUEUED_LOG_KEYS = 2048;
    private static final int MAX_BATCH_ITEMS = 6;
    private static final int MAX_BATCH_CHARS = 9000;
    private static final long BATCH_DELAY_MS = 100L;
    private static final String ITEM_TOOLTIP_CONTEXT_SURFACE = "tooltip.item_context.direct";
    private static final String ITEM_TOOLTIP_CONTEXT_ROLE = "tooltip-block-context";
    private static final String LINE_TEXT_MODE = "line-text";
    private static final Map<String, Long> DIAGNOSTIC_LAST_LOG = new ConcurrentHashMap<>();
    private static final Set<String> QUEUED_LOGGED_KEYS = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService BATCH_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SimpleTranslate-DirectBatch");
        thread.setDaemon(true);
        return thread;
    });

    private DirectFormattedTranslationPipeline() {
    }

    public static ComponentResult translateComponent(Component component, String surface, String role) {
        if (component == null) {
            return new ComponentResult(component, false, false);
        }
        return translateComponents(List.of(component), surface, role, false).asSingle(component);
    }

    public static ComponentListResult translateComponents(List<Component> components, String surface, String role,
                                                          boolean fixedLayout) {
        return translateComponents(components, surface, role, fixedLayout, "");
    }

    public static ComponentListResult translateComponents(List<Component> components, String surface, String role,
                                                          boolean fixedLayout, String context) {
        Template template = Template.fromComponents(components, surface, role, fixedLayout, context);
        if (!template.hasEnglish()) {
            return new ComponentListResult(components, false, false);
        }

        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null && ModConfig.CACHE_ENABLED.get()) {
            String cached = cache.get(template.cacheKey()).orElse(null);
            if (cached != null) {
                List<Component> restored = template.restore(cached);
                if (restored != null) {
                    return new ComponentListResult(restored, true, true);
                }
                cache.remove(template.cacheKey());
                cache.save();
            }
        }

        requestAsync(template);
        return new ComponentListResult(components, true, false);
    }

    public static CompletableFuture<ComponentListResult> translateComponentsAsync(List<Component> components,
                                                                                  String surface,
                                                                                  String role,
                                                                                  boolean fixedLayout,
                                                                                  String context) {
        Template template = Template.fromComponents(components, surface, role, fixedLayout, context);
        if (!template.hasEnglish()) {
            return CompletableFuture.completedFuture(new ComponentListResult(components, false, false));
        }

        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null && ModConfig.CACHE_ENABLED.get()) {
            String cached = cache.get(template.cacheKey()).orElse(null);
            if (cached != null) {
                List<Component> restored = template.restore(cached);
                if (restored != null) {
                    return CompletableFuture.completedFuture(new ComponentListResult(restored, true, true));
                }
                cache.remove(template.cacheKey());
                cache.save();
            }
        }

        return requestAsync(template).thenApply(restored -> {
            if (restored == null) {
                return new ComponentListResult(components, true, false);
            }
            return new ComponentListResult(restored, true, true);
        });
    }

    public static ComponentListResult getCachedComponents(List<Component> components, String surface, String role,
                                                          boolean fixedLayout, String context) {
        Template template = Template.fromComponents(components, surface, role, fixedLayout, context);
        if (!template.hasEnglish()) {
            return new ComponentListResult(components, false, false);
        }

        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null && ModConfig.CACHE_ENABLED.get()) {
            String cached = cache.get(template.cacheKey()).orElse(null);
            if (cached != null) {
                List<Component> restored = template.restore(cached);
                if (restored != null) {
                    return new ComponentListResult(restored, true, true);
                }
                cache.remove(template.cacheKey());
                cache.save();
            }
        }

        return new ComponentListResult(components, true, false);
    }

    public static CompletableFuture<String> translateTextAsync(String text, String surface, String role,
                                                               String context, String layoutSignature,
                                                               String styleSignature) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }
        String effectiveSurface = surface == null || surface.isBlank() ? "manager.direct" : surface;
        String effectiveRole = role == null || role.isBlank() ? "text" : role;
        Component component = Component.literal(text);
        Template template = Template.fromComponents(List.of(component), effectiveSurface, effectiveRole, false, context);
        template = template.withSignatures(layoutSignature, styleSignature);
        if (!template.hasEnglish()) {
            return CompletableFuture.completedFuture(text);
        }

        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null && ModConfig.CACHE_ENABLED.get()) {
            String cached = cache.get(template.cacheKey()).orElse(null);
            if (cached != null) {
                List<Component> restored = template.restore(cached);
                if (restored != null && !restored.isEmpty()) {
                    return CompletableFuture.completedFuture(restored.get(0).getString());
                }
                cache.remove(template.cacheKey());
                cache.save();
            }
        }

        return requestAsync(template).thenApply(restored -> {
            if (restored == null || restored.isEmpty()) {
                return null;
            }
            return restored.get(0).getString();
        });
    }

    public static String getCachedText(String text, String surface, String role, String context,
                                       String layoutSignature, String styleSignature) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Template template = Template.fromComponents(List.of(Component.literal(text)),
                surface == null || surface.isBlank() ? "manager.direct" : surface,
                role == null || role.isBlank() ? "text" : role,
                false,
                context);
        template = template.withSignatures(layoutSignature, styleSignature);
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null || !ModConfig.CACHE_ENABLED.get()) {
            return null;
        }
        String cached = cache.get(template.cacheKey()).orElse(null);
        if (cached == null) {
            return null;
        }
        List<Component> restored = template.restore(cached);
        if (restored == null || restored.isEmpty()) {
            cache.remove(template.cacheKey());
            cache.save();
            return null;
        }
        String translated = restored.get(0).getString();
        return translated == null || translated.isBlank() ? null : translated;
    }

    public static String serializeForTest(List<Component> components, String surface, String role, boolean fixedLayout) {
        return Template.fromComponents(components, surface, role, fixedLayout, "").document();
    }

    public static String requestPayloadForTest(List<Component> components, String surface, String role, boolean fixedLayout) {
        return Template.fromComponents(components, surface, role, fixedLayout, "").requestPayload();
    }

    public static String requestPayloadForTest(List<Component> components, String surface, String role,
                                               boolean fixedLayout, String context) {
        return Template.fromComponents(components, surface, role, fixedLayout, context).requestPayload();
    }

    public static List<Component> restoreForTest(List<Component> components, String surface, String role,
                                                 boolean fixedLayout, String translatedDocument) {
        return Template.fromComponents(components, surface, role, fixedLayout, "").restore(translatedDocument);
    }

    public static List<Component> restorePlainFallbackForTest(List<Component> components, String surface, String role,
                                                              boolean fixedLayout, String translatedText) {
        Template template = Template.fromComponents(components, surface, role, fixedLayout, "");
        String payload = template.plainFallbackDocument(translatedText);
        return payload == null ? null : template.restore(payload);
    }

    public static List<Component> restoreWithPlainFallbackForTest(List<Component> components, String surface, String role,
                                                                  boolean fixedLayout, String translatedText) {
        Template template = Template.fromComponents(components, surface, role, fixedLayout, "");
        List<Component> restored = template.restore(translatedText);
        if (restored != null) {
            return restored;
        }
        String payload = template.plainFallbackDocument(translatedText);
        return payload == null ? null : template.restore(payload);
    }

    private static CompletableFuture<List<Component>> requestAsync(Template template) {
        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            warnLimited("manager-not-ready", template,
                    "Direct formatted translation skipped reason=manager-not-ready surface={} key={} source={}");
            return CompletableFuture.completedFuture(null);
        }

        TranslationTask task = template.task();
        TranslationLane lane = TranslationLanes.forSurface(template.surface());
        if (!lane.begin(task, FAILURE_RETRY_MS)) {
            infoLimited("pending-or-cooldown", template,
                    "Direct formatted translation skipped reason=pending-or-cooldown surface={} key={} source={}");
            return CompletableFuture.completedFuture(null);
        }
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        String sourceLanguage = ModConfig.SOURCE_LANGUAGE.get();
        String targetLanguage = ModConfig.TARGET_LANGUAGE.get();
        if (rememberQueuedLogKey(template.cacheKey())) {
            SimpleTranslateMod.getLogger().debug(
                    "Direct formatted translation queued surface={} key={} source={}",
                    template.surface(), template.cacheKeySummary(), template.sourceSummary());
        }

        return requestTranslatedDocument(manager, template).thenCompose(translatedDocument -> {
            try {
                if (!SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)) {
                    lane.finish(task);
                    infoLimited("stale-response", template,
                            "Direct formatted translation ignored stale response surface={} key={} source={}",
                            false);
                    return CompletableFuture.completedFuture(null);
                }
                if (translatedDocument == null || translatedDocument.isBlank()) {
                    lane.fail(task, FAILURE_RETRY_MS);
                    warnLimited("blank-response", template,
                            "Direct formatted translation failed reason=blank-response surface={} key={} source={}");
                    return CompletableFuture.completedFuture(null);
                }
                String payload = extractDocumentPayload(translatedDocument);
                if (payload == null) {
                    payload = template.plainFallbackDocument(translatedDocument);
                    if (payload == null) {
                        lane.fail(task, FAILURE_RETRY_MS);
                        warnLimited("missing-document", template,
                                "Direct formatted translation rejected reason=missing-document surface={} key={} source={}");
                        return CompletableFuture.completedFuture(null);
                    }
                    infoLimited("plain-fallback", template,
                            "Direct formatted translation recovered reason=plain-fallback surface={} key={} source={}");
                }
                List<Component> restored = payload == null ? null : template.restore(payload);
                if (restored == null) {
                    String fallbackPayload = template.plainFallbackDocument(translatedDocument);
                    if (fallbackPayload != null) {
                        List<Component> fallbackRestored = template.restore(fallbackPayload);
                        if (fallbackRestored != null) {
                            cacheSuccessfulPayload(template, fallbackPayload, runtimeRevision, sourceLanguage, targetLanguage);
                            lane.finish(task);
                            infoLimited("plain-fallback", template,
                                    "Direct formatted translation recovered reason=plain-fallback surface={} key={} source={}");
                            return CompletableFuture.completedFuture(fallbackRestored);
                        }
                    }
                    String detail = template.validationFailureReason(payload);
                    lane.fail(task, FAILURE_RETRY_MS);
                    warnLimited("validation-failed:" + detail, template,
                            "Direct formatted translation rejected reason=validation-failed detail=" + detail
                                    + " surface={} key={} source={}");
                    return CompletableFuture.completedFuture(null);
                }
                cacheSuccessfulPayload(template, payload, runtimeRevision, sourceLanguage, targetLanguage);
                lane.finish(task);
                return CompletableFuture.completedFuture(restored);
            } catch (Exception e) {
                lane.fail(task, FAILURE_RETRY_MS);
                warnLimited("exception", template,
                        "Direct formatted translation validation failed surface={} key={} source={}", e);
                return CompletableFuture.completedFuture(null);
            }
        }).exceptionally(error -> {
            lane.fail(task, FAILURE_RETRY_MS);
            warnLimited(error == null ? "request-error" : error.getClass().getSimpleName(), template,
                    "Direct formatted translation failed reason=" + (error == null ? "request-error" : error.getClass().getSimpleName())
                            + " surface={} key={} source={}", error);
            return null;
        });
    }

    private static void cacheSuccessfulPayload(Template template, String payload, long runtimeRevision,
                                               String sourceLanguage, String targetLanguage) {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null && ModConfig.CACHE_ENABLED.get()
                && SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                && languageMatches(sourceLanguage, ModConfig.SOURCE_LANGUAGE.get())
                && languageMatches(targetLanguage, ModConfig.TARGET_LANGUAGE.get())) {
            cache.put(template.cacheKey(), payload, template.sourceText(), TranslationCache.displayTextFromValue(payload));
            cache.save();
        }
    }

    private static CompletableFuture<String> requestTranslatedDocument(TranslationManager manager, Template template) {
        if (isBatchCandidate(template)) {
            return DirectBatcher.enqueue(manager, template);
        }
        return manager.translateFormattedDocument(template.requestPayload());
    }

    private static boolean isBatchCandidate(Template template) {
        if (template == null || template.fixedLayout()) {
            return false;
        }
        String surface = template.surface() == null ? "" : template.surface().toLowerCase(Locale.ROOT);
        if (surface.startsWith("hud.title") || surface.startsWith("hud.actionbar")
                || surface.startsWith("tooltip.") || surface.startsWith("hover.")
                || surface.startsWith("sign.")) {
            return false;
        }
        if (!(surface.startsWith("chat.system")
                || surface.startsWith("scoreboard.")
                || surface.startsWith("advancement.")
                || surface.startsWith("bossbar.")
                || surface.startsWith("entity.")
                || surface.startsWith("text_display."))) {
            return false;
        }
        return template.requestPayload().length() <= MAX_BATCH_CHARS / 2;
    }

    private static boolean rememberQueuedLogKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (QUEUED_LOGGED_KEYS.size() > MAX_QUEUED_LOG_KEYS) {
            synchronized (QUEUED_LOGGED_KEYS) {
                if (QUEUED_LOGGED_KEYS.size() > MAX_QUEUED_LOG_KEYS) {
                    QUEUED_LOGGED_KEYS.clear();
                }
            }
        }
        return QUEUED_LOGGED_KEYS.add(key);
    }

    private static boolean languageMatches(String expected, String current) {
        return expected == null ? current == null : expected.equals(current);
    }

    private static void infoLimited(String reason, Template template, String message) {
        infoLimited(reason, template, message, true);
    }

    private static void infoLimited(String reason, Template template, String message, boolean includeStandardArgs) {
        if (!shouldLog(reason, template)) {
            return;
        }
        if (includeStandardArgs) {
            SimpleTranslateMod.getLogger().debug(message, template.surface(), template.cacheKeySummary(), template.sourceSummary());
        } else {
            SimpleTranslateMod.getLogger().debug(message, template.surface(), template.cacheKeySummary(), template.sourceSummary());
        }
    }

    private static void warnLimited(String reason, Template template, String message) {
        warnLimited(reason, template, message, null);
    }

    private static void warnLimited(String reason, Template template, String message, Throwable error) {
        if (!shouldLog(reason, template)) {
            return;
        }
        if (error == null) {
            SimpleTranslateMod.getLogger().warn(message, template.surface(), template.cacheKeySummary(), template.sourceSummary());
        } else {
            SimpleTranslateMod.getLogger().warn(message, template.surface(), template.cacheKeySummary(), template.sourceSummary(), error);
        }
    }

    private static boolean shouldLog(String reason, Template template) {
        String key = (reason == null ? "unknown" : reason) + "|" + (template == null ? "" : template.surface());
        long now = System.currentTimeMillis();
        Long previous = DIAGNOSTIC_LAST_LOG.get(key);
        if (previous != null && now - previous < DIAGNOSTIC_REPEAT_MS) {
            return false;
        }
        DIAGNOSTIC_LAST_LOG.put(key, now);
        if (DIAGNOSTIC_LAST_LOG.size() > 2048) {
            Set<String> remove = new HashSet<>();
            for (Map.Entry<String, Long> entry : DIAGNOSTIC_LAST_LOG.entrySet()) {
                if (now - entry.getValue() > DIAGNOSTIC_REPEAT_MS * 4) {
                    remove.add(entry.getKey());
                }
            }
            remove.forEach(DIAGNOSTIC_LAST_LOG::remove);
        }
        return true;
    }

    private static String extractDocumentPayload(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf("<st-doc");
        int end = trimmed.lastIndexOf("</st-doc>");
        if (start < 0 || end < start) {
            return null;
        }
        end += "</st-doc>".length();
        return trimmed.substring(start, end).trim();
    }

    private static String extractBatchPayload(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf("<st-batch");
        int end = trimmed.lastIndexOf("</st-batch>");
        if (start < 0 || end < start) {
            return null;
        }
        end += "</st-batch>".length();
        return trimmed.substring(start, end).trim();
    }

    private static Map<String, String> attrs(String raw) {
        Map<String, String> attrs = new LinkedHashMap<>();
        Matcher matcher = ATTR_PATTERN.matcher(raw == null ? "" : raw);
        while (matcher.find()) {
            attrs.put(matcher.group(1), unescape(matcher.group(2)));
        }
        return attrs;
    }

    private static boolean containsEnglish(String text) {
        return TranslationTextDetector.containsTranslatableText(text);
    }

    private static boolean isEditableRun(String text, Style style, String surface) {
        if (!containsEnglish(text)) {
            return false;
        }
        Style effectiveStyle = style == null ? Style.EMPTY : style;
        if (effectiveStyle.isObfuscated()) {
            return false;
        }
        return !isLegacyFormattedNoise(text, surface);
    }

    private static boolean shouldTranslateFreeLineAsUnit(String surface, String role,
                                                         List<TextSegmentInfo> segments, String lineText) {
        String effectiveSurface = surface == null ? "" : surface.toLowerCase(Locale.ROOT);
        String effectiveRole = role == null ? "" : role.toLowerCase(Locale.ROOT);
        boolean tooltipLike = effectiveSurface.startsWith("tooltip.")
                || effectiveSurface.startsWith("hover.")
                || effectiveRole.contains("tooltip")
                || effectiveRole.contains("hover");
        if (!tooltipLike || !containsEnglish(lineText)) {
            return false;
        }

        int editableSegments = 0;
        int singleCharSegments = 0;
        int tinySegments = 0;
        int maxEditableLength = 0;
        Set<String> editableStyleSignatures = new HashSet<>();
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null || segment.text.isBlank()) {
                continue;
            }
            Style style = segment.style == null ? Style.EMPTY : segment.style;
            if (!isEditableRun(segment.text, style, surface)) {
                continue;
            }
            int length = segment.text.trim().codePointCount(0, segment.text.trim().length());
            editableSegments++;
            editableStyleSignatures.add(styleSignature(style));
            maxEditableLength = Math.max(maxEditableLength, length);
            if (length <= 1) {
                singleCharSegments++;
            }
            if (length <= 3) {
                tinySegments++;
            }
        }

        if (editableStyleSignatures.size() > 1) {
            return false;
        }

        return editableSegments > 0
                && (editableSegments <= 2
                || editableSegments >= 6
                || singleCharSegments >= 2
                || (editableSegments >= 4 && maxEditableLength <= 8)
                || tinySegments >= 3);
    }

    private static boolean isLegacyFormattedNoise(String text, String surface) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String effectiveSurface = surface == null ? "" : surface.toLowerCase(Locale.ROOT);
        if (!effectiveSurface.startsWith("hud.") && !effectiveSurface.startsWith("title.")
                && !effectiveSurface.startsWith("actionbar.")) {
            return false;
        }

        String normalized = TranslationTextDetector.normalizeForDetection(text);
        Matcher matcher = ASCII_TOKEN_PATTERN.matcher(normalized);
        int tokens = 0;
        int artifactTokens = 0;
        int meaningfulTokens = 0;
        while (matcher.find()) {
            String token = matcher.group();
            if (token == null || token.isBlank()) {
                continue;
            }
            tokens++;
            if (isLegacyArtifactToken(token)) {
                artifactTokens++;
                continue;
            }
            if (isMeaningfulHudToken(token)) {
                meaningfulTokens++;
            }
        }
        return tokens > 0 && artifactTokens > 0 && meaningfulTokens == 0;
    }

    private static boolean isLegacyArtifactToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.matches("[0-9a-frklmno]{2,}")) {
            return true;
        }
        if (containsDigit(token) && legacyCodeRatio(lower) >= 0.55D) {
            return true;
        }
        String stripped = stripLegacyCodePrefix(token);
        return !stripped.equals(token) && !isMeaningfulHudToken(stripped);
    }

    private static double legacyCodeRatio(String token) {
        if (token == null || token.isBlank()) {
            return 0.0D;
        }
        int codeLike = 0;
        int total = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                continue;
            }
            total++;
            if ((c >= '0' && c <= '9') || "abcdefklmnor".indexOf(c) >= 0) {
                codeLike++;
            }
        }
        return total == 0 ? 0.0D : (double) codeLike / (double) total;
    }

    private static String stripLegacyCodePrefix(String token) {
        if (token == null || token.length() < 2) {
            return token == null ? "" : token;
        }
        int index = 0;
        while (index < token.length() - 1 && isLegacyFormatCodeChar(token.charAt(index))) {
            index++;
            if (index >= 4) {
                break;
            }
        }
        if (index == 0 || index >= token.length()) {
            return token;
        }
        return token.substring(index);
    }

    private static boolean isLegacyFormatCodeChar(char c) {
        return (c >= '0' && c <= '9') || "abcdefklmnor".indexOf(c) >= 0;
    }

    private static boolean isMeaningfulHudToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String stripped = stripLegacyCodePrefix(token).replaceAll("[^A-Za-z]", "");
        if (stripped.length() < 2) {
            return false;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (TRANSLATABLE_SINGLE_WORDS.contains(lower)) {
            return true;
        }
        if (stripped.matches("[A-Z]{2,}")) {
            return true;
        }
        if (stripped.matches("[a-z]{3,}") && !stripped.matches("[a-fk-or]+") && hasAsciiVowel(stripped)) {
            return true;
        }
        return Character.isUpperCase(stripped.charAt(0)) && hasLowercase(stripped) && !stripped.matches(".*\\d.*");
    }

    private static boolean hasAsciiVowel(String token) {
        if (token == null) {
            return false;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.indexOf('a') >= 0 || lower.indexOf('e') >= 0 || lower.indexOf('i') >= 0
                || lower.indexOf('o') >= 0 || lower.indexOf('u') >= 0;
    }

    private static boolean hasLowercase(String token) {
        if (token == null) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (Character.isLowerCase(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String unescape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&quot;", "\"")
                .replace("&gt;", ">")
                .replace("&lt;", "<")
                .replace("&amp;", "&");
    }

    private static String contextSection(String context) {
        if (context == null || context.isBlank()) {
            return "";
        }
        String normalized = context.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() > MAX_DIRECT_CONTEXT_CHARS) {
            int head = Math.max(0, MAX_DIRECT_CONTEXT_CHARS / 2);
            int tail = Math.max(0, MAX_DIRECT_CONTEXT_CHARS - head);
            normalized = normalized.substring(0, Math.min(head, normalized.length()))
                    + "\n...[context truncated for request size]...\n"
                    + normalized.substring(Math.max(0, normalized.length() - tail));
        }
        return "<st-context>" + escape(normalized) + "</st-context>\n";
    }

    private static String normalizedPlainContextSection(String sourceText) {
        String normalized = TranslationTextDetector.normalizeForDetection(sourceText);
        if (normalized.isBlank() || normalized.equals(sourceText == null ? "" : sourceText.trim())) {
            return "";
        }
        return "<st-normalized-plain-context>" + escape(normalized) + "</st-normalized-plain-context>\n";
    }

    private static boolean isLineTextMode(String surface, String role) {
        return (ITEM_TOOLTIP_CONTEXT_SURFACE.equals(surface) && ITEM_TOOLTIP_CONTEXT_ROLE.equals(role))
                || ("hud.history.caption_batch.direct".equals(surface) && "hud-caption-history-batch".equals(role))
                || ("chat-text".equals(role)
                && ("chat.context.direct".equals(surface) || "chat.message.segment.direct".equals(surface)));
    }

    private static boolean isPlainFallbackRecoverableSurface(String surface) {
        if (surface == null) {
            return false;
        }
        return surface.startsWith("chat.")
                || surface.startsWith("hud.")
                || surface.startsWith("text_display.")
                || ITEM_TOOLTIP_CONTEXT_SURFACE.equals(surface);
    }

    private static String normalizePlainFallbackText(String translatedText, String surface) {
        if (translatedText == null) {
            return null;
        }
        String translated = ModelOutputSanitizer.sanitize(translatedText);
        if (translated == null) {
            return null;
        }
        translated = translated.trim();
        if (translated.isBlank()) {
            return "";
        }
        if (isPlainFallbackRecoverableSurface(surface)) {
            String focused = directDocumentRegion(translated);
            String stripped = KNOWN_DIRECT_TAG_PATTERN.matcher(focused).replaceAll("").trim();
            if (!stripped.equals(focused.trim())) {
                translated = ModelOutputSanitizer.sanitize(unescape(stripped));
                translated = translated == null ? "" : translated.trim();
            }
        }
        return translated;
    }

    private static String directDocumentRegion(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int start = lower.indexOf("<st-doc");
        if (start < 0) {
            return text;
        }
        int end = lower.indexOf("</st-doc>", start);
        if (end >= 0) {
            return text.substring(start, Math.min(text.length(), end + "</st-doc>".length()));
        }
        return text.substring(start);
    }

    private record Template(String surface,
                            String role,
                            String context,
                            String document,
                            String requestPayload,
                            String sourceText,
                            String layoutSignature,
                            String styleSignature,
                            boolean fixedLayout,
                            boolean lineTextMode,
                            List<LineTemplate> lines,
                            boolean hasEnglish) {
        private static Template fromComponents(List<Component> components, String surface, String role,
                                               boolean fixedLayout, String context) {
            List<Component> safeComponents = components == null ? List.of() : components;
            String effectiveSurface = surface == null || surface.isBlank() ? "generic.direct" : surface;
            String effectiveRole = role == null || role.isBlank() ? "component" : role;
            boolean lineTextMode = isLineTextMode(effectiveSurface, effectiveRole);
            String mode = lineTextMode ? LINE_TEXT_MODE : fixedLayout ? "fixed" : "free";
            List<LineTemplate> lines = new ArrayList<>();
            StringBuilder doc = new StringBuilder();
            StringBuilder plain = new StringBuilder();
            StringBuilder source = new StringBuilder();
            StringBuilder layout = new StringBuilder(mode);
            StringBuilder style = new StringBuilder();
            boolean hasEnglish = false;

            plain.append("<st-plain-context surface=\"").append(escape(effectiveSurface))
                    .append("\" role=\"").append(escape(effectiveRole))
                    .append("\" fixed=\"").append(fixedLayout).append("\">");
            doc.append("<st-doc v=\"direct-v4\" mode=\"").append(mode)
                    .append("\" surface=\"").append(escape(effectiveSurface)).append("\" role=\"")
                    .append(escape(effectiveRole)).append("\" fixed=\"").append(fixedLayout).append("\">");
            for (int i = 0; i < safeComponents.size(); i++) {
                Component component = safeComponents.get(i);
                String lineText = component == null ? "" : component.getString();
                List<TextSegmentInfo> segments = new ArrayList<>();
                ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
                List<RunTemplate> runs = new ArrayList<>();
                plain.append("<line i=\"").append(i).append("\">")
                        .append(escape(lineText))
                        .append("</line>");
                layout.append("|line:").append(i);
                if (source.length() > 0) {
                    source.append('\n');
                }
                source.append(lineText);
                Style baseStyle = chooseSegmentBaseStyle(segments, effectiveSurface);
                if (!fixedLayout
                        && shouldTranslateFreeLineAsUnit(effectiveSurface, effectiveRole, segments, lineText)
                        && isEditableRun(lineText, baseStyle, effectiveSurface)) {
                    runs.add(new RunTemplate("r" + i + "_0", lineText, baseStyle, true));
                    hasEnglish = true;
                } else {
                    for (TextSegmentInfo segment : segments) {
                        if (segment == null || segment.text == null || segment.text.isEmpty()) {
                            continue;
                        }
                        Style runStyle = segment.style == null ? Style.EMPTY : segment.style;
                        boolean editable = isEditableRun(segment.text, runStyle, effectiveSurface);
                        hasEnglish |= editable;
                        if (!runs.isEmpty()) {
                            RunTemplate previous = runs.get(runs.size() - 1);
                            if (previous.editable() == editable
                                    && previous.styleSignature().equals(DirectFormattedTranslationPipeline.styleSignature(runStyle))) {
                                runs.set(runs.size() - 1,
                                        new RunTemplate(previous.id(), previous.sourceText() + segment.text,
                                                previous.style(), previous.editable()));
                                continue;
                            }
                        }
                        String id = "r" + i + "_" + runs.size();
                        runs.add(new RunTemplate(id, segment.text, runStyle, editable));
                    }
                    if (!lineTextMode) {
                        baseStyle = chooseBaseStyle(runs);
                    }
                }
                doc.append("<line i=\"").append(i).append("\" base=\"")
                        .append(escape(DirectFormattedTranslationPipeline.styleSignature(baseStyle))).append("\">");
                if (lineTextMode) {
                    layout.append(":text");
                    style.append("|line").append(i).append("=").append(DirectFormattedTranslationPipeline.styleSignature(baseStyle));
                    doc.append(escape(lineText));
                } else {
                    for (RunTemplate run : runs) {
                        layout.append(":").append(run.id());
                        style.append("|").append(run.id()).append("=").append(run.styleSignature());
                        if (fixedLayout) {
                            doc.append("<run id=\"").append(run.id())
                                    .append("\" editable=\"").append(run.editable())
                                    .append("\" style=\"").append(escape(run.styleSignature()))
                                    .append("\">").append(escape(run.sourceText())).append("</run>");
                        } else {
                            doc.append("<g id=\"").append(run.id())
                                    .append("\">").append(escape(run.sourceText())).append("</g>");
                        }
                    }
                }
                doc.append("</line>");
                lines.add(new LineTemplate(i, runs, baseStyle, component == null ? "" : component.getString()));
            }
            doc.append("</st-doc>");
            plain.append("</st-plain-context>");
            String document = doc.toString();
            String glossary = DirectStatusTerms.glossarySection(source.toString());
            String requestPayload = contextSection(context)
                    + plain
                    + "\n" + normalizedPlainContextSection(source.toString())
                    + (glossary.isEmpty() ? "" : "\n" + glossary)
                    + "\n" + document;
            return new Template(
                    effectiveSurface,
                    effectiveRole,
                    context == null ? "" : context,
                    document,
                    requestPayload,
                    source.toString(),
                    layout.toString(),
                    style.toString(),
                    fixedLayout,
                    lineTextMode,
                    List.copyOf(lines),
                    hasEnglish);
        }

        private Template withSignatures(String layout, String style) {
            String effectiveLayout = layout == null || layout.isBlank() ? layoutSignature : layoutSignature + "|" + layout;
            String effectiveStyle = style == null || style.isBlank() ? styleSignature : styleSignature + "|" + style;
            return new Template(surface, role, context, document, requestPayload, sourceText, effectiveLayout, effectiveStyle,
                    fixedLayout, lineTextMode, lines, hasEnglish);
        }

        private String cacheKey() {
            return TranslationCacheKeys.key(surface, sourceText, context, layoutSignature, styleSignature);
        }

        private String sourceSummary() {
            String summary = sourceText == null ? "" : sourceText.replace('\n', ' ').replace('\r', ' ').trim();
            if (summary.length() > 120) {
                return summary.substring(0, 117) + "...";
            }
            return summary;
        }

        private String cacheKeySummary() {
            String key = cacheKey();
            if (key == null || key.isBlank()) {
                return "none";
            }
            return Integer.toHexString(key.hashCode());
        }

        private TranslationTask task() {
            return TranslationTask.create(surface, sourceText, context, layoutSignature, styleSignature, List.of());
        }

        private List<Component> restore(String translatedDocument) {
            String payload = extractDocumentPayload(translatedDocument);
            if (payload == null) {
                return null;
            }
            int rootStartEnd = payload.indexOf('>');
            if (rootStartEnd < 0) {
                return null;
            }
            Map<String, String> rootAttrs = attrs(payload.substring(0, rootStartEnd));
            String expectedMode = lineTextMode ? LINE_TEXT_MODE : fixedLayout ? "fixed" : "free";
            if (!"direct-v4".equals(rootAttrs.get("v"))
                    || !expectedMode.equals(rootAttrs.get("mode"))
                    || !surface.equals(rootAttrs.get("surface"))
                    || !role.equals(rootAttrs.get("role"))
                    || !Boolean.toString(fixedLayout).equals(rootAttrs.get("fixed"))) {
                return null;
            }
            return lineTextMode ? restoreLineText(payload) : fixedLayout ? restoreFixed(payload) : restoreFree(payload);
        }

        private String plainFallbackDocument(String translatedText) {
            if (fixedLayout || translatedText == null || translatedText.isBlank()) {
                return null;
            }

            String translated = normalizePlainFallbackText(translatedText, surface);
            if (translated == null) {
                return null;
            }
            translated = translated.trim();
            String lowered = translated.toLowerCase(Locale.ROOT);
            if (translated.isBlank()
                    || lowered.contains("<st-")
                    || lowered.contains("<line")
                    || lowered.contains("<run")
                    || lowered.contains("<g ")
                    || lowered.contains("</")
                    || translated.contains("@@@")
            ) {
                return null;
            }

            if (lineTextMode) {
                String[] translatedLines = splitPlainFallbackLines(translated, lines.size());
                if (translatedLines == null) {
                    return null;
                }
                StringBuilder fallback = new StringBuilder();
                fallback.append("<st-doc v=\"direct-v4\" mode=\"").append(LINE_TEXT_MODE)
                        .append("\" surface=\"").append(escape(surface))
                        .append("\" role=\"").append(escape(role))
                        .append("\" fixed=\"false\">");
                for (int i = 0; i < lines.size(); i++) {
                    LineTemplate line = lines.get(i);
                    fallback.append("<line i=\"").append(i)
                            .append("\" base=\"")
                            .append(escape(DirectFormattedTranslationPipeline.styleSignature(line.baseStyle())))
                            .append("\">")
                            .append(escape(translatedLines[i].trim()))
                            .append("</line>");
                }
                fallback.append("</st-doc>");
                return fallback.toString();
            }

            if (lines.size() != 1) {
                return null;
            }
            LineTemplate line = lines.get(0);
            if (line == null || line.runs().size() != 1) {
                return null;
            }
            RunTemplate run = line.runs().get(0);
            if (run == null || !run.editable()) {
                return null;
            }
            return "<st-doc v=\"direct-v4\" mode=\"free\" surface=\"" + escape(surface)
                    + "\" role=\"" + escape(role)
                    + "\" fixed=\"false\"><line i=\"0\" base=\""
                    + escape(DirectFormattedTranslationPipeline.styleSignature(line.baseStyle()))
                    + "\"><g id=\"" + escape(run.id()) + "\">"
                    + escape(translated)
                    + "</g></line></st-doc>";
        }

        @Nullable
        private String[] splitPlainFallbackLines(String translated, int expectedLineCount) {
            if (expectedLineCount <= 0 || translated == null || translated.isBlank()) {
                return null;
            }
            if (expectedLineCount == 1) {
                return new String[] { translated.replace('\r', ' ').replace('\n', ' ').trim() };
            }
            String[] split = translated.split("\\R", -1);
            if (split.length != expectedLineCount) {
                return null;
            }
            return split;
        }

        private String validationFailureReason(String translatedDocument) {
            String payload = extractDocumentPayload(translatedDocument);
            if (payload == null) {
                return "missing-document";
            }
            int rootStartEnd = payload.indexOf('>');
            if (rootStartEnd < 0) {
                return "missing-root-end";
            }
            Map<String, String> rootAttrs = attrs(payload.substring(0, rootStartEnd));
            if (!"direct-v4".equals(rootAttrs.get("v"))) {
                return "root-version expected=direct-v4 actual=" + rootAttrs.getOrDefault("v", "");
            }
            String expectedMode = lineTextMode ? LINE_TEXT_MODE : fixedLayout ? "fixed" : "free";
            if (!expectedMode.equals(rootAttrs.get("mode"))) {
                return "root-mode expected=" + expectedMode + " actual=" + rootAttrs.getOrDefault("mode", "");
            }
            if (!surface.equals(rootAttrs.get("surface"))) {
                return "root-surface expected=" + surface + " actual=" + rootAttrs.getOrDefault("surface", "");
            }
            if (!role.equals(rootAttrs.get("role"))) {
                return "root-role expected=" + role + " actual=" + rootAttrs.getOrDefault("role", "");
            }
            if (!Boolean.toString(fixedLayout).equals(rootAttrs.get("fixed"))) {
                return "root-fixed expected=" + fixedLayout + " actual=" + rootAttrs.getOrDefault("fixed", "");
            }
            return lineTextMode ? lineTextValidationFailure(payload) : fixedLayout ? fixedValidationFailure(payload) : freeValidationFailure(payload);
        }

        private String fixedValidationFailure(String payload) {
            Matcher lineMatcher = LINE_PATTERN.matcher(payload);
            int expectedLine = 0;
            while (lineMatcher.find()) {
                Map<String, String> lineAttrs = attrs(lineMatcher.group(1));
                int lineIndex;
                try {
                    lineIndex = Integer.parseInt(lineAttrs.getOrDefault("i", ""));
                } catch (NumberFormatException e) {
                    return "line-index-nan line=" + expectedLine + " actual=" + lineAttrs.getOrDefault("i", "");
                }
                if (lineIndex != expectedLine || lineIndex >= lines.size()) {
                    return "line-index expected=" + expectedLine + " actual=" + lineIndex + " total=" + lines.size();
                }
                LineTemplate template = lines.get(lineIndex);
                Matcher runMatcher = RUN_PATTERN.matcher(lineMatcher.group(2));
                int expectedRun = 0;
                while (runMatcher.find()) {
                    if (expectedRun >= template.runs().size()) {
                        return "run-extra line=" + lineIndex + " actualIndex=" + expectedRun
                                + " expectedCount=" + template.runs().size();
                    }
                    RunTemplate run = template.runs().get(expectedRun);
                    Map<String, String> runAttrs = attrs(runMatcher.group(1));
                    String actualId = runAttrs.get("id");
                    if (!run.id().equals(actualId)) {
                        return "run-id line=" + lineIndex + " run=" + expectedRun
                                + " expected=" + run.id() + " actual=" + (actualId == null ? "" : actualId);
                    }
                    String translated = DirectStatusTerms.apply(run.sourceText(), unescape(runMatcher.group(2)));
                    String signFailure = fixedSignTokenFailure(run, translated);
                    if (signFailure != null) {
                        return signFailure + " line=" + lineIndex + " run=" + run.id();
                    }
                    expectedRun++;
                }
                if (expectedRun != template.runs().size()) {
                    return "run-count line=" + lineIndex + " expected=" + template.runs().size()
                            + " actual=" + expectedRun;
                }
                expectedLine++;
            }
            if (expectedLine != lines.size()) {
                return "line-count expected=" + lines.size() + " actual=" + expectedLine;
            }
            return "unknown-fixed";
        }

        private String freeValidationFailure(String payload) {
            Matcher lineMatcher = LINE_PATTERN.matcher(payload);
            int expectedLine = 0;
            while (lineMatcher.find()) {
                Map<String, String> lineAttrs = attrs(lineMatcher.group(1));
                int lineIndex;
                try {
                    lineIndex = Integer.parseInt(lineAttrs.getOrDefault("i", ""));
                } catch (NumberFormatException e) {
                    return "line-index-nan line=" + expectedLine + " actual=" + lineAttrs.getOrDefault("i", "");
                }
                if (lineIndex != expectedLine || lineIndex >= lines.size()) {
                    return "line-index expected=" + expectedLine + " actual=" + lineIndex + " total=" + lines.size();
                }

                LineTemplate template = lines.get(lineIndex);
                Map<String, RunTemplate> byId = new LinkedHashMap<>();
                Map<String, Boolean> seen = new LinkedHashMap<>();
                for (RunTemplate run : template.runs()) {
                    byId.put(run.id(), run);
                    seen.put(run.id(), false);
                }

                String body = lineMatcher.group(2);
                Matcher groupMatcher = GROUP_PATTERN.matcher(body);
                int cursor = 0;
                while (groupMatcher.find()) {
                    if (groupMatcher.start() < cursor) {
                        return "group-overlap line=" + lineIndex;
                    }
                    String base = body.substring(cursor, groupMatcher.start());
                    if (!base.isEmpty()) {
                        String text = DirectStatusTerms.apply(template.sourceText(), unescape(base));
                    }

                    Map<String, String> groupAttrs = attrs(groupMatcher.group(1));
                    String id = groupAttrs.get("id");
                    RunTemplate run = byId.get(id);
                    if (run == null) {
                        return "group-unknown line=" + lineIndex + " id=" + (id == null ? "" : id);
                    }
                    if (seen.getOrDefault(id, false)) {
                        return "group-duplicate line=" + lineIndex + " id=" + id;
                    }
                    seen.put(id, true);
                    String translated = DirectStatusTerms.apply(run.sourceText(), unescape(groupMatcher.group(2)));
                    cursor = groupMatcher.end();
                }
                String tail = body.substring(cursor);
                if (!tail.isEmpty()) {
                    String text = DirectStatusTerms.apply(template.sourceText(), unescape(tail));
                }
                for (Map.Entry<String, Boolean> entry : seen.entrySet()) {
                    if (!entry.getValue()) {
                        return "group-missing line=" + lineIndex + " id=" + entry.getKey();
                    }
                }
                expectedLine++;
            }
            if (expectedLine != lines.size()) {
                return "line-count expected=" + lines.size() + " actual=" + expectedLine;
            }
            return "unknown-free";
        }

        private String lineTextValidationFailure(String payload) {
            Matcher lineMatcher = LINE_PATTERN.matcher(payload);
            int expectedLine = 0;
            while (lineMatcher.find()) {
                Map<String, String> lineAttrs = attrs(lineMatcher.group(1));
                int lineIndex;
                try {
                    lineIndex = Integer.parseInt(lineAttrs.getOrDefault("i", ""));
                } catch (NumberFormatException e) {
                    return "line-index-nan line=" + expectedLine + " actual=" + lineAttrs.getOrDefault("i", "");
                }
                if (lineIndex != expectedLine || lineIndex >= lines.size()) {
                    return "line-index expected=" + expectedLine + " actual=" + lineIndex + " total=" + lines.size();
                }
                String body = lineMatcher.group(2);
                if (!isSafeLineTextBody(body)) {
                    return "line-text-tag line=" + lineIndex;
                }
                expectedLine++;
            }
            if (expectedLine != lines.size()) {
                return "line-count expected=" + lines.size() + " actual=" + expectedLine;
            }
            return "unknown-line-text";
        }

        private List<Component> restoreFixed(String payload) {
            Matcher lineMatcher = LINE_PATTERN.matcher(payload);
            List<Component> restored = new ArrayList<>();
            int expectedLine = 0;
            while (lineMatcher.find()) {
                Map<String, String> lineAttrs = attrs(lineMatcher.group(1));
                String lineIndexText = lineAttrs.getOrDefault("i", "");
                int lineIndex;
                try {
                    lineIndex = Integer.parseInt(lineIndexText);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (lineIndex != expectedLine || lineIndex >= lines.size()) {
                    return null;
                }
                LineTemplate template = lines.get(lineIndex);
                MutableComponent line = Component.empty();
                Matcher runMatcher = RUN_PATTERN.matcher(lineMatcher.group(2));
                int expectedRun = 0;
                while (runMatcher.find()) {
                    if (expectedRun >= template.runs().size()) {
                        return null;
                    }
                    RunTemplate run = template.runs().get(expectedRun++);
                    Map<String, String> runAttrs = attrs(runMatcher.group(1));
                    if (!run.id().equals(runAttrs.get("id"))) {
                        return null;
                    }
                    String translated = DirectStatusTerms.apply(run.sourceText(), unescape(runMatcher.group(2)));
                    if (fixedSignTokenFailure(run, translated) != null) {
                        return null;
                    }
                    String text = run.editable() ? translated : run.sourceText();
                    line.append(Component.literal(text).withStyle(run.style()));
                }
                if (expectedRun != template.runs().size()) {
                    return null;
                }
                restored.add(line);
                expectedLine++;
            }
            if (expectedLine != lines.size()) {
                return null;
            }
            return restored;
        }

        private List<Component> restoreLineText(String payload) {
            Matcher lineMatcher = LINE_PATTERN.matcher(payload);
            List<Component> restored = new ArrayList<>();
            int expectedLine = 0;
            while (lineMatcher.find()) {
                Map<String, String> lineAttrs = attrs(lineMatcher.group(1));
                String lineIndexText = lineAttrs.getOrDefault("i", "");
                int lineIndex;
                try {
                    lineIndex = Integer.parseInt(lineIndexText);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (lineIndex != expectedLine || lineIndex >= lines.size()) {
                    return null;
                }
                String body = lineMatcher.group(2);
                if (!isSafeLineTextBody(body)) {
                    return null;
                }
                LineTemplate template = lines.get(lineIndex);
                String translated = DirectStatusTerms.apply(template.sourceText(), unescape(body));
                String text = shouldKeepOriginalLineText(template, translated) ? template.sourceText() : translated;
                restored.add(restoreLineTextWithStyleAnchors(template, text));
                expectedLine++;
            }
            if (expectedLine != lines.size()) {
                return null;
            }
            return restored;
        }

        private Component restoreLineTextWithStyleAnchors(LineTemplate template, String text) {
            String value = text == null ? "" : text;
            Style baseStyle = template.baseStyle() == null ? Style.EMPTY : template.baseStyle();
            List<StyledRange> ranges = lineTextStyleAnchors(template, value);
            if (ranges.isEmpty()) {
                return Component.literal(value).withStyle(baseStyle);
            }
            ranges.sort((left, right) -> Integer.compare(left.start(), right.start()));
            MutableComponent component = Component.empty();
            int cursor = 0;
            for (StyledRange range : ranges) {
                if (range.start() < cursor || range.start() >= range.end() || range.end() > value.length()) {
                    continue;
                }
                if (range.start() > cursor) {
                    component.append(Component.literal(value.substring(cursor, range.start())).withStyle(baseStyle));
                }
                component.append(Component.literal(value.substring(range.start(), range.end())).withStyle(range.style()));
                cursor = range.end();
            }
            if (cursor < value.length()) {
                component.append(Component.literal(value.substring(cursor)).withStyle(baseStyle));
            }
            return component;
        }

        private List<StyledRange> lineTextStyleAnchors(LineTemplate template, String translatedText) {
            if (template == null || template.runs() == null || template.runs().isEmpty()
                    || translatedText == null || translatedText.isEmpty()) {
                return List.of();
            }
            Style baseStyle = template.baseStyle() == null ? Style.EMPTY : template.baseStyle();
            String baseSignature = DirectFormattedTranslationPipeline.styleSignature(baseStyle);
            List<StyledRange> ranges = new ArrayList<>();
            List<Style> sourceBracketStyles = new ArrayList<>();
            for (RunTemplate run : template.runs()) {
                if (run == null || run.sourceText() == null || run.sourceText().isBlank()
                        || run.style() == null) {
                    continue;
                }
                String source = run.sourceText().trim();
                Matcher bracketMatcher = BRACKETED_TOKEN_PATTERN.matcher(source);
                while (bracketMatcher.find()) {
                    sourceBracketStyles.add(run.style());
                }
                if (run.styleSignature().equals(baseSignature)) {
                    continue;
                }
                if (source.length() >= 2 && shouldCarryLineTextStyle(source)) {
                    addExactStyledRanges(translatedText, source, run.style(), ranges);
                }
            }
            if (!sourceBracketStyles.isEmpty()) {
                List<StyledRange> translatedBracketRanges = new ArrayList<>();
                Matcher matcher = BRACKETED_TOKEN_PATTERN.matcher(translatedText);
                while (matcher.find()) {
                    translatedBracketRanges.add(new StyledRange(matcher.start(), matcher.end(), baseStyle));
                }
                if (sourceBracketStyles.size() == translatedBracketRanges.size()) {
                    for (int i = 0; i < sourceBracketStyles.size(); i++) {
                        StyledRange bracket = translatedBracketRanges.get(i);
                        addStyledRange(ranges, bracket.start(), bracket.end(), sourceBracketStyles.get(i));
                    }
                }
            }
            return ranges;
        }

        private boolean shouldCarryLineTextStyle(String source) {
            return isBracketedToken(source)
                    || source.matches("[+-]?\\d+(?:\\.\\d+)?(?:/[+-]?\\d+(?:\\.\\d+)?)*%?")
                    || source.matches("[A-Za-z][A-Za-z0-9_.'\\-]*(?:,?\\s+[A-Za-z][A-Za-z0-9_.'\\-]*){0,3}");
        }

        private boolean isBracketedToken(String source) {
            return source != null && BRACKETED_TOKEN_PATTERN.matcher(source.trim()).matches();
        }

        private void addExactStyledRanges(String translatedText, String source, Style style, List<StyledRange> ranges) {
            int cursor = 0;
            while (cursor <= translatedText.length() - source.length()) {
                int index = indexOfIgnoreCase(translatedText, source, cursor);
                if (index < 0) {
                    return;
                }
                addStyledRange(ranges, index, index + source.length(), style);
                cursor = index + Math.max(1, source.length());
            }
        }

        private int indexOfIgnoreCase(String text, String needle, int fromIndex) {
            if (text == null || needle == null || needle.isEmpty()) {
                return -1;
            }
            int max = text.length() - needle.length();
            for (int i = Math.max(0, fromIndex); i <= max; i++) {
                if (text.regionMatches(true, i, needle, 0, needle.length())) {
                    return i;
                }
            }
            return -1;
        }

        private void addStyledRange(List<StyledRange> ranges, int start, int end, Style style) {
            if (start < 0 || end <= start || style == null) {
                return;
            }
            for (StyledRange range : ranges) {
                if (start < range.end() && end > range.start()) {
                    return;
                }
            }
            ranges.add(new StyledRange(start, end, style));
        }

        private boolean isSafeLineTextBody(String body) {
            if (body == null) {
                return true;
            }
            return !body.contains("<st-")
                    && !body.contains("<line")
                    && !body.contains("</line")
                    && !body.contains("<run")
                    && !body.contains("</run")
                    && !body.contains("<g")
                    && !body.contains("</g")
                    && !body.contains("</st-");
        }

        private boolean shouldKeepOriginalLineText(LineTemplate template, String translated) {
            if (template == null) {
                return translated == null || translated.isBlank();
            }
            String source = template.sourceText() == null ? "" : template.sourceText();
            if (source.isBlank()) {
                return true;
            }
            if (translated == null || translated.isBlank()) {
                return true;
            }
            return !TranslationTextDetector.containsTranslatableText(source);
        }

        private List<Component> restoreFree(String payload) {
            Matcher lineMatcher = LINE_PATTERN.matcher(payload);
            List<Component> restored = new ArrayList<>();
            int expectedLine = 0;
            while (lineMatcher.find()) {
                Map<String, String> lineAttrs = attrs(lineMatcher.group(1));
                String lineIndexText = lineAttrs.getOrDefault("i", "");
                int lineIndex;
                try {
                    lineIndex = Integer.parseInt(lineIndexText);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (lineIndex != expectedLine || lineIndex >= lines.size()) {
                    return null;
                }

                LineTemplate template = lines.get(lineIndex);
                MutableComponent line = Component.empty();
                Map<String, RunTemplate> byId = new LinkedHashMap<>();
                Map<String, Boolean> seen = new LinkedHashMap<>();
                for (RunTemplate run : template.runs()) {
                    byId.put(run.id(), run);
                    seen.put(run.id(), false);
                }

                String body = lineMatcher.group(2);
                Matcher groupMatcher = GROUP_PATTERN.matcher(body);
                int cursor = 0;
                while (groupMatcher.find()) {
                    if (groupMatcher.start() < cursor) {
                        return null;
                    }
                    if (!appendBaseText(line, body.substring(cursor, groupMatcher.start()), template.baseStyle(),
                            template.sourceText())) {
                        return null;
                    }

                    Map<String, String> groupAttrs = attrs(groupMatcher.group(1));
                    String id = groupAttrs.get("id");
                    RunTemplate run = byId.get(id);
                    if (run == null || seen.getOrDefault(id, false)) {
                        return null;
                    }
                    seen.put(id, true);
                    String translated = DirectStatusTerms.apply(run.sourceText(), unescape(groupMatcher.group(2)));
                    String text = run.editable() ? translated : run.sourceText();
                    line.append(Component.literal(text).withStyle(run.style()));
                    cursor = groupMatcher.end();
                }
                if (!appendBaseText(line, body.substring(cursor), template.baseStyle(), template.sourceText())) {
                    return null;
                }

                if (!appendMissingFreeRuns(line, template, seen, body)) {
                    return null;
                }
                restored.add(line);
                expectedLine++;
            }
            if (expectedLine != lines.size()) {
                return null;
            }
            return restored;
        }

        private boolean appendMissingFreeRuns(MutableComponent line, LineTemplate template, Map<String, Boolean> seen,
                String body) {
            boolean lineHasModelText = line != null && !line.getString().isEmpty();
            Set<String> editableStyleSignatures = new HashSet<>();
            for (RunTemplate run : template.runs()) {
                if (run.editable()) {
                    editableStyleSignatures.add(run.styleSignature());
                }
            }
            boolean requireEditableRuns = editableStyleSignatures.size() > 1
                    || (surface != null && surface.startsWith("text_display."));
            for (RunTemplate run : template.runs()) {
                if (seen.getOrDefault(run.id(), false)) {
                    continue;
                }
                if (run.editable()) {
                    if (requireEditableRuns) {
                        return false;
                    }
                    if (!lineHasModelText) {
                        return false;
                    }
                    continue;
                }
                String text = run.sourceText();
                if (text != null && !text.isEmpty()) {
                    line.append(Component.literal(text).withStyle(run.style()));
                }
            }
            return true;
        }

        private boolean appendBaseText(MutableComponent line, String raw, Style baseStyle, String sourceLineText) {
            String text = DirectStatusTerms.apply(sourceLineText, unescape(raw));
            if (!text.isEmpty()) {
                line.append(Component.literal(text).withStyle(baseStyle == null ? Style.EMPTY : baseStyle));
            }
            return true;
        }

        private String fixedSignTokenFailure(RunTemplate run, String translated) {
            if (!fixedLayout || surface == null || !surface.startsWith("sign.") || run == null || !run.editable()) {
                return null;
            }
            Set<String> sourceTokens = signProtectedTokens(run.sourceText());
            Set<String> translatedTokens = signProtectedTokens(translated);
            for (String token : sourceTokens) {
                if (!translatedTokens.contains(token)) {
                    return "sign-token-missing token=" + token;
                }
            }
            if (translatedTokens.isEmpty()) {
                return null;
            }
            Set<String> documentTokens = allSignProtectedTokens();
            for (String token : translatedTokens) {
                if (!sourceTokens.contains(token) && documentTokens.contains(token)) {
                    return "sign-token-moved token=" + token;
                }
            }
            return null;
        }

        private Set<String> allSignProtectedTokens() {
            Set<String> tokens = new HashSet<>();
            for (LineTemplate line : lines) {
                if (line == null || line.runs() == null) {
                    continue;
                }
                for (RunTemplate run : line.runs()) {
                    if (run != null) {
                        tokens.addAll(signProtectedTokens(run.sourceText()));
                    }
                }
            }
            return tokens;
        }
    }

    private static final class DirectBatcher {
        private static final Object LOCK = new Object();
        private static final List<BatchItem> PENDING = new ArrayList<>();
        private static ScheduledFuture<?> scheduled;

        private DirectBatcher() {
        }

        private static CompletableFuture<String> enqueue(TranslationManager manager, Template template) {
            CompletableFuture<String> future = new CompletableFuture<>();
            synchronized (LOCK) {
                PENDING.add(new BatchItem(manager, template, future));
                int totalChars = 0;
                for (BatchItem item : PENDING) {
                    totalChars += item.template().requestPayload().length();
                }
                if (PENDING.size() >= MAX_BATCH_ITEMS || totalChars >= MAX_BATCH_CHARS) {
                    flushLocked();
                } else if (scheduled == null || scheduled.isDone()) {
                    scheduled = BATCH_EXECUTOR.schedule(() -> {
                        synchronized (LOCK) {
                            flushLocked();
                        }
                    }, BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
                }
            }
            return future;
        }

        private static void flushLocked() {
            if (PENDING.isEmpty()) {
                return;
            }
            if (scheduled != null) {
                scheduled.cancel(false);
                scheduled = null;
            }
            List<BatchItem> items = new ArrayList<>(PENDING);
            PENDING.clear();
            if (items.size() == 1) {
                BatchItem item = items.get(0);
                item.manager().translateFormattedDocument(item.template().requestPayload())
                        .whenComplete((result, error) -> completeSingle(item, result, error));
                return;
            }

            String batchPayload = buildBatchPayload(items);
            items.get(0).manager().translateFormattedDocument(batchPayload)
                    .whenComplete((result, error) -> completeBatch(items, result, error));
        }

        private static String buildBatchPayload(List<BatchItem> items) {
            StringBuilder builder = new StringBuilder();
            builder.append("<st-batch v=\"direct-batch-v1\" count=\"").append(items.size()).append("\">");
            for (int i = 0; i < items.size(); i++) {
                builder.append("<st-item id=\"d").append(i).append("\">")
                        .append(items.get(i).template().requestPayload())
                        .append("</st-item>");
            }
            builder.append("</st-batch>");
            return builder.toString();
        }

        private static void completeSingle(BatchItem item, String result, Throwable error) {
            if (error != null) {
                item.future().completeExceptionally(error);
            } else {
                item.future().complete(result);
            }
        }

        private static void completeBatch(List<BatchItem> items, String result, Throwable error) {
            if (error != null || result == null || result.isBlank()) {
                for (BatchItem item : items) {
                    if (error != null) {
                        item.future().completeExceptionally(error);
                    } else {
                        item.future().complete(null);
                    }
                }
                return;
            }
            Map<String, String> payloads = parseBatchItems(result);
            SimpleTranslateMod.getLogger().debug(
                    "Direct formatted batch completed batchSize={} returnedItems={}",
                    items.size(), payloads.size());
            for (int i = 0; i < items.size(); i++) {
                String id = "d" + i;
                String itemPayload = payloads.get(id);
                if (itemPayload == null || itemPayload.isBlank()) {
                    items.get(i).future().complete(null);
                } else {
                    items.get(i).future().complete(itemPayload);
                }
            }
        }

        private static Map<String, String> parseBatchItems(String result) {
            Map<String, String> payloads = new LinkedHashMap<>();
            String batch = extractBatchPayload(result);
            if (batch == null) {
                return payloads;
            }
            Pattern itemPattern = Pattern.compile("(?s)<st-item\\s+([^>]*)>(.*?)</st-item>");
            Matcher matcher = itemPattern.matcher(batch);
            while (matcher.find()) {
                Map<String, String> attributes = attrs(matcher.group(1));
                String id = attributes.get("id");
                if (id == null || id.isBlank() || payloads.containsKey(id)) {
                    continue;
                }
                payloads.put(id, matcher.group(2).trim());
            }
            return payloads;
        }

        private record BatchItem(TranslationManager manager, Template template, CompletableFuture<String> future) {
        }
    }

    private static Set<String> signProtectedTokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = SIGN_PROTECTED_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (token != null && !token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static Style chooseBaseStyle(List<RunTemplate> runs) {
        Style fallback = Style.EMPTY;
        int fallbackLength = -1;
        for (RunTemplate run : runs) {
            int length = run.sourceText() == null ? 0 : run.sourceText().length();
            if (length > fallbackLength) {
                fallback = run.style();
                fallbackLength = length;
            }
        }
        return fallback == null ? Style.EMPTY : fallback;
    }

    private static Style chooseSegmentBaseStyle(List<TextSegmentInfo> segments, String surface) {
        Style fallback = Style.EMPTY;
        int fallbackLength = -1;
        for (TextSegmentInfo segment : segments) {
            if (segment == null) {
                continue;
            }
            Style style = segment.style == null ? Style.EMPTY : segment.style;
            if (isEditableRun(segment.text, style, surface)) {
                return style;
            }
            int length = segment.text == null ? 0 : segment.text.length();
            if (length > fallbackLength) {
                fallback = style;
                fallbackLength = length;
            }
        }
        return fallback == null ? Style.EMPTY : fallback;
    }

    private static boolean containsDigit(String word) {
        if (word == null) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            if (Character.isDigit(word.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String styleSignature(Style style) {
        Style effective = style == null ? Style.EMPTY : style;
        return "color=" + (effective.getColor() == null ? "" : effective.getColor())
                + ";bold=" + effective.isBold()
                + ";italic=" + effective.isItalic()
                + ";underlined=" + effective.isUnderlined()
                + ";strikethrough=" + effective.isStrikethrough()
                + ";obfuscated=" + effective.isObfuscated()
                + ";click=" + (effective.getClickEvent() != null)
                + ";hover=" + (effective.getHoverEvent() != null);
    }

    private record LineTemplate(int index, List<RunTemplate> runs, Style baseStyle, String sourceText) {
    }

    private record RunTemplate(String id, String sourceText, Style style, boolean editable) {
        private String styleSignature() {
            return DirectFormattedTranslationPipeline.styleSignature(style);
        }
    }

    private record StyledRange(int start, int end, Style style) {
    }

    public static final class ComponentResult {
        public final Component component;
        public final boolean handled;
        public final boolean translated;

        private ComponentResult(Component component, boolean handled, boolean translated) {
            this.component = component;
            this.handled = handled;
            this.translated = translated;
        }
    }

    public static final class ComponentListResult {
        public final List<Component> components;
        public final boolean handled;
        public final boolean translated;

        private ComponentListResult(List<Component> components, boolean handled, boolean translated) {
            this.components = components;
            this.handled = handled;
            this.translated = translated;
        }

        private ComponentResult asSingle(Component fallback) {
            if (components == null || components.size() != 1) {
                return new ComponentResult(fallback, handled, translated);
            }
            return new ComponentResult(components.get(0), handled, translated);
        }
    }
}
