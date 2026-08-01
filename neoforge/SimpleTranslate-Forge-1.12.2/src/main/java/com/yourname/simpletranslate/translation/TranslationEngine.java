package com.yourname.simpletranslate.translation;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.api.TranslationRequest;
import com.yourname.simpletranslate.api.TranslationResult;
import com.yourname.simpletranslate.api.TranslationService;
import com.yourname.simpletranslate.api.TokenUsage;
import com.yourname.simpletranslate.api.TranslationDiagnostics;
import com.yourname.simpletranslate.cache.TermDictionary;
import com.yourname.simpletranslate.cache.TranslationBlacklist;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.cache.LineTranslationMemory;
import com.yourname.simpletranslate.cache.SharedCacheEntry;
import com.yourname.simpletranslate.config.TranslationProfileManager;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.Surface;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationGlowRenderer;
import com.yourname.simpletranslate.feature.tooltip.TooltipRequestTriggerState;
import com.yourname.simpletranslate.core.TextContextMemory;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.ComponentTranslationResult;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import com.yourname.simpletranslate.core.DynamicTextTemplate;
import com.yourname.simpletranslate.transport.TokenUsageMonitor;
import com.yourname.simpletranslate.transport.JsonPassthroughPrompts;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Java-8 implementation of the product Component-JSON pipeline for 1.12.2.
 *
 * <p>Every game-text request is a top-level JSON
 * array of Minecraft components and every cached value is marked
 * {@code component_json_v1} in the {@code stx2} store.</p>
 */
public final class TranslationEngine implements TranslationService {
    public static final String CACHE_NAMESPACE = "stx2";
    public static final String COMPONENT_JSON_FORMAT = "component_json_v1";
    private static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final int MIN_REQUEST_WORKERS = 1;
    private static final int MAX_REQUEST_WORKERS = 8;
    private static final String[] TEXT_CONTEXT_SCOPES = {
            "received_chat", "sent_chat", "item_tooltip", "hover_tooltip", "book", "sign",
            "hud_captions", "hud_progress", "entity_name"
    };

