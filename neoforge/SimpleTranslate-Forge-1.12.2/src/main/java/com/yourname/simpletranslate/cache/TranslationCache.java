package com.yourname.simpletranslate.cache;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.cache.SharedCacheClient;
import com.yourname.simpletranslate.core.TranslationCacheKeys;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Categorized translation cache with JSON persistence.
 * Each translation lane writes to its own file to prevent cross-feature cache
 * contamination.
 */
public class TranslationCache {
    private static final Logger LOGGER = LogManager.getLogger("SimpleTranslate/Cache-1.12.2");
    private static final long SAVE_DELAY_MS = 750L;
    private static final String SHARE_MANIFEST_FILE = "simple_translate_cache_share.json";
    private static final String SHARE_FORMAT = "simpletranslate-cache-share-v1";
    private static final Set<String> KNOWN_LANES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
            "tooltip",
            "sign",
            "chat",
            "chat_batch",
            "hud",
            "book",
            "advancement",
            "scoreboard",
            "entity",
            "hover",
            "bossbar",
            "manager",
            "generic"
    )));
    private static final Set<String> AUXILIARY_CACHE_FILE_STEMS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
            "line_memory"
    )));
    private static final ScheduledExecutorService SAVE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SimpleTranslate-CacheSave");
        thread.setDaemon(true);
        return thread;
    });

    private final Path legacyCacheFile;
    private final Path cacheRoot;
    private final Map<String, Map<String, CacheRecord>> translationsByLane;
    private final Map<String, Map<String, Set<String>>> compatibleIndexByLane;
    private final Map<String, Set<CacheReference>> semanticIndex;
    private final Gson gson;
    private final Object saveLock = new Object();
    private final Object saveIoLock = new Object();
    private volatile boolean dirty;
    private long contentRevision;
    private ScheduledFuture<?> pendingSave;

    public TranslationCache(Path cacheFile) {
        this.legacyCacheFile = cacheFile;
        this.cacheRoot = determineCacheRoot(cacheFile);
        this.translationsByLane = new ConcurrentHashMap<>();
        this.compatibleIndexByLane = new ConcurrentHashMap<>();
        this.semanticIndex = new ConcurrentHashMap<>();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public synchronized void load() {
        try {
            Files.createDirectories(cacheRoot);
            archiveLegacyProtocolFiles();
            translationsByLane.clear();
            compatibleIndexByLane.clear();
            semanticIndex.clear();
            contentRevision++;

            Set<String> lanesToLoad = new HashSet<>(KNOWN_LANES);
            if (Files.exists(cacheRoot)) {
                try (Stream<Path> stream = Files.list(cacheRoot)) {
                    stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                            .map(path -> path.getFileName().toString())
                            .map(name -> name.substring(0, name.length() - ".json".length()))
                            .filter(name -> !AUXILIARY_CACHE_FILE_STEMS.contains(name))
                            .forEach(lanesToLoad::add);
                }
            }

            int loadedCount = 0;
            for (String lane : lanesToLoad) {
                loadedCount += loadLane(lane);
            }

            if (Files.exists(legacyCacheFile) && !legacyCacheFile.startsWith(cacheRoot)) {
                LOGGER.debug("Ignoring legacy translation cache at {} for {} protocol",
                        legacyCacheFile, TranslationCacheKeys.PROTOCOL);
            }
            LOGGER.debug("Loaded {} categorized cached translations from {}",
                    loadedCount, cacheRoot);
        } catch (IOException e) {
            LOGGER.error("Failed to load translation cache", e);
        }
    }

    public void save() {
        synchronized (saveLock) {
            dirty = true;
            if (pendingSave == null || pendingSave.isDone()) {
                pendingSave = SAVE_EXECUTOR.schedule(this::saveNow, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    public void saveNow() {
        synchronized (saveLock) {
            pendingSave = null;
            if (!dirty) {
                return;
            }
            dirty = false;
        }

        // Never hold the cache monitor while Gson serializes or the filesystem
        // writes. HUD cache reads occur on the client render thread, so the old
        // synchronized saveNow could stall an otherwise asynchronous dialogue
        // every time a larger cache lane was flushed.
        Map<String, Map<String, CacheRecord>> snapshot = snapshotForSave();
        synchronized (saveIoLock) {
            try {
                Files.createDirectories(cacheRoot);
                for (Map.Entry<String, Map<String, CacheRecord>> entry : snapshot.entrySet()) {
                    saveLane(entry.getKey(), entry.getValue());
                }
            } catch (IOException e) {
                LOGGER.error("Failed to save translation cache", e);
            }
        }
    }

    public void flush() {
        saveNow();
    }

    /** Captures a coherent copy under the short cache lock; disk I/O is always outside it. */
    private synchronized Map<String, Map<String, CacheRecord>> snapshotForSave() {
        Map<String, Map<String, CacheRecord>> snapshot = new ConcurrentHashMap<>();
        for (Map.Entry<String, Map<String, CacheRecord>> lane : translationsByLane.entrySet()) {
            Map<String, CacheRecord> records = new ConcurrentHashMap<>();
            for (Map.Entry<String, CacheRecord> entry : lane.getValue().entrySet()) {
                records.put(entry.getKey(), CacheRecord.copyForPersistence(entry.getValue()));
            }
            snapshot.put(lane.getKey(), records);
        }
        return snapshot;
    }

    public static void shutdownExecutor() {
        SAVE_EXECUTOR.shutdownNow();
    }

    public synchronized Optional<String> get(String original) {
        if (original == null || !TranslationCacheKeys.isCurrentProtocolKey(original)) {
            return Optional.empty();
        }

        CacheRecord record = getLaneMap(TranslationCacheKeys.laneFromKey(original), false).get(original);
        String translated = record == null ? null : record.translation;
        TranslationBlacklist blacklist = blacklist();
        if (translated != null && blacklist != null && blacklist.containsBlacklistedEntry(translated)) {
            return Optional.empty();
        }

        if (record != null) {
            long now = System.currentTimeMillis();
            if (now - record.lastUsedAt > 60_000L) {
                record.lastUsedAt = now;
                dirty = true;
            }
        }
        return Optional.ofNullable(translated);
    }

    /**
     * Returns translations for the same visible text across chat, hover and
     * tooltip cache lanes. Callers still have to restore the candidate against
     * their own component structure before displaying it.
     */
    public synchronized List<SemanticCacheCandidate> getSemanticBySource(String sourceText, String exactKey) {
        if (blank(sourceText)
                || exactKey == null || !TranslationCacheKeys.isCurrentProtocolKey(exactKey)) {
            return Collections.emptyList();
        }
        Set<CacheReference> references = semanticIndex.getOrDefault(
                semanticGroupKey(sourceText, exactKey), Collections.<CacheReference>emptySet());
        if (references.isEmpty()) {
            return Collections.emptyList();
        }

        TranslationBlacklist blacklist = blacklist();
        List<SemanticCacheCandidate> candidates = new ArrayList<>();
        String exactSurface = TranslationCacheKeys.surfaceFromKey(exactKey);
        for (CacheReference reference : references) {
            if (reference.key().equals(exactKey)) {
                continue;
            }
            CacheRecord record = getLaneMap(reference.lane(), false).get(reference.key());
            if (record == null || blank(record.translation) || blank(record.translationText)) {
                continue;
            }
            if (blacklist != null && (blacklist.containsBlacklistedEntry(record.translation)
                    || blacklist.containsBlacklistedEntry(record.translationText))) {
                continue;
            }
            candidates.add(new SemanticCacheCandidate(record.translation, record.translationText,
                    record.editedByPlayer, record.createdAt, reference.key()));
        }
        candidates.sort(Comparator
                .comparing((SemanticCacheCandidate candidate) ->
                        !TranslationCacheKeys.surfaceFromKey(candidate.sourceKey()).equals(exactSurface))
                .thenComparing(Comparator.comparing(SemanticCacheCandidate::editedByPlayer).reversed())
                .thenComparingLong(SemanticCacheCandidate::createdAt)
                .thenComparing(SemanticCacheCandidate::sourceKey));
        return candidates.isEmpty() ? Collections.<SemanticCacheCandidate>emptyList()
                : Collections.unmodifiableList(new ArrayList<SemanticCacheCandidate>(candidates));
    }

    private static String compatibleGroupKey(String surface, String sourceHash, String languageHash,
                                             String formatPreset) {
        if (blank(surface) || blank(sourceHash) || blank(languageHash)) {
            return "";
        }
        return surface + '\0' + sourceHash + '\0' + languageHash + '\0' + formatPreset;
    }

    private static String compatibleGroupKeyFromKey(String exactKey) {
        return compatibleGroupKey(
                TranslationCacheKeys.surfaceFromKey(exactKey),
                TranslationCacheKeys.sourceHashFromKey(exactKey),
                CacheRecord.extractKeyPart(exactKey, "lang="),
                formatPresetFromKey(exactKey));
    }

    private void indexCompatibleEntry(String lane, String key) {
        String groupKey = compatibleGroupKeyFromKey(key);
        if (groupKey.isEmpty()) {
            return;
        }
        compatibleIndexByLane
                .computeIfAbsent(normalizeLane(lane), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(groupKey, ignored -> ConcurrentHashMap.newKeySet())
                .add(key);
    }

    private static String semanticGroupKey(String sourceText, String key) {
        if (blank(sourceText) || key == null) {
            return "";
        }
        String languageHash = CacheRecord.extractKeyPart(key, "lang=");
        if (blank(languageHash)) {
            return "";
        }
        return TranslationCacheKeys.semanticHash(sourceText) + '\0' + languageHash + '\0'
                + formatPresetFromKey(key);
    }

    // Legacy cross-surface cache grouping dimension. The format preset was removed
    // (single placeholder protocol now), so keys no longer carry "fmt="; all
    // entries fall into one neutral group.
    private static String formatPresetFromKey(String key) {
        String value = CacheRecord.extractKeyPart(key, "fmt=");
        return blank(value) ? "default" : value;
    }

    private void indexSemanticEntry(String lane, String key, CacheRecord record) {
        if (record == null || blank(record.sourceText) || blank(record.translationText)) {
            return;
        }
        String groupKey = semanticGroupKey(record.sourceText, key);
        if (blank(groupKey)) {
            return;
        }
        semanticIndex.computeIfAbsent(groupKey, ignored -> ConcurrentHashMap.newKeySet())
                .add(new CacheReference(normalizeLane(lane), key));
    }

    private void unindexSemanticEntry(String lane, String key, CacheRecord record) {
        if (record == null) {
            return;
        }
        String groupKey = semanticGroupKey(record.sourceText, key);
        Set<CacheReference> references = semanticIndex.get(groupKey);
        if (references == null) {
            return;
        }
        references.remove(new CacheReference(normalizeLane(lane), key));
        if (references.isEmpty()) {
            semanticIndex.remove(groupKey);
        }
    }

    private void unindexCompatibleEntry(String lane, String key) {
        String normalizedLane = normalizeLane(lane);
        Map<String, Set<String>> laneIndex = compatibleIndexByLane.get(normalizedLane);
        if (laneIndex == null) {
            return;
        }
        String groupKey = compatibleGroupKeyFromKey(key);
        Set<String> keys = laneIndex.get(groupKey);
        if (keys == null) {
            return;
        }
        keys.remove(key);
        if (keys.isEmpty()) {
            laneIndex.remove(groupKey);
        }
    }

    public synchronized void put(String original, String translated) {
        put(original, translated, null, null);
    }

    public synchronized void put(String original, String translated, String sourceText, String translationText) {
        putInternal(original, translated, sourceText, translationText, true);
    }

    private CacheRecord putInternal(String original, String translated, String sourceText,
                                    String translationText, boolean enqueueForSharing) {
        if (original == null || translated == null || !TranslationCacheKeys.isCurrentProtocolKey(original)) {
            return null;
        }
        TranslationBlacklist blacklist = blacklist();
        if (blacklist != null && blacklist.containsBlacklistedEntry(translated)) {
            return null;
        }

        String lane = TranslationCacheKeys.laneFromKey(original);
        Map<String, CacheRecord> laneMap = getLaneMap(lane, true);
        CacheRecord existing = laneMap.get(original);
        if (existing != null) {
            unindexSemanticEntry(lane, original, existing);
        }
        long now = System.currentTimeMillis();
        CacheRecord record = existing == null ? CacheRecord.fromKey(original, translated, now) : existing;
        record.translation = translated;
        if (!blank(sourceText)) {
            record.sourceText = normalizeDisplayText(sourceText);
        }
        String displayTranslation = blank(translationText)
                ? displayTextFromValue(translated)
                : translationText;
        if (!blank(displayTranslation)) {
            record.translationText = normalizeDisplayText(displayTranslation);
        }
        record.lastUsedAt = now;
        record.sharedImported = false;
        laneMap.put(original, record);
        indexCompatibleEntry(lane, original);
        indexSemanticEntry(lane, original, record);
        markContentChanged();
        if (enqueueForSharing) {
            enqueueShareableLocalEntry(lane, original, record);
        }
        return record;
    }

    public synchronized void putComponentJson(String key, String translatedJson, String sourceJson,
                                              String sourceText, String translationText) {
        putComponentJson(key, translatedJson, sourceJson, sourceText, translationText, "");
    }

    public synchronized void putComponentJson(String key, String translatedJson, String sourceJson,
                                              String sourceText, String translationText,
                                              String promptFingerprint) {
        CacheRecord record = putInternal(key, translatedJson, sourceText, translationText, false);
        if (record == null) {
            return;
        }
        record.format = TranslationCacheKeys.COMPONENT_JSON_FORMAT;
        record.sourcePayload = sourceJson == null ? "" : sourceJson;
        record.promptFingerprint = promptFingerprint == null ? "" : promptFingerprint;
        markContentChanged();
        enqueueShareableLocalEntry(TranslationCacheKeys.laneFromKey(key), key, record);
    }

    public synchronized boolean putSharedIfAbsent(String key, String translated, String sourceText, String translationText,
                                     boolean editedByPlayer, long createdAt, long editedAt) {
        return putSharedIfAbsent(key, translated, sourceText, translationText,
                editedByPlayer, createdAt, editedAt, "");
    }

    public synchronized boolean putSharedIfAbsent(String key, String translated, String sourceText,
                                     String translationText, boolean editedByPlayer,
                                     long createdAt, long editedAt, String promptFingerprint) {
        if (key == null || translated == null || !isSupportedComponentJsonKey(key)
                || translated.trim().isEmpty()) {
            return false;
        }
        TranslationBlacklist blacklist = blacklist();
        if (blacklist != null && blacklist.containsBlacklistedEntry(translated)) {
            return false;
        }
        String lane = TranslationCacheKeys.laneFromKey(key);
        Map<String, CacheRecord> laneMap = getLaneMap(lane, true);
        long now = System.currentTimeMillis();
        CacheRecord record = CacheRecord.fromKey(key, translated, now);
        record.sourceText = normalizeDisplayText(sourceText);
        String displayTranslation = blank(translationText)
                ? displayTextFromValue(translated)
                : translationText;
        record.translationText = normalizeDisplayText(displayTranslation);
        record.createdAt = createdAt > 0 ? createdAt : now;
        record.lastUsedAt = now;
        record.editedByPlayer = editedByPlayer;
        record.editedAt = editedAt;
        record.sharedImported = true;
        record.promptFingerprint = promptFingerprint == null ? "" : promptFingerprint;
        if (laneMap.putIfAbsent(key, record) != null) {
            return false;
        }
        indexCompatibleEntry(lane, key);
        indexSemanticEntry(lane, key, record);
        markContentChanged();
        return true;
    }

    public synchronized void remove(String original) {
        if (original == null) {
            return;
        }
        String lane = TranslationCacheKeys.laneFromKey(original);
        Map<String, CacheRecord> laneMap = translationsByLane.get(normalizeLane(lane));
        CacheRecord removed = laneMap == null ? null : laneMap.remove(original);
        if (removed != null) {
            unindexCompatibleEntry(lane, original);
            unindexSemanticEntry(lane, original, removed);
            markContentChanged();
        }
    }

    public synchronized void clear() {
        translationsByLane.values().forEach(Map::clear);
        compatibleIndexByLane.clear();
        semanticIndex.clear();
        markContentChanged();
    }

    public synchronized int size() {
        int size = 0;
        for (Map<String, CacheRecord> map : translationsByLane.values()) {
            size += map.size();
        }
        return size;
    }

    /** Changes only when cached translation content or provenance changes. */
    public synchronized long contentRevision() {
        return contentRevision;
    }

    public synchronized Map<String, String> getAll() {
        Map<String, String> result = new ConcurrentHashMap<>();
        for (Map<String, CacheRecord> map : translationsByLane.values()) {
            for (Map.Entry<String, CacheRecord> entry : map.entrySet()) {
                result.put(entry.getKey(), entry.getValue().translation);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(result));
    }

    public synchronized Map<String, Integer> getLaneSizes() {
        Map<String, Integer> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, Map<String, CacheRecord>> entry : translationsByLane.entrySet()) {
            result.put(entry.getKey(), entry.getValue().size());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(result));
    }

    public synchronized Map<String, CacheViewEntry> getEntries() {
        Map<String, CacheViewEntry> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, Map<String, CacheRecord>> laneEntry : translationsByLane.entrySet()) {
            for (Map.Entry<String, CacheRecord> entry : laneEntry.getValue().entrySet()) {
                result.put(entry.getKey(), toViewEntry(laneEntry.getKey(), entry.getKey(), entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, CacheViewEntry>(result));
    }

    /** Allocation-free surface probe for startup compatibility migrations. */
    public synchronized boolean hasSurface(String surface) {
        if (blank(surface)) {
            return false;
        }
        for (Map<String, CacheRecord> lane : translationsByLane.values()) {
            for (Map.Entry<String, CacheRecord> entry : lane.entrySet()) {
                String recordSurface = entry.getValue().surface;
                if (blank(recordSurface)) {
                    recordSurface = TranslationCacheKeys.surfaceFromKey(entry.getKey());
                }
                if (surface.equals(recordSurface)) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized Optional<CacheViewEntry> getEntry(String key) {
        if (key == null || !TranslationCacheKeys.isCurrentProtocolKey(key)) {
            return Optional.empty();
        }
        String lane = TranslationCacheKeys.laneFromKey(key);
        CacheRecord record = getLaneMap(lane, false).get(key);
        if (record == null) {
            return Optional.empty();
        }
        return Optional.of(toViewEntry(lane, key, record));
    }

    public synchronized Optional<String> updateComponentJsonTextNodes(String key, List<String> textNodes) {
        if (key == null || !TranslationCacheKeys.isCurrentProtocolKey(key)) {
            return Optional.of("invalid-key");
        }
        Map<String, CacheRecord> laneMap = getLaneMap(TranslationCacheKeys.laneFromKey(key), false);
        CacheRecord record = laneMap.get(key);
        if (record == null || blank(record.translation)) {
            return Optional.of("missing-entry");
        }
        if (!TranslationCacheKeys.COMPONENT_JSON_FORMAT.equals(record.format)
                && !TranslationCacheKeys.isComponentJsonKey(key)) {
            return Optional.of("unsupported-format");
        }
        String rewritten = ComponentJsonCacheEditor.replaceTextNodes(record.translation, textNodes);
        if (blank(rewritten)) {
            return Optional.of("unsupported-format");
        }

        record.translation = rewritten;
        record.translationText = displayTextFromValue(rewritten);
        record.editedByPlayer = true;
        record.editedAt = System.currentTimeMillis();
        record.lastUsedAt = record.editedAt;
        record.sharedImported = false;
        markContentChanged();
        enqueueShareableLocalEntry(TranslationCacheKeys.laneFromKey(key), key, record);
        return Optional.empty();
    }

    private CacheViewEntry toViewEntry(String lane, String key, CacheRecord record) {
        String translationText = blank(record.translationText)
                ? displayTextFromValue(record.translation)
                : record.translationText;
        return new CacheViewEntry(
                lane,
                key,
                record.translation,
                record.sourceText == null ? "" : record.sourceText,
                translationText == null ? "" : translationText,
                record.surface,
                record.createdAt,
                record.lastUsedAt,
                record.editedByPlayer,
                record.editedAt,
                record.sharedImported,
                record.sourcePayload == null ? "" : record.sourcePayload,
                record.format == null ? "" : record.format,
                record.promptFingerprint == null ? "" : record.promptFingerprint);
    }

    public synchronized void exportToFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        writeFlatExport(file);
        LOGGER.info("Exported {} translations to {}", size(), file);
    }

    public synchronized CacheShareExportResult exportShareArchive(Path archiveFile, CacheShareMetadata metadata,
                                                     Path flatExportFile) throws IOException {
        if (archiveFile == null) {
            throw new IOException("Share archive file is missing");
        }
        Files.createDirectories(archiveFile.getParent());

        int laneCount = 0;
        int entryCount = 0;
        for (Map<String, CacheRecord> lane : translationsByLane.values()) {
            if (!lane.isEmpty()) {
                laneCount++;
                entryCount += lane.size();
            }
        }

        CacheShareManifest manifest = CacheShareManifest.from(metadata, laneCount, entryCount);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archiveFile), StandardCharsets.UTF_8)) {
            writeZipJson(zip, SHARE_MANIFEST_FILE, gson.toJson(manifest));
            for (Map.Entry<String, Map<String, CacheRecord>> entry : translationsByLane.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                CacheFileData data = new CacheFileData();
                data.version = TranslationCacheKeys.PROTOCOL;
                data.lane = normalizeLane(entry.getKey());
                data.entries = entry.getValue();
                writeZipJson(zip, "cache/" + data.lane + ".json", gson.toJson(data));
            }
            writeZipJson(zip, "cache_export.json", gson.toJson(getAll()));
        }

        if (flatExportFile != null) {
            Files.createDirectories(flatExportFile.getParent());
            writeFlatExport(flatExportFile);
        }

        LOGGER.debug(
                "Exported cache share archive: lanes={}, entries={}, archive={}",
                laneCount, entryCount, archiveFile);
        return new CacheShareExportResult(laneCount, entryCount, null, flatExportFile, archiveFile);
    }

    public synchronized CacheImportResult importFromFile(Path file, boolean merge) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("Import file does not exist: " + file);
        }

        if (!merge) {
            clear();
        }
        CacheImportResult result = new CacheImportResult();
        importSource(file, result, null, false);
        LOGGER.debug(
                "Imported cache file: sources={}, imported={}, existing={}, invalid={}, worldMismatch={}, failed={}",
                result.sourceCount(), result.imported(), result.skippedExisting(),
                result.skippedInvalid(), result.skippedWorldMismatch(), result.failedFiles());
        return result;
    }

    public synchronized CacheImportResult importFromShareSources(List<Path> sources) {
        CacheImportResult result = new CacheImportResult();
        if (sources == null || sources.isEmpty()) {
            return result;
        }
        for (Path source : sources) {
            importSource(source, result, null, true);
        }
        LOGGER.debug(
                "Imported cache share sources: sources={}, imported={}, existing={}, invalid={}, failed={}",
                result.sourceCount(), result.imported(), result.skippedExisting(),
                result.skippedInvalid(), result.failedFiles());
        return result;
    }

    public synchronized CacheImportResult importFromShareSources(List<Path> sources, String expectedWorldName) {
        CacheImportResult result = new CacheImportResult();
        if (sources == null || sources.isEmpty()) {
            return result;
        }
        for (Path source : sources) {
            importSource(source, result, expectedWorldName, true);
        }
        LOGGER.debug(
                "Imported cache share sources: sources={}, imported={}, existing={}, invalid={}, worldMismatch={}, failed={}",
                result.sourceCount(), result.imported(), result.skippedExisting(),
                result.skippedInvalid(), result.skippedWorldMismatch(), result.failedFiles());
        return result;
    }

    public static List<Path> discoverImportSources(Path configDir) {
        if (configDir == null) {
            return Collections.emptyList();
        }
        Set<Path> sources = new LinkedHashSet<>();
        addImportSourceIfUsable(sources, configDir.resolve("cache_share"));
        addImportSourceIfUsable(sources, configDir.resolve("cache_import"));
        addImportSourceIfUsable(sources, configDir.resolve("cache_import.json"));
        addImportSourceIfUsable(sources, configDir.resolve("cache_export.json"));
        return Collections.unmodifiableList(new ArrayList<Path>(sources));
    }

    public synchronized void update(String original, String newTranslation) {
        if (get(original).isPresent()) {
            put(original, newTranslation);
        }
    }

    private static Path determineCacheRoot(Path cacheFile) {
        Path parent = cacheFile.getParent();
        if (parent == null) {
            return java.nio.file.Paths.get("cache");
        }
        String fileName = cacheFile.getFileName() == null ? "" : cacheFile.getFileName().toString();
        if ("cache.json".equalsIgnoreCase(fileName)) {
            return parent.resolve("cache").resolve("global");
        }
        if (fileName.endsWith(".json")) {
            return parent;
        }
        return cacheFile;
    }

    private void archiveLegacyProtocolFiles() throws IOException {
        if (!Files.isDirectory(cacheRoot)) {
            return;
        }
        Path scope = cacheRoot.getFileName();
        Path cacheParent = cacheRoot.getParent();
        if (scope == null || cacheParent == null) {
            return;
        }
        Path legacyRoot = cacheParent.resolve("legacy").resolve(scope.toString());
        List<Path> legacyFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(cacheRoot)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !AUXILIARY_CACHE_FILE_STEMS.contains(
                            path.getFileName().toString().substring(
                                    0, path.getFileName().toString().length() - ".json".length())))
                    .forEach(path -> {
                        try {
                            JsonElement parsed = new com.google.gson.JsonParser().parse(readUtf8(path));
                            if (!parsed.isJsonObject()) {
                                return;
                            }
                            JsonObject object = parsed.getAsJsonObject();
                            String version = object.has("version") ? object.get("version").getAsString() : "";
                            if ("direct:v21-2tier".equals(version)) {
                                legacyFiles.add(path);
                            }
                        } catch (Exception ignored) {
                        }
                    });
        }
        if (legacyFiles.isEmpty()) {
            return;
        }
        Files.createDirectories(legacyRoot);
        for (Path source : legacyFiles) {
            String fileName = source.getFileName().toString() + ".bak";
            Path target = legacyRoot.resolve(fileName);
            if (Files.exists(target)) {
                target = legacyRoot.resolve(source.getFileName().toString() + "." + System.currentTimeMillis() + ".bak");
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Archived legacy translation cache {} -> {}", source, target);
        }
    }

    private Map<String, CacheRecord> getLaneMap(String lane, boolean create) {
        String normalized = normalizeLane(lane);
        if (!create) {
            Map<String, CacheRecord> existing = translationsByLane.get(normalized);
            return existing == null ? Collections.<String, CacheRecord>emptyMap() : existing;
        }
        return translationsByLane.computeIfAbsent(normalized, ignored -> new ConcurrentHashMap<>());
    }

    private int loadLane(String lane) {
        String normalizedLane = normalizeLane(lane);
        Path laneFile = cacheRoot.resolve(normalizedLane + ".json");
        if (!Files.exists(laneFile)) {
            return 0;
        }

        try {
            String json = readUtf8(laneFile);
            JsonElement parsed = new com.google.gson.JsonParser().parse(json);
            if (!parsed.isJsonObject()) {
                return 0;
            }

            JsonObject object = parsed.getAsJsonObject();
            Map<String, CacheRecord> laneMap = getLaneMap(normalizedLane, true);
            int loaded = 0;
            if (object.has("entries") && object.get("entries").isJsonObject()) {
                Type type = new TypeToken<Map<String, CacheRecord>>() {
                }.getType();
                Map<String, CacheRecord> records = gson.fromJson(object.get("entries"), type);
                if (records != null) {
                    for (Map.Entry<String, CacheRecord> entry : records.entrySet()) {
                        if (isSupportedComponentJsonKey(entry.getKey()) && entry.getValue() != null) {
                            if (blank(entry.getValue().format)) {
                                entry.getValue().format = TranslationCacheKeys.isComponentJsonKey(entry.getKey())
                                        ? TranslationCacheKeys.COMPONENT_JSON_FORMAT
                                        : "legacy_component_json";
                            }
                            laneMap.put(entry.getKey(), entry.getValue());
                            indexCompatibleEntry(normalizedLane, entry.getKey());
                            indexSemanticEntry(normalizedLane, entry.getKey(), entry.getValue());
                            loaded++;
                        }
                    }
                }
                return loaded;
            }

            Type flatType = new TypeToken<Map<String, String>>() {
            }.getType();
            Map<String, String> flatRecords = gson.fromJson(object, flatType);
            if (flatRecords != null) {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, String> entry : flatRecords.entrySet()) {
                    if (isSupportedComponentJsonKey(entry.getKey())) {
                        laneMap.put(entry.getKey(), CacheRecord.fromKey(entry.getKey(), entry.getValue(), now));
                        indexCompatibleEntry(normalizedLane, entry.getKey());
                        loaded++;
                    }
                }
            }
            return loaded;
        } catch (Exception e) {
            LOGGER.warn("Failed to load categorized translation cache {}", laneFile, e);
            return 0;
        }
    }

    private void saveLane(String lane, Map<String, CacheRecord> entries) throws IOException {
        saveLane(cacheRoot, lane, entries);
    }

    private void saveLane(Path root, String lane, Map<String, CacheRecord> entries) throws IOException {
        String normalizedLane = normalizeLane(lane);
        Files.createDirectories(root);
        Path laneFile = root.resolve(normalizedLane + ".json");
        CacheFileData data = new CacheFileData();
        data.version = TranslationCacheKeys.PROTOCOL;
        data.lane = normalizedLane;
        data.entries = entries;
        Path temporary = root.resolve(normalizedLane + ".json.tmp");
        writeUtf8(temporary, gson.toJson(data));
        try {
            Files.move(temporary, laneFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(temporary, laneFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String normalizeLane(String lane) {
        if (blank(lane)) {
            return "generic";
        }
        return lane.toLowerCase().replaceAll("[^a-z0-9_.-]+", "_");
    }

    public static final class CacheViewEntry {
        private final String lane;
        private final String key;
        private final String translation;
        private final String sourceText;
        private final String translationText;
        private final String surface;
        private final long createdAt;
        private final long lastUsedAt;
        private final boolean editedByPlayer;
        private final long editedAt;
        private final boolean sharedImported;
        private final String sourcePayload;
        private final String format;
        private final String promptFingerprint;

        public CacheViewEntry(String lane, String key, String translation, String sourceText,
                              String translationText, String surface, long createdAt, long lastUsedAt,
                              boolean editedByPlayer, long editedAt, boolean sharedImported,
                              String sourcePayload, String format, String promptFingerprint) {
            this.lane = lane;
            this.key = key;
            this.translation = translation;
            this.sourceText = sourceText;
            this.translationText = translationText;
            this.surface = surface;
            this.createdAt = createdAt;
            this.lastUsedAt = lastUsedAt;
            this.editedByPlayer = editedByPlayer;
            this.editedAt = editedAt;
            this.sharedImported = sharedImported;
            this.sourcePayload = sourcePayload;
            this.format = format;
            this.promptFingerprint = promptFingerprint;
        }

        public String lane() { return lane; }
        public String key() { return key; }
        public String translation() { return translation; }
        public String sourceText() { return sourceText; }
        public String translationText() { return translationText; }
        public String surface() { return surface; }
        public long createdAt() { return createdAt; }
        public long lastUsedAt() { return lastUsedAt; }
        public boolean editedByPlayer() { return editedByPlayer; }
        public long editedAt() { return editedAt; }
        public boolean sharedImported() { return sharedImported; }
        public String sourcePayload() { return sourcePayload; }
        public String format() { return format; }
        public String promptFingerprint() { return promptFingerprint; }
    }

    public static final class CacheShareMetadata {
        private final String worldKind;
        private final String worldName;
        public CacheShareMetadata(String worldKind, String worldName) {
            this.worldKind = worldKind;
            this.worldName = worldName;
        }
        public String worldKind() { return worldKind; }
        public String worldName() { return worldName; }
    }

    public static final class CacheShareExportResult {
        private final int lanes;
        private final int entries;
        private final Path directory;
        private final Path flatFile;
        private final Path archiveFile;
        public CacheShareExportResult(int lanes, int entries, Path directory, Path flatFile, Path archiveFile) {
            this.lanes = lanes;
            this.entries = entries;
            this.directory = directory;
            this.flatFile = flatFile;
            this.archiveFile = archiveFile;
        }
        public int lanes() { return lanes; }
        public int entries() { return entries; }
        public Path directory() { return directory; }
        public Path flatFile() { return flatFile; }
        public Path archiveFile() { return archiveFile; }
    }

    public static final class CacheImportResult {
        private int sourceCount;
        private int imported;
        private int skippedExisting;
        private int skippedInvalid;
        private int skippedWorldMismatch;
        private int failedFiles;

        public int sourceCount() {
            return sourceCount;
        }

        public int imported() {
            return imported;
        }

        public int skippedExisting() {
            return skippedExisting;
        }

        public int skippedInvalid() {
            return skippedInvalid;
        }

        public int skippedWorldMismatch() {
            return skippedWorldMismatch;
        }

        public int failedFiles() {
            return failedFiles;
        }

        public boolean changed() {
            return imported > 0;
        }

        private void addSource() {
            sourceCount++;
        }

        private void addImported() {
            imported++;
        }

        private void addExisting() {
            skippedExisting++;
        }

        private void addInvalid() {
            skippedInvalid++;
        }

        private void addWorldMismatch() {
            skippedWorldMismatch++;
        }

        private void addFailedFile() {
            failedFiles++;
        }
    }

    private static final class CacheShareManifest {
        String format;
        String protocol;
        String worldKind;
        String worldName;
        String worldKey;
        long exportedAt;
        int lanes;
        int entries;

        static CacheShareManifest from(CacheShareMetadata metadata, int lanes, int entries) {
            CacheShareManifest manifest = new CacheShareManifest();
            manifest.format = SHARE_FORMAT;
            manifest.protocol = TranslationCacheKeys.PROTOCOL;
            manifest.worldKind = metadata == null || metadata.worldKind() == null ? "unknown" : metadata.worldKind();
            manifest.worldName = metadata == null || metadata.worldName() == null ? "" : metadata.worldName();
            manifest.worldKey = normalizeShareWorldName(manifest.worldName);
            manifest.exportedAt = System.currentTimeMillis();
            manifest.lanes = lanes;
            manifest.entries = entries;
            return manifest;
        }
    }

    private static final class CacheFileData {
        String version;
        String lane;
        Map<String, CacheRecord> entries = new ConcurrentHashMap<>();
    }

    private static final class CacheRecord {
        volatile String translation;
        volatile String sourceText;
        volatile String translationText;
        volatile String surface;
        volatile String sourceHash;
        volatile String contextHash;
        volatile String layoutSignature;
        volatile long createdAt;
        volatile long lastUsedAt;
        volatile boolean editedByPlayer;
        volatile long editedAt;
        volatile boolean sharedImported;
        volatile String sourcePayload;
        volatile String format;
        volatile String promptFingerprint;

        static CacheRecord fromKey(String key, String translation, long now) {
            CacheRecord record = new CacheRecord();
            record.translation = translation;
            record.translationText = displayTextFromValue(translation);
            record.surface = TranslationCacheKeys.surfaceFromKey(key);
            record.sourceHash = TranslationCacheKeys.sourceHashFromKey(key);
            record.contextHash = extractKeyPart(key, "ctx=");
            record.layoutSignature = extractKeyPart(key, "layout=");
            record.format = extractKeyPart(key, "fmt=");
            record.createdAt = now;
            record.lastUsedAt = now;
            return record;
        }

        static CacheRecord copyForImport(String key, CacheRecord source, long now) {
            CacheRecord record = new CacheRecord();
            record.translation = source.translation;
            record.sourceText = normalizeDisplayText(source.sourceText);
            record.translationText = blank(source.translationText)
                    ? displayTextFromValue(source.translation)
                    : normalizeDisplayText(source.translationText);
            record.surface = blank(source.surface)
                    ? TranslationCacheKeys.surfaceFromKey(key)
                    : source.surface;
            record.sourceHash = blank(source.sourceHash)
                    ? TranslationCacheKeys.sourceHashFromKey(key)
                    : source.sourceHash;
            record.contextHash = source.contextHash == null ? "" : source.contextHash;
            record.layoutSignature = source.layoutSignature == null ? "" : source.layoutSignature;
            record.createdAt = source.createdAt > 0 ? source.createdAt : now;
            record.lastUsedAt = source.lastUsedAt > 0 ? source.lastUsedAt : now;
            record.editedByPlayer = source.editedByPlayer;
            record.editedAt = source.editedAt;
            record.sourcePayload = source.sourcePayload;
            record.format = source.format;
            record.promptFingerprint = source.promptFingerprint;
            return record;
        }

        static CacheRecord copyForPersistence(CacheRecord source) {
            CacheRecord record = new CacheRecord();
            record.translation = source.translation;
            record.sourceText = source.sourceText;
            record.translationText = source.translationText;
            record.surface = source.surface;
            record.sourceHash = source.sourceHash;
            record.contextHash = source.contextHash;
            record.layoutSignature = source.layoutSignature;
            record.createdAt = source.createdAt;
            record.lastUsedAt = source.lastUsedAt;
            record.editedByPlayer = source.editedByPlayer;
            record.editedAt = source.editedAt;
            record.sharedImported = source.sharedImported;
            record.sourcePayload = source.sourcePayload;
            record.format = source.format;
            record.promptFingerprint = source.promptFingerprint;
            return record;
        }

        private static String extractKeyPart(String key, String prefix) {
            if (key == null || prefix == null) {
                return "";
            }
            for (String part : key.split(":")) {
                if (part.startsWith(prefix)) {
                    return part.substring(prefix.length());
                }
            }
            return "";
        }
    }

    public static String displayTextFromValue(String value) {
        if (blank(value)) {
            return "";
        }
        String trimmed = value.trim();
        String jsonText = ComponentJsonCacheEditor.displayText(trimmed);
        return normalizeDisplayText(blank(jsonText) ? trimmed : jsonText);
    }

    private static boolean isSupportedComponentJsonKey(String key) {
        if (!TranslationCacheKeys.isCurrentProtocolKey(key)) {
            return false;
        }
        return TranslationCacheKeys.isComponentJsonKey(key)
                || TranslationCacheKeys.surfaceFromKey(key).startsWith("json.");
    }

    private static String normalizeDisplayText(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private void writeFlatExport(Path file) throws IOException {
        String json = gson.toJson(getAll());
        writeUtf8(file, json);
    }

    private static void addImportSourceIfUsable(Set<Path> sources, Path path) {
        if (path != null && isUsableImportSource(path)) {
            sources.add(path.toAbsolutePath().normalize());
        }
    }

    private static boolean isUsableImportSource(Path path) {
        if (!Files.exists(path)) {
            return false;
        }
        if (Files.isRegularFile(path)) {
            return isSupportedImportFile(path);
        }
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.anyMatch(file -> Files.isRegularFile(file)
                    && file.getFileName() != null
                    && isSupportedImportFile(file));
        } catch (IOException e) {
            LOGGER.warn("Unable to inspect cache import source {}: {}", path, e.getMessage());
            return false;
        }
    }

    private static boolean isSupportedImportFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".json") || name.endsWith(".zip");
    }

    private void importSource(Path source, CacheImportResult result, String expectedWorldName,
                              boolean sharedImport) {
        if (source == null || !Files.exists(source)) {
            if (result != null) {
                result.addFailedFile();
            }
            return;
        }
        result.addSource();
        try {
            if (Files.isDirectory(source)) {
                try (Stream<Path> stream = Files.walk(source)) {
                    stream.filter(path -> Files.isRegularFile(path)
                                    && path.getFileName() != null
                                    && isSupportedImportFile(path))
                            .forEach(path -> importFile(path, result, expectedWorldName, sharedImport));
                }
            } else {
                importFile(source, result, expectedWorldName, sharedImport);
            }
        } catch (Exception e) {
            result.addFailedFile();
            LOGGER.warn("Failed to import cache source {}: {}", source, e.getMessage());
        }
    }

    private void importFile(Path file, CacheImportResult result, String expectedWorldName,
                            boolean sharedImport) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".zip")) {
            importZipFile(file, result, expectedWorldName, sharedImport);
        } else {
            importJsonFile(file, result, sharedImport);
        }
    }

    private void importZipFile(Path file, CacheImportResult result, String expectedWorldName,
                               boolean sharedImport) {
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            // World name mismatch is intentionally NOT checked here.
            // Cache keys already contain all identity info (surface + source hash + lang hash).
            // Cross-world/cross-client import is a valid use case (e.g. sharing
            // translations between an integrated modpack and a test client).

            List<ZipJsonEntry> jsonEntries = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                        || SHARE_MANIFEST_FILE.equals(entry.getName())) {
                    continue;
                }
                jsonEntries.add(new ZipJsonEntry(entry.getName(), readZipEntry(zip, entry)));
            }

            boolean hasLaneEntries = jsonEntries.stream().anyMatch(entry -> looksLikeLaneJson(entry.json()));
            for (ZipJsonEntry entry : jsonEntries) {
                if (hasLaneEntries && "cache_export.json".equals(entry.name())) {
                    continue;
                }
                importJsonText(entry.name(), entry.json(), result, sharedImport);
            }
        } catch (Exception e) {
            result.addFailedFile();
            LOGGER.warn("Failed to import cache archive {}: {}", file, e.getMessage());
        }
    }

    private void importJsonFile(Path file, CacheImportResult result, boolean sharedImport) {
        try {
            importJsonText(file.toString(), readUtf8(file), result, sharedImport);
        } catch (Exception e) {
            result.addFailedFile();
            LOGGER.warn("Failed to import cache json {}: {}", file, e.getMessage());
        }
    }

    private void importJsonText(String label, String json, CacheImportResult result,
                                boolean sharedImport) {
        try {
            JsonElement parsed = new com.google.gson.JsonParser().parse(json);
            if (!parsed.isJsonObject()) {
                result.addInvalid();
                return;
            }
            JsonObject object = parsed.getAsJsonObject();
            if (object.has("entries")) {
                importLaneFileObject(object, result, sharedImport);
                return;
            }
            importFlatObject(object, result, sharedImport);
        } catch (Exception e) {
            result.addFailedFile();
            LOGGER.warn("Failed to import cache json {}: {}", label, e.getMessage());
        }
    }

    private void importLaneFileObject(JsonObject object, CacheImportResult result,
                                      boolean sharedImport) {
        if (!object.has("entries") || !object.get("entries").isJsonObject()) {
            result.addInvalid();
            return;
        }
        Type type = new TypeToken<Map<String, CacheRecord>>() {
        }.getType();
        Map<String, CacheRecord> records = gson.fromJson(object.get("entries"), type);
        if (records == null || records.isEmpty()) {
            return;
        }
        for (Map.Entry<String, CacheRecord> entry : records.entrySet()) {
            importRecord(entry.getKey(), entry.getValue(), result, sharedImport);
        }
    }

    private void importFlatObject(JsonObject object, CacheImportResult result, boolean sharedImport) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                result.addInvalid();
                continue;
            }
            String translation = value.getAsString();
            if (blank(translation)) {
                result.addInvalid();
                continue;
            }
            importRecord(entry.getKey(), CacheRecord.fromKey(entry.getKey(), translation, System.currentTimeMillis()),
                    result, sharedImport);
        }
    }

    private void importRecord(String key, CacheRecord source, CacheImportResult result,
                              boolean sharedImport) {
        if (key == null || source == null || !isSupportedComponentJsonKey(key)
                || blank(source.translation)) {
            result.addInvalid();
            return;
        }
        TranslationBlacklist blacklist = blacklist();
        if (blacklist != null && blacklist.containsBlacklistedEntry(source.translation)) {
            result.addInvalid();
            return;
        }
        String lane = TranslationCacheKeys.laneFromKey(key);
        CacheRecord imported = CacheRecord.copyForImport(key, source, System.currentTimeMillis());
        // Cache-share archives are usable as exact cache hits, but remain
        // identifiable so scoped Component context retrieval can exclude them by
        // default. Direct legacy-scope migration retains the source provenance.
        imported.sharedImported = sharedImport || source.sharedImported;
        CacheRecord previous = getLaneMap(lane, true).putIfAbsent(key, imported);
        if (previous != null) {
            result.addExisting();
            return;
        }
        indexCompatibleEntry(lane, key);
        indexSemanticEntry(lane, key, imported);
        markContentChanged();
        result.addImported();
        enqueueShareableLocalEntry(lane, key, imported);
    }

    private void enqueueShareableLocalEntry(String lane, String key, CacheRecord record) {
        SharedCacheClient.enqueueLocalEntry(toViewEntry(lane, key, record));
    }

    private void markContentChanged() {
        contentRevision++;
        dirty = true;
    }

    private void writeZipJson(ZipOutputStream zip, String name, String json) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(json.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private CacheShareManifest readManifest(ZipFile zip) throws IOException {
        ZipEntry manifestEntry = zip.getEntry(SHARE_MANIFEST_FILE);
        if (manifestEntry == null) {
            return null;
        }
        try {
            JsonElement parsed = new com.google.gson.JsonParser().parse(readZipEntry(zip, manifestEntry));
            if (!parsed.isJsonObject()) {
                return null;
            }
            CacheShareManifest manifest = gson.fromJson(parsed, CacheShareManifest.class);
            if (manifest == null || !SHARE_FORMAT.equals(manifest.format)
                    || !TranslationCacheKeys.PROTOCOL.equals(manifest.protocol)) {
                return null;
            }
            return manifest;
        } catch (Exception e) {
            LOGGER.warn("Ignoring invalid cache share manifest: {}", e.getMessage());
            return null;
        }
    }

    private static boolean matchesExpectedWorld(CacheShareManifest manifest, String expectedWorldName) {
        if (manifest == null || blank(expectedWorldName) || blank(manifest.worldName)) {
            return true;
        }
        return normalizeShareWorldName(expectedWorldName).equals(normalizeShareWorldName(manifest.worldName));
    }

    private static String normalizeShareWorldName(String name) {
        return name == null ? "" : name.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String readZipEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static boolean looksLikeLaneJson(String json) {
        try {
            JsonElement parsed = new com.google.gson.JsonParser().parse(json);
            return parsed.isJsonObject() && parsed.getAsJsonObject().has("entries");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class CacheReference {
        private final String lane;
        private final String key;
        private CacheReference(String lane, String key) { this.lane = lane; this.key = key; }
        private String lane() { return lane; }
        private String key() { return key; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof CacheReference)) return false;
            CacheReference that = (CacheReference) other;
            return java.util.Objects.equals(lane, that.lane) && java.util.Objects.equals(key, that.key);
        }
        @Override public int hashCode() { return java.util.Objects.hash(lane, key); }
    }

    public static final class SemanticCacheCandidate {
        private final String payload;
        private final String translationText;
        private final boolean editedByPlayer;
        private final long createdAt;
        private final String sourceKey;
        public SemanticCacheCandidate(String payload, String translationText,
                                      boolean editedByPlayer, long createdAt, String sourceKey) {
            this.payload = payload;
            this.translationText = translationText;
            this.editedByPlayer = editedByPlayer;
            this.createdAt = createdAt;
            this.sourceKey = sourceKey;
        }
        public String payload() { return payload; }
        public String translationText() { return translationText; }
        public boolean editedByPlayer() { return editedByPlayer; }
        public long createdAt() { return createdAt; }
        public String sourceKey() { return sourceKey; }
    }

    private static final class ZipJsonEntry {
        private final String name;
        private final String json;
        private ZipJsonEntry(String name, String json) { this.name = name; this.json = json; }
        private String name() { return name; }
        private String json() { return json; }
    }

    private static TranslationBlacklist blacklist() {
        return SimpleTranslateForge1122.getEngine() == null
                ? null : SimpleTranslateForge1122.getEngine().getTranslationBlacklist();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String readUtf8(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void writeUtf8(Path path, String text) throws IOException {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }
}
