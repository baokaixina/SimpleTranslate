package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.TranslationManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Chat translation runtime logic kept out of ChatComponentMixin.
 */
public final class ChatTranslationRuntime {
    public static final String CHAT_SEGMENT_SURFACE = "chat.message.segment.direct";
    public static final String CHAT_CONTEXT_SURFACE = "chat.context.direct";
    public static final String CHAT_SYSTEM_SURFACE = "chat.system.direct";

    private static final Pattern INTERNAL_MARKER_PATTERN =
            Pattern.compile("@{3}\\s*(?:SEG|TTS|CTX|S)\\s*[0-9A-Za-z_]*\\s*@{3}", Pattern.CASE_INSENSITIVE);

    private ChatTranslationRuntime() {
    }

    public static void extractSegments(Component component, List<TextSegmentInfo> segments) {
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
    }

    public static String getTranslatableChatSegmentText(List<TextSegmentInfo> segments, int segmentIndex, String plainText) {
        return getTranslatableChatSegmentText(segments, segmentIndex, plainText, findChatBodyStart(plainText));
    }

    public static String getTranslatableChatSegmentText(List<TextSegmentInfo> segments, int segmentIndex,
                                                        String plainText, int bodyStart) {
        if (segments == null || segmentIndex < 0 || segmentIndex >= segments.size()) {
            return null;
        }

        TextSegmentInfo segment = segments.get(segmentIndex);
        if (segment == null || segment.text == null || segment.text.isBlank()) {
            return null;
        }

        String text = segment.text;
        if (bodyStart <= 0) {
            return text;
        }

        int segmentStart = getSegmentStartOffset(segments, segmentIndex);
        int segmentEnd = segmentStart + text.length();
        if (segmentEnd <= bodyStart) {
            return null;
        }
        if (segmentStart >= bodyStart) {
            return text;
        }
        int cut = bodyStart - segmentStart;
        if (cut <= 0 || cut >= text.length()) {
            return text;
        }
        return text.substring(cut);
    }

    public static String applyChatSegmentTranslation(String originalSegment, String sourceText, String translated) {
        if (originalSegment == null || sourceText == null || translated == null || translated.isBlank()) {
            return originalSegment;
        }
        int index = originalSegment.indexOf(sourceText);
        if (index < 0) {
            return translated;
        }
        return originalSegment.substring(0, index) + translated + originalSegment.substring(index + sourceText.length());
    }

    public static CompletableFuture<String> translateWithContext(String plainText, List<String> contextLines,
                                                                  int targetIndex, TranslationManager manager) {
        return translateSingleMapping(
                manager,
                plainText,
                CHAT_CONTEXT_SURFACE,
                contextText(contextLines, targetIndex),
                "chat-context:v2:target=" + targetIndex + ":count=" + (contextLines == null ? 0 : contextLines.size()),
                "chat-context");
    }

    public static CompletableFuture<List<String>> translateBatch(List<String> texts, TranslationManager manager) {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        boolean cacheEnabled = ModConfig.CACHE_ENABLED.get();

        List<String> uncachedTexts = new ArrayList<>();
        List<Integer> uncachedIndices = new ArrayList<>();
        String[] results = new String[texts.size()];

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
                if (cache != null && cacheEnabled) {
                    String cached = getCachedMappingTranslation(CHAT_SEGMENT_SURFACE, text, "", "chat-segment", "chat-segment");
                if (cached != null && !cached.isBlank()) {
                    results[i] = cached;
                    continue;
                }
            }
            uncachedTexts.add(text);
            uncachedIndices.add(i);
        }

