package com.yourname.simpletranslate.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.translation.OcrTranslationService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class OcrHistoryManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ENTRIES = 80;
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static String loadedScope;
    private static List<Entry> entries = new ArrayList<>();

    private OcrHistoryManager() {
    }

    public static synchronized void record(byte[] pngBytes, OcrTranslationService.OcrResult result) {
        if (pngBytes == null || pngBytes.length == 0 || result == null
                || !result.success() || !result.hasText()) {
            return;
        }
        ensureLoaded();
        Path scopeDir = scopeDir();
        if (scopeDir == null) {
            return;
        }
        try {
            Files.createDirectories(imagesDir(scopeDir));
            long createdAt = System.currentTimeMillis();
            String imageHash = OcrManager.sha256(pngBytes);
            String id = createdAt + "-" + imageHash.substring(0, Math.min(12, imageHash.length()));
            String imageFile = id + ".png";
            Files.write(imagesDir(scopeDir).resolve(imageFile), pngBytes);
            Entry entry = new Entry(id, createdAt, imageFile,
                    result.sourceText(), result.translationText(), result.regions());
            entries.add(0, entry);
            trimOldEntries(scopeDir);
            save(scopeDir);
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().warn("Failed to save OCR history entry", e);
        }
    }

    public static synchronized List<Entry> entries() {
        ensureLoaded();
        return List.copyOf(entries);
    }

    public static synchronized Path imagePath(Entry entry) {
        Path scopeDir = scopeDir();
        if (scopeDir == null || entry == null || entry.imageFile().isBlank()) {
            return null;
        }
        return imagesDir(scopeDir).resolve(entry.imageFile());
    }

    public static synchronized String currentScopeName() {
        return scope();
    }

    private static void ensureLoaded() {
        String scope = scope();
        if (scope.equals(loadedScope)) {
            return;
        }
        loadedScope = scope;
        entries = new ArrayList<>();
        Path dir = scopeDir();
        if (dir == null) {
            return;
        }
        Path index = dir.resolve("history.json");
        if (!Files.exists(index)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(index)).getAsJsonObject();
            JsonElement arrayElement = root.get("entries");
            if (arrayElement == null || !arrayElement.isJsonArray()) {
                return;
            }
            for (JsonElement element : arrayElement.getAsJsonArray()) {
                Entry entry = parseEntry(element);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            entries.sort(Comparator.comparingLong(Entry::createdAt).reversed());
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().warn("Failed to load OCR history for scope {}", scope, e);
            entries = new ArrayList<>();
        }
    }

    private static Entry parseEntry(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String id = stringValue(object, "id");
        String imageFile = stringValue(object, "imageFile");
        if (id.isBlank() || imageFile.isBlank()) {
            return null;
        }
        List<OcrTranslationService.OcrRegion> regions = new ArrayList<>();
        JsonElement regionsElement = object.get("regions");
        if (regionsElement != null && regionsElement.isJsonArray()) {
            for (JsonElement regionElement : regionsElement.getAsJsonArray()) {
                OcrTranslationService.OcrRegion region = parseRegion(regionElement);
                if (region != null) {
                    regions.add(region);
                }
            }
        }
        return new Entry(id,
                longValue(object, "createdAt"),
                imageFile,
                stringValue(object, "sourceText"),
                stringValue(object, "translationText"),
                regions);
    }

    private static OcrTranslationService.OcrRegion parseRegion(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        try {
            return new OcrTranslationService.OcrRegion(
                    stringValue(object, "sourceText"),
                    stringValue(object, "translationText"),
                    intValue(object, "x"),
                    intValue(object, "y"),
                    intValue(object, "width"),
                    intValue(object, "height")).sanitized();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void trimOldEntries(Path scopeDir) {
        while (entries.size() > MAX_ENTRIES) {
            Entry removed = entries.remove(entries.size() - 1);
            try {
                Files.deleteIfExists(imagesDir(scopeDir).resolve(removed.imageFile()));
            } catch (IOException ignored) {
                // Old screenshots are best-effort cleanup only.
            }
        }
    }

    private static void save(Path scopeDir) throws IOException {
        Files.createDirectories(scopeDir);
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("scope", scope());
        JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            JsonObject object = new JsonObject();
            object.addProperty("id", entry.id());
            object.addProperty("createdAt", entry.createdAt());
            object.addProperty("imageFile", entry.imageFile());
            object.addProperty("sourceText", entry.sourceText());
            object.addProperty("translationText", entry.translationText());
            JsonArray regions = new JsonArray();
            for (OcrTranslationService.OcrRegion region : entry.regions()) {
                JsonObject regionObject = new JsonObject();
                regionObject.addProperty("sourceText", region.sourceText());
                regionObject.addProperty("translationText", region.translationText());
                regionObject.addProperty("x", region.x());
                regionObject.addProperty("y", region.y());
                regionObject.addProperty("width", region.width());
                regionObject.addProperty("height", region.height());
                regions.add(regionObject);
            }
            object.add("regions", regions);
            array.add(object);
        }
        root.add("entries", array);
        Files.writeString(scopeDir.resolve("history.json"), GSON.toJson(root));
    }

    private static String scope() {
        String scope = SimpleTranslateMod.getCurrentCacheScopeId();
        if (scope == null || scope.isBlank()) {
            return "global";
        }
        return scope;
    }

    private static Path scopeDir() {
        Path configDir = SimpleTranslateMod.getConfigDir();
        if (configDir == null) {
            return null;
        }
        return configDir.resolve("ocr_history").resolve(scope());
    }

    private static Path imagesDir(Path scopeDir) {
        return scopeDir.resolve("images");
    }

    private static String stringValue(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static long longValue(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static int intValue(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    public record Entry(String id, long createdAt, String imageFile, String sourceText,
                        String translationText, List<OcrTranslationService.OcrRegion> regions) {
        public Entry {
            id = id == null ? "" : id;
            imageFile = imageFile == null ? "" : imageFile;
            sourceText = sourceText == null ? "" : sourceText;
            translationText = translationText == null ? "" : translationText;
            regions = regions == null ? List.of() : List.copyOf(regions);
        }

        public String displayTime() {
            long time = createdAt <= 0L ? System.currentTimeMillis() : createdAt;
            return DISPLAY_TIME.format(Instant.ofEpochMilli(time));
        }

        public String summary() {
            String text = !translationText.isBlank() ? translationText : sourceText;
            String compact = text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
            if (compact.isBlank()) {
                return id;
            }
            return compact.length() > 80 ? compact.substring(0, 77).trim() + "..." : compact;
        }

        public String normalizedId() {
            return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        }
    }
}
