package com.yourname.simpletranslate;

import com.mojang.logging.LogUtils;
import com.yourname.simpletranslate.cache.LineTranslationMemory;
import com.yourname.simpletranslate.cache.SharedCacheNetworking;
import com.yourname.simpletranslate.cache.SharedCacheServer;
import com.yourname.simpletranslate.cache.TermDictionary;
import com.yourname.simpletranslate.cache.TranslationBlacklist;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.transport.TranslationManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * NeoForge entrypoint. This class is loaded on both physical sides, so it
 * must never reference net.minecraft.client.* types: every client lifecycle
 * hook and all client runtime state live in
 * {@link SimpleTranslateClientBootstrap}, which is touched only behind the
 * physical-side guard below.
 */
@Mod(SimpleTranslateMod.MODID)
public final class SimpleTranslateMod {
    public static final String MODID = "simple_translate";
    private static final Logger LOGGER = LogUtils.getLogger();

    public SimpleTranslateMod(IEventBus modEventBus) {
        SharedCacheNetworking.registerPayloadTypes();
        SharedCacheServer.register();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            SimpleTranslateClientBootstrap.initialize(modEventBus);
        }
        LOGGER.info("Simple Translate NeoForge mod initialized");
    }

    public static TranslationCache getTranslationCache() { return SimpleTranslateClientBootstrap.getTranslationCache(); }
    public static LineTranslationMemory getLineTranslationMemory() { return SimpleTranslateClientBootstrap.getLineTranslationMemory(); }
    public static TermDictionary getTermDictionary() { return SimpleTranslateClientBootstrap.getTermDictionary(); }
    public static TranslationBlacklist getTranslationBlacklist() { return SimpleTranslateClientBootstrap.getTranslationBlacklist(); }
    public static TranslationManager getTranslationManager() { return SimpleTranslateClientBootstrap.getTranslationManager(); }
    public static Logger getLogger() { return LOGGER; }
    public static String getCurrentWorldId() { return SimpleTranslateClientBootstrap.getCurrentWorldId(); }
    public static String getCurrentCacheScopeId() { return SimpleTranslateClientBootstrap.getCurrentCacheScopeId(); }
    public static boolean isCacheServerShareEnabled() { return SimpleTranslateClientBootstrap.isCacheServerShareEnabled(); }
    public static void setCacheServerShareEnabled(boolean enabled) { SimpleTranslateClientBootstrap.setCacheServerShareEnabled(enabled); }
    public static long getRuntimeRevision() { return SimpleTranslateClientBootstrap.getRuntimeRevision(); }
    public static boolean isRuntimeRevisionCurrent(long revision) { return SimpleTranslateClientBootstrap.isRuntimeRevisionCurrent(revision); }
    public static Path getConfigDir() { return SimpleTranslateClientBootstrap.getConfigDir(); }
    public static void onTranslationBlacklistChanged() { SimpleTranslateClientBootstrap.onTranslationBlacklistChanged(); }
    public static void onLanguageSettingsChanged() { SimpleTranslateClientBootstrap.onLanguageSettingsChanged(); }
    public static void onTranslationCacheEdited() { SimpleTranslateClientBootstrap.onTranslationCacheEdited(); }
    public static void onTranslationProfileChanged() { SimpleTranslateClientBootstrap.onTranslationProfileChanged(); }
    public static void onTextContextSettingsChanged() { SimpleTranslateClientBootstrap.onTextContextSettingsChanged(); }
    public static void onTermDictionaryChanged() { SimpleTranslateClientBootstrap.onTermDictionaryChanged(); }
    public static void onGlobalTranslationSettingChanged(boolean enabled) { SimpleTranslateClientBootstrap.onGlobalTranslationSettingChanged(enabled); }
    public static void onSharedTranslationCacheImported() { SimpleTranslateClientBootstrap.onSharedTranslationCacheImported(); }
    public static long getBlacklistRevision() { return SimpleTranslateClientBootstrap.getBlacklistRevision(); }
}
