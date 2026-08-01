package com.yourname.simpletranslate.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.core.AtomicFiles;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.KeyChord;
import com.yourname.simpletranslate.keybind.ShortcutAction;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, ConfigValue<?>> VALUES = new LinkedHashMap<>();
    public static final String DEFAULT_API_URL = "https://api.deepseek.com/chat/completions";
    private static Path configFile;
    private static JsonObject loadedRoot = new JsonObject();
    /** One-shot migration value consumed while the vanilla key mapping is registered. */
    private static KeyChord legacyOpenSettingsChord = KeyChord.NONE;

    public static final ConfigValue<String> DEEPSEEK_API_KEY = stringValue("api.apiKey", "");
    public static final ConfigValue<String> DEEPSEEK_MODEL = stringValue("api.model", DeepSeekModelPreset.V4_FLASH.getModelId());
    public static final BooleanValue DEEPSEEK_THINKING_ENABLED = bool("api.thinkingEnabled", false);
    public static final ConfigValue<String> DEEPSEEK_API_URL = stringValue("api.apiUrl", DEFAULT_API_URL);
    public static final EnumValue<ApiFormat> API_FORMAT = enumValue("api.format", ApiFormat.DEEPSEEK_CHAT, ApiFormat.class);
    /** Scope-local smart context applies to every model-backed translation surface. */
    public static final BooleanValue API_TEXT_CONTEXT_ENABLED = bool("api.textContext.enabled", true);
    public static final BooleanValue API_TEXT_CONTEXT_ALLOW_SHARED =
            bool("api.textContext.allowSharedImported", false);
    public static final BooleanValue API_TEXT_CONTEXT_RECEIVED_CHAT =
            bool("api.textContext.receivedChat", true);
    public static final BooleanValue API_TEXT_CONTEXT_SENT_CHAT =
            bool("api.textContext.sentChat", true);
    public static final BooleanValue API_TEXT_CONTEXT_ITEM_TOOLTIP =
            bool("api.textContext.itemTooltip", true);
    public static final BooleanValue API_TEXT_CONTEXT_HOVER_TOOLTIP =
            bool("api.textContext.hoverTooltip", true);
    public static final BooleanValue API_TEXT_CONTEXT_BOOK = bool("api.textContext.book", true);
    public static final BooleanValue API_TEXT_CONTEXT_SIGN = bool("api.textContext.sign", true);
    public static final BooleanValue API_TEXT_CONTEXT_HUD_CAPTIONS =
            bool("api.textContext.hudCaptions", true);
    public static final BooleanValue API_TEXT_CONTEXT_HUD_PROGRESS =
            bool("api.textContext.hudProgress", true);
    public static final BooleanValue API_TEXT_CONTEXT_ENTITY_NAME =
            bool("api.textContext.entityName", true);
    public static final BooleanValue API_TEXT_CONTEXT_TEXT_DISPLAY =
            bool("api.textContext.textDisplay", true);
    public static final IntValue API_MAX_PARALLEL_REQUESTS = intValue("api.maxParallelRequests", 5, 1, 8);
    public static final IntValue API_MAX_IN_FLIGHT_BATCHES = intValue("api.maxInFlightBatches", 2, 1, 2);
    public static final IntValue API_DIRECT_BATCH_DELAY_MS = intValue("api.directBatchDelayMs", 50, 0, 200);
    public static final ConfigValue<String> SOURCE_LANGUAGE = stringValue("language.sourceLanguage", "auto");
    public static final ConfigValue<String> TARGET_LANGUAGE = stringValue("language.targetLanguage", "zh_cn");
    public static final BooleanValue GLOBAL_ENABLED = bool("general.globalEnabled", true);
    public static final BooleanValue CONTENT_GUI_ENABLED = bool("content.guiEnabled", true);
    public static final EnumValue<GuiTranslationMode> CONTENT_GUI_MODE =
            enumValue("content.guiMode", GuiTranslationMode.SHORTCUT, GuiTranslationMode.class);
    /** Remembers that K has opted the in-world HUD/overlay frame into translation. */
    public static final BooleanValue CONTENT_HUD_FRAME_ACTIVE =
            bool("content.hudFrameActive", false);
    /** Remembers which Screens K has opted into whole-frame translation. */
    public static final ConfigValue<String> CONTENT_GUI_FRAME_SCREEN_KEYS =
            stringValue("content.guiFrameScreenKeys", "");
    public static final BooleanValue MOD_TRANSLATION_ENABLED = bool("mods.enabled", true);
    public static final BooleanValue MOD_FTB_QUESTS_ENABLED = bool("mods.ftbQuestsEnabled", true);
    public static final BooleanValue MOD_TIPS_ENABLED = bool("mods.tipsEnabled", true);
    public static final BooleanValue CUSTOM_FONT_CJK_FIX_ENABLED = bool("general.customFontCjkFixEnabled", true);
    /** When true, multi-region PUA-positioned HUD trees keep their original layout. */
    public static final BooleanValue LAYOUT_CRITICAL_HUD_KEEP_ORIGINAL =
            bool("general.layoutCriticalHudKeepOriginal", false);
    public static final BooleanValue TOKEN_MONITOR_ENABLED = bool("monitor.tokenEnabled", false);
    public static final BooleanValue CHAT_ENABLED = bool("chat.enabled", true);
    public static final EnumValue<TranslationMode> CHAT_MODE = enumValue("chat.mode", TranslationMode.BUTTON, TranslationMode.class);
    public static final BooleanValue CHAT_CONTEXT_ENABLED = bool("chat.contextEnabled", false);
    public static final IntValue CHAT_CONTEXT_MESSAGE_COUNT = intValue("chat.contextMessageCount", 6, 0, 20);
    public static final BooleanValue CHAT_OUTGOING_ENABLED = bool("chat.outgoingEnabled", false);
    public static final ConfigValue<String> CHAT_OUTGOING_SERVER_LANGUAGE = stringValue("chat.outgoingServerLanguage", "en");
    public static final BooleanValue TOOLTIP_ITEM_ENABLED = bool("tooltip.itemEnabled", true);
    public static final EnumValue<TooltipTriggerMode> TOOLTIP_ITEM_TRIGGER_MODE =
            enumValue("tooltip.itemTriggerMode", TooltipTriggerMode.HOVER, TooltipTriggerMode.class);
    public static final BooleanValue TOOLTIP_CHAT_HOVER_ENABLED = bool("tooltip.chatHoverEnabled", true);
    public static final EnumValue<TooltipTriggerMode> TOOLTIP_CHAT_HOVER_TRIGGER_MODE =
            enumValue("tooltip.chatHoverTriggerMode", TooltipTriggerMode.HOVER, TooltipTriggerMode.class);
    public static final BooleanValue TOOLTIP_GLOW_ENABLED = bool("tooltip.glow.enabled", false);
    public static final IntValue TOOLTIP_GLOW_LINE_WIDTH = intValue("tooltip.glow.lineWidth", 3, 1, 6);
    public static final IntValue TOOLTIP_GLOW_SPREAD = intValue("tooltip.glow.spread", 6, 0, 12);
    public static final IntValue TOOLTIP_GLOW_CYCLE_MS = intValue("tooltip.glow.cycleMs", 8000, 2000, 24000);
    public static final IntValue TOOLTIP_GLOW_OPACITY = intValue("tooltip.glow.opacity", 180, 20, 255);
    public static final EnumValue<TooltipGlowTheme> TOOLTIP_GLOW_THEME =
            enumValue("tooltip.glow.theme", TooltipGlowTheme.SOFT, TooltipGlowTheme.class);
    public static final BooleanValue TOOLTIP_BOOK_HOVER_ENABLED = bool("tooltip.bookHoverEnabled", true);
    public static final BooleanValue CONTENT_BOOK_ENABLED = bool("content.bookEnabled", true);
    public static final IntValue CONTENT_BOOK_BOOKMARK_OFFSET_X = intValue("content.bookBookmarkOffsetX", 166, 0, 192);
    public static final IntValue CONTENT_BOOK_BOOKMARK_OFFSET_Y = intValue("content.bookBookmarkOffsetY", 145, 0, 192);
    public static final BooleanValue CONTENT_SIGN_ENABLED = bool("content.signEnabled", true);
    public static final EnumValue<SignContextMode> CONTENT_SIGN_CONTEXT_MODE =
            enumValue("content.signContextMode", SignContextMode.AUTO, SignContextMode.class);
    public static final IntValue CONTENT_SIGN_RADIUS = intValue("content.signRadius", 3, 1, 32);
    public static final BooleanValue CONTENT_ADVANCEMENT_ENABLED = bool("content.advancementEnabled", true);
    public static final BooleanValue CONTENT_ENTITY_NAME_ENABLED = bool("content.entityNameEnabled", true);
    public static final IntValue CONTENT_ENTITY_NAME_RADIUS = intValue("content.entityNameRadius", 16, 1, 64);
    public static final BooleanValue CONTENT_TEXT_DISPLAY_ENABLED = bool("content.textDisplayEnabled", true);
    public static final IntValue CONTENT_TEXT_DISPLAY_RADIUS = intValue("content.textDisplayRadius", 16, 1, 64);
    public static final BooleanValue HUD_SCOREBOARD_ENABLED = bool("hud.scoreboardEnabled", true);
    public static final BooleanValue HUD_BOSSBAR_ENABLED = bool("hud.bossbarEnabled", true);
    public static final BooleanValue HUD_TITLE_ENABLED = bool("hud.titleEnabled", true);
    public static final BooleanValue HUD_ACTIONBAR_ENABLED = bool("hud.actionbarEnabled", true);
    public static final BooleanValue HUD_TITLE_CONTEXT_ENABLED = bool("hud.titleContextEnabled", false);
    public static final BooleanValue HUD_HISTORY_CHAT_ENABLED = bool("hud.historyChatEnabled", false);
    public static final IntValue HUD_CAPTION_BATCH_INTERVAL_MS = intValue("hud.captionBatchIntervalMs", 800, 500, 10000);
    public static final IntValue HUD_CAPTION_COLLECT_WINDOW_MS = intValue("hud.captionCollectWindowMs", 4500, 500, 30000);
    public static final IntValue TERM_AUTO_DETECT_COUNT = intValue("terms.autoDetectCount", 3, 1, 100);
    public static final BooleanValue TERM_AUTO_DETECT_ENABLED = bool("terms.autoDetectEnabled", false);
    public static final BooleanValue CACHE_ENABLED = bool("cache.enabled", true);

    public static final BooleanValue HOLD_ORIGINAL_ENABLED = bool("holdOriginal.enabled", false);
    public static final ConfigValue<String> SHORTCUT_TOGGLE_GLOBAL_TRANSLATION = stringValue(
            "shortcuts.toggleGlobalTranslation", KeyChord.NONE.serialize());
    public static final ConfigValue<String> SHORTCUT_TOGGLE_CHAT_MODE = stringValue("shortcuts.toggleChatMode", "keyboard:-1:0");
    public static final ConfigValue<String> SHORTCUT_TRANSLATE_GUI = stringValue("shortcuts.translateGui", "keyboard:75:0");
    public static final ConfigValue<String> SHORTCUT_TRANSLATE_TOOLTIP = stringValue("shortcuts.translateTooltip", "keyboard:86:0");
    public static final ConfigValue<String> SHORTCUT_SIGN_SELECT = stringValue("shortcuts.signSelect", "keyboard:71:0");
    public static final ConfigValue<String> SHORTCUT_SIGN_SUBMIT = stringValue("shortcuts.signSubmit", "keyboard:72:0");

    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_CHAT = stringValue("holdOriginal.chord.chat", KeyChord.NONE.serialize());
    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_TOOLTIP_ITEM = stringValue("holdOriginal.chord.tooltipItem", KeyChord.NONE.serialize());
    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_TOOLTIP_HOVER = stringValue("holdOriginal.chord.tooltipHover", KeyChord.NONE.serialize());
    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_BOOK = stringValue("holdOriginal.chord.book", KeyChord.NONE.serialize());
    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_SIGN = stringValue("holdOriginal.chord.sign", KeyChord.NONE.serialize());
    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_ADVANCEMENT = stringValue("holdOriginal.chord.advancement", KeyChord.NONE.serialize());
    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_ENTITY_NAME = stringValue("holdOriginal.chord.entityName", KeyChord.NONE.serialize());
    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_TEXT_DISPLAY = stringValue("holdOriginal.chord.textDisplay", KeyChord.NONE.serialize());
    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_SCOREBOARD = stringValue("holdOriginal.chord.scoreboard", KeyChord.NONE.serialize());
    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_BOSSBAR = stringValue("holdOriginal.chord.bossbar", KeyChord.NONE.serialize());
    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_TITLE = stringValue("holdOriginal.chord.title", KeyChord.NONE.serialize());
    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_ACTIONBAR = stringValue("holdOriginal.chord.actionbar", KeyChord.NONE.serialize());
    public static final ConfigValue<String> HOLD_ORIGINAL_CHORD_GUI = stringValue("holdOriginal.chord.gui", KeyChord.NONE.serialize());

    public static ConfigValue<String> getShortcutChord(ShortcutAction action) {
        return switch (action) {
            case TOGGLE_GLOBAL_TRANSLATION -> SHORTCUT_TOGGLE_GLOBAL_TRANSLATION;
            case TOGGLE_CHAT_MODE -> SHORTCUT_TOGGLE_CHAT_MODE;
            case TRANSLATE_GUI -> SHORTCUT_TRANSLATE_GUI;
            case TRANSLATE_TOOLTIP -> SHORTCUT_TRANSLATE_TOOLTIP;
            case SIGN_SELECT -> SHORTCUT_SIGN_SELECT;
            case SIGN_SUBMIT -> SHORTCUT_SIGN_SUBMIT;
        };
    }

    public static ConfigValue<String> getHoldOriginalChord(HoldOriginalFeature feature) {
        return switch (feature) {
            case CHAT -> HOLD_ORIGINAL_CHORD_CHAT;
            case TOOLTIP_ITEM -> HOLD_ORIGINAL_CHORD_TOOLTIP_ITEM;
            case TOOLTIP_HOVER -> HOLD_ORIGINAL_CHORD_TOOLTIP_HOVER;
            case BOOK -> HOLD_ORIGINAL_CHORD_BOOK;
            case SIGN -> HOLD_ORIGINAL_CHORD_SIGN;
            case ADVANCEMENT -> HOLD_ORIGINAL_CHORD_ADVANCEMENT;
            case ENTITY_NAME -> HOLD_ORIGINAL_CHORD_ENTITY_NAME;
            case TEXT_DISPLAY -> HOLD_ORIGINAL_CHORD_TEXT_DISPLAY;
            case SCOREBOARD -> HOLD_ORIGINAL_CHORD_SCOREBOARD;
            case BOSSBAR -> HOLD_ORIGINAL_CHORD_BOSSBAR;
            case TITLE -> HOLD_ORIGINAL_CHORD_TITLE;
            case ACTIONBAR -> HOLD_ORIGINAL_CHORD_ACTIONBAR;
            case GUI -> HOLD_ORIGINAL_CHORD_GUI;
        };
    }

    public static String normalizeDeepSeekModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return DeepSeekModelPreset.V4_FLASH.getModelId();
        }

        String normalized = modelId.trim();
        String compact = normalized.toLowerCase().replace("_", "-").replace(" ", "");
        return switch (compact) {
            case "deepseekv4flash", "deepseek-v4flash", "deepseek-v4-flsh", "deepseekv4flsh", "v4flash", "flash" ->
                    DeepSeekModelPreset.V4_FLASH.getModelId();
            case "deepseekv4pro", "deepseek-v4pro", "v4pro", "pro", "reasoner", "deepseekreasoner" ->
                    DeepSeekModelPreset.V4_PRO.getModelId();
            case "chat", "deepseekchat" -> DeepSeekModelPreset.V4_FLASH.getModelId();
            default -> DeepSeekModelPreset.V4_FLASH.getModelId();
        };
    }

    public static String normalizeModelId(String modelId) {
        ApiFormat format = API_FORMAT.get();
        if (format == ApiFormat.DEEPSEEK_CHAT) {
            return normalizeDeepSeekModelId(modelId);
        }
        String normalized = modelId == null ? "" : modelId.trim();
        return normalized.isBlank() ? format.getDefaultModel() : normalized;
    }

    public static String normalizeApiUrl(String apiUrl) {
        if (apiUrl == null) {
            return DEFAULT_API_URL;
        }
        String normalized = apiUrl.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        while (normalized.endsWith("/") && !normalized.endsWith("://")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? DEFAULT_API_URL : normalized;
    }

    public static String validateApiUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return "API URL is not configured";
        }
        try {
            URI uri = URI.create(apiUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                return "API URL must start with http:// or https://";
            }
            if (host == null || host.isBlank()) {
                return "API URL does not contain a valid host";
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (isExampleHost(normalizedHost)) {
                return "API URL is an example placeholder; enter the real provider address";
            }
            return "";
        } catch (IllegalArgumentException e) {
            return "API URL is invalid";
        }
    }

    private static boolean isExampleHost(String host) {
        return host.equals("example.com") || host.endsWith(".example.com")
                || host.equals("example.net") || host.endsWith(".example.net")
                || host.equals("example.org") || host.endsWith(".example.org")
                || host.equals("example.test") || host.endsWith(".example.test")
                || host.equals("example.invalid") || host.endsWith(".example.invalid");
    }

    public static synchronized void init(Path configDir) {
        configFile = configDir.resolve("simple_translate-client.json");
        load();
    }

    public static synchronized void load() {
        if (configFile == null) {
            return;
        }
        try {
            Files.createDirectories(configFile.getParent());
            if (!Files.exists(configFile)) {
                save();
                return;
            }
            String raw = Files.readString(configFile);
            if (raw == null || raw.isBlank()) {
                save();
                return;
            }
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            migrateLegacyKeys(root);
            loadedRoot = root.deepCopy();
            for (Map.Entry<String, ConfigValue<?>> entry : VALUES.entrySet()) {
                entry.getValue().read(root.get(entry.getKey()));
                normalizeLoadedValue(entry.getValue());
            }
        } catch (com.google.gson.JsonParseException | IllegalStateException e) {
            SimpleTranslateMod.getLogger().error("Config file is not valid JSON; backing it up before regenerating", e);
            backupCorruptConfigFile();
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to load config", e);
        }
    }

    /** A corrupt config is preserved next to the original instead of being overwritten with defaults. */
    private static void backupCorruptConfigFile() {
        try {
            if (configFile != null && Files.exists(configFile)) {
                Path backup = configFile.resolveSibling(configFile.getFileName() + ".corrupt.bak");
                Files.move(configFile, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                SimpleTranslateMod.getLogger().warn("Backed up unreadable config to {}", backup);
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized void save() {
        if (configFile == null) {
            return;
        }
        try {
            Files.createDirectories(configFile.getParent());
            JsonObject root = loadedRoot == null ? new JsonObject() : loadedRoot.deepCopy();
            for (Map.Entry<String, ConfigValue<?>> entry : VALUES.entrySet()) {
                root.add(entry.getKey(), entry.getValue().write());
            }
            loadedRoot = root.deepCopy();
            AtomicFiles.writeString(configFile, GSON.toJson(root));
        } catch (IOException e) {
            SimpleTranslateMod.getLogger().error("Failed to save config", e);
        }
    }

    private static void migrateLegacyKeys(JsonObject root) {
        captureLegacyOpenSettingsChord(root);
        copyLegacyKey(root, "content.bookButtonOffsetX", CONTENT_BOOK_BOOKMARK_OFFSET_X.getKey());
        copyLegacyKey(root, "content.bookButtonOffsetY", CONTENT_BOOK_BOOKMARK_OFFSET_Y.getKey());
        root.remove("chat.jsonPassthrough");
        root.remove("tooltip.itemJsonPassthrough");
        root.remove("tooltip.chatHoverJsonPassthrough");
        root.remove("hud.stylePrompt");
        root.remove("chat.contextBatchIntervalMs");
        root.remove("chat.contextCollectWindowMs");
        root.remove("cache.serverShareEnabled");
        root.remove("general.wynncraftProfileMode");
        root.remove("hud.wynnOverlayEnabled");
        root.remove("api.routerEnabled");
        root.remove("api.routerEndpoints");
        migrateLegacyHoldOriginalKeys(root);
        removeObsoleteHudHistoryWindowKeys(root);
        migrateLegacyLanguageDefaults(root);
    }

    /**
     * Opening the settings screen is now a normal Minecraft control. Preserve a
     * previous custom binding for this startup only, then remove the obsolete
     * private shortcut value from the mod configuration.
     */
    private static void captureLegacyOpenSettingsChord(JsonObject root) {
        String legacyKey = "shortcuts.openSettings";
        if (root != null && root.has(legacyKey) && root.get(legacyKey).isJsonPrimitive()) {
            legacyOpenSettingsChord = KeyChord.parse(root.get(legacyKey).getAsString(), KeyChord.NONE);
            root.remove(legacyKey);
        }
    }

    public static synchronized KeyChord consumeLegacyOpenSettingsChord() {
        KeyChord result = legacyOpenSettingsChord;
        legacyOpenSettingsChord = KeyChord.NONE;
        return result;
    }

    public static synchronized boolean hasPersistedKey(String key) {
        return loadedRoot != null && key != null && loadedRoot.has(key);
    }

    private static void migrateLegacyHoldOriginalKeys(JsonObject root) {
        migrateLegacySingleKey(root, "holdOriginal.key.chat", HOLD_ORIGINAL_CHORD_CHAT.getKey());
        migrateLegacySingleKey(root, "holdOriginal.key.tooltipItem", HOLD_ORIGINAL_CHORD_TOOLTIP_ITEM.getKey());
        migrateLegacySingleKey(root, "holdOriginal.key.tooltipHover", HOLD_ORIGINAL_CHORD_TOOLTIP_HOVER.getKey());
        migrateLegacySingleKey(root, "holdOriginal.key.book", HOLD_ORIGINAL_CHORD_BOOK.getKey());
        migrateLegacySingleKey(root, "holdOriginal.key.sign", HOLD_ORIGINAL_CHORD_SIGN.getKey());
        migrateLegacySingleKey(root, "holdOriginal.key.advancement", HOLD_ORIGINAL_CHORD_ADVANCEMENT.getKey());
        migrateLegacySingleKey(root, "holdOriginal.key.entityName", HOLD_ORIGINAL_CHORD_ENTITY_NAME.getKey());
        migrateLegacySingleKey(root, "holdOriginal.key.textDisplay", HOLD_ORIGINAL_CHORD_TEXT_DISPLAY.getKey());
        migrateLegacySingleKey(root, "holdOriginal.key.scoreboard", HOLD_ORIGINAL_CHORD_SCOREBOARD.getKey());
        migrateLegacySingleKey(root, "holdOriginal.key.bossbar", HOLD_ORIGINAL_CHORD_BOSSBAR.getKey());
        migrateLegacySingleKey(root, "holdOriginal.key.title", HOLD_ORIGINAL_CHORD_TITLE.getKey());
        migrateLegacySingleKey(root, "holdOriginal.key.actionbar", HOLD_ORIGINAL_CHORD_ACTIONBAR.getKey());
        migrateLegacySingleKey(root, "holdOriginal.key.gui", HOLD_ORIGINAL_CHORD_GUI.getKey());
    }

    private static void migrateLegacySingleKey(JsonObject root, String oldKey, String newKey) {
        if (!root.has(newKey) && root.has(oldKey) && root.get(oldKey).isJsonPrimitive()) {
            try {
                root.addProperty(newKey, KeyChord.keyboard(root.get(oldKey).getAsInt()).serialize());
            } catch (RuntimeException ignored) {
                // Invalid legacy values fall back to the unbound chord.
            }
        }
        root.remove(oldKey);
    }

    private static void removeObsoleteHudHistoryWindowKeys(JsonObject root) {
        if (root == null) {
            return;
        }
        root.remove("hud.history" + "OverlayEnabled");
        root.remove("hud.history" + "Anchor");
        root.remove("hud.history" + "OffsetX");
        root.remove("hud.history" + "OffsetY");
        root.remove("hud.history" + "OverlayWidth");
        root.remove("hud.history" + "OverlayHeight");
        root.remove("hud.history" + "ShowOriginal");
    }

    private static void migrateLegacyLanguageDefaults(JsonObject root) {
        if (root.has(SOURCE_LANGUAGE.getKey())
                && root.get(SOURCE_LANGUAGE.getKey()).isJsonPrimitive()
                && "en".equalsIgnoreCase(root.get(SOURCE_LANGUAGE.getKey()).getAsString())) {
            root.add(SOURCE_LANGUAGE.getKey(), new JsonPrimitive("auto"));
        }
        if (root.has(TARGET_LANGUAGE.getKey())
                && root.get(TARGET_LANGUAGE.getKey()).isJsonPrimitive()
                && "zh".equalsIgnoreCase(root.get(TARGET_LANGUAGE.getKey()).getAsString())) {
            root.add(TARGET_LANGUAGE.getKey(), new JsonPrimitive("zh_cn"));
        }
    }

    private static void copyLegacyKey(JsonObject root, String oldKey, String newKey) {
        if (root.has(newKey) || !root.has(oldKey)) {
            return;
        }
        root.add(newKey, root.get(oldKey).deepCopy());
    }

    private static <T> void normalizeLoadedValue(ConfigValue<T> value) {
        value.set(value.get());
    }

    private static ConfigValue<String> stringValue(String key, String defaultValue) {
        return register(new ConfigValue<>(
                key,
                defaultValue,
                (element, fallback) -> element != null && element.isJsonPrimitive() ? element.getAsString() : fallback,
                value -> value == null ? JsonNull.INSTANCE : new JsonPrimitive(value)));
    }

    private static BooleanValue bool(String key, boolean defaultValue) {
        return register(new BooleanValue(key, defaultValue));
    }

    private static IntValue intValue(String key, int defaultValue, int min, int max) {
        return register(new IntValue(key, defaultValue, min, max));
    }

    private static <E extends Enum<E>> EnumValue<E> enumValue(String key, E defaultValue, Class<E> enumClass) {
        return register(new EnumValue<>(key, defaultValue, enumClass));
    }

    private static <T extends ConfigValue<?>> T register(T value) {
        VALUES.put(value.getKey(), value);
        return value;
    }

    public static class ConfigValue<T> {
        private final String key;
        private final T defaultValue;
        private final BiFunction<JsonElement, T, T> parser;
        private final Function<T, JsonElement> serializer;
        private T value;

        public ConfigValue(String key, T defaultValue, BiFunction<JsonElement, T, T> parser, Function<T, JsonElement> serializer) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
            this.parser = parser;
            this.serializer = serializer;
        }

        public T get() {
            return value;
        }

        public String getKey() {
            return key;
        }

        public void set(T value) {
            this.value = value == null ? defaultValue : value;
        }

        private void read(JsonElement element) {
            try {
                this.value = parser.apply(element, defaultValue);
                if (this.value == null) {
                    this.value = defaultValue;
                }
            } catch (Exception ignored) {
                this.value = defaultValue;
            }
        }

        private JsonElement write() {
            try {
                return serializer.apply(value);
            } catch (Exception ignored) {
                return JsonNull.INSTANCE;
            }
        }
    }

    public static class BooleanValue extends ConfigValue<Boolean> {
        public BooleanValue(String key, boolean defaultValue) {
            super(
                    key,
                    defaultValue,
                    (element, fallback) -> element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback,
                    JsonPrimitive::new);
        }
    }

    public static class IntValue extends ConfigValue<Integer> {
        private final int min;
        private final int max;

        public IntValue(String key, int defaultValue, int min, int max) {
            super(
                    key,
                    defaultValue,
                    (element, fallback) -> element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback,
                    JsonPrimitive::new);
            this.min = min;
            this.max = max;
            set(defaultValue);
        }

        @Override
        public void set(Integer value) {
            int raw = value == null ? get() : value;
            int clamped = Math.max(min, Math.min(max, raw));
            super.set(clamped);
        }
    }

    public static class EnumValue<E extends Enum<E>> extends ConfigValue<E> {
        public EnumValue(String key, E defaultValue, Class<E> enumClass) {
            super(
                    key,
                    defaultValue,
                    (element, fallback) -> {
                        if (element == null || !element.isJsonPrimitive()) {
                            return fallback;
                        }
                        String raw = element.getAsString();
                        try {
                            return Enum.valueOf(enumClass, raw);
                        } catch (Exception ignored) {
                            return fallback;
                        }
                    },
                    value -> new JsonPrimitive(value.name()));
        }
    }

    public enum TranslationMode {
        AUTO,
        BUTTON
    }

    public enum GuiTranslationMode {
        SHORTCUT,
        AUTO
    }

    public enum TooltipTriggerMode {
        HOVER,
        SHORTCUT
    }

    public enum TooltipGlowTheme {
        SOFT,
        OCEAN,
        AURORA,
        SUNSET
    }

    public enum SignContextMode {
        AUTO,
        MANUAL
    }

    public enum ApiFormat {
        DEEPSEEK_CHAT("DeepSeek Chat", "deepseek-v4-flash"),
        OPENAI_CHAT_COMPAT("OpenAI Chat Compatible", "gpt-4o-mini"),
        OPENAI_RESPONSES("OpenAI Responses", "gpt-4.1-mini"),
        ANTHROPIC_MESSAGES("Anthropic Messages", "claude-3-5-haiku-latest"),
        GEMINI_GENERATE_CONTENT("Gemini generateContent", "gemini-1.5-flash"),
        LOCAL_OLLAMA("本地大模型 Ollama", "qwen2.5:7b");

        private final String displayName;
        private final String defaultModel;

        ApiFormat(String displayName, String defaultModel) {
            this.displayName = displayName;
            this.defaultModel = defaultModel;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDefaultModel() {
            return defaultModel;
        }
    }

    public enum DeepSeekModelPreset {
        V4_FLASH("DeepSeek V4 Flash", "deepseek-v4-flash"),
        V4_PRO("DeepSeek V4 Pro", "deepseek-v4-pro");

        private final String displayName;
        private final String modelId;

        DeepSeekModelPreset(String displayName, String modelId) {
            this.displayName = displayName;
            this.modelId = modelId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getModelId() {
            return modelId;
        }

        public static DeepSeekModelPreset fromModelId(String modelId) {
            String normalized = normalizeDeepSeekModelId(modelId);
            for (DeepSeekModelPreset preset : values()) {
                if (!preset.modelId.isEmpty() && preset.modelId.equalsIgnoreCase(normalized)) {
                    return preset;
                }
            }
            return V4_FLASH;
        }
    }

}
