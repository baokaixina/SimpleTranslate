package com.yourname.simpletranslate.cache;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.cache.SharedCacheClient;
import com.yourname.simpletranslate.core.TranslationCacheKeys;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

/**
 * Categorized translation cache with JSON persistence.
 * Each translation lane writes to its own file to prevent cross-feature cache
 * contamination.
 */
public class TranslationCache {
    private static final long SAVE_DELAY_MS = 750L;
    private static final String SHARE_MANIFEST_FILE = "simple_translate_cache_share.json";
    private static final String SHARE_FORMAT = "simpletranslate-cache-share-v1";
    private static final Set<String> KNOWN_LANES = Set.of(
            "tooltip",
            "sign",
            "chat",
            "chat_batch",
            "hud",
            "book",
            "advancement",
            "scoreboard",
            "entity",
            "text_display",
            "hover",
            "bossbar",
            "manager",
            "generic"
    );
    private static final Set<String> AUXILIARY_CACHE_FILE_STEMS = Set.of(
            "line_memory"
    );
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
    private volatile boolean dirty;
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

            Set<String> lanesToLoad = new HashSet<>(KNOWN_LANES);
            if (Files.exists(cacheRoot)) {
                try (var stream = Files.list(cacheRoot)) {
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
                SimpleTranslateMod.getLogger().debug("Ignoring legacy translation cache at {} for {} protocol",
                        legacyCacheFile, TranslationCacheKeys.PROTOCOL);
            }
            SimpleTranslateMod.getLogger().debug("Loaded {} categorized cached translations from {}",
                    loadedCount, cacheRoot);
        } catch (IOException e) {
            SimpleTranslateMod.getLogger().error("Failed to load translation cache", e);
        }
    }

    public synchronized void save() {
        synchronized (saveLock) {
            dirty = true;
            if (pendingSave == null || pendingSave.isDone()) {
                pendingSave = SAVE_EXECUTOR.schedule(this::saveNow, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    public synchronized void saveNow() {
        synchronized (saveLock) {
            pendingSave = null;
            if (!dirty) {
                return;
            }
            dirty = false;
        }

        try {
            Files.createDirectories(cacheRoot);
            for (Map.Entry<String, Map<String, CacheRecord>> entry : translationsByLane.entrySet()) {
                saveLane(entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            SimpleTranslateMod.getLogger().error("Failed to save translation cache", e);
        }
    }

    public synchronized void flush() {
        saveNow();
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
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
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

    public synchronized List<String> getCompatibleBySource(String exactKey) {
        if (exactKey == null || !TranslationCacheKeys.isCurrentProtocolKey(exactKey)) {
            return List.of();
        }

        String lane = TranslationCacheKeys.laneFromKey(exactKey);
        String surface = TranslationCacheKeys.surfaceFromKey(exactKey);
        String sourceHash = TranslationCacheKeys.sourceHashFromKey(exactKey);
        String languageHash = CacheRecord.extractKeyPart(exactKey, "lang=");
        String formatPreset = formatPresetFromKey(exactKey);
        if (surface.isBlank() || sourceHash.isBlank() || languageHash.isBlank()) {
            return List.of();
        }

        Map<String, CacheRecord> laneMap = getLaneMap(lane, false);
        if (laneMap.isEmpty()) {
            return List.of();
        }

        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        long now = System.currentTimeMillis();
        List<String> matches = new ArrayList<>();
        String groupKey = compatibleGroupKey(surface, sourceHash, languageHash, formatPreset);
        Set<String> candidateKeys = compatibleIndexByLane
                .getOrDefault(normalizeLane(lane), Map.of())
                .getOrDefault(groupKey, Set.of());
        if (candidateKeys.isEmpty()) {
            return List.of();
        }
        for (String key : candidateKeys) {
            CacheRecord record = laneMap.get(key);
            if (record == null || key == null || key.equals(exactKey)
                    || record.translation == null || record.translation.isBlank()) {
                continue;
            }
            if (blacklist != null && blacklist.containsBlacklistedEntry(record.translation)) {
                continue;
            }
            if (now - record.lastUsedAt > 60_000L) {
                record.lastUsedAt = now;
                dirty = true;
            }
            matches.add(record.translation);
        }
        return matches.isEmpty() ? List.of() : List.copyOf(matches);
    }

    /**
     * Returns translations for the same visible text across chat, hover and
     * tooltip cache lanes. Callers still have to restore the candidate against
     * their own component structure before displaying it.
     */
    public synchronized List<SemanticCacheCandidate> getSemanticBySource(String sourceText, String exactKey) {
        if (sourceText == null || sourceText.isBlank()
                || exactKey == null || !TranslationCacheKeys.isCurrentProtocolKey(exactKey)) {
            return List.of();
        }
        Set<CacheReference> references = semanticIndex.getOrDefault(
                semanticGroupKey(sourceText, exactKey), Set.of());
        if (references.isEmpty()) {
            return List.of();
        }

        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        List<SemanticCacheCandidate> candidates = new ArrayList<>();
        String exactSurface = TranslationCacheKeys.surfaceFromKey(exactKey);
        for (CacheReference reference : references) {
            if (reference.key().equals(exactKey)) {
                continue;
            }
            CacheRecord record = getLaneMap(reference.lane(), false).get(reference.key());
            if (record == null || record.translation == null || record.translation.isBlank()
                    || record.translationText == null || record.translationText.isBlank()) {
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
        return candidates.isEmpty() ? List.of() : List.copyOf(candidates);
    }

    private static String compatibleGroupKey(String surface, String sourceHash, String languageHash,
                                             String formatPreset) {
        if (surface.isBlank() || sourceHash.isBlank() || languageHash.isBlank()) {
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
        if (sourceText == null || sourceText.isBlank() || key == null) {
            return "";
        }
        String languageHash = CacheRecord.extractKeyPart(key, "lang=");
        if (languageHash.isBlank()) {
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
        return value == null || value.isBlank() ? "default" : value;
    }

    private void indexSemanticEntry(String lane, String key, CacheRecord record) {
        if (record == null || record.sourceText == null || record.sourceText.isBlank()
                || record.translationText == null || record.translationText.isBlank()) {
            return;
        }
        String groupKey = semanticGroupKey(record.sourceText, key);
        if (groupKey.isBlank()) {
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
        if (original == null || translated == null || !TranslationCacheKeys.isCurrentProtocolKey(original)) {
            return;
        }
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist != null && blacklist.containsBlacklistedEntry(translated)) {
            return;
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
        if (sourceText != null && !sourceText.isBlank()) {
            record.sourceText = normalizeDisplayText(sourceText);
        }
        String displayTranslation = translationText == null || translationText.isBlank()
                ? displayTextFromValue(translated)
                : translationText;
        if (displayTranslation != null && !displayTranslation.isBlank()) {
            record.translationText = normalizeDisplayText(displayTranslation);
        }
        record.lastUsedAt = now;
        record.sharedImported = false;
        laneMap.put(original, record);
        indexCompatibleEntry(lane, original);
        indexSemanticEntry(lane, original, record);
        dirty = true;
        enqueueShareableLocalEntry(lane, original, record);
    }

    public synchronized void putComponentJson(String key, String translatedJson, String sourceJson,
                                              String sourceText, String translationText) {
        put(key, translatedJson, sourceText, translationText);
        CacheRecord record = getLaneMap(TranslationCacheKeys.laneFromKey(key), false).get(key);
        if (record == null) {
            return;
        }
        record.format = TranslationCacheKeys.COMPONENT_JSON_FORMAT;
        record.sourcePayload = sourceJson == null ? "" : sourceJson;
        dirty = true;
    }

    public synchronized boolean putSharedIfAbsent(String key, String translated, String sourceText, String translationText,
                                     boolean editedByPlayer, long createdAt, long editedAt) {
        if (key == null || translated == null || !isSupportedComponentJsonKey(key)
                || translated.isBlank()) {
            return false;
        }
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist != null && blacklist.containsBlacklistedEntry(translated)) {
            return false;
        }
        String lane = TranslationCacheKeys.laneFromKey(key);
        Map<String, CacheRecord> laneMap = getLaneMap(lane, true);
        long now = System.currentTimeMillis();
        CacheRecord record = CacheRecord.fromKey(key, translated, now);
        record.sourceText = normalizeDisplayText(sourceText);
        String displayTranslation = translationText == null || translationText.isBlank()
                ? displayTextFromValue(translated)
                : translationText;
        record.translationText = normalizeDisplayText(displayTranslation);
        record.createdAt = createdAt > 0 ? createdAt : now;
        record.lastUsedAt = now;
        record.editedByPlayer = editedByPlayer;
        record.editedAt = editedAt;
        record.sharedImported = true;
        if (laneMap.putIfAbsent(key, record) != null) {
            return false;
        }
        indexCompatibleEntry(lane, key);
        indexSemanticEntry(lane, key, record);
        dirty = true;
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
            dirty = true;
        }
    }

    public synchronized void clear() {
        translationsByLane.values().forEach(Map::clear);
        compatibleIndexByLane.clear();
        semanticIndex.clear();
        dirty = true;
    }

    public synchronized int size() {
        int size = 0;
        for (Map<String, CacheRecord> map : translationsByLane.values()) {
            size += map.size();
        }
        return size;
    }

    public synchronized Map<String, String> getAll() {
        Map<String, String> result = new ConcurrentHashMap<>();
        for (Map<String, CacheRecord> map : translationsByLane.values()) {
            for (Map.Entry<String, CacheRecord> entry : map.entrySet()) {
                result.put(entry.getKey(), entry.getValue().translation);
            }
        }
        return Map.copyOf(result);
    }

    public synchronized Map<String, Integer> getLaneSizes() {
        Map<String, Integer> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, Map<String, CacheRecord>> entry : translationsByLane.entrySet()) {
            result.put(entry.getKey(), entry.getValue().size());
        }
        return Map.copyOf(result);
    }

    public synchronized Map<String, CacheViewEntry> getEntries() {
        Map<String, CacheViewEntry> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, Map<String, CacheRecord>> laneEntry : translationsByLane.entrySet()) {
            for (Map.Entry<String, CacheRecord> entry : laneEntry.getValue().entrySet()) {
                result.put(entry.getKey(), toViewEntry(laneEntry.getKey(), entry.getKey(), entry.getValue()));
            }
        }
        return Map.copyOf(result);
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
        if (record == null || record.translation == null || record.translation.isBlank()) {
            return Optional.of("missing-entry");
        }
        if (!TranslationCacheKeys.COMPONENT_JSON_FORMAT.equals(record.format)
                && !TranslationCacheKeys.isComponentJsonKey(key)) {
            return Optional.of("unsupported-format");
        }
        String rewritten = ComponentJsonCacheEditor.replaceTextNodes(record.translation, textNodes);
        if (rewritten == null || rewritten.isBlank()) {
            return Optional.of("unsupported-format");
        }

        record.translation = rewritten;
        record.translationText = displayTextFromValue(rewritten);
        record.editedByPlayer = true;
        record.editedAt = System.currentTimeMillis();
        record.lastUsedAt = record.editedAt;
        record.sharedImported = false;
        dirty = true;
        enqueueShareableLocalEntry(TranslationCacheKeys.laneFromKey(key), key, record);
        return Optional.empty();
    }

    private CacheViewEntry toViewEntry(String lane, String key, CacheRecord record) {
        String translationText = record.translationText == null || record.translationText.isBlank()
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
                record.format == null ? "" : record.format);
    }

    public synchronized void clearLane(String lane) {
        if (lane == null || lane.isBlank()) {
            return;
        }
        Map<String, CacheRecord> laneMap = getLaneMap(lane, false);
        if (!laneMap.isEmpty()) {
            for (Map.Entry<String, CacheRecord> entry : laneMap.entrySet()) {
                unindexCompatibleEntry(lane, entry.getKey());
                unindexSemanticEntry(lane, entry.getKey(), entry.getValue());
            }
            laneMap.clear();
            dirty = true;
        }
    }

    public synchronized void exportToFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        writeFlatExport(file);
        SimpleTranslateMod.getLogger().info("Exported {} translations to {}", size(), file);
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

        SimpleTranslateMod.getLogger().debug(
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
        importSource(file, result, null);
        SimpleTranslateMod.getLogger().debug(
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
            importSource(source, result, null);
        }
        SimpleTranslateMod.getLogger().debug(
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
            importSource(source, result, expectedWorldName);
        }
        SimpleTranslateMod.getLogger().debug(
                "Imported cache share sources: sources={}, imported={}, existing={}, invalid={}, worldMismatch={}, failed={}",
                result.sourceCount(), result.imported(), result.skippedExisting(),
                result.skippedInvalid(), result.skippedWorldMismatch(), result.failedFiles());
        return result;
    }

    public static List<Path> discoverImportSources(Path configDir) {
        if (configDir == null) {
            return List.of();
        }
        Set<Path> sources = new LinkedHashSet<>();
        addImportSourceIfUsable(sources, configDir.resolve("cache_share"));
        addImportSourceIfUsable(sources, configDir.resolve("cache_import"));
        addImportSourceIfUsable(sources, configDir.resolve("cache_import.json"));
        addImportSourceIfUsable(sources, configDir.resolve("cache_export.json"));
        return List.copyOf(sources);
    }

    public synchronized void update(String original, String newTranslation) {
        if (get(original).isPresent()) {
            put(original, newTranslation);
        }
    }

    private static Path determineCacheRoot(Path cacheFile) {
        Path parent = cacheFile.getParent();
        if (parent == null) {
            return Path.of("cache");
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
        try (var stream = Files.list(cacheRoot)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !AUXILIARY_CACHE_FILE_STEMS.contains(
                            path.getFileName().toString().substring(
                                    0, path.getFileName().toString().length() - ".json".length())))
                    .forEach(path -> {
                        try {
                            JsonElement parsed = JsonParser.parseString(Files.readString(path));
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
            SimpleTranslateMod.getLogger().info("Archived legacy translation cache {} -> {}", source, target);
        }
    }

    private Map<String, CacheRecord> getLaneMap(String lane, boolean create) {
        String normalized = normalizeLane(lane);
        if (!create) {
            Map<String, CacheRecord> existing = translationsByLane.get(normalized);
            return existing == null ? Map.of() : existing;
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
            String json = Files.readString(laneFile);
            JsonElement parsed = JsonParser.parseString(json);
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
                            if (entry.getValue().format == null || entry.getValue().format.isBlank()) {
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
            SimpleTranslateMod.getLogger().warn("Failed to load categorized translation cache {}", laneFile, e);
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
        Files.writeString(temporary, gson.toJson(data));
        try {
            Files.move(temporary, laneFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(temporary, laneFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String normalizeLane(String lane) {
        if (lane == null || lane.isBlank()) {
            return "generic";
        }
        return lane.toLowerCase().replaceAll("[^a-z0-9_.-]+", "_");
    }

    public record CacheViewEntry(
            String lane,
            String key,
            String translation,
            String sourceText,
            String translationText,
            String surface,
            long createdAt,
            long lastUsedAt,
            boolean editedByPlayer,
            long editedAt,
            boolean sharedImported,
            String sourcePayload,
            String format) {
    }

    public record CacheShareMetadata(String worldKind, String worldName) {
    }

    public record CacheShareExportResult(int lanes, int entries, Path directory, Path flatFile, Path archiveFile) {
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
            record.translationText = source.translationText == null || source.translationText.isBlank()
                    ? displayTextFromValue(source.translation)
                    : normalizeDisplayText(source.translationText);
            record.surface = source.surface == null || source.surface.isBlank()
                    ? TranslationCacheKeys.surfaceFromKey(key)
                    : source.surface;
            record.sourceHash = source.sourceHash == null || source.sourceHash.isBlank()
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
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        String jsonText = ComponentJsonCacheEditor.displayText(trimmed);
        return normalizeDisplayText(jsonText.isBlank() ? trimmed : jsonText);
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
        Files.writeString(file, json);
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
        try (var stream = Files.walk(path)) {
            return stream.anyMatch(file -> Files.isRegularFile(file)
                    && file.getFileName() != null
                    && isSupportedImportFile(file));
        } catch (IOException e) {
            SimpleTranslateMod.getLogger().warn("Unable to inspect cache import source {}: {}", path, e.getMessage());
            return false;
        }
    }

    private static boolean isSupportedImportFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".json") || name.endsWith(".zip");
    }

    private void importSource(Path source, CacheImportResult result, String expectedWorldName) {
        if (source == null || !Files.exists(source)) {
            if (result != null) {
                result.addFailedFile();
            }
            return;
        }
        result.addSource();
        try {
            if (Files.isDirectory(source)) {
                try (var stream = Files.walk(source)) {
                    stream.filter(path -> Files.isRegularFile(path)
                                    && path.getFileName() != null
                                    && isSupportedImportFile(path))
                            .forEach(path -> importFile(path, result, expectedWorldName));
                }
            } else {
                importFile(source, result, expectedWorldName);
            }
        } catch (Exception e) {
            result.addFailedFile();
            SimpleTranslateMod.getLogger().warn("Failed to import cache source {}: {}", source, e.getMessage());
        }
    }

    private void importFile(Path file, CacheImportResult result, String expectedWorldName) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase();
        if (name.endsWith(".zip")) {
            importZipFile(file, result, expectedWorldName);
        } else {
            importJsonFile(file, result);
        }
    }

    private void importZipFile(Path file, CacheImportResult result, String expectedWorldName) {
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            // World name mismatch is intentionally NOT checked here.
            // Cache keys already contain all identity info (surface + source hash + lang hash).
            // Cross-world/cross-client import is a valid use case (e.g. sharing
            // translations between an integrated modpack and a test client).

            List<ZipJsonEntry> jsonEntries = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".json")
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
                importJsonText(entry.name(), entry.json(), result);
            }
        } catch (Exception e) {
            result.addFailedFile();
            SimpleTranslateMod.getLogger().warn("Failed to import cache archive {}: {}", file, e.getMessage());
        }
    }

    private void importJsonFile(Path file, CacheImportResult result) {
        try {
            importJsonText(file.toString(), Files.readString(file), result);
        } catch (Exception e) {
            result.addFailedFile();
            SimpleTranslateMod.getLogger().warn("Failed to import cache json {}: {}", file, e.getMessage());
        }
    }

    private void importJsonText(String label, String json, CacheImportResult result) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                result.addInvalid();
                return;
            }
            JsonObject object = parsed.getAsJsonObject();
            if (object.has("entries")) {
                importLaneFileObject(object, result);
                return;
            }
            importFlatObject(object, result);
        } catch (Exception e) {
            result.addFailedFile();
            SimpleTranslateMod.getLogger().warn("Failed to import cache json {}: {}", label, e.getMessage());
        }
    }

    private void importLaneFileObject(JsonObject object, CacheImportResult result) {
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
            importRecord(entry.getKey(), entry.getValue(), result);
        }
    }

    private void importFlatObject(JsonObject object, CacheImportResult result) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                result.addInvalid();
                continue;
            }
            String translation = value.getAsString();
            if (translation == null || translation.isBlank()) {
                result.addInvalid();
                continue;
            }
            importRecord(entry.getKey(), CacheRecord.fromKey(entry.getKey(), translation, System.currentTimeMillis()),
                    result);
        }
    }

    private void importRecord(String key, CacheRecord source, CacheImportResult result) {
        if (key == null || source == null || !isSupportedComponentJsonKey(key)
                || source.translation == null || source.translation.isBlank()) {
            result.addInvalid();
            return;
        }
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist != null && blacklist.containsBlacklistedEntry(source.translation)) {
            result.addInvalid();
            return;
        }
        String lane = TranslationCacheKeys.laneFromKey(key);
        CacheRecord imported = CacheRecord.copyForImport(key, source, System.currentTimeMillis());
        imported.sharedImported = false;
        CacheRecord previous = getLaneMap(lane, true).putIfAbsent(key, imported);
        if (previous != null) {
            result.addExisting();
            return;
        }
        indexCompatibleEntry(lane, key);
        indexSemanticEntry(lane, key, imported);
        dirty = true;
        result.addImported();
        enqueueShareableLocalEntry(lane, key, imported);
    }

    private void enqueueShareableLocalEntry(String lane, String key, CacheRecord record) {
        SharedCacheClient.enqueueLocalEntry(toViewEntry(lane, key, record));
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
            JsonElement parsed = JsonParser.parseString(readZipEntry(zip, manifestEntry));
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
            SimpleTranslateMod.getLogger().warn("Ignoring invalid cache share manifest: {}", e.getMessage());
            return null;
        }
    }

    private static boolean matchesExpectedWorld(CacheShareManifest manifest, String expectedWorldName) {
        if (manifest == null || expectedWorldName == null || expectedWorldName.isBlank()
                || manifest.worldName == null || manifest.worldName.isBlank()) {
            return true;
        }
        return normalizeShareWorldName(expectedWorldName).equals(normalizeShareWorldName(manifest.worldName));
    }

    private static String normalizeShareWorldName(String name) {
        return name == null ? "" : name.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String readZipEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean looksLikeLaneJson(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() && parsed.getAsJsonObject().has("entries");
        } catch (Exception ignored) {
            return false;
        }
    }

    private record CacheReference(String lane, String key) {
    }

    public record SemanticCacheCandidate(String payload, String translationText,
                                         boolean editedByPlayer, long createdAt, String sourceKey) {
    }

    private record ZipJsonEntry(String name, String json) {
    }
}
