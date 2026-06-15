package com.yourname.simpletranslate;

import com.mojang.logging.LogUtils;
import com.yourname.simpletranslate.cache.TermDictionary;
import com.yourname.simpletranslate.cache.TranslationBlacklist;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.network.SharedCacheNetworking;
import com.yourname.simpletranslate.network.SharedCacheServer;
import com.yourname.simpletranslate.translation.TranslationManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.nio.file.Path;

@Mod(SimpleTranslateMod.MODID)
public final class SimpleTranslateMod {
    public static final String MODID = "simple_translate";
    private static final Logger LOGGER = LogUtils.getLogger();

    public SimpleTranslateMod(IEventBus modEventBus) {
        SharedCacheNetworking.register(modEventBus);
        SharedCacheServer.register();
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            SimpleTranslateClientBootstrap.initialize(modEventBus);
        }
        LOGGER.info("Simple Translate NeoForge mod initialized");
    }

    public static TranslationCache getTranslationCache() {
        return SimpleTranslateClientBootstrap.getTranslationCache();
    }

    public static TermDictionary getTermDictionary() {
        return SimpleTranslateClientBootstrap.getTermDictionary();
    }

    public static TranslationBlacklist getTranslationBlacklist() {
        return SimpleTranslateClientBootstrap.getTranslationBlacklist();
    }

    public static TranslationManager getTranslationManager() {
        return SimpleTranslateClientBootstrap.getTranslationManager();
    }

    public static Logger getLogger() {
        return LOGGER;
    }

    public static String getCurrentWorldId() {
        return SimpleTranslateClientBootstrap.getCurrentWorldId();
    }

    public static String getCurrentCacheScopeId() {
        return SimpleTranslateClientBootstrap.getCurrentCacheScopeId();
    }

    public static boolean isCacheServerShareEnabled() {
        return SimpleTranslateClientBootstrap.isCacheServerShareEnabled();
    }

    public static void setCacheServerShareEnabled(boolean enabled) {
        SimpleTranslateClientBootstrap.setCacheServerShareEnabled(enabled);
    }

    public static long getRuntimeRevision() {
        return SimpleTranslateClientBootstrap.getRuntimeRevision();
    }

    public static boolean isRuntimeRevisionCurrent(long revision) {
        return SimpleTranslateClientBootstrap.isRuntimeRevisionCurrent(revision);
    }

    public static Path getConfigDir() {
        return SimpleTranslateClientBootstrap.getConfigDir();
    }

    public static void onTranslationBlacklistChanged() {
        SimpleTranslateClientBootstrap.onTranslationBlacklistChanged();
    }

    public static void onLanguageSettingsChanged() {
        SimpleTranslateClientBootstrap.onLanguageSettingsChanged();
    }

    public static void onStyleSettingsChanged() {
        SimpleTranslateClientBootstrap.onStyleSettingsChanged();
    }

    public static void onTranslationCacheEdited() {
        SimpleTranslateClientBootstrap.onTranslationCacheEdited();
    }

    public static void onSharedTranslationCacheImported() {
        SimpleTranslateClientBootstrap.onSharedTranslationCacheImported();
    }

    public static long getBlacklistRevision() {
        return SimpleTranslateClientBootstrap.getBlacklistRevision();
    }
}
