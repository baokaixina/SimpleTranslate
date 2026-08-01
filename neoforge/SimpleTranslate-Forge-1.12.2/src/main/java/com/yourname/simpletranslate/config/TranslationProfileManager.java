package com.yourname.simpletranslate.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.yourname.simpletranslate.cache.CacheKey;
import com.yourname.simpletranslate.core.TextContextMemory;
import com.yourname.simpletranslate.SimpleTranslateForge1122;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Scope-local, player-authored localization orders for model translation. */
public final class TranslationProfileManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LogManager.getLogger("SimpleTranslate/Profile-1.12.2");
    private static final int MAX_DESCRIPTION_CODE_POINTS = 2_000;
    private static final String DEFAULT = "";

    private static String loadedScope = "";
    private static String loadedDescription = DEFAULT;
    private static Path configDir;

    private TranslationProfileManager() {
    }

    public static synchronized void init(File directory) {
        configDir = directory == null ? null : directory.toPath();
        loadedScope = "";
        loadedDescription = DEFAULT;
        ensureLoaded();
    }

    /** The active scope's player orders (normalized description, possibly blank). */
    public static synchronized String current() {
        ensureLoaded();
        return loadedDescription;
    }

    public static synchronized void saveCurrent(String description) {
        ensureLoaded();
        String normalized = normalize(description);
        Path file = profileFile(loadedScope);
        if (file == null) {
            loadedDescription = normalized;
            TextContextMemory.settingsChanged();
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(new StoredProfile(null, normalized), writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            loadedDescription = normalized;
            TextContextMemory.settingsChanged();
        } catch (Exception error) {
            LOGGER.warn("Failed to save translation profile for scope {}",
                    loadedScope, error);
        }
    }

    public static synchronized void resetCurrent() {
        ensureLoaded();
        Path file = profileFile(loadedScope);
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (Exception error) {
                LOGGER.warn("Failed to reset translation profile for scope {}",
                        loadedScope, error);
            }
        }
        loadedDescription = DEFAULT;
        TextContextMemory.settingsChanged();
    }

    /** Empty means no player orders and preserves existing cache/memory compatibility. */
    public static String fingerprint() {
        String description = current();
        return description.trim().isEmpty() ? "" : CacheKey.hash(description);
    }

    /**
     * The player's own highest-priority orders. Except for the output protocol
     * (top-level JSON array shape, valid Components, protected markers and
     * safety rules), they outrank every other translation guidance in the
     * prompt. The same section is emitted twice per request (opening primacy
     * position and closing recency position) so the model keeps it in focus.
     */
    public static String promptSection() {
        String description = current();
        if (description.trim().isEmpty()) {
            return "";
        }
        return "PLAYER'S HIGHEST-PRIORITY ORDERS (direct orders from the player. Except for the output "
                + "protocol — top-level JSON array shape, valid Components, protected markers/placeholders, "
                + "and safety rules — these orders outrank every other translation guidance in this prompt, "
                + "including the TEXT TRANSLATION RULES and the name-localization defaults. Follow them "
                + "exactly):\n" + GSON.toJson(description) + "\n";
    }

    private static void ensureLoaded() {
        String scope = normalizedScope(SimpleTranslateForge1122.getCurrentCacheScopeId());
        if (scope.equals(loadedScope)) {
            return;
        }
        loadedScope = scope;
        loadedDescription = load(profileFile(scope));
    }

    private static String load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return DEFAULT;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StoredProfile stored = GSON.fromJson(reader, StoredProfile.class);
            if (stored == null) {
                return DEFAULT;
            }
            String description = stored.description == null ? "" : stored.description;
            if (description.trim().isEmpty()) {
                description = legacyStyleDescription(stored.style);
            }
            return normalize(description);
        } catch (Exception error) {
            LOGGER.warn("Failed to load translation profile from {}", file, error);
            return DEFAULT;
        }
    }

    /**
     * One-time lazy migration for profiles saved before the style presets were
     * removed: a legacy non-natural style with no free-text description folds
     * into an equivalent player-order sentence. The next save drops the style
     * field entirely; legacy files are never deleted eagerly.
     */
    private static String legacyStyleDescription(String style) {
        if (style == null) {
            return "";
        }
        String value = style.trim().toLowerCase(Locale.ROOT);
        if ("faithful".equals(value)) return "Faithful wording that stays close to the original meaning.";
        if ("concise".equals(value)) return "Concise wording suitable for compact game UI.";
        if ("immersive".equals(value)) return "Immersive in-world localization consistent with the setting.";
        return "";
    }

    private static Path profileFile(String scope) {
        return configDir == null ? null : configDir.resolve("profiles").resolve(scope).resolve("profile.json");
    }

    private static String normalize(String description) {
        String value = description == null ? "" : description
                .replace('\u0000', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        int count = value.codePointCount(0, value.length());
        if (count > MAX_DESCRIPTION_CODE_POINTS) {
            value = value.substring(0, value.offsetByCodePoints(0, MAX_DESCRIPTION_CODE_POINTS)).trim();
        }
        return value;
    }

    private static String normalizedScope(String scope) {
        String value = scope == null || scope.trim().isEmpty() ? "global" : scope.toLowerCase(Locale.ROOT);
        value = value.replaceAll("[^a-z0-9._-]", "_");
        return value.trim().isEmpty() ? "global" : value;
    }

    private static final class StoredProfile {
        String style;
        String description;

        StoredProfile(String style, String description) {
            this.style = style;
            this.description = description;
        }
    }
}