    private final File configFile;
    private final File dataRoot;
    private final TranslationBlacklist translationBlacklist;
    private TermDictionary termDictionary;
    private LineTranslationMemory lineTranslationMemory;
    private TranslationCache translationCache;
    private final TranslationRequestQueue requestQueue = new TranslationRequestQueue();
    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(
            MAX_REQUEST_WORKERS, new ThreadFactory() {
        private final AtomicInteger nextId = new AtomicInteger(1);
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "SimpleTranslate-1.12.2-HTTP-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    });
    /** Read only during one-time migration from the retired properties file. */
    private final Map<String, Boolean> legacyFeatureEnabled = new HashMap<String, Boolean>();
    private final Map<String, Boolean> legacyTextContextScopeEnabled = new HashMap<String, Boolean>();
    private String endpoint = DEFAULT_ENDPOINT;
    private String apiKey = "";
    private String model = DEFAULT_MODEL;
    private String sourceLanguage = "auto";
    private String targetLanguage = "Chinese";
    private int maxParallelRequests = 5;
    /** Baseline default is BUTTON; AUTO retains the collect-window path. */
    private boolean chatAutoMode;
    private boolean holdOriginalEnabled;
    private boolean manualTooltipMode;
    private boolean outgoingChatEnabled;
    private boolean tooltipGlowEnabled;
    private boolean tokenMonitorEnabled;
    private boolean textContextEnabled = true;
    private int textContextMessageCount = 6;
    private volatile String lastRequestFailure = "";

    public TranslationEngine(File configFile) {
        this.configFile = configFile;
        File parent = configFile.getParentFile();
        this.dataRoot = new File(parent, "simple_translate");
        migrateLegacyDataFile(new File(parent, "simple_translate-blacklist.json"), new File(dataRoot, "blacklist.json"));
        this.translationBlacklist = new TranslationBlacklist(new File(dataRoot, "blacklist.json"));
        this.termDictionary = termsForScope("global");
        this.lineTranslationMemory = lineMemoryForScope("global");
        migrateLegacyDataFile(new File(new File(new File(dataRoot, "cache"), "global"), "translations.json"),
                new File(dataRoot, "cache.json"));
        this.translationCache = cacheForScope("global");
        TranslationProfileManager.init(dataRoot);
        load();
        applyRequestWorkerCount();
        translationCache.load();
        translationBlacklist.load();
        termDictionary.load();
        lineTranslationMemory.load();
    }

    public synchronized boolean isConfigured() {
        return ModConfig.API_FORMAT.get() == ModConfig.ApiFormat.LOCAL_OLLAMA
                ? !endpoint.trim().isEmpty() : !apiKey.trim().isEmpty();
    }

    public synchronized String getEndpoint() { return endpoint; }
    public synchronized String getApiKey() { return apiKey; }
    public synchronized String getModel() { return model; }
    public synchronized String getSourceLanguage() { return sourceLanguage; }
    public synchronized String getTargetLanguage() { return targetLanguage; }
    public synchronized int getMaxParallelRequests() { return maxParallelRequests; }
    public synchronized boolean isChatAutoMode() { return ModConfig.CHAT_MODE.get() == ModConfig.TranslationMode.AUTO; }
    public synchronized void setChatAutoMode(boolean enabled) {
        ModConfig.CHAT_MODE.set(enabled ? ModConfig.TranslationMode.AUTO : ModConfig.TranslationMode.BUTTON);
        ModConfig.save();
    }
    public synchronized boolean isHoldOriginalEnabled() { return ModConfig.HOLD_ORIGINAL_ENABLED.get(); }
    public synchronized void setHoldOriginalEnabled(boolean enabled) {
        ModConfig.HOLD_ORIGINAL_ENABLED.set(enabled);
        ModConfig.save();
    }
    public synchronized boolean isManualTooltipMode() {
        return ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get() == ModConfig.TooltipTriggerMode.SHORTCUT;
    }
    public synchronized void setManualTooltipMode(boolean enabled) {
        ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.set(enabled
                ? ModConfig.TooltipTriggerMode.SHORTCUT : ModConfig.TooltipTriggerMode.HOVER);
        if (!enabled) TooltipRequestTriggerState.clear();
        ModConfig.save();
    }
    public void requestManualTooltip() { TooltipRequestTriggerState.armShortcutRequest(); }
    public synchronized boolean isOutgoingChatEnabled() { return ModConfig.CHAT_OUTGOING_ENABLED.get(); }
    public synchronized void setOutgoingChatEnabled(boolean enabled) {
        ModConfig.CHAT_OUTGOING_ENABLED.set(enabled); ModConfig.save();
    }
    public synchronized boolean isTooltipGlowEnabled() { return ModConfig.TOOLTIP_GLOW_ENABLED.get(); }
    public synchronized void setTooltipGlowEnabled(boolean enabled) {
        ModConfig.TOOLTIP_GLOW_ENABLED.set(enabled); ModConfig.save();
    }
    public synchronized boolean isTokenMonitorEnabled() { return ModConfig.TOKEN_MONITOR_ENABLED.get(); }
    public synchronized void setTokenMonitorEnabled(boolean enabled) {
        ModConfig.TOKEN_MONITOR_ENABLED.set(enabled); ModConfig.save();
    }
    public synchronized boolean isTextContextEnabled() { return ModConfig.API_TEXT_CONTEXT_ENABLED.get(); }
    public synchronized void setTextContextEnabled(boolean enabled) {
        ModConfig.API_TEXT_CONTEXT_ENABLED.set(enabled); TextContextMemory.settingsChanged(); ModConfig.save();
    }
    public synchronized int getTextContextMessageCount() { return ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get(); }
    public synchronized void setTextContextMessageCount(int count) {
        ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.set(Math.max(0, Math.min(20, count)));
        ModConfig.save();
    }
    public synchronized boolean isTextContextScopeEnabled(String scope) {
        String normalized = normalizeContextScope(scope);
        if ("received_chat".equals(normalized)) return ModConfig.API_TEXT_CONTEXT_RECEIVED_CHAT.get();
        if ("sent_chat".equals(normalized)) return ModConfig.API_TEXT_CONTEXT_SENT_CHAT.get();
        if ("item_tooltip".equals(normalized)) return ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP.get();
        if ("hover_tooltip".equals(normalized)) return ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP.get();
        if ("book".equals(normalized)) return ModConfig.API_TEXT_CONTEXT_BOOK.get();
        if ("sign".equals(normalized)) return ModConfig.API_TEXT_CONTEXT_SIGN.get();
        if ("hud_captions".equals(normalized)) return ModConfig.API_TEXT_CONTEXT_HUD_CAPTIONS.get();
        if ("hud_progress".equals(normalized)) return ModConfig.API_TEXT_CONTEXT_HUD_PROGRESS.get();
        if ("entity_name".equals(normalized)) return ModConfig.API_TEXT_CONTEXT_ENTITY_NAME.get();
        return true;
    }
    public synchronized void setTextContextScopeEnabled(String scope, boolean enabled) {
        String normalized = normalizeContextScope(scope);
        if ("received_chat".equals(normalized)) ModConfig.API_TEXT_CONTEXT_RECEIVED_CHAT.set(enabled);
        else if ("sent_chat".equals(normalized)) ModConfig.API_TEXT_CONTEXT_SENT_CHAT.set(enabled);
        else if ("item_tooltip".equals(normalized)) ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP.set(enabled);
        else if ("hover_tooltip".equals(normalized)) ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP.set(enabled);
        else if ("book".equals(normalized)) ModConfig.API_TEXT_CONTEXT_BOOK.set(enabled);
        else if ("sign".equals(normalized)) ModConfig.API_TEXT_CONTEXT_SIGN.set(enabled);
        else if ("hud_captions".equals(normalized)) ModConfig.API_TEXT_CONTEXT_HUD_CAPTIONS.set(enabled);
        else if ("hud_progress".equals(normalized)) ModConfig.API_TEXT_CONTEXT_HUD_PROGRESS.set(enabled);
        else if ("entity_name".equals(normalized)) ModConfig.API_TEXT_CONTEXT_ENTITY_NAME.set(enabled);
        TextContextMemory.settingsChanged();
        ModConfig.save();
    }

    /** A disabled scope neither contributes examples nor receives them in its prompt. */
    public synchronized boolean isTextContextAllowedForSurface(String surface) {
        return ModConfig.API_TEXT_CONTEXT_ENABLED.get() && isTextContextScopeEnabled(contextScopeForSurface(surface));
    }

    public static String[] textContextScopes() {
        return TEXT_CONTEXT_SCOPES.clone();
    }

    /** Bound the Java-8 HTTP executor exactly as the baseline request setting does. */
    public synchronized void setMaxParallelRequests(int workers) {
        maxParallelRequests = clampWorkers(workers);
        ModConfig.API_MAX_PARALLEL_REQUESTS.set(maxParallelRequests);
        applyRequestWorkerCount();
        ModConfig.save();
    }
    public TranslationBlacklist getTranslationBlacklist() { return translationBlacklist; }
    public TermDictionary getTermDictionary() { return termDictionary; }
    public TranslationCache getTranslationCache() { return translationCache; }
    public LineTranslationMemory getLineTranslationMemory() { return lineTranslationMemory; }

    public synchronized void switchCacheScope(String scopeId) {
        String scope = scopeId == null || scopeId.trim().isEmpty() ? "global" : scopeId;
        if (translationCache != null) translationCache.flush();
        if (lineTranslationMemory != null) lineTranslationMemory.flush();
        if (termDictionary != null) termDictionary.save();
        translationCache = cacheForScope(scope);
        translationCache.load();
        lineTranslationMemory = lineMemoryForScope(scope);
        lineTranslationMemory.load();
        termDictionary = termsForScope(scope);
        termDictionary.load();
        requestQueue.clear();
        TextContextMemory.clear();
    }

    public synchronized void migrateLegacyScope(String legacyScopeId, String targetScopeId) {
        String legacy = legacyScopeId == null ? "" : legacyScopeId.trim();
        String target = targetScopeId == null ? "" : targetScopeId.trim();
        if (legacy.isEmpty() || target.isEmpty() || legacy.equals(target)) return;
        File legacyCacheDir = new File(new File(dataRoot, "cache"), legacy);
        if (!legacyCacheDir.isDirectory()) return;
        TranslationCache targetCache = cacheForScope(target);
        targetCache.load();
        File legacyTranslations = new File(legacyCacheDir, "translations.json");
        if (legacyTranslations.isFile()) {
            try { targetCache.importFromFile(legacyTranslations.toPath(), true); }
            catch (java.io.IOException error) {
                SimpleTranslateForge1122.getLogger().warn("Failed to migrate legacy cache scope {} to {}", legacy, target, error);
            }
        }
        targetCache.flush();
        LineTranslationMemory targetMemory = lineMemoryForScope(target);
        targetMemory.load();
        targetMemory.mergeFrom(new File(legacyCacheDir, "line_memory.json").toPath());
        targetMemory.flush();
        File legacyTerms = new File(new File(new File(dataRoot, "terms"), legacy), "terms.json");
        File targetTerms = new File(new File(new File(dataRoot, "terms"), target), "terms.json");
        migrateLegacyDataFile(legacyTerms, targetTerms);
    }

    private TranslationCache cacheForScope(String scope) {
        if ("global".equals(scope)) return new TranslationCache(new File(dataRoot, "cache.json").toPath());
        return new TranslationCache(new File(new File(new File(dataRoot, "cache"), scope), "translations.json").toPath());
    }

    private LineTranslationMemory lineMemoryForScope(String scope) {
        if ("global".equals(scope)) return new LineTranslationMemory(new File(dataRoot, "line_memory.json").toPath());
        return new LineTranslationMemory(new File(new File(new File(dataRoot, "cache"), scope), "line_memory.json").toPath());
    }

    private TermDictionary termsForScope(String scope) {
        File target = new File(new File(new File(dataRoot, "terms"), scope), "terms.json");
        if ("global".equals(scope)) {
            migrateLegacyDataFile(new File(configFile.getParentFile(), "simple_translate-terms.json"), target);
            migrateLegacyDataFile(new File(dataRoot, "terms.json"), target);
        }
        return new TermDictionary(target);
    }

    private static void migrateLegacyDataFile(File source, File target) {
        if (source == null || target == null || target.exists() || !source.isFile()) return;
        try {
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            java.nio.file.Files.copy(source.toPath(), target.toPath());
        } catch (Exception ignored) {
            // Legacy data is left untouched and can be retried on the next startup.
        }
    }

    /** Shared imports are additive and intentionally never change a local cache value. */
    public synchronized void importSharedCacheEntries(Map<String, String> incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        boolean changed = false;
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            if (isShareableComponentEntry(entry.getKey(), entry.getValue())
                    && translationCache.putSharedIfAbsent(entry.getKey(), entry.getValue(), "", "",
                    false, System.currentTimeMillis(), 0L)) {
                changed = true;
            }
        }
        if (changed && isFeatureEnabled("cache")) translationCache.save();
    }

    public synchronized int importSharedCacheEntries(List<SharedCacheEntry> incoming) {
        if (incoming == null || incoming.isEmpty()) return 0;
        int imported = 0;
        for (SharedCacheEntry entry : incoming) {
            if (entry != null && translationCache.putSharedIfAbsent(entry.key(), entry.translation(),
                    entry.sourceText(), entry.translationText(), entry.editedByPlayer(),
                    entry.createdAt(), entry.editedAt())) {
                imported++;
            }
        }
        if (imported > 0 && isFeatureEnabled("cache")) translationCache.save();
        return imported;
    }

    /** Imported shared values never re-upload unless a local translation later replaces them. */
    public synchronized Map<String, String> snapshotLocalCacheEntries() {
        Map<String, String> result = new HashMap<String, String>();
        for (TranslationCache.CacheViewEntry entry : translationCache.getEntries().values()) {
            if (!entry.sharedImported() && isShareableComponentEntry(entry.key(), entry.translation())) {
                result.put(entry.key(), entry.translation());
            }
        }
        return result;
    }

    public boolean isBlacklisted(ITextComponent component) {
        return component != null && translationBlacklist.contains(component.getUnformattedText());
    }

    public boolean containsBlacklistedText(String text) {
        return translationBlacklist != null && translationBlacklist.containsBlacklistedEntry(text);
    }

    public synchronized boolean isFeatureEnabled(String feature) {
        if ("global".equals(feature)) return ModConfig.GLOBAL_ENABLED.get();
        if ("chat".equals(feature)) return ModConfig.CHAT_ENABLED.get();
        if ("tooltip_item".equals(feature)) return ModConfig.TOOLTIP_ITEM_ENABLED.get();
        if ("tooltip_hover".equals(feature)) return ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get();
        if ("book".equals(feature)) return ModConfig.CONTENT_BOOK_ENABLED.get();
        if ("sign".equals(feature)) return ModConfig.CONTENT_SIGN_ENABLED.get();
        if ("advancement".equals(feature)) return ModConfig.CONTENT_ADVANCEMENT_ENABLED.get();
        if ("entity_name".equals(feature)) return ModConfig.CONTENT_ENTITY_NAME_ENABLED.get();
        if ("hud_scoreboard".equals(feature)) return ModConfig.HUD_SCOREBOARD_ENABLED.get();
        if ("hud_bossbar".equals(feature)) return ModConfig.HUD_BOSSBAR_ENABLED.get();
        if ("hud_title".equals(feature)) return ModConfig.HUD_TITLE_ENABLED.get();
        if ("hud_actionbar".equals(feature)) return ModConfig.HUD_ACTIONBAR_ENABLED.get();
        if ("ftb".equals(feature)) return ModConfig.MOD_TRANSLATION_ENABLED.get() && ModConfig.MOD_FTB_QUESTS_ENABLED.get();
        if ("tips".equals(feature)) return ModConfig.MOD_TRANSLATION_ENABLED.get() && ModConfig.MOD_TIPS_ENABLED.get();
        if ("gui".equals(feature)) return ModConfig.CONTENT_GUI_ENABLED.get();
        if ("cache".equals(feature)) return ModConfig.CACHE_ENABLED.get();
        return true;
    }

    public synchronized void setFeatureEnabled(String feature, boolean enabled) {
        if (feature == null || feature.trim().isEmpty()) return;
        if ("global".equals(feature)) ModConfig.GLOBAL_ENABLED.set(enabled);
        else if ("chat".equals(feature)) ModConfig.CHAT_ENABLED.set(enabled);
        else if ("tooltip_item".equals(feature)) ModConfig.TOOLTIP_ITEM_ENABLED.set(enabled);
        else if ("tooltip_hover".equals(feature)) ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.set(enabled);
        else if ("book".equals(feature)) ModConfig.CONTENT_BOOK_ENABLED.set(enabled);
        else if ("sign".equals(feature)) ModConfig.CONTENT_SIGN_ENABLED.set(enabled);
        else if ("advancement".equals(feature)) ModConfig.CONTENT_ADVANCEMENT_ENABLED.set(enabled);
        else if ("entity_name".equals(feature)) ModConfig.CONTENT_ENTITY_NAME_ENABLED.set(enabled);
        else if ("hud_scoreboard".equals(feature)) ModConfig.HUD_SCOREBOARD_ENABLED.set(enabled);
        else if ("hud_bossbar".equals(feature)) ModConfig.HUD_BOSSBAR_ENABLED.set(enabled);
        else if ("hud_title".equals(feature)) ModConfig.HUD_TITLE_ENABLED.set(enabled);
        else if ("hud_actionbar".equals(feature)) ModConfig.HUD_ACTIONBAR_ENABLED.set(enabled);
        else if ("ftb".equals(feature)) ModConfig.MOD_FTB_QUESTS_ENABLED.set(enabled);
        else if ("tips".equals(feature)) ModConfig.MOD_TIPS_ENABLED.set(enabled);
        else if ("gui".equals(feature)) ModConfig.CONTENT_GUI_ENABLED.set(enabled);
        else if ("cache".equals(feature)) ModConfig.CACHE_ENABLED.set(enabled);
        ModConfig.save();
    }

    /** Clears only this mod's Component-JSON cache; configuration is retained. */
    public synchronized void clearCache() {
        translationCache.clear();
        requestQueue.clear();
        TextContextMemory.clear();
        translationCache.save();
    }

    /** Clears non-persistent request/session state when the current world ends. */
    public void resetRuntimeState() {
        requestQueue.clear();
        synchronized (this) { TextContextMemory.clear(); }
        TooltipRequestTriggerState.clear();
    }

    /** Flushes the categorized baseline cache on disconnect/shutdown. */
    public void flushCache() {
        translationCache.flush();
        if (lineTranslationMemory != null) lineTranslationMemory.flush();
        if (termDictionary != null) termDictionary.save();
    }

    public int cancelRequestSurfacePrefix(String surfacePrefix) {
        return requestQueue.cancelSurfacePrefix(surfacePrefix);
    }

    public String getRecentRequestErrorStatus() {
        return requestQueue.getRecentErrorStatus();
    }

    public void shutdown() {
        flushCache();
        requestQueue.shutdown();
        httpExecutor.shutdownNow();
    }

    /** All public translation routes converge here, so a disabled surface never queues HTTP work. */
    public synchronized boolean isSurfaceEnabled(String surface) {
        surface = Surface.normalize(surface);
        if (!ModConfig.GLOBAL_ENABLED.get() || !isFeatureEnabled("global")) return false;
        if (surface.startsWith("chat.outgoing")) return ModConfig.CHAT_OUTGOING_ENABLED.get();
        if (surface.startsWith("chat")) return ModConfig.CHAT_ENABLED.get() && isFeatureEnabled("chat");
        if (surface.startsWith("tooltip.visible.item")) {
            return ModConfig.TOOLTIP_ITEM_ENABLED.get() && isFeatureEnabled("tooltip_item");
        }
        if (surface.startsWith("tooltip.visible.chat_hover")) {
            return ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get() && isFeatureEnabled("tooltip_hover");
        }
        if (surface.startsWith("tooltip.visible.book_hover")) {
            return ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get();
        }
        if ("item_tooltip".equals(surface) || surface.startsWith("tooltip.item")) {
            return ModConfig.TOOLTIP_ITEM_ENABLED.get() && isFeatureEnabled("tooltip_item");
        }
        if ("hover_text".equals(surface) || surface.startsWith("hover")
                || surface.startsWith("tooltip.hover") || surface.startsWith("tooltip.chat")) {
            return ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get() && isFeatureEnabled("tooltip_hover");
        }
        if (surface.startsWith("tooltip.book")) {
            return ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get();
        }
        if (surface.startsWith("book")) return ModConfig.CONTENT_BOOK_ENABLED.get() && isFeatureEnabled("book");
        if (surface.startsWith("sign")) return ModConfig.CONTENT_SIGN_ENABLED.get() && isFeatureEnabled("sign");
        if (surface.startsWith("advancement")) return ModConfig.CONTENT_ADVANCEMENT_ENABLED.get() && isFeatureEnabled("advancement");
        if (surface.startsWith("entity")) return ModConfig.CONTENT_ENTITY_NAME_ENABLED.get() && isFeatureEnabled("entity_name");
        if (surface.startsWith("scoreboard") || surface.startsWith("player_tab")
                || "hud_scoreboard".equals(surface)) {
            return ModConfig.HUD_SCOREBOARD_ENABLED.get() && isFeatureEnabled("hud_scoreboard");
        }
        if (surface.startsWith("bossbar") || "hud_bossbar".equals(surface)) {
            return ModConfig.HUD_BOSSBAR_ENABLED.get() && isFeatureEnabled("hud_bossbar");
        }
        if (surface.startsWith("hud.title") || surface.startsWith("hud.subtitle")
                || surface.startsWith("title.")
                || surface.startsWith("hud_title") || surface.startsWith("hud_subtitle")) {
            return ModConfig.HUD_TITLE_ENABLED.get() && isFeatureEnabled("hud_title");
        }
        if (surface.startsWith("hud.actionbar") || surface.startsWith("actionbar.")
                || surface.startsWith("hud_actionbar")) {
            return ModConfig.HUD_ACTIONBAR_ENABLED.get() && isFeatureEnabled("hud_actionbar");
        }
        if (surface.startsWith("hud.captions")) {
            return (ModConfig.HUD_TITLE_ENABLED.get() || ModConfig.HUD_ACTIONBAR_ENABLED.get())
                    && (isFeatureEnabled("hud_title") || isFeatureEnabled("hud_actionbar"));
        }
        if (surface.startsWith("ftb") || surface.startsWith("gui.ftb")) return ModConfig.CONTENT_GUI_ENABLED.get()
                && ModConfig.MOD_TRANSLATION_ENABLED.get()
                && ModConfig.MOD_FTB_QUESTS_ENABLED.get() && isFeatureEnabled("ftb");
        if (surface.startsWith("tips")) return ModConfig.CONTENT_GUI_ENABLED.get()
                && ModConfig.MOD_TRANSLATION_ENABLED.get()
                && ModConfig.MOD_TIPS_ENABLED.get() && isFeatureEnabled("tips");
        if (surface.startsWith("gui")) return ModConfig.CONTENT_GUI_ENABLED.get() && isFeatureEnabled("gui");
        if ("hud".equals(surface)) return isFeatureEnabled("hud_overlay");
        return true;
    }

    public synchronized void updateConfiguration(String endpoint, String apiKey, String model) {
        updateConfiguration(endpoint, apiKey, model, sourceLanguage, targetLanguage);
    }

    public synchronized void updateConfiguration(String endpoint, String apiKey, String model, String targetLanguage) {
        updateConfiguration(endpoint, apiKey, model, sourceLanguage, targetLanguage);
    }

    /**
     * Stores both language ends because source-language choice changes a model
     * result just as materially as the destination.  Keeping it in the cache
     * identity prevents an explicit English source setting from reusing a
     * previous AUTO translation.
     */
    public synchronized void updateConfiguration(String endpoint, String apiKey, String model,
                                                 String sourceLanguage, String targetLanguage) {
        String updatedEndpoint = normalizeEndpoint(endpoint);
        String updatedApiKey = apiKey == null ? "" : apiKey.trim();
        String updatedModel = blankOr(model, DEFAULT_MODEL);
        String updatedSourceLanguage = blankOr(sourceLanguage, "auto");
        String updatedTargetLanguage = blankOr(targetLanguage, "Chinese");
        boolean requestIdentityChanged = !this.endpoint.equals(updatedEndpoint)
                || !this.apiKey.equals(updatedApiKey)
                || !this.model.equals(updatedModel)
                || !this.sourceLanguage.equals(updatedSourceLanguage)
                || !this.targetLanguage.equals(updatedTargetLanguage);
        this.endpoint = updatedEndpoint;
        this.apiKey = updatedApiKey;
        this.model = updatedModel;
        this.sourceLanguage = updatedSourceLanguage;
        this.targetLanguage = updatedTargetLanguage;
        ModConfig.DEEPSEEK_API_URL.set(updatedEndpoint);
        ModConfig.DEEPSEEK_API_KEY.set(updatedApiKey);
        ModConfig.DEEPSEEK_MODEL.set(updatedModel);
        ModConfig.SOURCE_LANGUAGE.set(updatedSourceLanguage);
        ModConfig.TARGET_LANGUAGE.set(updatedTargetLanguage);
        if (requestIdentityChanged) {
            // Live-saved request settings take effect immediately. Old work
            // must not keep a request lane occupied or publish a response
            // produced with credentials or languages that are no longer active.
            requestQueue.clear();
            SimpleTranslateForge1122.onTranslationSettingsChanged();
        }
        ModConfig.save();
    }

    public synchronized void applyModernConfiguration() {
        updateConfiguration(ModConfig.DEEPSEEK_API_URL.get(), ModConfig.DEEPSEEK_API_KEY.get(),
                ModConfig.normalizeModelId(ModConfig.DEEPSEEK_MODEL.get()),
                ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
        setMaxParallelRequests(ModConfig.API_MAX_PARALLEL_REQUESTS.get());
    }

    public synchronized void migrateLegacyConfigurationToModern() {
        ModConfig.DEEPSEEK_API_URL.set(endpoint);
        ModConfig.DEEPSEEK_API_KEY.set(apiKey);
        ModConfig.DEEPSEEK_MODEL.set(model);
        ModConfig.SOURCE_LANGUAGE.set(sourceLanguage);
        ModConfig.TARGET_LANGUAGE.set(targetLanguage);
        ModConfig.API_MAX_PARALLEL_REQUESTS.set(maxParallelRequests);
        ModConfig.CHAT_MODE.set(chatAutoMode ? ModConfig.TranslationMode.AUTO : ModConfig.TranslationMode.BUTTON);
        ModConfig.HOLD_ORIGINAL_ENABLED.set(holdOriginalEnabled);
        ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.set(manualTooltipMode
                ? ModConfig.TooltipTriggerMode.SHORTCUT : ModConfig.TooltipTriggerMode.HOVER);
        ModConfig.CHAT_OUTGOING_ENABLED.set(outgoingChatEnabled);
        ModConfig.TOOLTIP_GLOW_ENABLED.set(tooltipGlowEnabled);
        ModConfig.TOKEN_MONITOR_ENABLED.set(tokenMonitorEnabled);
        ModConfig.API_TEXT_CONTEXT_ENABLED.set(textContextEnabled);
        for (Map.Entry<String, Boolean> entry : legacyTextContextScopeEnabled.entrySet()) {
            applyLegacyContextScope(entry.getKey(), entry.getValue().booleanValue());
        }
        for (Map.Entry<String, Boolean> entry : legacyFeatureEnabled.entrySet()) {
            applyLegacyFeature(entry.getKey(), entry.getValue().booleanValue());
        }
        ModConfig.save();
    }

    /**
     * Sends one deliberately small Component-JSON document through the normal
     * model transport without touching cache, context, or game text.  The
     * settings UI uses this to verify the currently persisted credentials.
     */
    public CompletableFuture<ApiCheckResult> verifyApiAccess() {
        lastRequestFailure = "";
        final String endpointSnapshot;
        final String apiKeySnapshot;
        final String modelSnapshot;
        final String sourceSnapshot;
        final String targetSnapshot;
        synchronized (this) {
            endpointSnapshot = endpoint;
            apiKeySnapshot = apiKey;
            modelSnapshot = model;
            sourceSnapshot = sourceLanguage;
            targetSnapshot = targetLanguage;
        }
        if (apiKeySnapshot.trim().isEmpty()
                && ModConfig.API_FORMAT.get() != ModConfig.ApiFormat.LOCAL_OLLAMA) {
            return CompletableFuture.completedFuture(ApiCheckResult.failed("missing_api_key"));
        }
        if (modelSnapshot.trim().isEmpty()) {
            return CompletableFuture.completedFuture(ApiCheckResult.failed("missing_model"));
        }
        final String requestKey = "api_check\u0001" + sha256(endpointSnapshot + "\n" + apiKeySnapshot + "\n" + modelSnapshot);
        return requestQueue.submit(requestKey, "api_check",
                new Supplier<CompletableFuture<TranslationResult>>() {
            @Override public CompletableFuture<TranslationResult> get() {
                return executeHttpAsync(new HttpOperation<TranslationResult>() {
                    @Override public TranslationResult execute(
                            CancellableHttpFuture<TranslationResult> future) {
                        JsonArray document = new JsonArray();
                        JsonObject component = new JsonObject();
                        component.addProperty("text", "connection check");
                        document.add(component);
                        String response = requestComponentDocument(
                                document, "api_check", sourceSnapshot, targetSnapshot, future);
                        return response == null ? TranslationResult.failed("request_failed")
                                : TranslationResult.success(response);
                    }
                });
            }
        }).thenApply(new Function<TranslationResult, ApiCheckResult>() {
            @Override public ApiCheckResult apply(TranslationResult result) {
                if (result != null && result.isSuccess()) return ApiCheckResult.available();
                String detail = lastRequestFailure;
                if ((detail == null || detail.trim().isEmpty()) && result != null) {
                    detail = result.getFailureReason();
                }
                return ApiCheckResult.failed(detail == null || detail.trim().isEmpty()
                        ? "request_failed" : detail);
            }
        });
    }

    public static final class ApiCheckResult {
        private final boolean available;
        private final String status;

        private ApiCheckResult(boolean available, String status) {
            this.available = available;
            this.status = status;
        }

        public boolean isAvailable() { return available; }
        public String getStatus() { return status; }
        private static ApiCheckResult available() { return new ApiCheckResult(true, "available"); }
        private static ApiCheckResult failed(String status) { return new ApiCheckResult(false, status); }
    }

    /** Java-8 provider model discovery used by the complete model settings page. */
    public CompletableFuture<TranslationDiagnostics.ModelDetection> detectAvailableModels(
            final String suppliedApiKey, final String suppliedApiUrl, final ModConfig.ApiFormat format) {
        return executeHttpAsync(new HttpOperation<TranslationDiagnostics.ModelDetection>() {
            @Override public TranslationDiagnostics.ModelDetection execute(
                    CancellableHttpFuture<TranslationDiagnostics.ModelDetection> future) {
                String url = modelsEndpoint(suppliedApiUrl, format);
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    future.attach(connection);
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(15000);
                    applyDiagnosticAuth(connection, suppliedApiKey, format);
                    int status = connection.getResponseCode();
                    String body = readResponseBody(connection, status);
                    if (status < 200 || status >= 300) {
                        return new TranslationDiagnostics.ModelDetection(false, url, status,
                                Collections.<String>emptyList(), "HTTP " + status);
                    }
                    JsonElement parsed = new JsonParser().parse(body);
                    List<String> models = new ArrayList<String>();
                    if (parsed.isJsonObject()) {
                        JsonObject object = parsed.getAsJsonObject();
                        collectModelIds(object.get("data"), models);
                        collectModelIds(object.get("models"), models);
                    }
                    Collections.sort(models);
                    return new TranslationDiagnostics.ModelDetection(!models.isEmpty(), url, status, models,
                            models.isEmpty() ? "No models returned" : "available");
                } catch (Exception error) {
                    return new TranslationDiagnostics.ModelDetection(false, url, 0,
                            Collections.<String>emptyList(), safeDiagnosticMessage(error));
                } finally {
                    future.detach(connection);
                    if (connection != null) connection.disconnect();
                }
            }
        });
    }

    /** Sends the provider's smallest legal request without changing saved settings. */
    public CompletableFuture<TranslationDiagnostics.ModelAccess> verifyModelAccess(
            final String suppliedApiKey, final String suppliedApiUrl, final String suppliedModel,
            final ModConfig.ApiFormat format) {
        return executeHttpAsync(new HttpOperation<TranslationDiagnostics.ModelAccess>() {
            @Override public TranslationDiagnostics.ModelAccess execute(
                    CancellableHttpFuture<TranslationDiagnostics.ModelAccess> future) {
                String modelId = suppliedModel == null ? "" : suppliedModel.trim();
                if (modelId.isEmpty()) {
                    return new TranslationDiagnostics.ModelAccess(false, "", 0, "Model ID not configured");
                }
                HttpURLConnection connection = null;
                try {
                    String endpoint = diagnosticEndpoint(suppliedApiUrl, format, modelId, suppliedApiKey);
                    connection = (HttpURLConnection) new URL(endpoint).openConnection();
                    future.attach(connection);
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(15000);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    applyDiagnosticAuth(connection, suppliedApiKey, format);
                    byte[] body = diagnosticRequest(format, modelId).toString().getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(body.length);
                    OutputStream output = connection.getOutputStream();
                    output.write(body);
                    output.close();
                    int status = connection.getResponseCode();
                    readResponseBody(connection, status);
                    boolean success = status >= 200 && status < 300;
                    return new TranslationDiagnostics.ModelAccess(success, modelId, status,
                            success ? "available" : "HTTP " + status);
                } catch (Exception error) {
                    return new TranslationDiagnostics.ModelAccess(false, modelId, 0,
                            safeDiagnosticMessage(error));
                } finally {
                    future.detach(connection);
                    if (connection != null) connection.disconnect();
                }
            }
        });
    }

    /**
     * Returns a validated cached component or the original component. A miss is
     * queued asynchronously; it never performs HTTP work on the client thread.
     */
    public ITextComponent translateCachedOrEnqueue(ITextComponent original, String surface) {
        if (original == null) return null;
        if (HoldOriginalState.isHoldingSurface(surface) || !isSurfaceEnabled(surface) || isBlacklisted(original)) return original;
        DynamicTextTemplate template = dynamicSurface(surface) ? DynamicTextTemplate.capture(original) : null;
        ITextComponent requestSource = template != null && template.hasValues() && template.normalized() != null
                ? template.normalized() : original;
        ComponentTranslationResult result = DirectSurfaceTranslator.translateComponent(
                requestSource, Surface.normalize(surface), "game-text");
        if (result == null || result.component == null) return original;
        if (template != null && template.hasValues()) {
            ITextComponent restored = template.restore(result.component);
            return restored == null ? original : restored;
        }
        return result.component;
    }

    private static boolean dynamicSurface(String surface) {
        String value = Surface.normalize(surface);
        return value.startsWith("hud") || value.startsWith("title") || value.startsWith("actionbar")
                || value.startsWith("scoreboard") || value.startsWith("bossbar")
                || value.startsWith("player_tab");
    }

    /** Cache-only lookup used by collect-window surfaces before they batch a miss. */
    public ITextComponent getCachedComponent(ITextComponent original, String surface) {
        if (original == null || HoldOriginalState.isHoldingSurface(surface) || !isSurfaceEnabled(surface) || isBlacklisted(original)) return null;
        DynamicTextTemplate template = dynamicSurface(surface) ? DynamicTextTemplate.capture(original) : null;
        ITextComponent requestSource = template != null && template.hasValues() && template.normalized() != null
                ? template.normalized() : original;
        ComponentListTranslationResult result = DirectSurfaceTranslator.getCachedComponents(
                Collections.singletonList(requestSource), Surface.normalize(surface), "game-text",
                DirectSurfaceTranslator.isFixedLayoutSurface(surface), "");
        if (result == null || !result.translated || result.components == null
                || result.components.size() != 1) return null;
        ITextComponent translated = result.components.get(0);
        if (template != null && template.hasValues()) {
            ITextComponent restored = template.restore(translated);
            return restored == null ? null : restored;
        }
        return translated;
    }

    /**
     * Baseline-compatible SPI entry point. Every request item is an exact
     * 1.12.2 Component JSON document; both the wire payload and accepted
     * response are top-level arrays. There is no prose/string fallback.
     */
    @Override
    public CompletableFuture<TranslationResult> translate(final TranslationRequest request) {
        if (request == null || request.getComponentJson().isEmpty() || !isSurfaceEnabled(request.getSurface())) {
            return CompletableFuture.completedFuture(TranslationResult.failed("surface disabled or empty"));
        }
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(TranslationResult.failed("API key is not configured"));
        }
        String requestKey = sha256(request.getSurface() + "\u0001" + request.getSourceLanguage() + "\u0001"
                + request.getTargetLanguage() + "\u0001" + request.getComponentJson().toString());
        return requestQueue.submit(requestKey, request.getSurface(),
                new Supplier<CompletableFuture<TranslationResult>>() {
            @Override public CompletableFuture<TranslationResult> get() {
                return executeHttpAsync(new HttpOperation<TranslationResult>() {
                    @Override public TranslationResult execute(
                            CancellableHttpFuture<TranslationResult> future) {
                        try {
                            JsonArray document = new JsonArray();
                            JsonArray originalDocument = new JsonArray();
                            for (String source : request.getComponentJson()) {
                                JsonElement parsed = new JsonParser().parse(source);
                                ITextComponent component = ITextComponent.Serializer.jsonToComponent(parsed.toString());
                                if (isBlacklisted(component)) return TranslationResult.failed("blacklisted source");
                                originalDocument.add(copyJson(parsed));
                                document.add(stripHoverEvents(parsed));
                            }
                            String response = requestComponentDocument(document, request.getSurface(),
                                    request.getSourceLanguage(), request.getTargetLanguage(), request.terms(),
                                    request.promptContext(), request.maxTokenMultiplier(), future);
                            if (response == null) return TranslationResult.failed("invalid response or request failure");
                            JsonArray translated = new JsonParser().parse(response).getAsJsonArray();
                            for (int i = 0; i < translated.size(); i++) {
                                reattachHoverEvents(originalDocument.get(i), translated.get(i));
                                ITextComponent.Serializer.jsonToComponent(translated.get(i).toString());
                            }
                            return TranslationResult.success(translated.toString());
                        } catch (TranslationRequestQueue.RetryableRequestException retryable) {
                            throw retryable;
                        } catch (Exception ignored) {
                            return TranslationResult.failed("invalid component JSON");
                        }
                    }
                });
            }
        });
    }

