package com.yourname.simpletranslate.translation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.util.ModelOutputSanitizer;

import java.util.concurrent.CompletableFuture;

public interface OcrTranslationService {
    String SURFACE = "ocr.region.vision";

    CompletableFuture<OcrResult> translateImage(byte[] pngBytes, String imageHash,
                                                String sourceLanguage, String targetLanguage);

    CompletableFuture<OcrResult> verifyAccess();

    boolean isReady();

    String describeActiveProfile();

    record OcrResult(boolean success, String sourceText, String translationText, String errorMessage) {
        public static OcrResult success(String sourceText, String translationText) {
            String source = sanitize(sourceText);
            String translation = sanitize(translationText);
            return new OcrResult(true, source, translation, "");
        }

        public static OcrResult failure(String message) {
            return new OcrResult(false, "", "", message == null || message.isBlank() ? "OCR failed" : message);
        }

        public boolean hasText() {
            return (sourceText != null && !sourceText.isBlank())
                    || (translationText != null && !translationText.isBlank());
        }

        public String toCacheValue() {
            JsonObject object = new JsonObject();
            object.addProperty("version", "ocr-cache-v1");
            object.addProperty("sourceText", sourceText == null ? "" : sourceText);
            object.addProperty("translationText", translationText == null ? "" : translationText);
            return object.toString();
        }

        public static OcrResult fromCacheValue(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                JsonObject object = JsonParser.parseString(value).getAsJsonObject();
                if (!object.has("translationText")) {
                    return null;
                }
                String source = object.has("sourceText") && !object.get("sourceText").isJsonNull()
                        ? object.get("sourceText").getAsString() : "";
                String translation = object.get("translationText").getAsString();
                return success(source, translation);
            } catch (Exception ignored) {
                return null;
            }
        }

        private static String sanitize(String value) {
            String cleaned = ModelOutputSanitizer.sanitize(value);
            return cleaned == null ? "" : cleaned.trim();
        }
    }
}
