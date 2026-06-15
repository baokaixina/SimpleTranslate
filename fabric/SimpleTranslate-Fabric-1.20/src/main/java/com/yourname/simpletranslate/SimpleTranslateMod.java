package com.yourname.simpletranslate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.yourname.simpletranslate.cache.TermDictionary;
import com.yourname.simpletranslate.cache.TranslationBlacklist;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.chat.ChatTranslationController;
import com.yourname.simpletranslate.chat.ChatMessageStore;
import com.yourname.simpletranslate.chat.ChatContextBatchTranslator;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import com.yourname.simpletranslate.network.SharedCacheClient;
import com.yourname.simpletranslate.translation.TranslationManager;
import com.yourname.simpletranslate.translation.TranslationRequestQueue;
import com.yourname.simpletranslate.util.AdvancementTranslationHelper;
import com.yourname.simpletranslate.util.BlacklistRefreshAware;
import com.yourname.simpletranslate.util.BookTranslationHelper;
import com.yourname.simpletranslate.util.HudTranslationHistory;
import com.yourname.simpletranslate.util.ScoreboardTranslationHelper;
import com.yourname.simpletranslate.util.SignContextSelectionManager;
import com.yourname.simpletranslate.util.SignSelectionHighlighter;
import com.yourname.simpletranslate.util.SignTranslationHelper;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import com.yourname.simpletranslate.util.TranslationLanes;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import net.minecraft.world.level.storage.LevelResource;

public class SimpleTranslateMod implements ClientModInitializer {
    public static final String MODID = "simple_translate";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String GLOBAL_CACHE_SCOPE = "global";
    private static final String CACHE_SETTINGS_FILE = "cache_settings.json";

    private static TranslationCache translationCache;
    private static TermDictionary termDictionary;
    private static TranslationBlacklist translationBlacklist;
    private static TranslationManager translationManager;
    private static Path configDir;
    private static String currentWorldId = null;
    private static Set<String> lastLegacyLocalWorldIds = Set.of();
    private static volatile long runtimeRevision = 0L;
    private static long blacklistRevision = 0L;
    private static boolean currentCacheServerShareEnabled = false;

