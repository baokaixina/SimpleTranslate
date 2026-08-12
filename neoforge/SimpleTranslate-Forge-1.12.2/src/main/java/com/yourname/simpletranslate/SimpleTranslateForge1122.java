package com.yourname.simpletranslate;

import com.yourname.simpletranslate.translation.TranslationEngine;
import com.yourname.simpletranslate.transport.TranslationManager;
import com.yourname.simpletranslate.cache.SharedCacheStore;
import com.yourname.simpletranslate.cache.SharedCacheNetworking;
import com.yourname.simpletranslate.cache.SharedCacheClient;
import com.yourname.simpletranslate.cache.SharedCacheServer;
import com.yourname.simpletranslate.chat.ChatTranslationController;
import com.yourname.simpletranslate.chat.OutgoingChatTranslator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.core.AtomicFiles;
import com.yourname.simpletranslate.feature.book.BookTranslationSession;
import com.yourname.simpletranslate.feature.hud.HudTranslationController;
import com.yourname.simpletranslate.feature.hud.ScoreboardTranslationController;
import com.yourname.simpletranslate.feature.sign.SignContextSelectionManager;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationGlowRenderer;
import com.yourname.simpletranslate.gui.GuiTranslationController;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.transport.TokenUsageMonitor;
import com.yourname.simpletranslate.transport.TranslationLanes;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = SimpleTranslateForge1122.MOD_ID, name = "Simple Translate", version = SimpleTranslateForge1122.VERSION,
        dependencies = "required-after:mixinbooter@[9.4,)",
        acceptedMinecraftVersions = "[1.12.2]",
        guiFactory = "com.yourname.simpletranslate.forge.ForgeConfigGuiFactory")
public final class SimpleTranslateForge1122 {
    public static final String MOD_ID = "simple_translate";
    public static final String VERSION = "2.1.29";
    private static final Logger LOGGER = LogManager.getLogger("SimpleTranslate/Forge-1.12.2");
    private static TranslationEngine engine;
    private static TranslationManager translationManager;
    private static Object forgeEvents;
    private static Path modConfigDir;
    private static boolean cacheServerShareEnabled;
    private static String currentWorldId;
    private static long runtimeRevision;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        SharedCacheNetworking.register();
        SharedCacheServer.register();
        modConfigDir = new File(event.getModConfigurationDirectory(), "simple_translate").toPath();
        boolean modernConfigExists = Files.exists(modConfigDir.resolve("simple_translate-client.json"));
        ModConfig.init(modConfigDir);
        loadCacheSettings();
        if (!event.getSide().isClient()) return;
        File file = new File(event.getModConfigurationDirectory(), "simple_translate.properties");
        engine = new TranslationEngine(file);
        if (modernConfigExists) engine.applyModernConfiguration();
        else engine.migrateLegacyConfigurationToModern();
        translationManager = new TranslationManager(engine);
        try {
            Class<?> eventClass = Class.forName("com.yourname.simpletranslate.forge.ForgeClientEvents");
            Object clientEvents = eventClass.getConstructor(TranslationEngine.class).newInstance(engine);
            forgeEvents = clientEvents;
            // Forge 1.12.2 has two distinct buses. GUI/render/network events
            // use the MinecraftForge bus, while ClientTickEvent is posted on
            // FMLCommonHandler#bus (verified against exact Forge 2795).
            MinecraftForge.EVENT_BUS.register(clientEvents);
            FMLCommonHandler.instance().bus().register(clientEvents);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize Simple Translate client events", error);
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (event.getSide().isClient()) SharedCacheClient.register();
        if (forgeEvents != null) {
            try {
                forgeEvents.getClass().getMethod("registerKeyBinding").invoke(forgeEvents);
            } catch (Exception error) {
                throw new IllegalStateException("Unable to register Simple Translate client keys", error);
            }
        }
    }