    /**
     * Low-level provider entry used by the canonical JSON passthrough pipeline.
     * The provider's assistant text is returned verbatim so the pipeline owns
     * exact array/count/Component validation and its bounded structural retry.
     */
    public CompletableFuture<String> translateRawComponentDocument(
            final String componentDocument, final String surface,
            final int maxTokenMultiplier, final String sourceLanguage,
            final String targetLanguage, final String promptMetadata,
            final List<TranslationRequest.Term> termHints) {
        if (componentDocument == null || componentDocument.trim().isEmpty()
                || !isSurfaceEnabled(surface) || !isConfigured()) {
            return CompletableFuture.completedFuture(null);
        }
        final JsonArray document;
        try {
            JsonElement parsed = new JsonParser().parse(componentDocument);
            if (!parsed.isJsonArray()) return CompletableFuture.completedFuture(null);
            document = parsed.getAsJsonArray();
            for (JsonElement component : document) {
                ITextComponent.Serializer.jsonToComponent(component.toString());
            }
        } catch (Exception invalidDocument) {
            return CompletableFuture.completedFuture(null);
        }
        if (document.size() == 0) return CompletableFuture.completedFuture("[]");
        final String requestKey = "provider_raw\u0001" + sha256(surface + "\u0001"
                + sourceLanguage + "\u0001" + targetLanguage + "\u0001"
                + maxTokenMultiplier + "\u0001" + promptMetadata + "\u0001" + componentDocument);
        return requestQueue.submit(requestKey, surface,
                new Supplier<CompletableFuture<TranslationResult>>() {
            @Override public CompletableFuture<TranslationResult> get() {
                return executeHttpAsync(new HttpOperation<TranslationResult>() {
                    @Override public TranslationResult execute(
                            CancellableHttpFuture<TranslationResult> future) {
                        String response = requestComponentDocument(document, surface, sourceLanguage, targetLanguage,
                                termHints, promptMetadata, maxTokenMultiplier, false, true, future);
                        return response == null || response.trim().isEmpty()
                                ? TranslationResult.failed("blank provider response")
                                : TranslationResult.success(response);
                    }
                });
            }
        }).thenApply(new Function<TranslationResult, String>() {
            @Override public String apply(TranslationResult result) {
                return result != null && result.isSuccess() ? result.getComponentJsonArray() : null;
            }
        });
    }