        if (uncachedTexts.isEmpty()) {
            List<String> resultList = new ArrayList<>();
            for (String result : results) {
                resultList.add(result);
            }
            return CompletableFuture.completedFuture(resultList);
        }

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (String uncachedText : uncachedTexts) {
            futures.add(translateSingleMapping(manager, uncachedText, CHAT_SEGMENT_SURFACE, "", "chat-segment", "chat-segment"));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(ignored -> {
            for (int i = 0; i < uncachedIndices.size(); i++) {
                int originalIndex = uncachedIndices.get(i);
                String originalText = uncachedTexts.get(i);
                String translated = null;
                try {
                    translated = futures.get(i).join();
                } catch (Exception e) {
                    SimpleTranslateMod.getLogger().debug("Chat mapping segment translation failed: {}", e.getMessage());
                }
                if (translated == null || translated.isBlank() || isBlacklisted(originalText) || containsBlacklistedText(translated)) {
                    translated = originalText;
                }
                results[originalIndex] = translated;
            }

            List<String> resultList = new ArrayList<>();
            for (String result : results) {
                resultList.add(result != null ? result : "");
            }
            return resultList;
        });
    }

    public static Component applyFullTranslation(Component message, String translatedText) {
        if (message == null || translatedText == null || translatedText.isEmpty()) {
            return message;
        }

        List<TextSegmentInfo> segments = new ArrayList<>();
        extractSegments(message, segments);
        if (segments.isEmpty()) {
            return applyFlattenedTranslation(message, translatedText);
        }
        if (segments.size() > 1) {
            Component chatBodyTranslation = applyChatBodyTranslation(message, segments, translatedText);
            if (chatBodyTranslation != null) {
                return chatBodyTranslation;
            }
            SimpleTranslateMod.getLogger().debug("Chat message kept original because translated text has no slot-level style map: '{}'",
                    message.getString());
            return null;
        }
        return Component.literal(translatedText).withStyle(segments.get(0).style);
    }

