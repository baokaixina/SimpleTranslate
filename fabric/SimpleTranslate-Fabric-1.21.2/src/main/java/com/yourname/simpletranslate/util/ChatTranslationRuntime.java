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
    public static final String CHAT_CONTEXT_SURFACE = "chat.context.direct";
    public static final String CHAT_CONTEXT_BATCH_SURFACE = "chat.context.batch.direct";
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

    public static CompletableFuture<Component> translateWithContext(Component message, String plainText,
                                                                    List<String> contextLines,
                                                                    int targetIndex, TranslationManager manager) {
        if (manager == null || plainText == null || plainText.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        List<TextSegmentInfo> segments = new ArrayList<>();
        extractSegments(message, segments);
        String context = contextText(contextLines, targetIndex);
        String layoutSignature = "chat-context:v2:target=" + targetIndex + ":count="
                + (contextLines == null ? 0 : contextLines.size());

        if (segments.size() > 1) {
            return DirectSurfaceTranslator.translateComponentsAsync(
                    List.of(message), CHAT_CONTEXT_SURFACE, "chat-text", false, context).thenApply(result -> {
                if (result == null || !result.translated || result.components == null || result.components.isEmpty()) {
                    return null;
                }
                Component translated = result.components.get(0);
                if (translated == null || translated.getString().equals(plainText)) {
                    return null;
                }
                return translated;
            });
        }

        return translateSingleMapping(manager, plainText, CHAT_CONTEXT_SURFACE, context, layoutSignature, "chat-context")
                .thenApply(translated -> {
                    if (translated == null || translated.isBlank() || translated.equals(plainText)) {
                        return null;
                    }
                    return applyFullTranslation(message, translated);
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
        if (message == null || translatedText == null || translatedText.isBlank()) {
            return null;
        }

        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(message), CHAT_CONTEXT_SURFACE, "chat-text", false, translatedText);
        if (restored != null && !restored.isEmpty()) {
            return restored.get(0);
        }

        restored = DirectFormattedTranslationPipeline.restoreWithPlainFallbackForTest(
                List.of(message), CHAT_CONTEXT_SURFACE, "chat-text", false, translatedText);
        if (restored != null && !restored.isEmpty()) {
            return restored.get(0);
        }

        String sourcePlain = message.getString();
        int bodyStart = findChatBodyStart(sourcePlain);
        if (bodyStart <= 0) {
            return null;
        }
        String translatedBody = stripTranslatedChatPrefix(sourcePlain, bodyStart, translatedText);
        if (translatedBody.isBlank()) {
            return null;
        }

        int bodySegmentCount = 0;
        int offset = 0;
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null) {
                continue;
            }
            int end = offset + segment.text.length();
            if (end > bodyStart) {
                bodySegmentCount++;
            }
            offset = end;
        }
        if (bodySegmentCount > 1) {
            return null;
        }

        offset = 0;
        boolean inserted = false;
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null) {
                continue;
            }
            int start = offset;
            int end = offset + segment.text.length();
            offset = end;
            if (end <= bodyStart) {
                segment.translatedText = segment.text;
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

    public static Component buildCachedContextTranslation(Component message, String plainText,
                                                          List<String> contextLines, int targetIndex) {
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null || !ModConfig.CACHE_ENABLED.get()) {
            return null;
        }

        List<TextSegmentInfo> segments = new ArrayList<>();
        extractSegments(message, segments);
        String context = contextText(contextLines, targetIndex);
        String layoutSignature = "chat-context:v2:target=" + targetIndex + ":count="
                + (contextLines == null ? 0 : contextLines.size());

        if (segments.size() > 1) {
            DirectFormattedTranslationPipeline.ComponentListResult result =
                    DirectSurfaceTranslator.getCachedComponents(
                            List.of(message), CHAT_CONTEXT_SURFACE, "chat-text", false, context);
            if (result == null || !result.translated || result.components == null || result.components.isEmpty()) {
                return null;
            }
            Component translated = result.components.get(0);
            if (translated == null || translated.getString().equals(plainText)) {
                return null;
            }
            return translated;
        }

        String cached = getCachedMappingTranslation(
                CHAT_CONTEXT_SURFACE,
                plainText,
                context,
                layoutSignature,
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

    public static Component buildCachedAutoMessageTranslation(Component message) {
        if (message == null) {
            return null;
        }
        DirectFormattedTranslationPipeline.ComponentListResult result =
                DirectSurfaceTranslator.getCachedComponents(
                        List.of(message), CHAT_CONTEXT_BATCH_SURFACE, "chat-context-batch", false, "");
        if (result == null || !result.translated || result.components == null || result.components.size() != 1) {
            return null;
        }
        return result.components.get(0);
    }

    public static CompletableFuture<Component> translateAutoMessage(Component message) {
        if (message == null) {
            return CompletableFuture.completedFuture(null);
        }
        return DirectSurfaceTranslator.translateComponentsAsync(
                List.of(message), CHAT_CONTEXT_BATCH_SURFACE, "chat-context-batch", false, "").thenApply(result -> {
            if (result == null || !result.translated || result.components == null || result.components.size() != 1) {
                return null;
            }
            return result.components.get(0);
        });
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
        builder.append(" Narrator/system lines and comma-ending fragments may continue one sentence or list;");
        builder.append(" translate the target as a natural continuation and do not duplicate trailing punctuation.");
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
        boolean literalOrTranslatable = !originalText.isEmpty()
                || original.getContents() instanceof TranslatableContents;

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

    private static int findChatBodyStart(String plainText) {
        // Single source of truth: supports both "<player> body" and
        // "KnownPlayer: body" so context translations attach the body correctly.
        return com.yourname.simpletranslate.chat.ChatContextHelper.findChatBodyStart(plainText);
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
