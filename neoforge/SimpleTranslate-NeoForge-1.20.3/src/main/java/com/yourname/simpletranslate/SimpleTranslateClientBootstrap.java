package com.yourname.simpletranslate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.yourname.simpletranslate.cache.TermDictionary;
import com.yourname.simpletranslate.cache.TranslationBlacklist;
import com.yourname.simpletranslate.cache.LineTranslationMemory;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.feature.chat.ChatTranslationController;
import com.yourname.simpletranslate.feature.chat.ChatContextBatchTranslator;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import com.yourname.simpletranslate.cache.SharedCacheClient;
import com.yourname.simpletranslate.transport.TranslationManager;
import com.yourname.simpletranslate.transport.TranslationRequestQueue;
import com.yourname.simpletranslate.core.BlacklistRefreshAware;
import com.yourname.simpletranslate.feature.book.BookTranslationHelper;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.feature.hud.HudTranslationHistory;
import com.yourname.simpletranslate.feature.hud.ScoreboardTranslationHelper;
import com.yourname.simpletranslate.feature.sign.SignContextSelectionManager;
import com.yourname.simpletranslate.feature.sign.SignSelectionHighlighter;
import com.yourname.simpletranslate.feature.sign.SignTranslationHelper;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationTriggerState;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import com.yourname.simpletranslate.core.TextContextMemory;
import com.yourname.simpletranslate.transport.TranslationLanes;
import com.yourname.simpletranslate.transport.TokenUsageMonitor;
import com.yourname.simpletranslate.gui.SimpleTranslateScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.ConfigScreenHandler;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.world.level.storage.LevelResource;

/**
 * Client-only runtime state and lifecycle. Loaded exclusively through
 * {@link SimpleTranslateMod}'s physical-side guard, so this class may import
 * net.minecraft.client.* types freely; dedicated-server initialization never
 * touches it.
 *
 * <p>The Iceberg gather compat is intentionally absent: Iceberg has no
 * NeoForge artifact for Minecraft 1.20.3. Modrinth project 5faXoLqX lists no
 * 1.20.3 build at all, and the 1.20.1 Forge build declares a dependency on mod
 * id "forge", which NeoForge 20.3 does not provide, so it cannot load here.</p>
 *
 * <p>The FTB Library terminal bridge is likewise absent: maven.ftb.dev
 * publishes no ftb-library-neoforge 2002.x/2003.x series (the NeoForge
 * artifacts start at 2004.x for Minecraft 1.20.4), and the
 * ftb-library-forge-2001.x builds require mod id "forge", which NeoForge
 * 20.3 does not provide. No FTB artifact can load on this target, so the
 * pseudo mixins and the gating plugin are removed, not merely disabled.</p>
 */
