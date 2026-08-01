package com.yourname.simpletranslate.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Provider/model diagnostics returned to the complete model settings page. */
public final class TranslationDiagnostics {
    private TranslationDiagnostics() {}

    public static final class ApiDetection {
        private final boolean success;
        private final String providerMode;
        private final String authMode;
        private final String endpointUrl;
        private final int statusCode;
        private final String message;

        public ApiDetection(boolean success, String providerMode, String authMode,
                            String endpointUrl, int statusCode, String message) {
            this.success = success;
            this.providerMode = safe(providerMode);
            this.authMode = safe(authMode);
            this.endpointUrl = safe(endpointUrl);
            this.statusCode = statusCode;
            this.message = safe(message);
        }

        public boolean success() { return success; }
        public String providerMode() { return providerMode; }
        public String authMode() { return authMode; }
        public String endpointUrl() { return endpointUrl; }
        public int statusCode() { return statusCode; }
        public String message() { return message; }
    }

    public static final class ModelDetection {
        private final boolean success;
        private final String endpointUrl;
        private final int statusCode;
        private final List<String> models;
        private final String message;

        public ModelDetection(boolean success, String endpointUrl, int statusCode,
                              List<String> models, String message) {
            this.success = success;
            this.endpointUrl = safe(endpointUrl);
            this.statusCode = statusCode;
            this.models = models == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(models));
            this.message = safe(message);
        }

        public boolean success() { return success; }
        public String endpointUrl() { return endpointUrl; }
        public int statusCode() { return statusCode; }
        public List<String> models() { return models; }
        public String message() { return message; }
    }

    public static final class ModelAccess {
        private final boolean success;
        private final String modelId;
        private final int statusCode;
        private final String message;

        public ModelAccess(boolean success, String modelId, int statusCode, String message) {
            this.success = success;
            this.modelId = safe(modelId);
            this.statusCode = statusCode;
            this.message = safe(message);
        }

        public boolean success() { return success; }
        public String modelId() { return modelId; }
        public int statusCode() { return statusCode; }
        public String message() { return message; }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
