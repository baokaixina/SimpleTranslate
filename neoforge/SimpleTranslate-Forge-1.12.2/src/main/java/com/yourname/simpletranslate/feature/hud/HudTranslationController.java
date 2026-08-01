package com.yourname.simpletranslate.feature.hud;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.chat.ChatTranslationController;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.DynamicTextTemplate;
import com.yourname.simpletranslate.core.TranslationTextDetector;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Context-batched title/subtitle/actionbar translation and toggleable chat history. */
public final class HudTranslationController {
    public static final String HISTORY_PREFIX = "simple_translate:hud_history:";
    private static final int MAX_PENDING = 64;
    private static final int MAX_READY = 512;
    private static final int MAX_HISTORY = 40;
    private static final Map<String, PendingCaption> PENDING = new LinkedHashMap<String, PendingCaption>();
    private static final Map<String, String> READY = new LinkedHashMap<String, String>();
    private static final Map<String, HistoryEntry> HISTORY = new LinkedHashMap<String, HistoryEntry>();
    private static final List<String> RECENT_CONTEXT = new ArrayList<String>();
    private static long firstPendingAt;
    private static long nextBatchAllowedAt;
    private static final Set<Long> IN_FLIGHT_BATCHES = new HashSet<Long>();
    private static long nextBatchToken = 1L;
    private static int nextHistoryId = -2000000000;
    private static long seenRuntimeRevision = -1L;

    private HudTranslationController() { }

    public enum Type {
        TITLE("title", "[标题]", TextFormatting.GOLD, HoldOriginalFeature.TITLE),
        SUBTITLE("subtitle", "[副标题]", TextFormatting.YELLOW, HoldOriginalFeature.TITLE),
        ACTIONBAR("actionbar", "[动作栏]", TextFormatting.AQUA, HoldOriginalFeature.ACTIONBAR);

        private final String id;
        private final String label;
        private final TextFormatting color;
        private final HoldOriginalFeature holdFeature;
        Type(String id, String label, TextFormatting color, HoldOriginalFeature holdFeature) {
            this.id = id; this.label = label; this.color = color; this.holdFeature = holdFeature;
        }
    }

    public static String translate(String source, Type type) {
        syncRuntimeRevision();
        if (source == null || source.isEmpty() || type == null) return source;
        if (HoldOriginalState.isHolding(type.holdFeature)) return source;
        com.yourname.simpletranslate.translation.TranslationEngine engine =
                SimpleTranslateForge1122.getEngine();
        String surface = type == Type.ACTIONBAR ? "hud.actionbar.component.direct"
                : type == Type.SUBTITLE ? "hud.subtitle.component.direct"
                : "hud.title.component.direct";
        if (engine == null || !engine.isConfigured() || !engine.isSurfaceEnabled(surface)) return source;
        if (!TranslationTextDetector.containsTranslatableText(source, 1)
                || engine.isBlacklisted(new TextComponentString(source))) return source;
        if (type == Type.TITLE || type == Type.SUBTITLE) {
            if (!ModConfig.HUD_TITLE_ENABLED.get()) return source;
        } else if (!ModConfig.HUD_ACTIONBAR_ENABLED.get()) return source;

        boolean contextual = ModConfig.HUD_TITLE_CONTEXT_ENABLED.get() || ModConfig.HUD_HISTORY_CHAT_ENABLED.get();
        if (!contextual) {
            return engine.translateStringCachedOrEnqueue(source, surface);
        }

        DynamicTextTemplate template = DynamicTextTemplate.captureText(source);
        String normalized = template.normalizedText();
        String key = type.id + '\u0000' + normalized;
        String translated;
        synchronized (HudTranslationController.class) {
            translated = READY.get(key);
            if (translated == null && !PENDING.containsKey(key)) {
                long now = System.currentTimeMillis();
                PENDING.put(key, new PendingCaption(key, type, source, normalized, now));
                if (firstPendingAt == 0L) firstPendingAt = now;
                while (PENDING.size() > MAX_PENDING) PENDING.remove(PENDING.keySet().iterator().next());
            }
        }
        return translated == null ? source : template.restoreText(translated);
    }

