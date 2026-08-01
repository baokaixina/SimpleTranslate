package com.yourname.simpletranslate.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import com.yourname.simpletranslate.core.TranslationTextDetector;
import com.yourname.simpletranslate.transport.TranslationPromptPolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scope-safe Component semantic-slot translation memory.
 *
 * <p>The exact block cache remains authoritative for complete Component trees.
 * This second layer stores only accepted semantic slots and requires an exact
 * source, language pair, surface, role, logical document scope and active prompt
 * fingerprint. It therefore supports incremental item-tooltip translation
 * without allowing short labels to leak between unrelated items or surfaces.
 * Explicit player edits remain profile-independent and override scoped automatic
 * entries. Older unscoped automatic records may remain in existing files but are
 * intentionally never read.</p>
 */
public final class LineTranslationMemory {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Logger LOGGER = LogManager.getLogger("SimpleTranslate/LineMemory-1.12.2");
    private static final String COMPONENT_SLOT_AUTOMATIC_SCHEMA = "component_slot_auto_v2";

    private final Path file;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;
    private long mutationRevision = 0L;
    private long flushedRevision = 0L;

    public LineTranslationMemory(Path file) {
        this.file = file;
    }

    private static String baseKey(String lineSource, String sourceLang, String targetLang) {
        String hash = TranslationCacheKeys.semanticHash(lineSource);
        if (blank(hash)) {
            return "";
        }
        String languagePair = TranslationTextDetector.languagePairKey(sourceLang, targetLang)
                .trim().toLowerCase(Locale.ROOT);
        return hash + '\u0000' + languagePair;
    }

    private static ScopedKeyContext scopedKeyContext(String sourceLang, String targetLang,
                                                     String surface, String role,
                                                     String reuseScope) {
        if (blank(surface) || blank(reuseScope)) {
            return null;
        }
        String normalizedSurface = TranslationPromptPolicy.normalizedSurface(surface);
        String normalizedRole = TranslationPromptPolicy.normalizedRole(role);
        String normalizedScope = TranslationCacheKeys.normalizeSemanticSource(reuseScope);
        if (blank(normalizedScope)) {
            return null;
        }
        String languagePair = TranslationTextDetector.languagePairKey(sourceLang, targetLang)
                .trim().toLowerCase(Locale.ROOT);
        String promptFingerprint = TranslationPromptPolicy.cacheFingerprint(normalizedSurface);
        return new ScopedKeyContext(languagePair, normalizedSurface, normalizedRole,
                normalizedScope, promptFingerprint);
    }

    private static String scopedAutomaticKey(String lineSource, ScopedKeyContext context) {
        if (blank(lineSource) || context == null) {
            return "";
        }
        // Hash the exact UTF-8 source rather than the semantic-normalized form used by the
        // legacy line memory. Component slots must not alias merely because whitespace differs.
        return exactHash(lineSource) + '\u0000'
                + context.languagePair + '\u0000'
                + COMPONENT_SLOT_AUTOMATIC_SCHEMA + '\u0000'
                + context.surface + '\u0000'
                + context.role + '\u0000'
                + exactHash(context.reuseScope) + '\u0000'
                + context.promptFingerprint;
    }