    @Override
    public void onInitializeClient() {
        configDir = FabricLoader.getInstance().getConfigDir().resolve(MODID);
        ModConfig.init(configDir);

        translationCache = new TranslationCache(configDir.resolve("cache.json"));
        translationCache.load();
        loadCacheScopeSettings(null);
        translationBlacklist = new TranslationBlacklist(configDir.resolve("blacklist.json"));
        translationBlacklist.load();
        termDictionary = null;
        translationManager = new TranslationManager();

        ModKeyBindings.register();
        ChatContextBatchTranslator.register();
        HoldOriginalState.register();
        SignSelectionHighlighter.register();
        SharedCacheClient.register();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String worldId = getWorldIdentifier();
            if (worldId != null && !worldId.equals(currentWorldId)) {
                resetTranslationRuntime("world-switch:" + (currentWorldId == null ? "none" : currentWorldId) + "->" + worldId);
                currentWorldId = worldId;
                switchWorldData(worldId);
                LOGGER.debug("Switched to world data for: {}", worldId);
            }
            SharedCacheClient.onJoinedWorld();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (translationCache != null) {
                translationCache.flush();
            }
            if (termDictionary != null) {
                termDictionary.save();
            }
            if (translationBlacklist != null) {
                translationBlacklist.save();
            }
            resetTranslationRuntime("disconnect");
            termDictionary = null;
            currentWorldId = null;
            lastLegacyLocalWorldIds = Set.of();
            SharedCacheClient.onDisconnected();
            switchGlobalData();
        });

        LOGGER.info("Simple Translate Fabric mod initialized");
    }

    private static String getWorldIdentifier() {
        Minecraft mc = Minecraft.getInstance();

        ServerData serverData = mc.getCurrentServer();
        if (serverData != null) {
            lastLegacyLocalWorldIds = Set.of();
            return sanitizeFilename("server_" + serverData.ip);
        }

        if (mc.getSingleplayerServer() != null) {
            String levelName = mc.getSingleplayerServer().getWorldData().getLevelName();
            Set<String> legacyIds = new LinkedHashSet<>();
            legacyIds.add(sanitizeFilename("local_" + levelName));
            legacyIds.add(sanitizeFilename("local_" + stripMinecraftFormatting(levelName)));
            lastLegacyLocalWorldIds = legacyIds;
            try {
                Path worldPath = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                        .toAbsolutePath()
                        .normalize();
                String folderName = worldPath.getFileName() == null ? levelName : worldPath.getFileName().toString();
                String pathHash = Integer.toHexString(worldPath.toString().toLowerCase(Locale.ROOT).hashCode());
                return sanitizeFilename("local_" + folderName + "_" + pathHash);
            } catch (Exception e) {
                LOGGER.debug("Falling back to legacy local world id for {}", levelName, e);
                return legacyIds.iterator().next();
            }
        }

        lastLegacyLocalWorldIds = Set.of();
        return null;
    }

    private static String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
    }

    private static String stripMinecraftFormatting(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.replaceAll("(?i)[§&][0-9a-fk-or]", "");
    }

    private static void switchWorldData(String worldId) {
        if (translationCache != null) {
            translationCache.flush();
        }
        if (termDictionary != null) {
            termDictionary.save();
        }

        migrateLegacyWorldDataIfNeeded(worldId);

        Path worldCacheDir = configDir.resolve("cache").resolve(worldId);
        translationCache = new TranslationCache(worldCacheDir.resolve("translations.json"));
        translationCache.load();
        loadCacheScopeSettings(worldId);

        Path worldTermsDir = configDir.resolve("terms").resolve(worldId);
        Path worldTermsFile = worldTermsDir.resolve("terms.json");
        Path legacyTermsFile = configDir.resolve("terms.json");
        if (!Files.exists(worldTermsFile) && Files.exists(legacyTermsFile)) {
            try {
                Files.createDirectories(worldTermsDir);
                Files.copy(legacyTermsFile, worldTermsFile);
                LOGGER.info("Migrated legacy terms to world file for {}", worldId);
            } catch (IOException e) {
                LOGGER.warn("Failed to migrate legacy terms for {}", worldId, e);
            }
        }

        termDictionary = new TermDictionary(worldTermsFile);
        termDictionary.load();

        LOGGER.debug("Loaded cache for world: {} ({} entries)", worldId, translationCache.size());
        LOGGER.debug("Loaded terms for world: {}", worldId);
    }

    private static void switchGlobalData() {
        if (configDir == null) {
            currentCacheServerShareEnabled = false;
            return;
        }
        translationCache = new TranslationCache(configDir.resolve("cache.json"));
        translationCache.load();
        loadCacheScopeSettings(null);
    }

    private static void migrateLegacyWorldDataIfNeeded(String worldId) {
        Set<String> legacyWorldIds = lastLegacyLocalWorldIds;
        if (legacyWorldIds == null || legacyWorldIds.isEmpty()) {
            return;
        }

        Path targetCacheDir = configDir.resolve("cache").resolve(worldId);
        Path targetTermsDir = configDir.resolve("terms").resolve(worldId);
        Path targetTermsFile = targetTermsDir.resolve("terms.json");
        for (String legacyWorldId : legacyWorldIds) {
            if (legacyWorldId == null || legacyWorldId.isBlank() || legacyWorldId.equals(worldId)) {
                continue;
            }

            Path legacyCacheDir = configDir.resolve("cache").resolve(legacyWorldId);
            if (Files.exists(legacyCacheDir) && shouldMigrateCacheDirectory(targetCacheDir)) {
                copyDirectoryFiles(legacyCacheDir, targetCacheDir, "cache", legacyWorldId, worldId);
            }

            Path legacyTermsFile = configDir.resolve("terms").resolve(legacyWorldId).resolve("terms.json");
            if (!Files.exists(targetTermsFile) && Files.exists(legacyTermsFile)) {
                try {
                    Files.createDirectories(targetTermsDir);
                    Files.copy(legacyTermsFile, targetTermsFile, StandardCopyOption.COPY_ATTRIBUTES);
                    LOGGER.info("Migrated legacy world terms from {} to {}", legacyWorldId, worldId);
                } catch (IOException e) {
                    LOGGER.warn("Failed to migrate legacy world terms from {} to {}", legacyWorldId, worldId, e);
                }
            }
        }
    }

    private static boolean shouldMigrateCacheDirectory(Path targetCacheDir) {
        if (!Files.exists(targetCacheDir)) {
            return true;
        }
        try (var stream = Files.list(targetCacheDir)) {
            return stream.noneMatch(path -> Files.isRegularFile(path)
                    && path.getFileName() != null
                    && path.getFileName().toString().endsWith(".json"));
        } catch (IOException e) {
            LOGGER.debug("Unable to inspect target cache directory {}", targetCacheDir, e);
            return false;
        }
    }

    private static void copyDirectoryFiles(Path sourceDir, Path targetDir, String label, String fromWorldId,
                                           String toWorldId) {
        try {
            Files.createDirectories(targetDir);
            try (var stream = Files.list(sourceDir)) {
                stream.filter(Files::isRegularFile)
                        .forEach(source -> {
                            try {
                                Files.copy(source, targetDir.resolve(source.getFileName()),
                                        StandardCopyOption.COPY_ATTRIBUTES);
                            } catch (IOException e) {
                                LOGGER.warn("Failed to migrate {} file {} from {} to {}",
                                        label, source.getFileName(), fromWorldId, toWorldId, e);
                            }
                        });
            }
            LOGGER.info("Migrated legacy world {} from {} to {}", label, fromWorldId, toWorldId);
        } catch (IOException e) {
            LOGGER.warn("Failed to migrate legacy world {} from {} to {}", label, fromWorldId, toWorldId, e);
        }
    }

    public static TranslationCache getTranslationCache() {
        return translationCache;
    }

    public static TermDictionary getTermDictionary() {
        return termDictionary;
    }

    public static TranslationBlacklist getTranslationBlacklist() {
        return translationBlacklist;
    }

    public static TranslationManager getTranslationManager() {
        return translationManager;
    }

    public static Logger getLogger() {
        return LOGGER;
    }

    public static String getCurrentWorldId() {
        return currentWorldId;
    }

    public static String getCurrentCacheScopeId() {
        return currentWorldId == null ? GLOBAL_CACHE_SCOPE : currentWorldId;
    }

    public static boolean isCacheServerShareEnabled() {
        return currentCacheServerShareEnabled;
    }

    public static void setCacheServerShareEnabled(boolean enabled) {
        currentCacheServerShareEnabled = enabled;
        saveCacheScopeSettings(currentWorldId);
    }

    public static long getRuntimeRevision() {
        return runtimeRevision;
    }

    public static boolean isRuntimeRevisionCurrent(long revision) {
        return runtimeRevision == revision;
    }

    public static Path getConfigDir() {
        return configDir;
    }

    public static void onTranslationBlacklistChanged() {
        blacklistRevision++;
        resetTranslationRuntime("blacklist");
        refreshVisibleBlacklistedTranslations();
    }

    public static void onLanguageSettingsChanged() {
        resetTranslationRuntime("language-settings");
    }

    public static void onStyleSettingsChanged() {
        resetTranslationRuntime("style-settings");
    }

    public static void onTranslationCacheEdited() {
        resetTranslationRuntime("cache-edit");
    }

    public static void onSharedTranslationCacheImported() {
        refreshCacheBackedRenderState("shared-cache-import");
    }

    private static synchronized void resetTranslationRuntime(String reason) {
        runtimeRevision++;
        ChatTranslationController.clearRuntimeState();
        ChatContextBatchTranslator.clear();
        TranslationLanes.clearAll();
        TranslationRequestQueue.clear();
        TooltipTranslationHelper.clearPendingCache();
        AdvancementTranslationHelper.clearCache();
        BookTranslationHelper.clearCache();
        HudTranslationHistory.clear();
        ScoreboardTranslationHelper.clearLocalCache();
        SignTranslationHelper.clearAllCache();
        SignContextSelectionManager.clearAll();
        LOGGER.debug("Reset SimpleTranslate runtime state: {} (revision={})", reason, runtimeRevision);
    }

    private static synchronized void refreshCacheBackedRenderState(String reason) {
        TooltipTranslationHelper.clearPendingCache();
        AdvancementTranslationHelper.clearCache();
        BookTranslationHelper.clearCache();
        ScoreboardTranslationHelper.clearLocalCache();
        SignTranslationHelper.clearAllCache();
        LOGGER.debug("Refreshed SimpleTranslate cache-backed render state: {}", reason);
    }

    private static void refreshVisibleBlacklistedTranslations() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }

        minecraft.execute(() -> {
            if (minecraft.gui == null) {
                return;
            }
            if (minecraft.gui instanceof BlacklistRefreshAware aware) {
                aware.simple_translate$refreshBlacklistedTranslations();
            }
            var chat = minecraft.gui.getChat();
            if (chat instanceof BlacklistRefreshAware aware) {
                aware.simple_translate$refreshBlacklistedTranslations();
            }
        });
    }

    public static long getBlacklistRevision() {
        return blacklistRevision;
    }

    private static Path cacheScopeSettingsFile(String worldId) {
        String scope = worldId == null || worldId.isBlank() ? GLOBAL_CACHE_SCOPE : worldId;
        return configDir.resolve("cache").resolve(scope).resolve(CACHE_SETTINGS_FILE);
    }

    private static void loadCacheScopeSettings(String worldId) {
        currentCacheServerShareEnabled = false;
        if (configDir == null) {
            return;
        }
        Path file = cacheScopeSettingsFile(worldId);
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject object = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (object.has("serverShareEnabled")) {
                currentCacheServerShareEnabled = object.get("serverShareEnabled").getAsBoolean();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load cache scope settings {}", file, e);
            currentCacheServerShareEnabled = false;
        }
    }

    private static void saveCacheScopeSettings(String worldId) {
        if (configDir == null) {
            return;
        }
        Path file = cacheScopeSettingsFile(worldId);
        try {
            Files.createDirectories(file.getParent());
            CacheScopeSettings settings = new CacheScopeSettings();
            settings.serverShareEnabled = currentCacheServerShareEnabled;
            Files.writeString(file, GSON.toJson(settings));
        } catch (IOException e) {
            LOGGER.warn("Failed to save cache scope settings {}", file, e);
        }
    }

    private static final class CacheScopeSettings {
        boolean serverShareEnabled;
    }
}