    /**
     * Adapter for 1.12-only APIs that expose text as a raw String. The request
     * still goes through the Component-JSON protocol; this method never sends
     * or caches a plain-text document.
     */
    public String translateStringCachedOrEnqueue(String original, String surface) {
        if (original == null || original.isEmpty()) return original;
        ITextComponent translated = translateCachedOrEnqueue(new TextComponentString(original), surface);
        return translated == null ? original : translated.getUnformattedText();
    }

    /** Dedicated item-tooltip route; it never consumes hidden chat hover text. */
    public List<String> translateTooltipLines(List<String> original) {
        if (original == null || original.isEmpty()) return original;
        if (!isConfigured() || HoldOriginalState.isHoldingSurface("item_tooltip")
                || !isSurfaceEnabled("item_tooltip")) return original;
        final String surface = "tooltip.visible.item.component.v2";
        final String role = "visible-tooltip-component";
        final String reuseScope = buildTooltipReuseScope(original);
        List<String> translated = new ArrayList<String>(original);
        List<ITextComponent> visibleComponents = new ArrayList<ITextComponent>(original.size());
        List<ITextComponent> missingComponents = new ArrayList<ITextComponent>();
        List<String> missingSources = new ArrayList<String>();
        List<Integer> missingIndexes = new ArrayList<Integer>();
        for (int index = 0; index < original.size(); index++) {
            String line = original.get(index);
            ITextComponent component = new TextComponentString(line == null ? "" : line);
            visibleComponents.add(component);
            if (isBlacklisted(component)) return original;
            String remembered = lineTranslationMemory.lookupScoped(line, sourceLanguage, targetLanguage,
                    surface, role, reuseScope, ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.get());
            if (remembered != null) {
                translated.set(index, remembered);
            } else {
                missingComponents.add(component);
                missingSources.add(line == null ? "" : line);
                missingIndexes.add(Integer.valueOf(index));
            }
        }
        if (missingComponents.isEmpty()) return translated;
        final String requestSignature = TooltipRequestTriggerState.requestSignature(
                TooltipRequestTriggerState.Context.ITEM, visibleComponents);
        TooltipTranslationGlowRenderer.observe(requestSignature);
        StringBuilder context = new StringBuilder("Incremental Component request. Full ordered item tooltip:");
        for (int i = 0; i < original.size(); i++) context.append("\n").append(i + 1).append(": ").append(original.get(i));
        ComponentListTranslationResult result = DirectSurfaceTranslator.getCachedComponents(
                missingComponents, surface, role, false, context.toString());
        if (result == null || !result.translated) {
            if (!TooltipRequestTriggerState.allowRequest(
                    TooltipRequestTriggerState.Context.ITEM, visibleComponents)) return original;
            if (TooltipRequestTriggerState.beginRequest(requestSignature)) {
                final List<ITextComponent> requestComponents = new ArrayList<ITextComponent>(missingComponents);
                DirectSurfaceTranslator.translateComponentsAsync(
                        requestComponents, surface, role, false, context.toString())
                        .whenComplete(new java.util.function.BiConsumer<ComponentListTranslationResult, Throwable>() {
                            @Override public void accept(ComponentListTranslationResult asyncResult, Throwable error) {
                                boolean success = error == null && asyncResult != null && asyncResult.translated
                                        && asyncResult.components != null
                                        && asyncResult.components.size() == requestComponents.size();
                                TooltipRequestTriggerState.finishRequest(requestSignature, success);
                            }
                        });
            }
            return original;
        }
        if (result == null || !result.translated || result.components == null
                || result.components.size() != missingComponents.size()) return original;
        List<String> accepted = new ArrayList<String>(result.components.size());
        for (int i = 0; i < result.components.size(); i++) {
            String value = result.components.get(i).getFormattedText();
            accepted.add(value);
            translated.set(missingIndexes.get(i).intValue(), value);
        }
        lineTranslationMemory.recordScoped(missingSources, accepted, sourceLanguage, targetLanguage,
                surface, role, reuseScope, false);
        return translated;
    }

