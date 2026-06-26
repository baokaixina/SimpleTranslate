package com.yourname.simpletranslate.feature.chat;

import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds prior visible-chat context for Component JSON chat requests. */
final class ChatBatchContextBuilder {
    private static final String PENDING = "[pending]";

    private ChatBatchContextBuilder() {
    }

    static String buildDirectContext(ChatTranslationController controller,
                                     String currentPlainText,
                                     int contextLimit) {
        return buildDirectContext(controller, currentPlainText, contextLimit, Set.of());
    }

    static String buildDirectContext(ChatTranslationController controller,
                                     String currentPlainText,
                                     int contextLimit,
                                     Set<String> excludePlainTexts) {
        if (controller == null || contextLimit <= 0 || currentPlainText == null || currentPlainText.isBlank()) {
            return "";
        }
        List<GuiMessage> allMessages = controller.access().simpleTranslateAllMessages();
        if (allMessages == null || allMessages.isEmpty()) {
            return "";
        }

        Set<String> excludeKeys = new HashSet<>();
        if (excludePlainTexts != null) {
            for (String text : excludePlainTexts) {
                if (text != null && !text.isBlank()) {
                    excludeKeys.add(normalizeKey(text));
                }
            }
        }

        String currentKey = normalizeKey(currentPlainText);
        Set<String> seenPlainTexts = new HashSet<>();
        seenPlainTexts.add(currentKey);

        Map<String, ContextLine> merged = new HashMap<>();
        for (int i = allMessages.size() - 1; i >= 0 && merged.size() < contextLimit; i--) {
            GuiMessage guiMessage = allMessages.get(i);
            if (guiMessage == null || guiMessage.content() == null) {
                continue;
            }
            Component content = guiMessage.content();
            if (controller.hudHistory().isHudHistoryChatMessage(content)) {
                continue;
            }
            String original = controller.getOriginalContextText(content);
            if (original == null || original.isBlank()) {
                continue;
            }
            String originalKey = normalizeKey(original);
            if (seenPlainTexts.contains(originalKey)
                    || excludeKeys.contains(originalKey)
                    || ChatBlacklistGuard.hasBlacklistedSourceText(content, original)) {
                continue;
            }
            String translated = resolveTranslatedText(controller, content, null);
            mergeLine(merged, originalKey, new ContextLine(
                    original.trim(),
                    translated,
                    guiMessage.addedTime(),
                    !PENDING.equals(translated)));
            seenPlainTexts.add(originalKey);
        }
        if (merged.isEmpty()) {
            return "";
        }
        List<ContextLine> lines = new ArrayList<>(merged.values());
        lines.sort((left, right) -> Long.compare(left.sortKey, right.sortKey));
        if (lines.size() > contextLimit) {
            lines = lines.subList(lines.size() - contextLimit, lines.size());
        }
        return formatContext(lines);
    }

    static String resolveTranslatedText(ChatTranslationController controller,
                                        Component content,
                                        @Nullable Component translatedComponent) {
        if (translatedComponent != null) {
            String translated = translatedComponent.getString().trim();
            if (!translated.isBlank()) {
                return translated;
            }
        }
        if (content != null && controller.autoPeerMap().containsKey(content)) {
            String translated = content.getString().trim();
            if (!translated.isBlank()) {
                return translated;
            }
        }
        if (content != null) {
            String original = controller.getOriginalContextText(content);
            String displayed = content.getString().trim();
            if (original != null && !original.isBlank() && !original.equals(displayed) && !displayed.isBlank()) {
                return displayed;
            }
        }
        return PENDING;
    }

    private static void mergeLine(Map<String, ContextLine> merged, String dedupeKey, ContextLine candidate) {
        ContextLine existing = merged.get(dedupeKey);
        if (existing == null
                || (candidate.hasTranslation && !existing.hasTranslation)
                || (candidate.hasTranslation == existing.hasTranslation && candidate.sortKey >= existing.sortKey)) {
            merged.put(dedupeKey, candidate);
        }
    }

    private static String normalizeKey(String plainText) {
        return plainText == null ? "" : plainText.trim().toLowerCase(Locale.ROOT);
    }

    private static String formatContext(List<ContextLine> lines) {
        StringBuilder context = new StringBuilder();
        context.append("Prior chat lines are shown for context. ");
        context.append("The current Component JSON array may be part of a multi-line server passage. ");
        context.append("Use prior lines to understand meaning, but return only the requested JSON array.\n");
        int index = 1;
        for (ContextLine line : lines) {
            context.append(index++).append(". chat\n");
            context.append("   original: ").append(line.original).append('\n');
            context.append("   translated: ").append(line.translated).append('\n');
        }
        return context.toString().trim();
    }

    private record ContextLine(String original, String translated, long sortKey, boolean hasTranslation) {
    }
}


