package com.yourname.simpletranslate.feature.chat;

import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.ComponentSegmentHelper;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.TextSegmentInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Component JSON chat translation runtime kept out of ChatComponentMixin. */
public final class ChatTranslationRuntime {
    public static final String CHAT_CONTEXT_BATCH_SURFACE = "chat.context.batch.direct";

    private ChatTranslationRuntime() {
    }

    public static void extractSegments(Component component, List<TextSegmentInfo> segments) {
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
    }

    public static String getTranslatableChatSegmentText(List<TextSegmentInfo> segments,
                                                        int segmentIndex,
                                                        String plainText) {
        return getTranslatableChatSegmentText(
                segments, segmentIndex, plainText, findChatBodyStart(plainText));
    }

    public static String getTranslatableChatSegmentText(List<TextSegmentInfo> segments,
                                                        int segmentIndex,
                                                        String plainText,
                                                        int bodyStart) {
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

    public static Component buildCachedAutoMessageTranslation(Component message) {
        if (message == null) {
            return null;
        }
        ComponentListTranslationResult result =
                DirectSurfaceTranslator.getCachedComponents(
                        List.of(message), CHAT_CONTEXT_BATCH_SURFACE, "chat-context-batch", false, "");
        return firstTranslated(result, 1);
    }

    public static CompletableFuture<Component> translateAutoMessage(Component message) {
        if (message == null) {
            return CompletableFuture.completedFuture(null);
        }
        return DirectSurfaceTranslator.translateComponentsAsync(
                List.of(message), CHAT_CONTEXT_BATCH_SURFACE, "chat-context-batch", false, "")
                .thenApply(result -> firstTranslated(result, 1));
    }

    public static CompletableFuture<Component> translateAutoMessageWithContext(Component message, String context) {
        if (message == null) {
            return CompletableFuture.completedFuture(null);
        }
        String effectiveContext = context == null ? "" : context;
        return DirectSurfaceTranslator.translateComponentsAsync(
                List.of(message), CHAT_CONTEXT_BATCH_SURFACE, "chat-context-batch", false, effectiveContext)
                .thenApply(result -> firstTranslated(result, 1));
    }

    public static CompletableFuture<List<Component>> translateAutoMessagesWithContext(
            List<Component> messages, String context) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (messages.size() == 1) {
            return translateAutoMessageWithContext(messages.get(0), context)
                    .thenApply(component -> component == null ? List.of() : List.of(component));
        }
        String effectiveContext = context == null ? "" : context;
        return DirectSurfaceTranslator.translateComponentsAsync(
                messages, CHAT_CONTEXT_BATCH_SURFACE, "chat-context-batch", false, effectiveContext)
                .thenApply(result -> {
                    if (result == null || !result.translated || result.components == null
                            || result.components.size() != messages.size()) {
                        return List.of();
                    }
                    return result.components;
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

    private static Component firstTranslated(ComponentListTranslationResult result, int expectedSize) {
        if (result == null || !result.translated || result.components == null
                || result.components.size() != expectedSize || result.components.isEmpty()) {
            return null;
        }
        return result.components.get(0);
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
        return ChatContextHelper.findChatBodyStart(plainText);
    }
}