    private static String buildTooltipReuseScope(List<String> lines) {
        StringBuilder normalized = new StringBuilder();
        for (String line : lines) {
            String value = line == null ? "" : line.replaceAll("\\u00a7.", "")
                    .replaceAll("[-+]?\\d+(?:[.,:/-]\\d+)*", "<number>")
                    .replaceAll("\\s+", " ").trim();
            normalized.append(value).append('\n');
        }
        return TranslationCacheKeys.semanticHash(normalized.toString());
    }

    /**
     * Dedicated SHOW_TEXT hover path.  It deliberately receives the hover
     * component itself, rather than piggybacking on the visible chat/message
     * request: visible components strip hoverEvent payloads by contract.
     */
    public ITextComponent translateHoverTextCachedOrEnqueue(ITextComponent original) {
        if (original == null) return null;
        if (!isConfigured() || HoldOriginalState.isHoldingSurface("tooltip.hover") || !isSurfaceEnabled("hover_text")
                || isBlacklisted(original)) return original;
        final String surface = "tooltip.visible.chat_hover.component.v2";
        final String role = "hover-block";
        final List<ITextComponent> components = Collections.singletonList(original);
        final String requestSignature = TooltipRequestTriggerState.requestSignature(
                TooltipRequestTriggerState.Context.CHAT_HOVER, components);
        TooltipTranslationGlowRenderer.observe(requestSignature);
        ComponentListTranslationResult cached = DirectSurfaceTranslator.getCachedComponents(
                components, surface, role,
                DirectSurfaceTranslator.isFixedLayoutSurface(surface), "");
        if (cached != null && cached.translated && cached.components != null && cached.components.size() == 1) {
            return cached.components.get(0);
        }
        if (!TooltipRequestTriggerState.allowRequest(TooltipRequestTriggerState.Context.CHAT_HOVER,
                Collections.singletonList(original))) return original;
        if (TooltipRequestTriggerState.beginRequest(requestSignature)) {
            DirectSurfaceTranslator.translateComponentsAsync(components, surface, role, false, "")
                    .whenComplete(new java.util.function.BiConsumer<ComponentListTranslationResult, Throwable>() {
                        @Override public void accept(ComponentListTranslationResult asyncResult, Throwable error) {
                            TooltipRequestTriggerState.finishRequest(requestSignature,
                                    error == null && asyncResult != null && asyncResult.translated
                                            && asyncResult.components != null && asyncResult.components.size() == 1);
                        }
                    });
        }
        return original;
    }

