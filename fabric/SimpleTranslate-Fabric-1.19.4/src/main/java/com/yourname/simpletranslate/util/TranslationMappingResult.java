package com.yourname.simpletranslate.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public record TranslationMappingResult(String taskId, Map<String, String> translations) {

    public TranslationMappingResult {
        translations = translations == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(translations));
    }

    public String translationFor(String unitId) {
        if (unitId == null || translations == null) {
            return null;
        }
        return translations.get(unitId);
    }

    public boolean isSingleUnit() {
        return translations != null && translations.size() == 1;
    }

    public String toCacheValue() {
        JsonObject root = new JsonObject();
        root.addProperty("version", "mapping-v1");
        root.addProperty("taskId", taskId == null ? "" : taskId);
        JsonArray array = new JsonArray();
        for (Map.Entry<String, String> entry : translations.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.getKey());
            item.addProperty("translation", entry.getValue());
            array.add(item);
        }
        root.add("translations", array);
        return root.toString();
    }

    public static TranslationMappingResult fromCacheValue(String cacheValue) {
        if (cacheValue == null || cacheValue.isBlank()) {
            return new TranslationMappingResult("", Map.of());
        }

        String trimmed = cacheValue.trim();
        if (!trimmed.startsWith("{")) {
            return new TranslationMappingResult("", Map.of("u0", cacheValue));
        }

        try {
            JsonObject root = JsonParser.parseString(trimmed).getAsJsonObject();
            String taskId = root.has("taskId") ? root.get("taskId").getAsString() : "";
            Map<String, String> translations = new LinkedHashMap<>();
            if (root.has("translations") && root.get("translations").isJsonArray()) {
                for (var element : root.getAsJsonArray("translations")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject item = element.getAsJsonObject();
                    String id = item.has("id") ? item.get("id").getAsString() : "";
                    String translation = item.has("translation") ? item.get("translation").getAsString() : "";
                    if (!id.isBlank()) {
                        translations.put(id, translation);
                    }
                }
            }
            if (translations.isEmpty() && root.has("translation")) {
                translations.put("u0", root.get("translation").getAsString());
            }
            return new TranslationMappingResult(taskId, translations);
        } catch (Exception ignored) {
            return new TranslationMappingResult("", Map.of("u0", cacheValue));
        }
    }
}