    public static void tick() {
        syncRuntimeRevision();
        com.yourname.simpletranslate.translation.TranslationEngine engine =
                SimpleTranslateForge1122.getEngine();
        if (engine == null || !engine.isConfigured()
                || !engine.isSurfaceEnabled("hud.captions.context.direct")) {
            synchronized (HudTranslationController.class) {
                PENDING.clear();
                firstPendingAt = 0L;
            }
            return;
        }
        final List<PendingCaption> batch = new ArrayList<PendingCaption>();
        final String context;
        final long batchToken;
        synchronized (HudTranslationController.class) {
            if (PENDING.isEmpty()) { firstPendingAt = 0L; return; }
            if(IN_FLIGHT_BATCHES.size()>=Math.max(1,Math.min(2,ModConfig.API_MAX_IN_FLIGHT_BATCHES.get())))return;
            long now = System.currentTimeMillis();
            if(IN_FLIGHT_BATCHES.isEmpty()&&now<nextBatchAllowedAt)return;
            long collectWindow = Math.max(500L, Math.min(30000L,
                    ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.get()));
            if(PENDING.size()<MAX_PENDING&&now-firstPendingAt<collectWindow)return;
            batch.addAll(PENDING.values());
            PENDING.clear();
            firstPendingAt = 0L;
            if(ModConfig.HUD_TITLE_CONTEXT_ENABLED.get()){
                StringBuilder builder = new StringBuilder("Recent HUD captions in chronological order:");
                for (String line : RECENT_CONTEXT) builder.append("\n- ").append(line);
                for (PendingCaption pending : batch) {
                    builder.append("\n- ").append(pending.type.id).append(": ").append(pending.source);
                }
                context = builder.toString();
            }else{
                context = "";
            }
            batchToken=nextBatchToken++;
            IN_FLIGHT_BATCHES.add(Long.valueOf(batchToken));
        }

        List<ITextComponent> components = new ArrayList<ITextComponent>(batch.size());
        for (PendingCaption pending : batch) components.add(new TextComponentString(pending.normalized));
        final long runtimeRevision = SimpleTranslateForge1122.getRuntimeRevision();
        DirectSurfaceTranslator.translateComponentsAsync(components, "hud.captions.context.direct",
                "hud-caption-history", true, context).whenComplete(
                new java.util.function.BiConsumer<ComponentListTranslationResult, Throwable>() {
                    @Override public void accept(final ComponentListTranslationResult result, Throwable error) {
                        Minecraft minecraft = Minecraft.getMinecraft();
                        if (minecraft == null) { synchronized(HudTranslationController.class){finishBatchClock(batchToken);} return; }
                        minecraft.addScheduledTask(new Runnable() {
                            @Override public void run() {
                                synchronized (HudTranslationController.class) {
                                    try {
                                        if (!IN_FLIGHT_BATCHES.contains(Long.valueOf(batchToken))
                                                || !SimpleTranslateForge1122.isRuntimeRevisionCurrent(runtimeRevision)) return;
                                        if (result == null || !result.translated || result.components == null
                                                || result.components.size() != batch.size()) return;
                                        for (int i = 0; i < batch.size(); i++) {
                                            PendingCaption pending = batch.get(i);
                                            String translated = result.components.get(i).getUnformattedText();
                                            READY.put(pending.key, translated);
                                            rememberContext(pending.source);
                                            if (ModConfig.HUD_HISTORY_CHAT_ENABLED.get()) publishHistory(pending, translated);
                                        }
                                        while (READY.size() > MAX_READY) READY.remove(READY.keySet().iterator().next());
                                    } finally {
                                        finishBatchClock(batchToken);
                                    }
                                }
                            }
                        });
                    }
                });
    }

    public static synchronized boolean handleHistoryClick(String value) {
        if (value == null || !value.startsWith(HISTORY_PREFIX)) return false;
        HistoryEntry entry = HISTORY.get(value);
        if (entry == null) return true;
        entry.showingOriginal = !entry.showingOriginal;
        replaceHistory(entry);
        return true;
    }

    public static synchronized void clear() {
        clearState(true);
    }

    private static void clearState(boolean resetHistoryIds) {
        PENDING.clear(); READY.clear(); HISTORY.clear(); RECENT_CONTEXT.clear();
        firstPendingAt = 0L; nextBatchAllowedAt=0L; IN_FLIGHT_BATCHES.clear();
        if (resetHistoryIds) nextHistoryId = -2000000000;
        seenRuntimeRevision = SimpleTranslateForge1122.getRuntimeRevision();
    }

    /** Replaces still-visible history controls before their backing map is reset. */
    public static synchronized void resetForSettings() {
        Minecraft minecraft = Minecraft.getMinecraft();
        GuiNewChat chat = minecraft == null || minecraft.ingameGUI == null
                ? null : minecraft.ingameGUI.getChatGUI();
        if (chat != null) {
            for (HistoryEntry entry : HISTORY.values()) {
                TextComponentString original = new TextComponentString(entry.type.label + " " + entry.original);
                original.setStyle(new Style().setColor(entry.type.color));
                ChatTranslationController.printInternal(chat, original, entry.chatId);
            }
        }
        clearState(false);
    }