    public ITextComponent translateBookHoverTextCachedOrEnqueue(ITextComponent original) {
        if (original == null || !isConfigured() || HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER)
                || !isSurfaceEnabled("tooltip.book") || isBlacklisted(original)) return original;
        final String surface = "tooltip.visible.book_hover.component.v2";
        final String role = "book-hover-block";
        final List<ITextComponent> components = Collections.singletonList(original);
        final String requestSignature = "BOOK:" + TooltipRequestTriggerState.requestSignature(
                TooltipRequestTriggerState.Context.CHAT_HOVER, components);
        TooltipTranslationGlowRenderer.observe(requestSignature);
        ComponentListTranslationResult cached = DirectSurfaceTranslator.getCachedComponents(
                components, surface, role, false, "");
        if (cached != null && cached.translated && cached.components != null && cached.components.size() == 1) {
            TooltipRequestTriggerState.finishRequest(requestSignature, true);
            return cached.components.get(0);
        }
        if (TooltipRequestTriggerState.beginRequest(requestSignature)) {
            DirectSurfaceTranslator.translateComponentsAsync(components, surface, role, false, "")
                    .whenComplete(new java.util.function.BiConsumer<ComponentListTranslationResult, Throwable>() {
                        @Override public void accept(ComponentListTranslationResult asyncResult, Throwable error) {
                            TooltipRequestTriggerState.finishRequest(requestSignature,
                                    error == null && asyncResult != null && asyncResult.translated
                                            && asyncResult.components != null && asyncResult.components.size() == 1);
                        }
                    });
        }
        return original;
    }

    /** Book NBT pages can be either plain strings or serialized ITextComponents. */
    public String translateBookPageCachedOrEnqueue(String serializedPage) {
        if (serializedPage == null || serializedPage.isEmpty()) return serializedPage;
        try {
            ITextComponent source = ITextComponent.Serializer.jsonToComponent(serializedPage);
            ITextComponent translated = translateCachedOrEnqueue(source, "book");
            return translated == source ? serializedPage : ITextComponent.Serializer.componentToJson(translated);
        } catch (Exception ignored) {
            return translateStringCachedOrEnqueue(serializedPage, "book");
        }
    }

    private String requestComponentDocument(JsonArray document, String surface,
                                            String sourceLanguage, String targetLanguage,
                                            CancellableHttpFuture<?> requestFuture)
            throws TranslationRequestQueue.RetryableRequestException {
        return requestComponentDocument(document, surface, sourceLanguage, targetLanguage,
                termDictionary.matchTermsInText(document == null ? "" : document.toString()), "", 1,
                requestFuture);
    }

    private String requestComponentDocument(JsonArray document, String surface,
                                            String sourceLanguage, String targetLanguage,
                                            List<TranslationRequest.Term> termHints,
                                            String explicitPromptContext, int maxTokenMultiplier,
                                            CancellableHttpFuture<?> requestFuture)
            throws TranslationRequestQueue.RetryableRequestException {
        return requestComponentDocument(document, surface, sourceLanguage, targetLanguage,
                termHints, explicitPromptContext, maxTokenMultiplier, true, false, requestFuture);
    }

    private String requestComponentDocument(JsonArray document, String surface,
                                            String sourceLanguage, String targetLanguage,
                                            List<TranslationRequest.Term> termHints,
                                            String explicitPromptContext, int maxTokenMultiplier,
                                            boolean validateComponentResponse,
                                            boolean promptContextIsMetadata,
                                            CancellableHttpFuture<?> requestFuture)
            throws TranslationRequestQueue.RetryableRequestException {
        String endpointSnapshot;
        String apiKeySnapshot;
        String modelSnapshot;
        String languageSnapshot;
        boolean contextEnabledSnapshot;
        boolean tokenMonitorSnapshot;
        ModConfig.ApiFormat apiFormatSnapshot = ModConfig.API_FORMAT.get();
        synchronized (this) {
            endpointSnapshot = endpoint;
            apiKeySnapshot = apiKey;
            modelSnapshot = model;
            languageSnapshot = targetLanguage;
            contextEnabledSnapshot = ModConfig.API_TEXT_CONTEXT_ENABLED.get();
            tokenMonitorSnapshot = ModConfig.TOKEN_MONITOR_ENABLED.get();
        }
        try {
            long requestStartedAt = System.currentTimeMillis();
            String destination = targetLanguage == null || targetLanguage.trim().isEmpty()
                    ? languageSnapshot : targetLanguage.trim();
            String source = sourceLanguage == null || sourceLanguage.trim().isEmpty()
                    ? "auto-detected language" : sourceLanguage.trim();
            String callerContext = explicitPromptContext == null ? "" : explicitPromptContext.trim();
            String promptContext;
            if (promptContextIsMetadata && !callerContext.isEmpty()) {
                promptContext = callerContext;
            } else {
                TextContextMemory.PromptMetadata metadata = TextContextMemory.buildPromptMetadata(
                        callerContext, surface, "game-text", document.toString(),
                        contextEnabledSnapshot && isTextContextAllowedForSurface(surface), source, destination);
                promptContext = metadata.json();
            }
            String systemPrompt = JsonPassthroughPrompts.buildSystemPrompt(
                    source, destination, termHints, surface, promptContext);
            int maxTokens = Math.max(512, Math.min(32768,
                    document.toString().length() * Math.max(1, maxTokenMultiplier) * 2));
            JsonObject request = buildTranslationProviderRequest(apiFormatSnapshot, endpointSnapshot,
                    modelSnapshot, systemPrompt, document.toString(), maxTokens);

            String requestEndpoint = diagnosticEndpoint(endpointSnapshot, apiFormatSnapshot,
                    modelSnapshot, apiKeySnapshot);
            HttpURLConnection connection = (HttpURLConnection) new URL(requestEndpoint).openConnection();
            try {
                if (requestFuture != null) requestFuture.attach(connection);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                applyDiagnosticAuth(connection, apiKeySnapshot, apiFormatSnapshot);
                byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                OutputStream output = connection.getOutputStream();
                output.write(body);
                output.close();
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    String bodyText = readResponseBody(connection, responseCode);
                    String safeBody = bodyText;
                    if (apiKeySnapshot != null && !apiKeySnapshot.isEmpty()) {
                        safeBody = safeBody.replace(apiKeySnapshot, "***");
                    }
                    SimpleTranslateForge1122.getLogger().warn(
                            "Translation provider rejected surface={} status={}{}",
                            surface, responseCode, compactDiagnosticBody(safeBody));
                    if ("api_check".equals(surface)) {
                        lastRequestFailure = "HTTP " + responseCode + compactDiagnosticBody(safeBody);
                    }
                    if (isRetryableHttpStatus(responseCode)) {
                        throw new TranslationRequestQueue.RetryableRequestException("HTTP " + responseCode);
                    }
                    return null;
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();
                try {
                    JsonObject responseJson = new JsonParser().parse(response.toString()).getAsJsonObject();
                    String content = extractAssistantContent(responseJson);
                    if (content == null || content.trim().isEmpty()) {
                        SimpleTranslateForge1122.getLogger().warn(
                                "Translation provider returned no assistant text surface={} response={}",
                                surface, compactDiagnosticBody(response.toString()));
                        if ("api_check".equals(surface)) {
                            lastRequestFailure = "provider response did not contain assistant text";
                        }
                        throw new TranslationRequestQueue.RetryableRequestException(
                                "provider response did not contain assistant text");
                    }
                    content = content.trim();
                    if (tokenMonitorSnapshot) {
                        int[] usage = extractTokenUsage(responseJson);
                        int promptTokens = usage[0];
                        int completionTokens = usage[1];
                        int totalTokens = usage[2];
                        if (totalTokens > 0) {
                            TokenUsageMonitor.record(new TokenUsage(modelSnapshot, promptTokens, completionTokens, totalTokens,
                                    System.currentTimeMillis() - requestStartedAt, System.currentTimeMillis(), surface));
                        }
                    }
                    if (!validateComponentResponse) return content;
                    JsonElement translatedDocument = new JsonParser().parse(content);
                    if (!translatedDocument.isJsonArray() || translatedDocument.getAsJsonArray().size() != document.size()) {
                        SimpleTranslateForge1122.getLogger().warn(
                                "Translation provider returned invalid Component array surface={} expected={} response={}",
                                surface, document.size(), compactDiagnosticBody(content));
                        if ("api_check".equals(surface)) {
                            lastRequestFailure = "provider returned an invalid component array";
                        }
                        throw new TranslationRequestQueue.RetryableRequestException("provider returned an invalid component array");
                    }
                    for (JsonElement translated : translatedDocument.getAsJsonArray()) {
                        ITextComponent.Serializer.jsonToComponent(translated.toString());
                    }
                    if ("api_check".equals(surface)) lastRequestFailure = "";
                    return translatedDocument.toString();
                } catch (TranslationRequestQueue.RetryableRequestException retryable) {
                    throw retryable;
                } catch (Exception invalidResponse) {
                    SimpleTranslateForge1122.getLogger().warn(
                            "Translation provider returned invalid JSON surface={} reason={} response={}",
                            surface, safeDiagnosticMessage(invalidResponse), compactDiagnosticBody(response.toString()));
                    throw new TranslationRequestQueue.RetryableRequestException("provider returned invalid component JSON");
                }
            } finally {
                if (requestFuture != null) requestFuture.detach(connection);
                connection.disconnect();
            }
        } catch (TranslationRequestQueue.RetryableRequestException retryable) {
            throw retryable;
        } catch (java.io.IOException error) {
            SimpleTranslateForge1122.getLogger().warn("Translation request I/O failure surface={} reason={}",
                    surface, safeDiagnosticMessage(error));
            if ("api_check".equals(surface)) lastRequestFailure = safeDiagnosticMessage(error);
            throw new TranslationRequestQueue.RetryableRequestException("I/O error");
        } catch (Exception error) {
            SimpleTranslateForge1122.getLogger().warn("Translation request failed surface={} reason={}",
                    surface, safeDiagnosticMessage(error));
            if ("api_check".equals(surface)) lastRequestFailure = safeDiagnosticMessage(error);
            return null;
        }
    }

    private static boolean isRetryableHttpStatus(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429
                || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private static JsonElement stripHoverEvents(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return copyJson(value);
        if (value.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) copy.add(stripHoverEvents(child));
            return copy;
        }
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (!"hoverEvent".equals(entry.getKey()) && !"hover_event".equals(entry.getKey())) {
                copy.add(entry.getKey(), stripHoverEvents(entry.getValue()));
            }
        }
        return copy;
    }

    private static void reattachHoverEvents(JsonElement original, JsonElement translated) {
        if (original == null || translated == null || !original.isJsonObject() || !translated.isJsonObject()) return;
        JsonObject source = original.getAsJsonObject();
        JsonObject target = translated.getAsJsonObject();
        if (source.has("hoverEvent")) target.add("hoverEvent", copyJson(source.get("hoverEvent")));
        if (source.has("hover_event")) target.add("hover_event", copyJson(source.get("hover_event")));
        reattachChildren(source.get("extra"), target.get("extra"));
    }

    private static void reattachChildren(JsonElement original, JsonElement translated) {
        if (original == null || translated == null || !original.isJsonArray() || !translated.isJsonArray()) return;
        int count = Math.min(original.getAsJsonArray().size(), translated.getAsJsonArray().size());
        for (int i = 0; i < count; i++) reattachHoverEvents(original.getAsJsonArray().get(i), translated.getAsJsonArray().get(i));
    }

    /** Gson 2.8.0 bundled with 1.12.2 has no public JsonElement#deepCopy. */
    private static JsonElement copyJson(JsonElement value) {
        if (value == null) return null;
        if (value.isJsonNull() || value.isJsonPrimitive()) return value;
        if (value.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) copy.add(copyJson(child));
            return copy;
        }
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            copy.add(entry.getKey(), copyJson(entry.getValue()));
        }
        return copy;
    }

    private static String normalizeContextScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) return "other";
        String normalized = scope.trim().toLowerCase(java.util.Locale.ROOT);
        for (String candidate : TEXT_CONTEXT_SCOPES) {
            if (candidate.equals(normalized)) return candidate;
        }
        return "other";
    }

    private static String contextScopeForSurface(String surface) {
        String normalized = Surface.normalize(surface);
        if (normalized.startsWith("chat.outgoing")) return "sent_chat";
        if (normalized.startsWith("chat")) return "received_chat";
        if ("item_tooltip".equals(normalized) || normalized.startsWith("tooltip.item")) return "item_tooltip";
        if (normalized.startsWith("tooltip.visible.book_hover") || normalized.startsWith("tooltip.book")) return "book";
        if ("hover_text".equals(normalized) || normalized.startsWith("hover")
                || normalized.startsWith("tooltip.hover") || normalized.startsWith("tooltip.visible.chat_hover")) {
            return "hover_tooltip";
        }
        if (normalized.startsWith("book")) return "book";
        if (normalized.startsWith("sign")) return "sign";
        if (normalized.startsWith("advancement")) return "hud_progress";
        if (normalized.startsWith("entity")) return "entity_name";
        if (normalized.startsWith("hud.title") || normalized.startsWith("hud.subtitle")
                || normalized.startsWith("hud.actionbar") || normalized.startsWith("hud.captions")
                || normalized.startsWith("title.") || normalized.startsWith("actionbar.")) return "hud_captions";
        if (normalized.startsWith("scoreboard") || normalized.startsWith("bossbar")
                || normalized.startsWith("player_tab")) return "hud_progress";
        return "other";
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte value : digest) output.append(String.format("%02x", value & 0xff));
            return output.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private synchronized void load() {
        if (!configFile.exists()) return;
        Properties properties = new Properties();
        try {
            InputStream input = new FileInputStream(configFile);
            properties.load(input);
            input.close();
            String persistedEndpoint = blankOr(properties.getProperty("endpoint"), endpoint);
            endpoint = normalizeEndpoint(persistedEndpoint);
            apiKey = properties.getProperty("apiKey", apiKey);
            model = blankOr(properties.getProperty("model"), model);
            sourceLanguage = blankOr(properties.getProperty("sourceLanguage"), sourceLanguage);
            targetLanguage = blankOr(properties.getProperty("targetLanguage"), targetLanguage);
            maxParallelRequests = clampWorkers(parseInt(properties.getProperty("maxParallelRequests"), maxParallelRequests));
            chatAutoMode = Boolean.parseBoolean(properties.getProperty("chatAutoMode", "false"));
            holdOriginalEnabled = Boolean.parseBoolean(properties.getProperty("holdOriginalEnabled", "false"));
            manualTooltipMode = Boolean.parseBoolean(properties.getProperty("manualTooltipMode", "false"));
            outgoingChatEnabled = Boolean.parseBoolean(properties.getProperty("outgoingChatEnabled", "false"));
            tooltipGlowEnabled = Boolean.parseBoolean(properties.getProperty("tooltipGlowEnabled", "false"));
            tokenMonitorEnabled = Boolean.parseBoolean(properties.getProperty("tokenMonitorEnabled", "false"));
            textContextEnabled = Boolean.parseBoolean(properties.getProperty("textContextEnabled", "true"));
            textContextMessageCount = Math.max(0, Math.min(20, parseInt(properties.getProperty("textContextMessageCount"), 6)));
            for (String scope : TEXT_CONTEXT_SCOPES) {
                String persisted = properties.getProperty("textContext." + scope);
                if (persisted != null) legacyTextContextScopeEnabled.put(scope,
                        Boolean.valueOf(Boolean.parseBoolean(persisted)));
            }
            String[] legacyFeatures = {"global", "chat", "tooltip_item", "tooltip_hover", "book", "sign",
                    "advancement", "entity_name", "hud_scoreboard", "hud_bossbar", "hud_title",
                    "hud_actionbar", "ftb", "tips", "gui", "cache"};
            for (String feature : legacyFeatures) {
                String persisted = properties.getProperty("feature." + feature);
                if (persisted != null) legacyFeatureEnabled.put(feature,
                        Boolean.valueOf(Boolean.parseBoolean(persisted)));
            }
        } catch (Exception ignored) {
        }
    }

    private static int integer(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int[] extractTokenUsage(JsonObject response) {
        int prompt = 0, completion = 0, total = 0;
        if (response != null && response.has("usage") && response.get("usage").isJsonObject()) {
            JsonObject usage = response.getAsJsonObject("usage");
            prompt = Math.max(integer(usage, "prompt_tokens"), integer(usage, "input_tokens"));
            completion = Math.max(integer(usage, "completion_tokens"), integer(usage, "output_tokens"));
            total = integer(usage, "total_tokens");
        }
        if (response != null && response.has("usageMetadata") && response.get("usageMetadata").isJsonObject()) {
            JsonObject usage = response.getAsJsonObject("usageMetadata");
            prompt = Math.max(prompt, integer(usage, "promptTokenCount"));
            completion = Math.max(completion, integer(usage, "candidatesTokenCount"));
            total = Math.max(total, integer(usage, "totalTokenCount"));
        }
        if (response != null) {
            prompt = Math.max(prompt, integer(response, "prompt_eval_count"));
            completion = Math.max(completion, integer(response, "eval_count"));
        }
        if (total == 0) total = prompt + completion;
        return new int[]{prompt, completion, total};
    }

    private static boolean isShareableComponentEntry(String key, String value) {
        if (key == null || value == null || !TranslationCacheKeys.isCurrentProtocolKey(key)
                || !key.contains(":fmt=" + COMPONENT_JSON_FORMAT + ":")) return false;
        try {
            ITextComponent.Serializer.jsonToComponent(value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String modelsEndpoint(String rawUrl, ModConfig.ApiFormat format) {
        String endpoint = blankOr(rawUrl, DEFAULT_ENDPOINT).replaceAll("/+$", "");
        ModConfig.ApiFormat resolved = format == null ? ModConfig.ApiFormat.DEEPSEEK_CHAT : format;
        if (resolved == ModConfig.ApiFormat.LOCAL_OLLAMA) {
            int api = endpoint.indexOf("/api/");
            return (api >= 0 ? endpoint.substring(0, api) : endpoint) + "/api/tags";
        }
        if (resolved == ModConfig.ApiFormat.GEMINI_GENERATE_CONTENT) {
            int models = endpoint.indexOf("/models/");
            String base = models >= 0 ? endpoint.substring(0, models) : endpoint;
            if (!base.endsWith("/models")) base += "/models";
            return base;
        }
        if (endpoint.endsWith("/chat/completions")) {
            return endpoint.substring(0, endpoint.length() - "/chat/completions".length()) + "/models";
        }
        if (endpoint.endsWith("/responses")) {
            return endpoint.substring(0, endpoint.length() - "/responses".length()) + "/models";
        }
        if (endpoint.endsWith("/messages")) {
            return endpoint.substring(0, endpoint.length() - "/messages".length()) + "/models";
        }
        return endpoint.endsWith("/models") ? endpoint : endpoint + "/models";
    }

    private static String diagnosticEndpoint(String rawUrl, ModConfig.ApiFormat format,
                                             String modelId, String suppliedApiKey) {
        String endpoint = blankOr(rawUrl, DEFAULT_ENDPOINT).replaceAll("/+$", "");
        ModConfig.ApiFormat resolved = format == null ? ModConfig.ApiFormat.DEEPSEEK_CHAT : format;
        if (resolved == ModConfig.ApiFormat.GEMINI_GENERATE_CONTENT) {
            endpoint = endpoint.replace("{model}", modelId);
            if (!endpoint.contains(":generateContent")) {
                int models = endpoint.indexOf("/models/");
                String base = models >= 0 ? endpoint.substring(0, models) : endpoint;
                endpoint = base + "/models/" + modelId + ":generateContent";
            }
            if (suppliedApiKey != null && !suppliedApiKey.trim().isEmpty() && !endpoint.contains("key=")) {
                endpoint += (endpoint.contains("?") ? "&" : "?") + "key="
                        + urlEncode(suppliedApiKey.trim());
            }
            return endpoint;
        }
        if (resolved == ModConfig.ApiFormat.LOCAL_OLLAMA) {
            if (endpoint.contains("/chat/completions")) return endpoint;
            return endpoint + "/chat/completions";
        }
        if (resolved == ModConfig.ApiFormat.ANTHROPIC_MESSAGES) {
            if (endpoint.endsWith("/messages")) return endpoint;
            return endpoint.endsWith("/v1") ? endpoint + "/messages" : endpoint + "/v1/messages";
        }
        if (resolved == ModConfig.ApiFormat.OPENAI_RESPONSES) {
            return endpoint.endsWith("/responses") ? endpoint : endpoint + "/responses";
        }
        String normalized = normalizeEndpoint(endpoint);
        return normalized.contains("/chat/completions") ? normalized : normalized + "/chat/completions";
    }

    private static JsonObject diagnosticRequest(ModConfig.ApiFormat format, String modelId) {
        ModConfig.ApiFormat resolved = format == null ? ModConfig.ApiFormat.DEEPSEEK_CHAT : format;
        JsonObject request = new JsonObject();
        request.addProperty("model", modelId);
        if (resolved == ModConfig.ApiFormat.OPENAI_RESPONSES) {
            request.addProperty("input", "Return exactly this JSON array: [{\"text\":\"ok\"}]");
            request.addProperty("max_output_tokens", 32);
            return request;
        }
        if (resolved == ModConfig.ApiFormat.ANTHROPIC_MESSAGES) {
            request.addProperty("max_tokens", 32);
        }
        if (resolved == ModConfig.ApiFormat.GEMINI_GENERATE_CONTENT) {
            request.remove("model");
            JsonObject part = new JsonObject();
            part.addProperty("text", "Return exactly this JSON array: [{\"text\":\"ok\"}]");
            JsonArray parts = new JsonArray();
            parts.add(part);
            JsonObject content = new JsonObject();
            content.add("parts", parts);
            JsonArray contents = new JsonArray();
            contents.add(content);
            request.add("contents", contents);
            return request;
        }
        request.addProperty("stream", false);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", "Return exactly this JSON array: [{\"text\":\"ok\"}]");
        JsonArray messages = new JsonArray();
        messages.add(message);
        request.add("messages", messages);
        if (resolved != ModConfig.ApiFormat.LOCAL_OLLAMA) request.addProperty("max_tokens", 32);
        return request;
    }

    private static JsonObject buildTranslationProviderRequest(ModConfig.ApiFormat format, String endpointBase,
                                                              String modelId,
                                                              String systemPrompt, String userPrompt,
                                                              int maxTokens) {
        ModConfig.ApiFormat resolved = format == null ? ModConfig.ApiFormat.DEEPSEEK_CHAT : format;
        JsonObject request = new JsonObject();
        request.addProperty("model", modelId);
        int outputTokens = Math.max(512, Math.min(32768, maxTokens));
        boolean openAiReasoning = isOpenAiReasoningModel(modelId);
        if (resolved == ModConfig.ApiFormat.OPENAI_RESPONSES) {
            request.addProperty("stream", false);
            if (openAiReasoning) {
                outputTokens = Math.max(outputTokens, 2048);
                JsonObject reasoning = new JsonObject();
                reasoning.addProperty("effort", openAiReasoningEffort(modelId,
                        ModConfig.DEEPSEEK_THINKING_ENABLED.get()));
                request.add("reasoning", reasoning);
            } else {
                request.addProperty("temperature", 0.0D);
            }
            request.addProperty("max_output_tokens", Math.min(32768, outputTokens));
            request.addProperty("instructions", systemPrompt);
            JsonObject part = new JsonObject();
            part.addProperty("type", "input_text");
            part.addProperty("text", userPrompt);
            JsonArray content = new JsonArray();
            content.add(part);
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.add("content", content);
            JsonArray input = new JsonArray();
            input.add(message);
            request.add("input", input);
            return request;
        }
        if (resolved == ModConfig.ApiFormat.ANTHROPIC_MESSAGES) {
            boolean thinkingEnabled = ModConfig.DEEPSEEK_THINKING_ENABLED.get();
            if (thinkingEnabled) {
                outputTokens = Math.max(outputTokens, 2048);
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "enabled");
                thinking.addProperty("budget_tokens", 1024);
                request.add("thinking", thinking);
            } else {
                request.addProperty("temperature", 0.0D);
            }
            request.addProperty("max_tokens", Math.min(32768, outputTokens));
            request.addProperty("system", systemPrompt);
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", userPrompt);
            JsonArray messages = new JsonArray();
            messages.add(message);
            request.add("messages", messages);
            return request;
        }
        if (resolved == ModConfig.ApiFormat.GEMINI_GENERATE_CONTENT) {
            request.remove("model");
            JsonObject systemPart = new JsonObject();
            systemPart.addProperty("text", systemPrompt);
            JsonArray systemParts = new JsonArray();
            systemParts.add(systemPart);
            JsonObject systemInstruction = new JsonObject();
            systemInstruction.add("parts", systemParts);
            request.add("systemInstruction", systemInstruction);
            JsonObject userPart = new JsonObject();
            userPart.addProperty("text", userPrompt);
            JsonArray userParts = new JsonArray();
            userParts.add(userPart);
            JsonObject content = new JsonObject();
            content.addProperty("role", "user");
            content.add("parts", userParts);
            JsonArray contents = new JsonArray();
            contents.add(content);
            request.add("contents", contents);
            JsonObject generation = new JsonObject();
            generation.addProperty("temperature", 0.0D);
            if (ModConfig.DEEPSEEK_THINKING_ENABLED.get()) outputTokens = Math.max(outputTokens, 2048);
            generation.addProperty("maxOutputTokens", Math.min(32768, outputTokens));
            JsonObject thinkingConfig = new JsonObject();
            if (ModConfig.DEEPSEEK_THINKING_ENABLED.get()) {
                thinkingConfig.addProperty("thinkingBudget", 1024);
                generation.add("thinkingConfig", thinkingConfig);
            } else if (modelId != null && modelId.toLowerCase(java.util.Locale.ROOT).contains("flash")) {
                thinkingConfig.addProperty("thinkingBudget", 0);
                generation.add("thinkingConfig", thinkingConfig);
            }
            request.add("generationConfig", generation);
            return request;
        }
        request.addProperty("stream", false);
        boolean deepSeekThinkingCapable = resolved == ModConfig.ApiFormat.DEEPSEEK_CHAT
                || ((resolved == ModConfig.ApiFormat.OPENAI_CHAT_COMPAT
                || resolved == ModConfig.ApiFormat.LOCAL_OLLAMA)
                && (isOfficialDeepSeekEndpoint(endpointBase) || isDeepSeekReasoningModel(modelId)));
        boolean thinkingEnabled = deepSeekThinkingCapable && ModConfig.DEEPSEEK_THINKING_ENABLED.get();
        openAiReasoning = !deepSeekThinkingCapable && openAiReasoning;
        if (thinkingEnabled) outputTokens = Math.max(outputTokens, 4096);
        else if (openAiReasoning) outputTokens = Math.max(outputTokens, 2048);
        request.addProperty("max_tokens", Math.min(32768, outputTokens));
        if (!thinkingEnabled && !openAiReasoning) request.addProperty("temperature", 0.0D);
        if (deepSeekThinkingCapable) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", thinkingEnabled ? "enabled" : "disabled");
            request.add("thinking", thinking);
        } else if (openAiReasoning) {
            request.addProperty("reasoning_effort", openAiReasoningEffort(modelId,
                    ModConfig.DEEPSEEK_THINKING_ENABLED.get()));
        }
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt);
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt);
        messages.add(user);
        request.add("messages", messages);
        return request;
    }

    private static boolean isOfficialDeepSeekEndpoint(String apiUrl) {
        if (apiUrl == null || apiUrl.trim().isEmpty()) return false;
        try {
            String host = new URL(apiUrl).getHost();
            if (host == null) return false;
            String normalized = host.toLowerCase(java.util.Locale.ROOT);
            return "deepseek.com".equals(normalized) || normalized.endsWith(".deepseek.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isDeepSeekReasoningModel(String model) {
        if (model == null || model.trim().isEmpty()) return false;
        String normalized = model.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("deepseek-")
                && (normalized.contains("reasoner") || normalized.contains("r1") || normalized.contains("v4"));
    }

    private static boolean isOpenAiReasoningModel(String model) {
        if (model == null || model.trim().isEmpty()) return false;
        String normalized = model.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("o1") || normalized.startsWith("o3")
                || normalized.startsWith("o4") || normalized.startsWith("gpt-5");
    }

    private static String openAiReasoningEffort(String model, boolean enabled) {
        if (enabled) return "medium";
        String normalized = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("gpt-5") ? "minimal" : "low";
    }

    private static String extractAssistantContent(JsonObject response) {
        if (response == null) return null;
        if (response.has("choices") && response.get("choices").isJsonArray()
                && response.getAsJsonArray("choices").size() > 0) {
            JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (choice.has("message") && choice.get("message").isJsonObject()) {
                JsonElement content = choice.getAsJsonObject("message").get("content");
                if (content != null && !content.isJsonNull()) return content.getAsString();
            }
        }
        if (response.has("message") && response.get("message").isJsonObject()) {
            JsonElement content = response.getAsJsonObject("message").get("content");
            if (content != null && !content.isJsonNull()) return content.getAsString();
        }
        if (response.has("output_text") && !response.get("output_text").isJsonNull()) {
            return response.get("output_text").getAsString();
        }
        if (response.has("output") && response.get("output").isJsonArray()) {
            for (JsonElement output : response.getAsJsonArray("output")) {
                if (!output.isJsonObject()) continue;
                JsonElement content = output.getAsJsonObject().get("content");
                if (content == null || !content.isJsonArray()) continue;
                for (JsonElement part : content.getAsJsonArray()) {
                    if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                        return part.getAsJsonObject().get("text").getAsString();
                    }
                }
            }
        }
        if (response.has("content") && response.get("content").isJsonArray()) {
            for (JsonElement part : response.getAsJsonArray("content")) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    return part.getAsJsonObject().get("text").getAsString();
                }
            }
        }
        if (response.has("candidates") && response.get("candidates").isJsonArray()
                && response.getAsJsonArray("candidates").size() > 0) {
            JsonObject candidate = response.getAsJsonArray("candidates").get(0).getAsJsonObject();
            if (candidate.has("content") && candidate.get("content").isJsonObject()) {
                JsonElement parts = candidate.getAsJsonObject("content").get("parts");
                if (parts != null && parts.isJsonArray()) {
                    StringBuilder text = new StringBuilder();
                    for (JsonElement part : parts.getAsJsonArray()) {
                        if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                            text.append(part.getAsJsonObject().get("text").getAsString());
                        }
                    }
                    if (text.length() > 0) return text.toString();
                }
            }
        }
        return null;
    }

    private static void applyDiagnosticAuth(HttpURLConnection connection, String suppliedApiKey,
                                            ModConfig.ApiFormat format) {
        String key = suppliedApiKey == null ? "" : suppliedApiKey.trim();
        ModConfig.ApiFormat resolved = format == null ? ModConfig.ApiFormat.DEEPSEEK_CHAT : format;
        if (key.isEmpty() || resolved == ModConfig.ApiFormat.LOCAL_OLLAMA
                || resolved == ModConfig.ApiFormat.GEMINI_GENERATE_CONTENT) return;
        if (resolved == ModConfig.ApiFormat.ANTHROPIC_MESSAGES) {
            connection.setRequestProperty("x-api-key", key);
            connection.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            connection.setRequestProperty("Authorization", "Bearer " + key);
        }
    }

    private static void collectModelIds(JsonElement element, List<String> target) {
        if (element == null || !element.isJsonArray()) return;
        for (JsonElement item : element.getAsJsonArray()) {
            String id = "";
            if (item.isJsonPrimitive()) id = item.getAsString();
            else if (item.isJsonObject()) {
                JsonObject object = item.getAsJsonObject();
                for (String key : new String[]{"id", "name", "model"}) {
                    if (object.has(key) && object.get(key).isJsonPrimitive()) {
                        id = object.get(key).getAsString();
                        break;
                    }
                }
            }
            if (id.startsWith("models/")) id = id.substring("models/".length());
            if (!id.trim().isEmpty() && !target.contains(id)) target.add(id);
        }
    }

    private static String readResponseBody(HttpURLConnection connection, int status) throws java.io.IOException {
        InputStream input = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (input == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        try {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            return body.toString();
        } finally {
            reader.close();
        }
    }

    private static String compactDiagnosticBody(String body) {
        if (body == null || body.trim().isEmpty()) return "";
        String compact = body.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (compact.length() > 180) compact = compact.substring(0, 180) + "...";
        return ": " + compact;
    }

    private static String safeDiagnosticMessage(Exception error) {
        if (error == null) return "request_failed";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static String urlEncode(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); }
        catch (Exception ignored) { return value; }
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** Accept DeepSeek's documented base URL while this legacy UI stores a full POST endpoint. */
    private static String normalizeEndpoint(String value) {
        String endpoint = blankOr(value, DEFAULT_ENDPOINT);
        try {
            java.net.URL url = new java.net.URL(endpoint);
            if ("api.deepseek.com".equalsIgnoreCase(url.getHost())
                    && (url.getQuery() == null || url.getQuery().isEmpty())) {
                String path = url.getPath() == null ? "" : url.getPath();
                if (path.isEmpty() || "/".equals(path) || "/v1".equals(path) || "/v1/".equals(path)
                        || ("/chat/completions".startsWith(path) && path.startsWith("/chat/"))) {
                    return url.getProtocol() + "://" + url.getAuthority() + "/chat/completions";
                }
            }
        } catch (Exception ignored) {
            // Preserve custom/provider endpoints verbatim and let API detection report them.
        }
        return endpoint;
    }

    private <T> CompletableFuture<T> executeHttpAsync(final HttpOperation<T> operation) {
        final CancellableHttpFuture<T> result = new CancellableHttpFuture<T>();
        Future<?> worker = httpExecutor.submit(new Runnable() {
            @Override public void run() {
                if (result.isCancelled()) return;
                try {
                    T value = operation.execute(result);
                    if (!result.isCancelled()) result.complete(value);
                } catch (CancellationException canceled) {
                    result.cancel(true);
                } catch (Throwable error) {
                    if (!result.isCancelled()) result.completeExceptionally(error);
                }
            }
        });
        result.setWorker(worker);
        return result;
    }

    private interface HttpOperation<T> {
        T execute(CancellableHttpFuture<T> future) throws Exception;
    }

    /** CompletableFuture whose cancellation interrupts and disconnects the actual legacy HTTP call. */
    private static final class CancellableHttpFuture<T> extends CompletableFuture<T> {
        private final AtomicReference<HttpURLConnection> connection =
                new AtomicReference<HttpURLConnection>();
        private volatile Future<?> worker;

        private void setWorker(Future<?> value) {
            worker = value;
            if (isCancelled() && value != null) value.cancel(true);
        }

        private void attach(HttpURLConnection value) {
            if (value == null) return;
            connection.set(value);
            if (isCancelled()) {
                connection.compareAndSet(value, null);
                value.disconnect();
                throw new CancellationException("HTTP request canceled");
            }
        }

        private void detach(HttpURLConnection value) {
            if (value != null) connection.compareAndSet(value, null);
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            HttpURLConnection active = connection.getAndSet(null);
            if (active != null) active.disconnect();
            Future<?> activeWorker = worker;
            if (activeWorker != null) activeWorker.cancel(mayInterruptIfRunning);
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private void applyRequestWorkerCount() {
        requestQueue.setMaxParallelRequests(clampWorkers(maxParallelRequests));
    }

    private static void applyLegacyContextScope(String scope, boolean enabled) {
        if ("received_chat".equals(scope)) ModConfig.API_TEXT_CONTEXT_RECEIVED_CHAT.set(enabled);
        else if ("sent_chat".equals(scope)) ModConfig.API_TEXT_CONTEXT_SENT_CHAT.set(enabled);
        else if ("item_tooltip".equals(scope)) ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP.set(enabled);
        else if ("hover_tooltip".equals(scope)) ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP.set(enabled);
        else if ("book".equals(scope)) ModConfig.API_TEXT_CONTEXT_BOOK.set(enabled);
        else if ("sign".equals(scope)) ModConfig.API_TEXT_CONTEXT_SIGN.set(enabled);
        else if ("hud_captions".equals(scope)) ModConfig.API_TEXT_CONTEXT_HUD_CAPTIONS.set(enabled);
        else if ("hud_progress".equals(scope)) ModConfig.API_TEXT_CONTEXT_HUD_PROGRESS.set(enabled);
        else if ("entity_name".equals(scope)) ModConfig.API_TEXT_CONTEXT_ENTITY_NAME.set(enabled);
    }

    private static void applyLegacyFeature(String feature, boolean enabled) {
        if ("global".equals(feature)) ModConfig.GLOBAL_ENABLED.set(enabled);
        else if ("chat".equals(feature)) ModConfig.CHAT_ENABLED.set(enabled);
        else if ("tooltip_item".equals(feature)) ModConfig.TOOLTIP_ITEM_ENABLED.set(enabled);
        else if ("tooltip_hover".equals(feature)) ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.set(enabled);
        else if ("book".equals(feature)) ModConfig.CONTENT_BOOK_ENABLED.set(enabled);
        else if ("sign".equals(feature)) ModConfig.CONTENT_SIGN_ENABLED.set(enabled);
        else if ("advancement".equals(feature)) ModConfig.CONTENT_ADVANCEMENT_ENABLED.set(enabled);
        else if ("entity_name".equals(feature)) ModConfig.CONTENT_ENTITY_NAME_ENABLED.set(enabled);
        else if ("hud_scoreboard".equals(feature)) ModConfig.HUD_SCOREBOARD_ENABLED.set(enabled);
        else if ("hud_bossbar".equals(feature)) ModConfig.HUD_BOSSBAR_ENABLED.set(enabled);
        else if ("hud_title".equals(feature)) ModConfig.HUD_TITLE_ENABLED.set(enabled);
        else if ("hud_actionbar".equals(feature)) ModConfig.HUD_ACTIONBAR_ENABLED.set(enabled);
        else if ("ftb".equals(feature)) ModConfig.MOD_FTB_QUESTS_ENABLED.set(enabled);
        else if ("tips".equals(feature)) ModConfig.MOD_TIPS_ENABLED.set(enabled);
        else if ("gui".equals(feature)) ModConfig.CONTENT_GUI_ENABLED.set(enabled);
        else if ("cache".equals(feature)) ModConfig.CACHE_ENABLED.set(enabled);
    }

    private static int clampWorkers(int workers) {
        return Math.max(MIN_REQUEST_WORKERS, Math.min(MAX_REQUEST_WORKERS, workers));
    }

    private static int parseInt(String raw, int fallback) {
        try { return Integer.parseInt(raw); } catch (Exception ignored) { return fallback; }
    }

}
