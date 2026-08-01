package com.yourname.simpletranslate.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.core.AtomicFiles;
import com.yourname.simpletranslate.core.TextContextMemory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final Logger LOGGER = LogManager.getLogger("SimpleTranslate/Blacklist-1.12.2");
    private final Path blacklistFile;
    private final Map<String, String> entries;
    private final Gson gson;

    public TranslationBlacklist(Path blacklistFile) {
        this.blacklistFile = blacklistFile;
        this.entries = new ConcurrentHashMap<>();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public TranslationBlacklist(File blacklistFile) { this(blacklistFile.toPath()); }

    public void load() {
        try {
            Files.createDirectories(blacklistFile.getParent());
            if (!Files.exists(blacklistFile)) {
                save();
                return;
            }

            String json = new String(Files.readAllBytes(blacklistFile), StandardCharsets.UTF_8);
            if (json.trim().isEmpty()) {
                return;
            }

            entries.clear();
            JsonElement root = new com.google.gson.JsonParser().parse(json);
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
            LOGGER.debug("Loaded {} blacklist entries", entries.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load translation blacklist", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(blacklistFile.getParent());
            AtomicFiles.writeString(blacklistFile, gson.toJson(getAllEntries()));
        } catch (IOException e) {
            LOGGER.error("Failed to save translation blacklist", e);
        }
    }

    public boolean isBlacklisted(String text) {
        if (text == null || text.trim().isEmpty() || entries.isEmpty()) {
            return false;
        }

        String normalizedText = normalize(text);
        if (normalizedText.isEmpty()) {
            return false;
        }

        return entries.containsKey(normalizedText);
    }

    public boolean containsBlacklistedEntry(String text) {
        if (text == null || text.trim().isEmpty() || entries.isEmpty()) {
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
            changed();
        }
    }

    public void removeEntry(String entry) {
        String normalized = normalize(entry);
        if (!normalized.isEmpty() && entries.remove(normalized) != null) {
            save();
            changed();
        }
    }

    public void clear() {
        entries.clear();
        save();
        changed();
    }

    public List<String> getAllEntries() {
        List<String> result = new ArrayList<>(entries.values());
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public boolean contains(String text) { return containsBlacklistedEntry(text); }

    public List<String> entries() { return getAllEntries(); }
    public void add(String entry) { addEntry(entry); }

    public int size() {
        return entries.size();
    }

    public void exportToFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, gson.toJson(getAllEntries()).getBytes(StandardCharsets.UTF_8));
    }

    public void importFromFile(Path file, boolean merge) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("Import file does not exist: " + file);
        }

        if (!merge) {
            entries.clear();
        }

        JsonElement root = new com.google.gson.JsonParser().parse(
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                if (element != null && element.isJsonPrimitive()) {
                    addEntryInternal(element.getAsString());
                }
            }
        }
        save();
        changed();
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

    private static void changed() { TextContextMemory.settingsChanged(); }
}