    private static synchronized void syncRuntimeRevision() {
        long current = SimpleTranslateForge1122.getRuntimeRevision();
        if (seenRuntimeRevision == current) return;
        PENDING.clear(); READY.clear(); RECENT_CONTEXT.clear();
        firstPendingAt = 0L; nextBatchAllowedAt=0L; IN_FLIGHT_BATCHES.clear();
        seenRuntimeRevision = current;
    }

    private static void rememberContext(String text) {
        if (text == null || text.trim().isEmpty()) return;
        RECENT_CONTEXT.add(text);
        while (RECENT_CONTEXT.size() > 12) RECENT_CONTEXT.remove(0);
    }

    private static void finishBatchClock(long batchToken){
        if(!IN_FLIGHT_BATCHES.remove(Long.valueOf(batchToken)))return;
        if(IN_FLIGHT_BATCHES.isEmpty()){
            long interval=Math.max(500L,Math.min(10000L,ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.get()));
            nextBatchAllowedAt=System.currentTimeMillis()+interval;
        }
    }

    private static void publishHistory(PendingCaption pending, String normalizedTranslation) {
        DynamicTextTemplate current = DynamicTextTemplate.captureText(pending.source);
        String translated = current.restoreText(normalizedTranslation);
        if (translated == null || translated.trim().isEmpty() || translated.equals(pending.source)) return;
        String token = HISTORY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
                pending.key.getBytes(StandardCharsets.UTF_8));
        // Refresh insertion order when the same semantic HUD caption is shown
        // again, so the bounded history retains the most recently visible row.
        HistoryEntry entry = HISTORY.remove(token);
        if (entry == null) {
            entry = new HistoryEntry(nextHistoryId++, token, pending.type, pending.source, translated);
        } else {
            entry.original = pending.source;
            entry.translated = translated;
        }
        HISTORY.put(token, entry);
        replaceHistory(entry);
        while (HISTORY.size() > MAX_HISTORY) {
            Iterator<Map.Entry<String, HistoryEntry>> iterator = HISTORY.entrySet().iterator();
            if (!iterator.hasNext()) break;
            HistoryEntry removed = iterator.next().getValue();
            iterator.remove();
            removeHistoryLine(removed);
        }
    }

    private static void replaceHistory(HistoryEntry entry) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.ingameGUI == null) return;
        GuiNewChat chat = minecraft.ingameGUI.getChatGUI();
        ChatTranslationController.printInternal(chat, historyComponent(entry), entry.chatId);
    }

    private static void removeHistoryLine(HistoryEntry entry) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (entry == null || minecraft == null || minecraft.ingameGUI == null) return;
        ChatTranslationController.deleteInternal(minecraft.ingameGUI.getChatGUI(), entry.chatId);
    }

    private static ITextComponent historyComponent(HistoryEntry entry) {
        TextComponentString root = new TextComponentString(entry.type.label + " ");
        root.setStyle(new Style().setColor(entry.type.color));
        root.appendSibling(new TextComponentString(entry.showingOriginal ? entry.original : entry.translated)
                .setStyle(new Style().setColor(TextFormatting.WHITE)));
        String key = entry.showingOriginal
                ? "chat.simple_translate.hud_caption.show_translation"
                : "chat.simple_translate.hud_caption.show_original";
        String hoverKey = key + ".hover";
        TextComponentTranslation button = new TextComponentTranslation(key);
        button.setStyle(new Style()
                .setColor(entry.showingOriginal ? TextFormatting.AQUA : TextFormatting.YELLOW)
                .setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, entry.token))
                .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponentTranslation(hoverKey))));
        root.appendSibling(button);
        return root;
    }

    private static final class PendingCaption {
        final String key; final Type type; final String source; final String normalized; final long createdAt;
        PendingCaption(String key, Type type, String source, String normalized, long createdAt) {
            this.key = key; this.type = type; this.source = source; this.normalized = normalized; this.createdAt = createdAt;
        }
    }

    private static final class HistoryEntry {
        final int chatId; final String token; final Type type;
        String original; String translated; boolean showingOriginal;
        HistoryEntry(int chatId, String token, Type type, String original, String translated) {
            this.chatId = chatId; this.token = token; this.type = type; this.original = original; this.translated = translated;
        }
    }
}