public final class SimpleTranslateClientBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String GLOBAL_CACHE_SCOPE = "global";
    private static final String CACHE_SETTINGS_FILE = "cache_settings.json";

    private static volatile TranslationCache translationCache;
    private static volatile LineTranslationMemory lineTranslationMemory;
    private static volatile TermDictionary termDictionary;
    private static TranslationBlacklist translationBlacklist;
    private static TranslationManager translationManager;
    private static Path configDir;
    private static volatile String currentWorldId = null;
    private static Set<String> lastLegacyLocalWorldIds = Set.of();
    private static volatile long runtimeRevision = 0L;
    private static long blacklistRevision = 0L;
    private static boolean currentCacheServerShareEnabled = false;
    private static boolean firstRunHintShown = false;

    private SimpleTranslateClientBootstrap() {
    }

    public static void initialize(IEventBus modEventBus) {
        ModKeyBindings.register(modEventBus);
        modEventBus.addListener((FMLClientSetupEvent event) -> onClientSetup(event, modEventBus));
        NeoForge.EVENT_BUS.addListener(SimpleTranslateClientBootstrap::onGameShuttingDown);
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new SimpleTranslateScreen(parent)));
    }

    private static void onClientSetup(FMLClientSetupEvent event, IEventBus modEventBus) {
        event.enqueueWork(() -> initializeClient(modEventBus));
    }

    private static void initializeClient(IEventBus modEventBus) {
        configDir = FMLPaths.CONFIGDIR.get().resolve(SimpleTranslateMod.MODID);
        ModConfig.init(configDir);

        translationCache = new TranslationCache(configDir.resolve("cache.json"));
        translationCache.load();
        lineTranslationMemory = new LineTranslationMemory(configDir.resolve("line_memory.json"));
        lineTranslationMemory.load();
        loadCacheScopeSettings(null);
        translationBlacklist = new TranslationBlacklist(configDir.resolve("blacklist.json"));
        translationBlacklist.load();
        termDictionary = null;
        translationManager = new TranslationManager();

        ChatContextBatchTranslator.register();
        HoldOriginalState.register();
        SignSelectionHighlighter.register();
        SharedCacheClient.register();

        NeoForge.EVENT_BUS.addListener(SimpleTranslateClientBootstrap::onClientTick);
        NeoForge.EVENT_BUS.addListener(SimpleTranslateClientBootstrap::onClientLoggingIn);
        NeoForge.EVENT_BUS.addListener(SimpleTranslateClientBootstrap::onClientLoggingOut);

        LOGGER.info("Simple Translate Forge mod initialized");
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ChatContextBatchTranslator.tickAllCollectors();
        }
    }

    private static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft client = Minecraft.getInstance();
            try {
                String worldId = getWorldIdentifier();
                if (worldId != null && !worldId.equals(currentWorldId)) {
                    resetTranslationRuntime("world-switch:" + (currentWorldId == null ? "none" : currentWorldId) + "->" + worldId);
                    currentWorldId = worldId;
                    switchWorldData(worldId);
                    LOGGER.debug("Switched to world data for: {}", worldId);
                }
            } catch (Throwable error) {
                LOGGER.error("SimpleTranslate failed to switch world data on join; continuing with current state", error);
            }
            try {
                SharedCacheClient.onJoinedWorld();
            } catch (Throwable error) {
                LOGGER.error("SimpleTranslate shared-cache join failed; continuing", error);
            }
            showFirstRunHintIfNeeded(client);
    }

    private static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            if (translationCache != null) {
                translationCache.flush();
            }
            if (lineTranslationMemory != null) {
                lineTranslationMemory.flush();
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
            firstRunHintShown = false;
            SharedCacheClient.onDisconnected();
            switchGlobalData();
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        shutdown();
    }

    private static void shutdown() {
        if (translationCache != null) {
            translationCache.flush();
        }
        if (lineTranslationMemory != null) {
            lineTranslationMemory.flush();
        }
        if (termDictionary != null) {
            termDictionary.save();
        }
        if (translationBlacklist != null) {
            translationBlacklist.save();
        }
        TranslationRequestQueue.shutdown();
        JsonPassthroughPipeline.shutdown();
        if (translationManager != null) {
            translationManager.shutdown();
        }
        TranslationCache.shutdownExecutor();
    }

    private static void showFirstRunHintIfNeeded(Minecraft client) {
        if (firstRunHintShown) {
            return;
        }
        firstRunHintShown = true;
        var manager = getTranslationManager();
        if (manager != null && manager.isReady()) {
            return;
        }
        if (client == null || client.player == null) {
            return;
        }
        client.player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("chat.simple_translate.first_run_hint"), true);
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
            try {
                Path worldPath = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                        .toAbsolutePath()
                        .normalize();
                String folderName = worldPath.getFileName() == null ? levelName : worldPath.getFileName().toString();
                String stableWorldId = sanitizeFilename("local_" + folderName);
                String pathWorldId = sanitizeFilename("local_" + folderName + "_"
                        + Integer.toHexString(worldPath.toString().toLowerCase(Locale.ROOT).hashCode()));
                legacyIds.add(pathWorldId);
                legacyIds.addAll(discoverHashedLocalWorldIds(stableWorldId));
                legacyIds.remove(stableWorldId);
                lastLegacyLocalWorldIds = Set.copyOf(legacyIds);
                return stableWorldId;
            } catch (Exception e) {
                String stableWorldId = sanitizeFilename("local_" + stripMinecraftFormatting(levelName));
                legacyIds.addAll(discoverHashedLocalWorldIds(stableWorldId));
                legacyIds.remove(stableWorldId);
                lastLegacyLocalWorldIds = Set.copyOf(legacyIds);
                LOGGER.debug("Falling back to stable local world id for {}", levelName, e);
                return stableWorldId;
            }
        }

        lastLegacyLocalWorldIds = Set.of();
        return null;
    }

    private static String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase(java.util.Locale.ROOT);
    }

    private static String stripMinecraftFormatting(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.replaceAll("(?i)[§&][0-9a-fk-or]", "");
    }

    private static Set<String> discoverHashedLocalWorldIds(String stableWorldId) {
        Set<String> discovered = new LinkedHashSet<>();
        if (configDir == null || stableWorldId == null || stableWorldId.isBlank()) {
            return discovered;
        }
        Pattern hashedScope = Pattern.compile(Pattern.quote(stableWorldId) + "_[0-9a-f]{1,8}");
        discoverMatchingScopeDirectories(configDir.resolve("cache"), hashedScope, discovered);
        discoverMatchingScopeDirectories(configDir.resolve("terms"), hashedScope, discovered);
        return discovered;
    }

    private static void discoverMatchingScopeDirectories(Path root, Pattern pattern, Set<String> target) {
        if (root == null || pattern == null || target == null || !Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName() == null ? "" : path.getFileName().toString())
                    .filter(name -> pattern.matcher(name).matches())
                    .forEach(target::add);
        } catch (IOException e) {
            LOGGER.debug("Unable to discover legacy cache scopes under {}", root, e);
        }
    }

    private static void switchWorldData(String worldId) {
        if (translationCache != null) {
            translationCache.flush();
        }
        if (lineTranslationMemory != null) {
            lineTranslationMemory.flush();
        }
        if (termDictionary != null) {
            termDictionary.save();
        }

        migrateLegacyWorldDataIfNeeded(worldId);

        Path worldCacheDir = configDir.resolve("cache").resolve(worldId);
        translationCache = new TranslationCache(worldCacheDir.resolve("translations.json"));
        translationCache.load();
        GuiTranslationHelper.migrateLegacyHudFrameActivation(translationCache);
        lineTranslationMemory = new LineTranslationMemory(worldCacheDir.resolve("line_memory.json"));
        lineTranslationMemory.load();
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
        lineTranslationMemory = new LineTranslationMemory(configDir.resolve("line_memory.json"));
        lineTranslationMemory.load();
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
        TranslationCache mergedCache = new TranslationCache(targetCacheDir.resolve("translations.json"));
        mergedCache.load();
        LineTranslationMemory mergedLineMemory =
                new LineTranslationMemory(targetCacheDir.resolve("line_memory.json"));
        mergedLineMemory.load();
        List<String> orderedLegacyWorldIds = legacyWorldIds.stream()
                .filter(id -> id != null && !id.isBlank() && !id.equals(worldId))
                .sorted(Comparator.comparingLong(SimpleTranslateClientBootstrap::legacyScopeModifiedTime).reversed())
                .toList();
        for (String legacyWorldId : orderedLegacyWorldIds) {
            Path legacyCacheDir = configDir.resolve("cache").resolve(legacyWorldId);
            if (Files.isDirectory(legacyCacheDir)) {
                mergeLegacyTranslationCache(mergedCache, legacyCacheDir, legacyWorldId, worldId);
                mergedLineMemory.mergeFrom(legacyCacheDir.resolve("line_memory.json"));
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
        mergedCache.flush();
        mergedLineMemory.flush();
    }

    private static long legacyScopeModifiedTime(String worldId) {
        if (configDir == null || worldId == null || worldId.isBlank()) {
            return Long.MIN_VALUE;
        }
        Path cacheDir = configDir.resolve("cache").resolve(worldId);
        try {
            return Files.exists(cacheDir)
                    ? Files.getLastModifiedTime(cacheDir).toMillis()
                    : Long.MIN_VALUE;
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private static void mergeLegacyTranslationCache(TranslationCache targetCache, Path sourceDir,
                                                    String fromWorldId, String toWorldId) {
        if (targetCache == null || sourceDir == null || !Files.isDirectory(sourceDir)) {
            return;
        }
        int imported = 0;
        try (var stream = Files.list(sourceDir)) {
            for (Path source : stream.filter(Files::isRegularFile).toList()) {
                String name = source.getFileName() == null
                        ? "" : source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!name.endsWith(".json")
                        || "line_memory.json".equals(name)
                        || CACHE_SETTINGS_FILE.equals(name)) {
                    continue;
                }
                imported += targetCache.importFromFile(source, true).imported();
            }
            if (imported > 0) {
                LOGGER.info("Merged {} cached translations from legacy world scope {} into {}",
                        imported, fromWorldId, toWorldId);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to merge legacy world cache from {} to {}", fromWorldId, toWorldId, e);
        }
    }

    public static TranslationCache getTranslationCache() {
        return translationCache;
    }

    public static LineTranslationMemory getLineTranslationMemory() {
        return lineTranslationMemory;
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

    public static void onTranslationCacheEdited() {
        resetTranslationRuntime("cache-edit");
    }

    public static void onTranslationProfileChanged() {
        resetTranslationRuntime("translation-profile");
    }

    public static void onTextContextSettingsChanged() {
        TextContextMemory.settingsChanged();
        resetTranslationRuntime("text-context-settings");
    }

    public static void onTermDictionaryChanged() {
        resetTranslationRuntime("term-dictionary");
    }

    public static void onGlobalTranslationSettingChanged(boolean enabled) {
        if (!enabled) {
            ChatContextBatchTranslator.restoreVisibleOriginalMessages();
        }
        resetTranslationRuntime("global-translation:" + (enabled ? "enabled" : "disabled"));
    }

    public static void onSharedTranslationCacheImported() {
        refreshCacheBackedRenderState("shared-cache-import");
    }

    /**
     * Clears every in-memory translation surface that can retain stale results
     * across world switches, language changes, blacklist edits, or global toggle.
     *
     * <p>Keep this list complete when adding a new surface with static/session
     * caches. HudFeature instances clear themselves on the next render via
     * {@link #getRuntimeRevision()} rather than a static registry.</p>
     */
    private static synchronized void resetTranslationRuntime(String reason) {
        runtimeRevision++;
        // chat AUTO/BUTTON + message store + peer identity maps
        ChatTranslationController.clearRuntimeState();
        ChatContextBatchTranslator.clear();
        // request lanes, JSON batcher, recovery freezes
        TranslationLanes.clearAll();
        TranslationRequestQueue.clear();
        // feature session caches
        TooltipTranslationTriggerState.clearShortcutRequest();
        TooltipTranslationHelper.clearPendingCache();
        BookTranslationHelper.clearCache();
        HudTranslationHistory.clear();
        ScoreboardTranslationHelper.clearLocalCache();
        GuiTranslationHelper.clearLocalState();
        SignTranslationHelper.clearAllCache();
        SignContextSelectionManager.clearAll();
        TokenUsageMonitor.clear();
        LOGGER.debug("Reset SimpleTranslate runtime state: {} (revision={})", reason, runtimeRevision);
    }

    private static synchronized void refreshCacheBackedRenderState(String reason) {
        TooltipTranslationHelper.clearPendingCache();
        BookTranslationHelper.clearCache();
        ScoreboardTranslationHelper.clearLocalCache();
        GuiTranslationHelper.clearLocalState();
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
            com.yourname.simpletranslate.core.AtomicFiles.writeString(file, GSON.toJson(settings));
        } catch (IOException e) {
            LOGGER.warn("Failed to save cache scope settings {}", file, e);
        }
    }

    private static final class CacheScopeSettings {
        boolean serverShareEnabled;
    }
}
