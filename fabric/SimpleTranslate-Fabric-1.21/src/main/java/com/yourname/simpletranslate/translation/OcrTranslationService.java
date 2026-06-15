package com.yourname.simpletranslate.translation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.util.ModelOutputSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface OcrTranslationService {
    String SURFACE = "ocr.region.vision";

    CompletableFuture<OcrResult> translateImage(byte[] pngBytes, String imageHash,
                                                String sourceLanguage, String targetLanguage);

    CompletableFuture<OcrResult> verifyAccess();

    boolean isReady();

    String describeActiveProfile();

    record OcrResult(boolean success, String sourceText, String translationText,
                     List<OcrRegion> regions, String errorMessage) {
        public static OcrResult success(String sourceText, String translationText) {
            return success(sourceText, translationText, List.of());
        }

        public static OcrResult success(String sourceText, String translationText, List<OcrRegion> regions) {
            String source = sanitize(sourceText);
            String translation = sanitize(translationText);
            List<OcrRegion> safeRegions = regions == null
                    ? List.of()
                    : regions.stream().map(OcrRegion::sanitized).filter(OcrRegion::hasTranslation).toList();
            if (source.isBlank() && !safeRegions.isEmpty()) {
                source = joinRegionText(safeRegions, true);
            }
            if (translation.isBlank() && !safeRegions.isEmpty()) {
                translation = joinRegionText(safeRegions, false);
            }
            return new OcrResult(true, source, translation, safeRegions, "");
        }

        public static OcrResult failure(String message) {
            return new OcrResult(false, "", "", List.of(),
                    message == null || message.isBlank() ? "OCR failed" : message);
        }

        public boolean hasText() {
            return (sourceText != null && !sourceText.isBlank())
                    || (translationText != null && !translationText.isBlank())
                    || !regions.isEmpty();
        }

        public boolean hasPositionedRegions() {
            return regions != null && regions.stream().anyMatch(OcrRegion::hasTranslation);
        }

        public String toCacheValue() {
            JsonObject object = new JsonObject();
            object.addProperty("version", "ocr-cache-v2");
            object.addProperty("sourceText", sourceText == null ? "" : sourceText);
            object.addProperty("translationText", translationText == null ? "" : translationText);
            JsonArray regionArray = new JsonArray();
            for (OcrRegion region : regions == null ? List.<OcrRegion>of() : regions) {
                JsonObject entry = new JsonObject();
                entry.addProperty("sourceText", region.sourceText());
                entry.addProperty("translationText", region.translationText());
                entry.addProperty("x", region.x());
                entry.addProperty("y", region.y());
                entry.addProperty("width", region.width());
                entry.addProperty("height", region.height());
                regionArray.add(entry);
            }
            object.add("regions", regionArray);
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
                return success(source, translation, parseCachedRegions(object.get("regions")));
            } catch (Exception ignored) {
                return null;
            }
        }

        private static List<OcrRegion> parseCachedRegions(JsonElement element) {
            if (element == null || !element.isJsonArray()) {
                return List.of();
            }
            List<OcrRegion> parsed = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject object = item.getAsJsonObject();
                try {
                    parsed.add(new OcrRegion(
                            stringValue(object, "sourceText"),
                            stringValue(object, "translationText"),
                            intValue(object, "x"),
                            intValue(object, "y"),
                            intValue(object, "width"),
                            intValue(object, "height")));
                } catch (Exception ignored) {
                    // Skip malformed cache regions while preserving the rest of the OCR result.
                }
            }
            return parsed;
        }

        private static String stringValue(JsonObject object, String key) {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
        }

        private static int intValue(JsonObject object, String key) {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : 0;
        }

        private static String joinRegionText(List<OcrRegion> regions, boolean source) {
            return regions.stream()
                    .map(region -> source ? region.sourceText() : region.translationText())
                    .filter(text -> text != null && !text.isBlank())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }

        private static String sanitize(String value) {
            String cleaned = ModelOutputSanitizer.sanitize(value);
            return cleaned == null ? "" : cleaned.trim();
        }
    }

    record OcrRegion(String sourceText, String translationText,
                     int x, int y, int width, int height) {
        private static final int COORDINATE_MAX = 1000;

        public OcrRegion sanitized() {
            return new OcrRegion(
                    sanitize(sourceText),
                    sanitize(translationText),
                    clamp(x, 0, COORDINATE_MAX - 1),
                    clamp(y, 0, COORDINATE_MAX - 1),
                    clamp(width, 1, COORDINATE_MAX),
                    clamp(height, 1, COORDINATE_MAX));
        }

        public boolean hasTranslation() {
            return translationText != null && !translationText.isBlank();
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static String sanitize(String value) {
            String cleaned = ModelOutputSanitizer.sanitize(value);
            return cleaned == null ? "" : cleaned.trim();
        }
    }
}
