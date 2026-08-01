package com.yourname.simpletranslate.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.yourname.simpletranslate.core.AtomicFiles;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent server-side store for complete baseline cache entries. */
public final class SharedCacheStore {
    private static final Logger LOGGER = LogManager.getLogger("SimpleTranslateSharedCache-1.12.2");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_VERSION = "simpletranslate-shared-cache-v1";
    private static final long SAVE_DELAY_MS = 1500L;
    private final Map<String, SharedCacheEntry> entries = new LinkedHashMap<String, SharedCacheEntry>();
    private Path file;
    private boolean dirty;
    private long nextSaveAt;

    public SharedCacheStore() { }
    public SharedCacheStore(File file) { this.file = file == null ? null : file.toPath(); }

    public synchronized void load() { load(file); }
    public synchronized void load(Path file) {
        this.file = file;
        entries.clear();
        dirty = false;
        nextSaveAt = 0L;
        if (file == null || !Files.exists(file)) return;
        try {
            StoreFile data = GSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), StoreFile.class);
            if (data == null || data.entries == null) return;
            for (SharedCacheEntry entry : data.entries) {
                if (isAccepted(entry) && !entries.containsKey(entry.key())) entries.put(entry.key(), entry);
            }
            LOGGER.debug("Loaded {} shared cache entries from {}", entries.size(), file);
        } catch (Exception error) {
            LOGGER.warn("Failed to load shared cache store {}: {}", file, error.getMessage());
        }
    }

    public synchronized List<SharedCacheEntry> allEntries() {
        return new ArrayList<SharedCacheEntry>(entries.values());
    }

    public synchronized List<SharedCacheEntry> putMissing(Collection<SharedCacheEntry> incoming) {
        if (incoming == null || incoming.isEmpty()) return Collections.emptyList();
        List<SharedCacheEntry> accepted = new ArrayList<SharedCacheEntry>();
        for (SharedCacheEntry entry : incoming) {
            if (!isAccepted(entry) || entries.containsKey(entry.key())) continue;
            entries.put(entry.key(), entry);
            accepted.add(entry);
        }
        if (!accepted.isEmpty()) markDirty();
        return accepted;
    }

    public synchronized void saveIfDue(long now) { if (dirty && now >= nextSaveAt) saveNow(); }
    public synchronized void save() { saveNow(); }
    public synchronized void saveNow() {
        if (!dirty || file == null) return;
        dirty = false;
        try {
            StoreFile data = new StoreFile();
            data.version = FILE_VERSION;
            data.protocol = TranslationCacheKeys.PROTOCOL;
            data.entries = new ArrayList<SharedCacheEntry>(entries.values());
            AtomicFiles.writeString(file, GSON.toJson(data));
        } catch (IOException error) {
            dirty = true;
            LOGGER.warn("Failed to save shared cache store {}: {}", file, error.getMessage());
        }
    }

    private void markDirty() {
        dirty = true;
        long now = System.currentTimeMillis();
        if (nextSaveAt <= now) nextSaveAt = now + SAVE_DELAY_MS;
    }
    private static boolean isAccepted(SharedCacheEntry entry) { return entry != null && entry.isShareable(); }
    private static final class StoreFile {
        String version;
        String protocol;
        List<SharedCacheEntry> entries = new ArrayList<SharedCacheEntry>();
    }
}