    public static TranslationEngine getEngine() {
        return engine;
    }
    public static Logger getLogger() { return LOGGER; }
    public static TranslationManager getTranslationManager() { return translationManager; }
    public static long getRuntimeRevision() { return runtimeRevision; }
    public static boolean isRuntimeRevisionCurrent(long revision) { return runtimeRevision == revision; }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        SharedCacheServer.onServerStarted(FMLCommonHandler.instance().getMinecraftServerInstance());
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        SharedCacheServer.onServerStopping();
    }

    public static SharedCacheStore getSharedCacheStore() { return SharedCacheServer.store(); }
    public static com.yourname.simpletranslate.cache.TranslationCache getTranslationCache() {
        return engine == null ? null : engine.getTranslationCache();
    }
    public static com.yourname.simpletranslate.cache.LineTranslationMemory getLineTranslationMemory() {
        return engine == null ? null : engine.getLineTranslationMemory();
    }
    public static com.yourname.simpletranslate.cache.TermDictionary getTermDictionary() {
        return engine == null ? null : engine.getTermDictionary();
    }
    public static com.yourname.simpletranslate.cache.TranslationBlacklist getTranslationBlacklist() {
        return engine == null ? null : engine.getTranslationBlacklist();
    }
    public static String getCurrentWorldId() { return currentWorldId; }
    public static String getCurrentCacheScopeId() { return currentWorldId == null ? "global" : currentWorldId; }
    public static Path getConfigDir() { return modConfigDir; }
    public static boolean isCacheServerShareEnabled() { return cacheServerShareEnabled; }
    public static void setCacheServerShareEnabled(boolean enabled) {
        cacheServerShareEnabled = enabled;
        saveCacheSettings();
    }
    public static void onSharedTranslationCacheImported() {
        resetTranslationRuntime("shared-cache-import");
    }
    public static void onGlobalTranslationSettingChanged(boolean enabled) {
        if (engine != null) {
            engine.setFeatureEnabled("global", enabled);
        }
        resetTranslationRuntime("global-translation:" + (enabled ? "enabled" : "disabled"));
    }
    public static void onTranslationSettingsChanged() {
        resetTranslationRuntime("translation-settings");
    }

    /**
     * Invalidates every in-memory surface that can otherwise publish work from
     * the previous API/language/mode revision. Persistent Component-JSON cache
     * entries are intentionally retained; their keys already include the
     * relevant request identity.
     */
    private static synchronized void resetTranslationRuntime(String reason) {
        runtimeRevision++;
        if (engine != null) engine.resetRuntimeState();
        ChatTranslationController.resetForSettings();
        TranslationLanes.clearAll();
        BookTranslationSession.clear();
        GuiTranslationController.clearRuntimeState();
        HudTranslationController.resetForSettings();
        ScoreboardTranslationController.clear();
        SignContextSelectionManager.clearRuntimeState();
        TooltipTranslationGlowRenderer.clear();
        OutgoingChatTranslator.clear();
        TokenUsageMonitor.clear();
        LOGGER.debug("Reset SimpleTranslate runtime state: {} (revision={})", reason, runtimeRevision);
    }

    private static Path cacheSettingsFile() {
        String scope = currentWorldId == null || currentWorldId.trim().isEmpty() ? "global" : currentWorldId;
        return modConfigDir == null ? null : modConfigDir.resolve("cache").resolve(scope)
                .resolve("cache_settings.json");
    }

    public static void onClientWorldJoined() {
        Minecraft minecraft = Minecraft.getMinecraft();
        String id = null;
        ServerData server = minecraft.getCurrentServerData();
        if (server != null && server.serverIP != null) {
            id = sanitizeScope("server_" + server.serverIP);
        } else if (minecraft.getIntegratedServer() != null) {
            String legacyId = sanitizeScope("local_" + minecraft.getIntegratedServer().getWorldName());
            id = sanitizeScope("local_" + minecraft.getIntegratedServer().getFolderName());
            if (engine != null) engine.migrateLegacyScope(legacyId, id);
        }
        currentWorldId = id;
        runtimeRevision++;
        if (engine != null) engine.switchCacheScope(id);
        loadCacheSettings();
    }

    public static void onClientWorldDisconnected() {
        currentWorldId = null;
        runtimeRevision++;
        if (engine != null) engine.switchCacheScope(null);
        loadCacheSettings();
    }

    private static String sanitizeScope(String value) {
        return value == null ? null : value.replaceAll("[^a-zA-Z0-9._-]", "_")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static void loadCacheSettings() {
        cacheServerShareEnabled = false;
        Path file = cacheSettingsFile();
        if (file == null || !Files.exists(file)) return;
        try {
            JsonObject object = new JsonParser().parse(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
            if (object.has("serverShareEnabled")) {
                cacheServerShareEnabled = object.get("serverShareEnabled").getAsBoolean();
            }
        } catch (Exception ignored) {
            cacheServerShareEnabled = false;
        }
    }

    private static void saveCacheSettings() {
        Path file = cacheSettingsFile();
        if (file == null) return;
        try {
            JsonObject object = new JsonObject();
            object.addProperty("serverShareEnabled", cacheServerShareEnabled);
            AtomicFiles.writeString(file, object.toString());
        } catch (Exception ignored) {
        }
    }
}
