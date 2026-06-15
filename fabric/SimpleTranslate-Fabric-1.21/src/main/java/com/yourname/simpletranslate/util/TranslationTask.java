package com.yourname.simpletranslate.util;

import java.util.List;

public record TranslationTask(
        String taskId,
        String surface,
        String sourceText,
        String context,
        String layoutSignature,
        String styleSignature,
        long createdAt,
        List<TranslationSlot> slots) {

    public static TranslationTask create(String surface, String sourceText, String context,
                                         String layoutSignature, String styleSignature,
                                         List<TranslationSlot> slots) {
        return new TranslationTask(
                TranslationJobIds.next(),
                surface,
                sourceText == null ? "" : sourceText,
                context == null ? "" : context,
                layoutSignature == null ? "" : layoutSignature,
                styleSignature == null ? "" : styleSignature,
                System.currentTimeMillis(),
                slots == null ? List.of() : List.copyOf(slots));
    }

    public static TranslationTask fromMapping(TranslationMapping mapping) {
        if (mapping == null) {
            return create("", "", "", "", "", List.of());
        }
        return new TranslationTask(
                mapping.taskId(),
                mapping.surface(),
                mapping.sourceText(),
                mapping.context(),
                mapping.layoutSignature(),
                mapping.styleSignature(),
                mapping.createdAt(),
                mapping.slots());
    }

    public String cacheKey() {
        return TranslationCacheKeys.key(surface, sourceText, context, layoutSignature, styleSignature);
    }

    public String sourceHash() {
        return TranslationCacheKeys.hashSource(sourceText);
    }

    public String layoutHash() {
        return TranslationCacheKeys.hashSource(layoutSignature);
    }

    public String pendingKey() {
        return surface + ":" + cacheKey();
    }

    public TranslationMapping toMapping() {
        return new TranslationMapping(taskId, surface, sourceText, context, layoutSignature, styleSignature,
                createdAt, slots);
    }
}
