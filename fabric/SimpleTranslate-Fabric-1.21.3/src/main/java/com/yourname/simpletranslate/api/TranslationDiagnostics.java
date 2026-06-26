package com.yourname.simpletranslate.api;

import java.util.List;

public final class TranslationDiagnostics {
    private TranslationDiagnostics() {
    }

    public record ApiDetection(
            boolean success,
            String providerMode,
            String authMode,
            String endpointUrl,
            int statusCode,
            String message) {
    }

    public record ModelDetection(
            boolean success,
            String endpointUrl,
            int statusCode,
            List<String> models,
            String message) {
        public ModelDetection {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    public record ModelAccess(
            boolean success,
            String modelId,
            int statusCode,
            String message) {
    }
}