    private static Component applyChatBodyTranslation(Component message, List<TextSegmentInfo> segments, String translatedText) {
        String sourcePlain = message == null ? "" : message.getString();
        int bodyStart = findChatBodyStart(sourcePlain);
        if (bodyStart <= 0 || translatedText == null || translatedText.isBlank()) {
            return null;
        }
        String translatedBody = stripTranslatedChatPrefix(sourcePlain, bodyStart, translatedText);
        if (translatedBody.isBlank()) {
            return null;
        }

        int offset = 0;
        boolean inserted = false;
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null) {
                continue;
            }
            int start = offset;
            int end = offset + segment.text.length();
            offset = end;
            if (end <= bodyStart) {
                continue;
            }
            if (!inserted) {
                int cut = Math.max(0, Math.min(segment.text.length(), bodyStart - start));
                String prefixInsideSegment = cut > 0 ? segment.text.substring(0, cut) : "";
                segment.translatedText = prefixInsideSegment + translatedBody;
                inserted = true;
            } else {
                segment.translatedText = "";
            }
        }
        return inserted ? rebuildComponent(message, segments) : null;
    }

    private static String stripTranslatedChatPrefix(String sourcePlain, int bodyStart, String translatedText) {
        String translated = stripInternalMarkers(translatedText).trim();
        if (sourcePlain == null || sourcePlain.isEmpty() || bodyStart <= 0 || bodyStart > sourcePlain.length()) {
            return translated;
        }
        String prefix = sourcePlain.substring(0, bodyStart);
        if (translated.startsWith(prefix)) {
            return translated.substring(prefix.length()).trim();
        }
        String compactPrefix = prefix.trim();
        if (!compactPrefix.equals(prefix) && translated.startsWith(compactPrefix)) {
            return translated.substring(compactPrefix.length()).trim();
        }
        return translated;
    }

    public static Component buildCachedSegmentTranslation(Component message, String plainText) {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null || !ModConfig.CACHE_ENABLED.get()) {
            return null;
        }

        List<TextSegmentInfo> segments = new ArrayList<>();
        extractSegments(message, segments);

        boolean hasEnglishSegments = false;
        boolean allCached = true;
        for (int i = 0; i < segments.size(); i++) {
            TextSegmentInfo seg = segments.get(i);
            String sourceText = getTranslatableChatSegmentText(segments, i, plainText);
            if (sourceText != null && TooltipTranslationHelper.containsEnglish(sourceText)) {
                hasEnglishSegments = true;
            String cached = getCachedMappingTranslation(
                        CHAT_SEGMENT_SURFACE,
                        sourceText,
                        "",
                        "chat-segment",
                        "chat-segment");
                if (cached != null && !cached.isBlank()) {
                    seg.translatedText = applyChatSegmentTranslation(seg.text, sourceText, cached);
                } else {
                    allCached = false;
                    break;
                }
            }
        }

        if (!hasEnglishSegments || !allCached) {
            return null;
        }
        return rebuildComponent(message, segments);
    }

    public static Component buildCachedContextTranslation(Component message, String plainText,
                                                          List<String> contextLines, int targetIndex) {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null || !ModConfig.CACHE_ENABLED.get()) {
            return null;
        }
        String cached = getCachedMappingTranslation(
                CHAT_CONTEXT_SURFACE,
                plainText,
                contextText(contextLines, targetIndex),
                "chat-context:v2:target=" + targetIndex + ":count=" + (contextLines == null ? 0 : contextLines.size()),
                "chat-context");
        if (cached == null || cached.isBlank() || cached.equals(plainText)) {
            return null;
        }
        return applyFullTranslation(message, cached);
    }

    public static Component rebuildComponent(Component original, List<TextSegmentInfo> segments) {
        int[] segmentIndex = {0};
        return rebuildRecursive(original, segments, segmentIndex);
    }

    public static String getCachedMappingTranslation(String surface, String text, String context,
                                                     String layoutSignature, String styleSignature) {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null || !ModConfig.CACHE_ENABLED.get() || text == null || text.isBlank()) {
            return null;
        }
        return DirectFormattedTranslationPipeline.getCachedText(text, surface, "chat-text", context,
                layoutSignature, styleSignature);
    }

    public static Component buildCachedSystemTranslation(Component message) {
        if (message == null) {
            return null;
        }
        DirectFormattedTranslationPipeline.ComponentListResult result =
                DirectSurfaceTranslator.getCachedComponents(
                        List.of(message), CHAT_SYSTEM_SURFACE, "chat-system", false, "");
        if (result == null || !result.translated || result.components == null || result.components.size() != 1) {
            return null;
        }
        return result.components.get(0);
    }

    public static CompletableFuture<Component> translateSystemMessage(Component message) {
        if (message == null) {
            return CompletableFuture.completedFuture(null);
        }
        return DirectSurfaceTranslator.translateComponentsAsync(
                List.of(message), CHAT_SYSTEM_SURFACE, "chat-system", false, "").thenApply(result -> {
            if (result == null || !result.translated || result.components == null || result.components.size() != 1) {
                return null;
            }
            return result.components.get(0);
        });
    }

    public static String contextText(List<String> contextLines) {
        return contextText(contextLines, -1);
    }

    public static String contextText(List<String> contextLines, int targetIndex) {
        if (contextLines == null || contextLines.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Chat history is ordered oldest to newest. Translate only the target line.");
        for (int i = 0; i < contextLines.size(); i++) {
            String line = contextLines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            builder.append('\n')
                    .append(i + 1)
                    .append(i == targetIndex ? ". [target] " : ". [previous] ")
                    .append(line);
        }
        return builder.toString();
    }

    public static String stripInternalMarkers(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String cleaned = INTERNAL_MARKER_PATTERN.matcher(text).replaceAll("");
        cleaned = cleaned.replaceAll("(?i)@{3}\\s*(?:SEG|TTS|CTX|S)\\s*[0-9A-Za-z_]*", "");
        cleaned = cleaned.replaceAll("(?i)(?:SEG|TTS|CTX|S)\\s*[0-9A-Za-z_]*\\s*@{3}", "");
        return cleaned;
    }

    private static CompletableFuture<String> translateSingleMapping(TranslationManager manager, String text,
                                                                     String surface, String context,
                                                                     String layoutSignature,
                                                                     String styleSignature) {
        if (manager == null || text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return DirectSurfaceTranslator.translateTextAsync(
                text, surface, "chat-text", context, layoutSignature, styleSignature).handle((translated, error) -> {
            if (error != null || translated == null) {
                if (error != null) {
                    SimpleTranslateMod.getLogger().debug("Chat direct formatted translation failed: {}", error.getMessage());
                }
                return null;
            }
            if (translated == null || translated.isBlank()) {
                return null;
            }
            return stripInternalMarkers(translated).trim();
        });
    }

    private static Component applyFlattenedTranslation(Component message, String translatedText) {
        List<TextSegmentInfo> segments = new ArrayList<>();
        message.visit((style, text) -> {
            if (text != null && !text.isEmpty()) {
                segments.add(new TextSegmentInfo(text, style, null));
            }
            return Optional.empty();
        }, Style.EMPTY);
        if (segments.isEmpty()) {
            return Component.literal(translatedText).withStyle(message.getStyle());
        }
        if (segments.size() > 1) {
            SimpleTranslateMod.getLogger().debug("Chat flattened message kept original because translated text has no slot-level style map: '{}'",
                    message.getString());
            return null;
        }
        return Component.literal(translatedText).withStyle(segments.get(0).style);
    }

    private static Component rebuildRecursive(Component original, List<TextSegmentInfo> segments, int[] indexHolder) {
        String originalText = ComponentSegmentHelper.getDirectText(original);
        boolean literalOrTranslatable = simple_translate$isLiteralOrTranslatable(original);

        String newText = originalText;
        if (!originalText.isEmpty() && indexHolder[0] < segments.size()) {
            TextSegmentInfo segment = segments.get(indexHolder[0]);
            if (segment.text.equals(originalText)) {
                newText = segment.translatedText != null ? segment.translatedText : originalText;
                indexHolder[0]++;
            }
        } else if (originalText.isEmpty() && original.getSiblings().isEmpty() && indexHolder[0] < segments.size()) {
            indexHolder[0]++;
        }

        MutableComponent result = literalOrTranslatable ? Component.literal(newText) : Component.empty();
        Style originalStyle = original.getStyle();
        if (originalStyle != null && !originalStyle.isEmpty()) {
            result = result.withStyle(originalStyle);
        }
        for (Component sibling : original.getSiblings()) {
            result.append(rebuildRecursive(sibling, segments, indexHolder));
        }
        return result;
    }

    private static int getSegmentStartOffset(List<TextSegmentInfo> segments, int segmentIndex) {
        int offset = 0;
        for (int i = 0; i < segmentIndex && i < segments.size(); i++) {
            TextSegmentInfo segment = segments.get(i);
            if (segment != null && segment.text != null) {
                offset += segment.text.length();
            }
        }
        return offset;
    }

    private static boolean simple_translate$isLiteralOrTranslatable(Component component) {
        if (component == null || component.getContents() == null) {
            return false;
        }
        Object contents = component.getContents();
        if (contents instanceof TranslatableContents) {
            return true;
        }
        String className = contents.getClass().getName();
        return className.endsWith("LiteralContents")
                || className.endsWith("PlainTextContents$LiteralContents");
    }

    private static int findChatBodyStart(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return -1;
        }

        int angleIndex = plainText.indexOf('>');
        if (plainText.startsWith("<") && angleIndex > 1 && angleIndex + 1 < plainText.length()) {
            return skipWhitespace(plainText, angleIndex + 1);
        }

        return -1;
    }

    private static int skipWhitespace(String text, int index) {
        int i = Math.max(0, index);
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isBlacklisted(String text) {
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        return blacklist != null && blacklist.isBlacklisted(text);
    }

    private static boolean containsBlacklistedText(String text) {
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        return blacklist != null && blacklist.containsBlacklistedEntry(text);
    }
}
