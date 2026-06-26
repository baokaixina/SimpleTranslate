package com.yourname.simpletranslate.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.SimpleTranslateMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores text snippets that should never be sent through translation.
 */
public class TranslationBlacklist {
    private final Path blacklistFile;
    private final Map<String, String> entries;
    private final Gson gson;

    public TranslationBlacklist(Path blacklistFile) {
        this.blacklistFile = blacklistFile;
        this.entries = new ConcurrentHashMap<>();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public void load() {
        try {
            Files.createDirectories(blacklistFile.getParent());
            if (!Files.exists(blacklistFile)) {
                save();
                return;
            }

            String json = Files.readString(blacklistFile);
            if (json == null || json.isBlank()) {
                return;
            }

            entries.clear();
            JsonElement root = JsonParser.parseString(json);
            JsonArray array = null;
            if (root.isJsonArray()) {
                array = root.getAsJsonArray();
            } else if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                JsonElement entriesElement = object.get("entries");
                if (entriesElement != null && entriesElement.isJsonArray()) {
                    array = entriesElement.getAsJsonArray();
                }
            }

            if (array != null) {
                for (JsonElement element : array) {
                    if (element != null && element.isJsonPrimitive()) {
                        addEntryInternal(element.getAsString());
                    }
                }
            }
            SimpleTranslateMod.getLogger().debug("Loaded {} blacklist entries", entries.size());
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to load translation blacklist", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(blacklistFile.getParent());
            Files.writeString(blacklistFile, gson.toJson(getAllEntries()));
        } catch (IOException e) {
            SimpleTranslateMod.getLogger().error("Failed to save translation blacklist", e);
        }
    }

    public boolean isBlacklisted(String text) {
        if (text == null || text.isBlank() || entries.isEmpty()) {
            return false;
        }

        String normalizedText = normalize(text);
        if (normalizedText.isEmpty()) {
            return false;
        }

        return entries.containsKey(normalizedText);
    }

    public boolean containsBlacklistedEntry(String text) {
        if (text == null || text.isBlank() || entries.isEmpty()) {
            return false;
        }

        String normalizedText = normalize(text);
        if (normalizedText.isEmpty()) {
            return false;
        }

        for (String entry : entries.keySet()) {
            if (normalizedText.contains(entry)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsBlacklistedLine(String[] lines) {
        if (lines == null || lines.length == 0) {
            return false;
        }
        for (String line : lines) {
            if (isBlacklisted(line)) {
                return true;
            }
        }
        return false;
    }

    public void addEntry(String entry) {
        if (addEntryInternal(entry)) {
            save();
            SimpleTranslateMod.onTranslationBlacklistChanged();
        }
    }

    public void removeEntry(String entry) {
        String normalized = normalize(entry);
        if (!normalized.isEmpty() && entries.remove(normalized) != null) {
            save();
            SimpleTranslateMod.onTranslationBlacklistChanged();
        }
    }

    public void clear() {
        entries.clear();
        save();
        SimpleTranslateMod.onTranslationBlacklistChanged();
    }

    public List<String> getAllEntries() {
        List<String> result = new ArrayList<>(entries.values());
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public int size() {
        return entries.size();
    }

    public void exportToFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, gson.toJson(getAllEntries()));
    }

    public void importFromFile(Path file, boolean merge) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("Import file does not exist: " + file);
        }

        if (!merge) {
            entries.clear();
        }

        JsonElement root = JsonParser.parseString(Files.readString(file));
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                if (element != null && element.isJsonPrimitive()) {
                    addEntryInternal(element.getAsString());
                }
            }
        }
        save();
        SimpleTranslateMod.onTranslationBlacklistChanged();
    }

    private boolean addEntryInternal(String entry) {
        if (entry == null) {
            return false;
        }

        String display = entry.trim();
        String normalized = normalize(display);
        if (display.isEmpty() || normalized.isEmpty()) {
            return false;
        }

        entries.put(normalized, display);
        return true;
    }

    private static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String withoutFormatting = stripMinecraftFormatting(text);
        return withoutFormatting.trim().toLowerCase(Locale.ROOT);
    }

    private static String stripMinecraftFormatting(String text) {
        StringBuilder result = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }
}