    private static String exactHash(String value) {
        if (value == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                int unsigned = current & 0xff;
                result.append(Character.forDigit(unsigned >>> 4, 16));
                result.append(Character.forDigit(unsigned & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /**
     * Returns an automatic Component-JSON slot translation from one stable reuse scope.
     *
     * <p>An explicit player correction remains authoritative across scopes and prompt
     * profiles. Automatic entries are narrower: exact source bytes, language pair,
     * surface, normalized role, stable reuse scope and prompt-policy fingerprint must
     * all match. A translation equal to its source is still a valid remembered result
     * (for example, an English proper name).</p>
     */
    public String lookupScoped(String lineSource, String sourceLang, String targetLang,
                               String surface, String role, String reuseScope,
                               boolean allowSharedImported) {
        if (blank(lineSource)) {
            return null;
        }
        String playerKey = baseKey(lineSource, sourceLang, targetLang);
        if (!playerKey.isEmpty()) {
            Entry playerEntry = entries.get(playerKey);
            if (playerEntry != null && playerEntry.playerEdited
                    && (!playerEntry.sharedImported || allowSharedImported)) {
                return playerEntry.translation;
            }
        }

        ScopedKeyContext context = scopedKeyContext(sourceLang, targetLang, surface, role, reuseScope);
        String key = scopedAutomaticKey(lineSource, context);
        if (key.isEmpty()) {
            return null;
        }
        Entry entry = entries.get(key);
        if (entry == null || !lineSource.equals(entry.source)
                || (entry.sharedImported && !allowSharedImported)) {
            return null;
        }
        return entry.translation;
    }

    /** Records an explicit cache-editor correction that overrides every scoped automatic entry. */
    public synchronized boolean recordPlayerEdited(
            String lineSource, String sourceLang, String targetLang,
            String translation, boolean sharedImported) {
        if (blank(lineSource) || blank(translation)) {
            return false;
        }
        String key = baseKey(lineSource, sourceLang, targetLang);
        if (key.isEmpty()) {
            return false;
        }
        Entry existing = entries.get(key);
        if (existing != null && existing.playerEdited
                && translation.equals(existing.translation)
                && sharedImported == existing.sharedImported) {
            return false;
        }
        entries.put(key, new Entry(lineSource, translation, true, sharedImported));
        markDirty();
        return true;
    }

    /**
     * Records one accepted Component-JSON slot batch without flushing the backing file.
     *
     * <p>The lists are positional and must have the same size. Invalid pairs are skipped.
     * The first accepted automatic value wins; a local result may only replace an entry
     * whose sole provenance was an imported shared archive. The returned count includes
     * both newly inserted entries and that provenance upgrade.</p>
     */
    public synchronized int recordScoped(List<String> sources, List<String> translations,
                                         String sourceLang, String targetLang,
                                         String surface, String role, String reuseScope,
                                         boolean sharedImported) {
        if (sources == null || translations == null || sources.isEmpty()
                || sources.size() != translations.size()) {
            return 0;
        }
        ScopedKeyContext context = scopedKeyContext(sourceLang, targetLang, surface, role, reuseScope);
        if (context == null) {
            return 0;
        }

        int changed = 0;
        for (int index = 0; index < sources.size(); index++) {
            String source = sources.get(index);
            String translation = translations.get(index);
            if (blank(source) || blank(translation)
                    || source.equals(translation)) {
                continue;
            }
            String key = scopedAutomaticKey(source, context);
            if (key.isEmpty()) {
                continue;
            }
            Entry existing = entries.get(key);
            if (existing == null) {
                entries.put(key, new Entry(source, translation, false, sharedImported));
                changed++;
                continue;
            }
            // The exact source field makes a cryptographic-key collision fail closed.
            if (!source.equals(existing.source) || existing.playerEdited) {
                continue;
            }
            if (!sharedImported && existing.sharedImported) {
                entries.put(key, new Entry(source, translation, false, false));
                changed++;
            }
        }
        if (changed > 0) {
            markDirty();
        }
        return changed;
    }

    public synchronized void load() {
        entries.clear();
        Map<String, Entry> loaded = readEntries(file);
        if (loaded.isEmpty()) {
            dirty = false;
            mutationRevision = 0L;
            flushedRevision = 0L;
            return;
        }
        entries.putAll(loaded);
        dirty = false;
        mutationRevision = 0L;
        flushedRevision = 0L;
    }

    public synchronized int mergeFrom(Path sourceFile) {
        Map<String, Entry> sourceEntries = readEntries(sourceFile);
        if (sourceEntries.isEmpty()) {
            return 0;
        }
        int[] imported = {0};
        sourceEntries.forEach((key, incoming) -> entries.compute(key, (ignored, existing) -> {
            if (existing == null || (incoming.playerEdited && !existing.playerEdited)) {
                imported[0]++;
                markDirty();
                return incoming;
            }
            return existing;
        }));
        return imported[0];
    }

    public synchronized void flush() {
        if (!dirty || mutationRevision == flushedRevision || file == null) {
            return;
        }
        long revisionBeingWritten = mutationRevision;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(new LinkedHashMap<>(entries), writer);
            }
            com.yourname.simpletranslate.core.AtomicFiles.moveAtomically(temporary, file);
            flushedRevision = revisionBeingWritten;
            dirty = mutationRevision != flushedRevision;
        } catch (IOException e) {
            LOGGER.warn("Failed to save line translation memory", e);
        }
    }

    public int size() {
        return entries.size();
    }

    private void markDirty() {
        mutationRevision++;
        dirty = true;
    }

    private static Map<String, Entry> readEntries(Path sourceFile) {
        Map<String, Entry> valid = new LinkedHashMap<>();
        if (sourceFile == null || !Files.exists(sourceFile)) {
            return valid;
        }
        try (Reader reader = Files.newBufferedReader(sourceFile, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, Entry>>() {
            }.getType();
            Map<String, Entry> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                loaded.forEach((key, entry) -> {
                    if (key != null && entry != null
                            && !blank(entry.translation)) {
                        valid.put(key, entry);
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to load line translation memory from {}", sourceFile, e);
        }
        return valid;
    }

    private static final class Entry {
        String source;
        String translation;
        boolean playerEdited;
        boolean sharedImported;

        Entry(String source, String translation, boolean playerEdited, boolean sharedImported) {
            this.source = source;
            this.translation = translation;
            this.playerEdited = playerEdited;
            this.sharedImported = sharedImported;
        }
    }

    private static final class ScopedKeyContext {
        private final String languagePair;
        private final String surface;
        private final String role;
        private final String reuseScope;
        private final String promptFingerprint;

        private ScopedKeyContext(String languagePair, String surface, String role,
                                 String reuseScope, String promptFingerprint) {
            this.languagePair = languagePair;
            this.surface = surface;
            this.role = role;
            this.reuseScope = reuseScope;
            this.promptFingerprint = promptFingerprint;
        }
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
