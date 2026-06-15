package com.yourname.simpletranslate.util;

import java.util.List;

public record TranslationMapping(
        String taskId,
        String surface,
        String sourceText,
        String context,
        String layoutSignature,
        String styleSignature,
        long createdAt,
        List<TranslationSlot> slots) {

    public static TranslationMapping create(String surface, String sourceText, String context,
                                            String layoutSignature, String styleSignature,
                                            List<TranslationSlot> slots) {
        return new TranslationMapping(
                TranslationJobIds.next(),
                surface == null ? "" : surface,
                sourceText == null ? "" : sourceText,
                context == null ? "" : context,
                layoutSignature == null ? "" : layoutSignature,
                styleSignature == null ? "" : styleSignature,
                System.currentTimeMillis(),
                slots == null ? List.of() : List.copyOf(slots));
    }

    public static TranslationMapping fromTask(TranslationTask task) {
        if (task == null) {
            return create("", "", "", "", "", List.of());
        }
        return task.toMapping();
    }

    public static TranslationMapping single(String surface, String sourceText, String context,
                                            String layoutSignature, String styleSignature) {
        TranslationSlot slot = TranslationSlot.translatable(
                "u0",
                sourceText == null ? "" : sourceText,
                "body",
                sourceText == null ? 0 : sourceText.length() * 2,
                styleSignature,
                0,
                0,
                "");
        return create(surface, sourceText, context, layoutSignature, styleSignature, List.of(slot));
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

    public TranslationTask toTask() {
        return TranslationTask.fromMapping(this);
    }
}
