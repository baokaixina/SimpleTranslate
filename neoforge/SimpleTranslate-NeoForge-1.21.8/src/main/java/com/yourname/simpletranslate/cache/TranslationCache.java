package com.yourname.simpletranslate.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.network.SharedCacheClient;
import com.yourname.simpletranslate.util.TranslationCacheKeys;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Pattern ST_DOC_PATTERN = Pattern.compile("(?is)<st-doc\\b[^>]*>.*?</st-doc>");
    private static final Pattern LINE_PATTERN = Pattern.compile("(?s)<line\\s+([^>]*)>(.*?)</line>");
    private static final Pattern GROUP_OR_RUN_PATTERN = Pattern.compile("(?s)<(g|run)\\s+([^>]*)>(.*?)</\\1>");
    private static final Pattern EDITABLE_FALSE_PATTERN = Pattern.compile("(?i)(?:^|\\s)editable\\s*=\\s*\"false\"");
    private static final Pattern WIRE_LINE_PATTERN = Pattern.compile("^\\s*(\\d{1,4})\\s*[|｜](.*)$");
    private static final String SHARE_MANIFEST_FILE = "simple_translate_cache_share.json";
    private static final String SHARE_FORMAT = "simpletranslate-cache-share-v1";
    private static final Set<String> KNOWN_LANES = Set.of(
            "tooltip",
            "sign",
            "chat",
            "hud",
            "book",
            "advancement",
            "scoreboard",
            "entity",
            "text_display",
            "hover",
            "bossbar",
            "manager",
            "ocr",
            "generic"
    );
    private static final ScheduledExecutorService SAVE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SimpleTranslate-CacheSave");
        thread.setDaemon(true);
        return thread;
    });

    private final Path legacyCacheFile;
    private final Path cacheRoot;
    private final Map<String, Map<String, CacheRecord>> translationsByLane;
    private final Gson gson;
    private final Object saveLock = new Object();
    private volatile boolean dirty;
    private ScheduledFuture<?> pendingSave;

    public TranslationCache(Path cacheFile) {
        this.legacyCacheFile = cacheFile;
        this.cacheRoot = determineCacheRoot(cacheFile);
        this.translationsByLane = new ConcurrentHashMap<>();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public void load() {
        try {
            Files.createDirectories(cacheRoot);
            translationsByLane.clear();

            Set<String> lanesToLoad = new HashSet<>(KNOWN_LANES);
            if (Files.exists(cacheRoot)) {
                try (var stream = Files.list(cacheRoot)) {
                    stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                            .map(path -> path.getFileName().toString())
                            .map(name -> name.substring(0, name.length() - ".json".length()))
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

        try {
            Files.createDirectories(cacheRoot);
            for (Map.Entry<String, Map<String, CacheRecord>> entry : translationsByLane.entrySet()) {
                saveLane(entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            SimpleTranslateMod.getLogger().error("Failed to save translation cache", e);
        }
    }

    public void flush() {
        saveNow();
    }

    public Optional<String> get(String original) {
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

    public List<String> getCompatibleBySource(String exactKey) {
        if (exactKey == null || !TranslationCacheKeys.isCurrentProtocolKey(exactKey)) {
            return List.of();
        }

        String lane = TranslationCacheKeys.laneFromKey(exactKey);
        String surface = TranslationCacheKeys.surfaceFromKey(exactKey);
        String sourceHash = TranslationCacheKeys.sourceHashFromKey(exactKey);
        String languageHash = CacheRecord.extractKeyPart(exactKey, "lang=");
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
        for (Map.Entry<String, CacheRecord> entry : laneMap.entrySet()) {
            String key = entry.getKey();
            CacheRecord record = entry.getValue();
            if (record == null || key == null || key.equals(exactKey)
                    || !TranslationCacheKeys.isCurrentProtocolKey(key)
                    || record.translation == null || record.translation.isBlank()) {
                continue;
            }
            if (!surface.equals(TranslationCacheKeys.surfaceFromKey(key))
                    || !sourceHash.equals(TranslationCacheKeys.sourceHashFromKey(key))
                    || !languageHash.equals(CacheRecord.extractKeyPart(key, "lang="))) {
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

    public void put(String original, String translated) {
        put(original, translated, null, null);
    }

    public void put(String original, String translated, String sourceText, String translationText) {
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
        dirty = true;
        enqueueShareableLocalEntry(lane, original, record);
    }

    public boolean putSharedIfAbsent(String key, String translated, String sourceText, String translationText,
                                     boolean editedByPlayer, long createdAt, long editedAt) {
        if (key == null || translated == null || !TranslationCacheKeys.isCurrentProtocolKey(key)
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
        dirty = true;
        return true;
    }

    public void remove(String original) {
        if (original == null) {
            return;
        }
        String lane = TranslationCacheKeys.laneFromKey(original);
        Map<String, CacheRecord> laneMap = translationsByLane.get(normalizeLane(lane));
        if (laneMap != null && laneMap.remove(original) != null) {
            dirty = true;
        }
    }

    public void clear() {
        translationsByLane.values().forEach(Map::clear);
        dirty = true;
    }

    public int size() {
        int size = 0;
        for (Map<String, CacheRecord> map : translationsByLane.values()) {
            size += map.size();
        }
        return size;
    }

    public Map<String, String> getAll() {
        Map<String, String> result = new ConcurrentHashMap<>();
        for (Map<String, CacheRecord> map : translationsByLane.values()) {
            for (Map.Entry<String, CacheRecord> entry : map.entrySet()) {
                result.put(entry.getKey(), entry.getValue().translation);
            }
        }
        return Map.copyOf(result);
    }

    public Map<String, Integer> getLaneSizes() {
        Map<String, Integer> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, Map<String, CacheRecord>> entry : translationsByLane.entrySet()) {
            result.put(entry.getKey(), entry.getValue().size());
        }
        return Map.copyOf(result);
    }

    public Map<String, CacheViewEntry> getEntries() {
        Map<String, CacheViewEntry> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, Map<String, CacheRecord>> laneEntry : translationsByLane.entrySet()) {
            for (Map.Entry<String, CacheRecord> entry : laneEntry.getValue().entrySet()) {
                result.put(entry.getKey(), toViewEntry(laneEntry.getKey(), entry.getKey(), entry.getValue()));
            }
        }
        return Map.copyOf(result);
    }

    public Optional<CacheViewEntry> getEntry(String key) {
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

    public Optional<String> updateEditableTranslationText(String key, String editedText) {
        if (key == null || !TranslationCacheKeys.isCurrentProtocolKey(key)) {
            return Optional.of("invalid-key");
        }
        if (editedText == null || editedText.isBlank()) {
            return Optional.of("blank-translation");
        }
        Map<String, CacheRecord> laneMap = getLaneMap(TranslationCacheKeys.laneFromKey(key), false);
        CacheRecord record = laneMap.get(key);
        if (record == null || record.translation == null || record.translation.isBlank()) {
            return Optional.of("missing-entry");
        }

        String rewritten = rewriteEditableCacheValue(record.translation, editedText);
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
        String translationText = displayTextFromValue(record.translation);
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
                record.sharedImported);
    }

    public void clearLane(String lane) {
        if (lane == null || lane.isBlank()) {
            return;
        }
        Map<String, CacheRecord> laneMap = getLaneMap(lane, false);
        if (!laneMap.isEmpty()) {
            laneMap.clear();
            dirty = true;
        }
    }

    public void exportToFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        writeFlatExport(file);
        SimpleTranslateMod.getLogger().info("Exported {} translations to {}", size(), file);
    }

    public CacheShareExportResult exportShareArchive(Path archiveFile, CacheShareMetadata metadata,
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

    public CacheImportResult importFromFile(Path file, boolean merge) throws IOException {
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

    public CacheImportResult importFromShareSources(List<Path> sources) {
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

    public CacheImportResult importFromShareSources(List<Path> sources, String expectedWorldName) {
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

    public void update(String original, String newTranslation) {
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
                        if (TranslationCacheKeys.isCurrentProtocolKey(entry.getKey()) && entry.getValue() != null) {
                            laneMap.put(entry.getKey(), entry.getValue());
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
                    if (TranslationCacheKeys.isCurrentProtocolKey(entry.getKey())) {
                        laneMap.put(entry.getKey(), CacheRecord.fromKey(entry.getKey(), entry.getValue(), now));
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
        Files.writeString(laneFile, gson.toJson(data));
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
            boolean sharedImported) {
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
        volatile String slotSignature;
        volatile String styleSignature;
        volatile long createdAt;
        volatile long lastUsedAt;
        volatile boolean editedByPlayer;
        volatile long editedAt;
        volatile boolean sharedImported;

        static CacheRecord fromKey(String key, String translation, long now) {
            CacheRecord record = new CacheRecord();
            record.translation = translation;
            record.translationText = displayTextFromValue(translation);
            record.surface = TranslationCacheKeys.surfaceFromKey(key);
            record.sourceHash = TranslationCacheKeys.sourceHashFromKey(key);
            record.contextHash = extractKeyPart(key, "ctx=");
            record.slotSignature = extractKeyPart(key, "slot=");
            record.styleSignature = extractKeyPart(key, "style=");
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
            record.slotSignature = source.slotSignature == null ? "" : source.slotSignature;
            record.styleSignature = source.styleSignature == null ? "" : source.styleSignature;
            record.createdAt = source.createdAt > 0 ? source.createdAt : now;
            record.lastUsedAt = source.lastUsedAt > 0 ? source.lastUsedAt : now;
            record.editedByPlayer = source.editedByPlayer;
            record.editedAt = source.editedAt;
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
        String signComponents = com.yourname.simpletranslate.util.SignTranslationHelper
                .displayTextFromSignComponentsCache(trimmed);
        if (signComponents != null) {
            return normalizeDisplayText(signComponents);
        }
        String wire = displayTextFromWireValue(trimmed);
        if (wire != null) {
            return wire;
        }
        String rawWire = displayTextFromRawNumberedWire(trimmed);
        if (rawWire != null) {
            return rawWire;
        }
        String direct = displayTextFromDirectDocument(trimmed);
        if (direct != null) {
            return direct;
        }
        String ocr = displayTextFromOcrValue(trimmed);
        if (ocr != null) {
            return ocr;
        }
        String mapping = displayTextFromMappingValue(trimmed);
        if (mapping != null) {
            return mapping;
        }
        return normalizeDisplayText(trimmed);
    }

    private static String displayTextFromOcrValue(String value) {
        try {
            JsonElement parsed = JsonParser.parseString(value);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            if (!object.has("version") || !object.has("translationText")) {
                return null;
            }
            String version = object.get("version").getAsString();
            if (!"ocr-cache-v1".equals(version) && !"ocr-cache-v2".equals(version)) {
                return null;
            }
            String translation = object.get("translationText").isJsonNull()
                    ? "" : object.get("translationText").getAsString();
            return normalizeDisplayText(translation);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String displayTextFromRawNumberedWire(String value) {
        if (com.yourname.simpletranslate.core.WireCodec.isCanonicalPayload(value)) {
            return null;
        }
        Map<Integer, String> parsed = com.yourname.simpletranslate.core.WireCodec.parseResponse(value, 512);
        if (parsed == null || parsed.isEmpty()) {
            return null;
        }
        int maxIndex = -1;
        for (Integer index : parsed.keySet()) {
            if (index != null && index > maxIndex) {
                maxIndex = index;
            }
        }
        if (maxIndex < 0) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i <= maxIndex; i++) {
            String content = parsed.get(i);
            if (content == null) {
                continue;
            }
            if (content.startsWith("*")) {
                content = content.substring(1);
            }
            lines.add(com.yourname.simpletranslate.core.WireCodec.unescape(
                    com.yourname.simpletranslate.core.WireCodec.stripTags(content)));
        }
        return lines.isEmpty() ? null : normalizeDisplayText(String.join("\n", lines));
    }

    private static String displayTextFromWireValue(String value) {
        if (!com.yourname.simpletranslate.core.WireCodec.isCanonicalPayload(value)) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (String raw : value.split("\\R", -1)) {
            Matcher matcher = WIRE_LINE_PATTERN.matcher(raw);
            if (!matcher.matches()) {
                continue;
            }
            String content = matcher.group(2);
            if (content.startsWith("*")) {
                content = content.substring(1);
            }
            lines.add(com.yourname.simpletranslate.core.WireCodec.unescape(
                    com.yourname.simpletranslate.core.WireCodec.stripTags(content)));
        }
        return lines.isEmpty() ? "" : normalizeDisplayText(String.join("\n", lines));
    }

    private static String displayTextFromMappingValue(String value) {
        if (!value.startsWith("{")) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(value).getAsJsonObject();
            if (!root.has("translations") || !root.get("translations").isJsonArray()) {
                return root.has("translation") ? root.get("translation").getAsString() : null;
            }
            List<String> lines = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("translations")) {
                if (element.isJsonObject()) {
                    JsonObject item = element.getAsJsonObject();
                    if (item.has("translation")) {
                        lines.add(item.get("translation").getAsString());
                    }
                }
            }
            return lines.isEmpty() ? null : normalizeDisplayText(String.join("\n", lines));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String displayTextFromDirectDocument(String value) {
        Matcher docMatcher = ST_DOC_PATTERN.matcher(value);
        if (!docMatcher.find()) {
            return null;
        }
        String payload = docMatcher.group();
        Matcher lineMatcher = LINE_PATTERN.matcher(payload);
        List<String> lines = new ArrayList<>();
        while (lineMatcher.find()) {
            lines.add(displayTextFromDirectLine(lineMatcher.group(2)));
        }
        return lines.isEmpty() ? "" : normalizeDisplayText(String.join("\n", lines));
    }

    private static String displayTextFromDirectLine(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        Matcher tagMatcher = GROUP_OR_RUN_PATTERN.matcher(body);
        StringBuilder text = new StringBuilder();
        boolean foundTag = false;
        while (tagMatcher.find()) {
            foundTag = true;
            text.append(unescapeXml(tagMatcher.group(3)));
        }
        if (!foundTag) {
            return unescapeXml(stripDirectTags(body));
        }
        return text.toString();
    }

    private static String rewriteEditableCacheValue(String currentValue, String editedText) {
        if (currentValue != null
                && com.yourname.simpletranslate.core.WireCodec.isCanonicalPayload(currentValue.trim())) {
            return rewriteWireValue(currentValue, editedText);
        }
        String direct = rewriteDirectDocument(currentValue, editedText);
        if (direct != null) {
            return direct;
        }
        if (currentValue != null && ST_DOC_PATTERN.matcher(currentValue).find()) {
            return null;
        }
        String mapping = rewriteMappingValue(currentValue, editedText);
        if (mapping != null) {
            return mapping;
        }
        if (currentValue != null && currentValue.trim().startsWith("{")) {
            return null;
        }
        if (currentValue != null && currentValue.trim().startsWith("sign-components-v1")) {
            return null;
        }
        if (currentValue != null
                && com.yourname.simpletranslate.core.WireCodec.parseResponse(currentValue.trim(), 512) != null) {
            return null;
        }
        return editedText.trim();
    }

    private static String rewriteMappingValue(String currentValue, String editedText) {
        if (currentValue == null || !currentValue.trim().startsWith("{")) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(currentValue).getAsJsonObject();
            if (!root.has("translations") || !root.get("translations").isJsonArray()) {
                if (root.has("translation")) {
                    root.addProperty("translation", editedText.trim());
                    return root.toString();
                }
                return null;
            }
            JsonArray array = root.getAsJsonArray("translations");
            String[] editedLines = splitEditedLines(editedText, array.size());
            if (editedLines == null) {
                return null;
            }
            for (int i = 0; i < array.size(); i++) {
                JsonElement element = array.get(i);
                if (element.isJsonObject()) {
                    element.getAsJsonObject().addProperty("translation", editedLines[i]);
                }
            }
            return root.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Rewrites a minimal-wire cached payload with player-edited lines. Edited
     * lines are stored untagged and marked trusted so numeric guards never
     * revert an intentional player edit.
     */
    private static String rewriteWireValue(String currentValue, String editedText) {
        if (currentValue == null || editedText == null
                || !com.yourname.simpletranslate.core.WireCodec.isCanonicalPayload(currentValue.trim())) {
            return null;
        }
        List<Integer> indices = new ArrayList<>();
        for (String raw : currentValue.trim().split("\\R", -1)) {
            Matcher matcher = WIRE_LINE_PATTERN.matcher(raw);
            if (matcher.matches()) {
                try {
                    indices.add(Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        if (indices.isEmpty()) {
            return null;
        }
        String[] editedLines = splitEditedLines(editedText, indices.size());
        if (editedLines == null) {
            return null;
        }
        StringBuilder rewritten = new StringBuilder(com.yourname.simpletranslate.core.WireCodec.PAYLOAD_MARKER);
        for (int i = 0; i < indices.size(); i++) {
            rewritten.append('\n').append(indices.get(i)).append('|').append('*')
                    .append(com.yourname.simpletranslate.core.WireCodec.escape(editedLines[i]));
        }
        return rewritten.toString();
    }

    private static String rewriteDirectDocument(String currentValue, String editedText) {
        if (currentValue == null || currentValue.isBlank()) {
            return null;
        }
        Matcher docMatcher = ST_DOC_PATTERN.matcher(currentValue);
        if (!docMatcher.find()) {
            return null;
        }
        String payload = docMatcher.group();
        int lineCount = countLines(payload);
        String[] editedLines = splitEditedLines(editedText, lineCount);
        if (editedLines == null) {
            return null;
        }

        Matcher lineMatcher = LINE_PATTERN.matcher(payload);
        StringBuffer rewritten = new StringBuffer();
        int lineIndex = 0;
        while (lineMatcher.find()) {
            String body = rewriteDirectLine(lineMatcher.group(2), editedLines[lineIndex++]);
            if (body == null) {
                return null;
            }
            String line = "<line " + lineMatcher.group(1) + ">" + body + "</line>";
            lineMatcher.appendReplacement(rewritten, Matcher.quoteReplacement(line));
        }
        lineMatcher.appendTail(rewritten);
        return currentValue.substring(0, docMatcher.start())
                + rewritten
                + currentValue.substring(docMatcher.end());
    }

    private static int countLines(String payload) {
        Matcher matcher = LINE_PATTERN.matcher(payload);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String[] splitEditedLines(String editedText, int expectedCount) {
        if (expectedCount <= 0 || editedText == null) {
            return null;
        }
        String normalized = editedText.replace("\r\n", "\n").replace('\r', '\n');
        if (expectedCount == 1) {
            return new String[] { normalized.replace('\n', ' ').trim() };
        }
        String[] lines = normalized.split("\n", -1);
        if (lines.length != expectedCount) {
            return null;
        }
        for (int i = 0; i < lines.length; i++) {
            lines[i] = lines[i].trim();
        }
        return lines;
    }

    private static String rewriteDirectLine(String body, String editedLine) {
        Matcher tagMatcher = GROUP_OR_RUN_PATTERN.matcher(body);
        List<Integer> editableSlotLengths = new ArrayList<>();
        boolean foundTag = false;
        while (tagMatcher.find()) {
            foundTag = true;
            if (isEditableTag(tagMatcher.group(1), tagMatcher.group(2))) {
                editableSlotLengths.add(unescapeXml(tagMatcher.group(3)).codePointCount(
                        0, unescapeXml(tagMatcher.group(3)).length()));
            }
        }
        if (!foundTag) {
            return escapeXml(editedLine == null ? "" : editedLine);
        }
        int editableCount = editableSlotLengths.size();
        if (editableCount <= 0) {
            return null;
        }
        String editableText = removeFixedTextSegments(editedLine == null ? "" : editedLine, body);
        List<String> chunks = splitForEditableSlots(editableText, editableSlotLengths);
        Matcher replacementMatcher = GROUP_OR_RUN_PATTERN.matcher(body);
        StringBuffer rewritten = new StringBuffer();
        int editableIndex = 0;
        while (replacementMatcher.find()) {
            if (!isEditableTag(replacementMatcher.group(1), replacementMatcher.group(2))) {
                continue;
            }
            String tag = "<" + replacementMatcher.group(1) + " " + replacementMatcher.group(2) + ">"
                    + escapeXml(chunks.get(editableIndex++))
                    + "</" + replacementMatcher.group(1) + ">";
            replacementMatcher.appendReplacement(rewritten, Matcher.quoteReplacement(tag));
        }
        replacementMatcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String removeFixedTextSegments(String editedLine, String body) {
        String work = editedLine == null ? "" : editedLine;
        Matcher tagMatcher = GROUP_OR_RUN_PATTERN.matcher(body == null ? "" : body);
        while (tagMatcher.find()) {
            if (isEditableTag(tagMatcher.group(1), tagMatcher.group(2))) {
                continue;
            }
            String fixed = unescapeXml(tagMatcher.group(3));
            if (fixed == null || fixed.isBlank()) {
                continue;
            }
            int index = work.indexOf(fixed);
            if (index >= 0) {
                work = work.substring(0, index) + work.substring(index + fixed.length());
            }
        }
        return work.trim().replaceAll("\\s{2,}", " ");
    }

    private static boolean isEditableTag(String tag, String attrs) {
        return !"run".equals(tag) || !EDITABLE_FALSE_PATTERN.matcher(attrs == null ? "" : attrs).find();
    }

    private static List<String> splitForEditableSlots(String text, List<Integer> slotLengths) {
        int slotCount = slotLengths == null ? 0 : slotLengths.size();
        List<String> result = new ArrayList<>();
        if (slotCount <= 1) {
            result.add(text.trim());
            return result;
        }
        int[] codePoints = text == null ? new int[0] : text.trim().codePoints().toArray();
        int offset = 0;
        for (int i = 0; i < slotCount; i++) {
            if (offset >= codePoints.length) {
                result.add("");
            } else if (i == slotCount - 1) {
                result.add(new String(codePoints, offset, codePoints.length - offset));
            } else {
                int length = Math.max(0, slotLengths.get(i));
                int available = codePoints.length - offset;
                int chunkLength = Math.min(length, available);
                result.add(new String(codePoints, offset, chunkLength));
                offset += chunkLength;
            }
        }
        return result;
    }

    private static String stripDirectTags(String text) {
        return text.replaceAll("(?is)</?\\s*(?:st-doc|line|g|run)\\b[^>]*>", "");
    }

    private static String normalizeDisplayText(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String escapeXml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String unescapeXml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
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
            CacheShareManifest manifest = readManifest(zip);
            if (manifest != null && !matchesExpectedWorld(manifest, expectedWorldName)) {
                result.addWorldMismatch();
                SimpleTranslateMod.getLogger().warn("Skipped cache archive {}: world name does not match current world",
                        file);
                return;
            }

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
        if (key == null || source == null || !TranslationCacheKeys.isCurrentProtocolKey(key)
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
        dirty = true;
        result.addImported();
        enqueueShareableLocalEntry(lane, key, imported);
    }

    private void enqueueShareableLocalEntry(String lane, String key, CacheRecord record) {
        if ("ocr".equals(normalizeLane(lane))) {
            return;
        }
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

    private record ZipJsonEntry(String name, String json) {
    }
}
