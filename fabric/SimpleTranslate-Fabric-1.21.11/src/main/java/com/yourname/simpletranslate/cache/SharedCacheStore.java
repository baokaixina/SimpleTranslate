package com.yourname.simpletranslate.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SharedCacheStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("SimpleTranslateSharedCache");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_VERSION = "simpletranslate-shared-cache-v1";
    private static final long SAVE_DELAY_MS = 1500L;

    private final Map<String, SharedCacheEntry> entries = new LinkedHashMap<>();
    private Path file;
    private boolean dirty;
    private long nextSaveAt;

    public synchronized void load(Path file) {
        this.file = file;
        entries.clear();
        dirty = false;
        nextSaveAt = 0L;
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            StoreFile data = GSON.fromJson(Files.readString(file), StoreFile.class);
            if (data == null || data.entries == null) {
                return;
            }
            for (SharedCacheEntry entry : data.entries) {
                if (isAccepted(entry) && !entries.containsKey(entry.key())) {
                    entries.put(entry.key(), entry);
                }
            }
            LOGGER.debug("Loaded {} shared cache entries from {}", entries.size(), file);
        } catch (Exception e) {
            LOGGER.warn("Failed to load shared cache store {}: {}", file, e.getMessage());
        }
    }

    public synchronized List<SharedCacheEntry> allEntries() {
        return new ArrayList<>(entries.values());
    }

    public synchronized List<SharedCacheEntry> putMissing(Collection<SharedCacheEntry> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return List.of();
        }
        List<SharedCacheEntry> accepted = new ArrayList<>();
        for (SharedCacheEntry entry : incoming) {
            if (!isAccepted(entry) || entries.containsKey(entry.key())) {
                continue;
            }
            entries.put(entry.key(), entry);
            accepted.add(entry);
        }
        if (!accepted.isEmpty()) {
            markDirty();
        }
        return accepted;
    }

    public synchronized void saveIfDue(long now) {
        if (dirty && now >= nextSaveAt) {
            saveNow();
        }
    }

    public synchronized void saveNow() {
        if (!dirty || file == null) {
            return;
        }
        dirty = false;
        try {
            StoreFile data = new StoreFile();
            data.version = FILE_VERSION;
            data.protocol = TranslationCacheKeys.PROTOCOL;
            data.entries = new ArrayList<>(entries.values());
            Files.writeString(file, GSON.toJson(data));
        } catch (IOException e) {
            dirty = true;
            LOGGER.warn("Failed to save shared cache store {}: {}", file, e.getMessage());
        }
    }

    private void markDirty() {
        dirty = true;
        long now = System.currentTimeMillis();
        if (nextSaveAt <= now) {
            nextSaveAt = now + SAVE_DELAY_MS;
        }
    }

    private static boolean isAccepted(SharedCacheEntry entry) {
        return entry != null && entry.isShareable();
    }

    private static final class StoreFile {
        String version;
        String protocol;
        List<SharedCacheEntry> entries = new ArrayList<>();
    }
}
