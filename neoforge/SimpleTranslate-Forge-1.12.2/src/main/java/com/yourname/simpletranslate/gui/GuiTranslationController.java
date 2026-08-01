package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.TranslationTextDetector;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.mixin.GuiLabelAccessor;
import com.yourname.simpletranslate.mixin.GuiScreenAccessor;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiLabel;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.advancements.GuiScreenAdvancements;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.inventory.IInventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Ordered visible-frame GUI translation for the legacy FontRenderer pipeline. */
public final class GuiTranslationController {
    private static final String SCREEN_KEY_SEPARATOR = "\u0000";
    private static final int MAX_COMPONENTS = 96;
    private static final int MAX_SNAPSHOTS = 24;
    private static final int MAX_TRANSLATIONS_PER_SNAPSHOT = 2048;
    private static final Pattern URL_OR_ADDRESS = Pattern.compile(
            "(?i)^(?:https?://|www\\.|play\\.)?[^\\s/]+\\.[a-z]{2,}(?:[/:].*)?$");
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final Pattern UUID_OR_VERSION = Pattern.compile(
            "(?i)^(?:[0-9a-f]{8}-[0-9a-f-]{27}|v?\\d+(?:\\.\\d+){1,4})$");
    private static final Pattern TECH_TOKEN = Pattern.compile(
            "(?i)^(?:API|FPS|TPS|NBT|UUID|ID|URL|HTTP|HTTPS|IP|GUI|UI|CPU|GPU|RAM|VRAM)$");
    private static final Map<GuiScreen, String> SCREEN_KEYS = new IdentityHashMap<GuiScreen, String>();
    private static final Map<GuiScreen, Boolean> MANUAL = new IdentityHashMap<GuiScreen, Boolean>();
    private static final Map<GuiScreen, FrameState> FRAMES = new IdentityHashMap<GuiScreen, FrameState>();
    private static final Map<String, Map<String, String>> SNAPSHOTS =
            new LinkedHashMap<String, Map<String, String>>(16, 0.75F, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) {
                    return size() > MAX_SNAPSHOTS;
                }
            };
    private static final Set<String> PENDING = new HashSet<String>();
    private static final Set<String> PENDING_FRAME_KEYS = new HashSet<String>();
    private static final Map<String, Long> FAILED_UNTIL = new LinkedHashMap<String, Long>(32, 0.75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) { return size() > 64; }
    };
    private static final Set<GuiScreen> LAST_ACTIVE = Collections.newSetFromMap(new IdentityHashMap<GuiScreen, Boolean>());
    private static final Map<GuiScreen, Integer> LAST_COUNTS = new IdentityHashMap<GuiScreen, Integer>();
    private static final Map<GuiScreen, Status> LAST_STATUS = new IdentityHashMap<GuiScreen, Status>();
    private static FrameState activeFrame;
    private static FrameState tipsOverlayFrame;
    private static FrameState tipsSuspendedFrame;
    private static int tipsOverlayDepth;
    private static int dedicatedTooltipDepth;
    private static int textInputDepth;
    private static final Map<String, String> HUD_SNAPSHOT = new LinkedHashMap<String, String>();
    private static final Set<String> HUD_PENDING = new HashSet<String>();
    private static final Map<String, Long> HUD_FAILED_UNTIL = new LinkedHashMap<String, Long>(16, 0.75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) { return size() > 32; }
    };
    private static Status hudStatus = Status.NONE;
    private static int hudStatusCount;
    private static long hudStatusUntil;
    private static long seenRuntimeRevision = -1L;
    private static long controllerRevision;
    private static GuiScreen observedScreen;

    private GuiTranslationController() { }

    public static synchronized void toggle(GuiScreen screen) {
        if (screen == null || excluded(screen) || !ModConfig.GLOBAL_ENABLED.get()
                || !ModConfig.CONTENT_GUI_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.GUI)) return;
        String key = screenKey(screen);
        if (ModConfig.CONTENT_GUI_MODE.get() == ModConfig.GuiTranslationMode.SHORTCUT) {
            Set<String> persisted = persistedScreenKeys();
            MANUAL.put(screen, Boolean.TRUE);
            persisted.remove(screen.getClass().getName());
            if (persisted.add(key)) {
                while (persisted.size() > 16) persisted.remove(persisted.iterator().next());
                ModConfig.CONTENT_GUI_FRAME_SCREEN_KEYS.set(join(persisted));
                ModConfig.save();
            }
        }
        Map<String, String> snapshot = SNAPSHOTS.get(key);
        LAST_STATUS.put(screen, snapshot == null || snapshot.isEmpty()
                ? Status.COLLECTING : Status.COMPLETE);
    }

    public static synchronized boolean isEnabled(GuiScreen screen) {
        if (screen == null || excluded(screen) || HoldOriginalState.isHolding(HoldOriginalFeature.GUI)) return false;
        if (ModConfig.CONTENT_GUI_MODE.get() == ModConfig.GuiTranslationMode.AUTO || MANUAL.containsKey(screen)) {
            return true;
        }
        Set<String> persisted = persistedScreenKeys();
        String key = screenKey(screen);
        if (persisted.contains(key)) return true;
        String legacy = screen.getClass().getName();
        if (!legacy.equals(key) && persisted.remove(legacy)) {
            persisted.add(key);
            while (persisted.size() > 16) persisted.remove(persisted.iterator().next());
            ModConfig.CONTENT_GUI_FRAME_SCREEN_KEYS.set(join(persisted));
            ModConfig.save();
            return true;
        }
        return false;
    }

    /** K is a repeatable request/refresh action, never an on/off toggle. */
    public static synchronized void requestHudTranslation() {
        if (!ModConfig.GLOBAL_ENABLED.get() || !ModConfig.CONTENT_GUI_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.GUI)) return;
        ModConfig.CONTENT_HUD_FRAME_ACTIVE.set(true);
        ModConfig.save();
        hudStatus = HUD_SNAPSHOT.isEmpty() ? Status.COLLECTING : Status.COMPLETE;
        hudStatusCount = HUD_SNAPSHOT.size();
        hudStatusUntil = HUD_SNAPSHOT.isEmpty() ? Long.MAX_VALUE : System.currentTimeMillis() + 2500L;
    }

    public static synchronized void beginFrame(GuiScreen screen) {
        observeScreen(screen);
        syncRuntimeRevision();
        if (!isEnabled(screen) || SimpleTranslateForge1122.getEngine() == null
                || !SimpleTranslateForge1122.getEngine().isConfigured()
                || !SimpleTranslateForge1122.getEngine().isSurfaceEnabled("gui")) {
            activeFrame = null;
            LAST_ACTIVE.remove(screen);
            return;
        }
        String key = screenKey(screen);
        FrameState state = new FrameState(screen, key, SNAPSHOTS.get(key));
        dedicatedTooltipDepth = 0;
        textInputDepth = 0;
        collectTextInputValues(screen, state);
        GuiScreenAccessor access = (GuiScreenAccessor) screen;
        for (GuiButton button : access.simpletranslate$getButtons()) {
            if (button == null || blank(button.displayString)) continue;
            state.collect(button.displayString);
            String translated = state.translation(button.displayString);
            if (translated != null) {
                state.buttons.put(button, button.displayString);
                button.displayString = fitButtonLabel(translated, button.width);
                state.renderedValues.add(button.displayString);
            }
        }
        for (GuiLabel label : access.simpletranslate$getLabels()) {
            if (label == null) continue;
            List<String> lines = ((GuiLabelAccessor) label).simpletranslate$getLines();
            if (lines == null || lines.isEmpty()) continue;
            List<String> originals = null;
            for (int i = 0; i < lines.size(); i++) {
                String source = lines.get(i);
                if (blank(source)) continue;
                state.collect(source);
                String translated = state.translation(source);
                if (translated != null) {
                    if (originals == null) originals = new ArrayList<String>(lines);
                    lines.set(i, translated);
                    state.renderedValues.add(translated);
                }
            }
            if (originals != null) state.labels.put(label, originals);
        }
        FRAMES.put(screen, state);
        LAST_ACTIVE.add(screen);
        LAST_STATUS.put(screen, Status.COLLECTING);
        activeFrame = state;
    }

    /** Called from the exact FontRenderer measurement method while a screen frame is open. */
    public static synchronized String transformVisibleText(String source) {
        if (GuiLayoutProgramRenderer.isReplaying()) return source;
        FrameState state = activeFrame;
        if (state == null || dedicatedTooltipDepth > 0 || textInputDepth > 0 || blank(source)
                || state.inputValues.contains(source) || state.renderedValues.contains(source)) return source;
        state.collect(source);
        String translated = state.translation(source);
        if (translated == null) return source;
        state.renderedValues.add(translated);
        if (GuiLayoutProgramRenderer.isLayoutProgram(source)
                && GuiLayoutProgramRenderer.hasCompatibleVisualRuns(source, translated)) return source;
        return translated;
    }

    /** Replays one exact private FontRenderer render pass with the translated text. */
    public static synchronized Integer renderVisibleText(net.minecraft.client.gui.FontRenderer font,
                                                         String source, float x, float y,
                                                         int color, boolean shadowPass) {
        if (GuiLayoutProgramRenderer.isReplaying()) return null;
        FrameState state = activeFrame;
        if (state == null || dedicatedTooltipDepth > 0 || textInputDepth > 0 || blank(source)
                || state.inputValues.contains(source) || state.renderedValues.contains(source)) return null;
        state.collect(source);
        String translated = state.translation(source);
        if (translated == null || translated.equals(source)) return null;
        state.renderedValues.add(translated);
        Integer layoutResult = GuiLayoutProgramRenderer.renderText(
                font, source, translated, x, y, color, shadowPass);
        return layoutResult != null ? layoutResult
                : GuiLayoutProgramRenderer.renderPlainPass(font, translated, x, y, color, shadowPass);
    }

    public static synchronized void endFrame(GuiScreen screen) {
        FrameState state = FRAMES.remove(screen);
        if (activeFrame == state) activeFrame = null;
        if (state == null) return;
        restoreFrame(state);
        LAST_COUNTS.put(screen, Integer.valueOf(state.translatedCount()));
        if (state.sources.isEmpty()) {
            LAST_STATUS.put(screen, Status.EMPTY);
        } else {
            requestSnapshot(state);
        }
    }

    public static synchronized void beginDedicatedTooltip() { dedicatedTooltipDepth++; }
    public static synchronized void endDedicatedTooltip() {
        dedicatedTooltipDepth = Math.max(0, dedicatedTooltipDepth - 1);
    }
    public static synchronized void beginTextInput() { textInputDepth++; }
    public static synchronized void endTextInput() { textInputDepth = Math.max(0, textInputDepth - 1); }

    /**
     * Opens the exact Tips 1.12.2 render window. Tips draws outside the normal
     * screen widget list, so its product toggle needs an explicit frame rather
     * than depending on AUTO/K whole-screen translation being active.
     */
    public static synchronized void beginTipsOverlay() {
        if (tipsOverlayDepth++ > 0) return;
        syncRuntimeRevision();
        tipsSuspendedFrame = activeFrame;
        tipsOverlayFrame = null;
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (!ModConfig.GLOBAL_ENABLED.get() || !ModConfig.CONTENT_GUI_ENABLED.get()
                || !ModConfig.MOD_TRANSLATION_ENABLED.get() || !ModConfig.MOD_TIPS_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.GUI)
                || engine == null || !engine.isConfigured() || !engine.isSurfaceEnabled("tips.overlay")) {
            activeFrame = null;
            return;
        }
        if (activeFrame != null) return;
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (screen == null) {
            activeFrame = null;
            return;
        }
        String key = "tips:" + screenKey(screen);
        tipsOverlayFrame = new FrameState(screen, key, SNAPSHOTS.get(key));
        activeFrame = tipsOverlayFrame;
    }

    public static synchronized void endTipsOverlay() {
        if (tipsOverlayDepth <= 0 || --tipsOverlayDepth > 0) return;
        FrameState completed = tipsOverlayFrame;
        tipsOverlayFrame = null;
        activeFrame = tipsSuspendedFrame;
        tipsSuspendedFrame = null;
        if (completed != null) requestSnapshot(completed, "tips.overlay.component.v1",
                "Tips 1.12.2 loading-screen title and body.");
    }

    public static synchronized void translateHudFrame(final List<String> left, final List<String> right) {
        syncRuntimeRevision();
        if (!ModConfig.CONTENT_HUD_FRAME_ACTIVE.get() || !ModConfig.CONTENT_GUI_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.GUI)) return;
        final List<String> allSources = new ArrayList<String>();
        collectHudLines(left, allSources);
        collectHudLines(right, allSources);
        if (allSources.isEmpty()) {
            if (hudStatus == Status.COLLECTING || hudStatus == Status.TRANSLATING) {
                setHudStatus(Status.EMPTY, 0, 2500L);
            }
            return;
        }
        applyHudSnapshot(left);
        applyHudSnapshot(right);
        final List<String> sources = new ArrayList<String>();
        for (String source : allSources) if (!HUD_SNAPSHOT.containsKey(source)) sources.add(source);
        if (sources.isEmpty()) {
            if (hudStatus == Status.COLLECTING || hudStatus == Status.TRANSLATING) {
                setHudStatus(Status.COMPLETE, allSources.size(), 2500L);
            }
            return;
        }
        final String retryKey = sources.toString();
        Long retryAt = HUD_FAILED_UNTIL.get(retryKey);
        if (retryAt != null && System.currentTimeMillis() < retryAt.longValue()) return;
        HUD_FAILED_UNTIL.remove(retryKey);
        final long requestControllerRevision=controllerRevision;
        final String requestKey=requestControllerRevision+"\u0000"+retryKey;
        if (!HUD_PENDING.isEmpty()) return;
        HUD_PENDING.add(requestKey);
        setHudStatus(Status.TRANSLATING, allSources.size(), Long.MAX_VALUE);
        final long runtimeRevision = SimpleTranslateForge1122.getRuntimeRevision();
        List<ITextComponent> components = new ArrayList<ITextComponent>(sources.size());
        for (String source : sources) components.add(new TextComponentString(source));
        DirectSurfaceTranslator.translateComponentsAsync(components, "hud.visible_frame.component.v2",
                "hud-visible-frame", true, "Ordered visible in-world HUD text frame.")
                .whenComplete(new java.util.function.BiConsumer<ComponentListTranslationResult, Throwable>() {
                    @Override public void accept(final ComponentListTranslationResult result, Throwable error) {
                        Minecraft minecraft = Minecraft.getMinecraft();
                        if (minecraft == null) { synchronized (GuiTranslationController.class) { HUD_PENDING.remove(requestKey); } return; }
                        minecraft.addScheduledTask(new Runnable() {
                            @Override public void run() {
                                synchronized (GuiTranslationController.class) {
                                    try {
                                        if(requestControllerRevision!=controllerRevision
                                                ||!SimpleTranslateForge1122.isRuntimeRevisionCurrent(runtimeRevision)) return;
                                        if (error != null || result == null || !result.translated || result.components == null
                                                || result.components.size() != sources.size()
                                                ) {
                                            setHudStatus(Status.FAILED, 0, 3000L);
                                            HUD_FAILED_UNTIL.put(retryKey,
                                                    Long.valueOf(System.currentTimeMillis() + 6000L));
                                            return;
                                        }
                                        int changed=0;
                                        for (int i = 0; i < sources.size(); i++) {
                                            String translated=result.components.get(i).getFormattedText();
                                            HUD_SNAPSHOT.put(sources.get(i),translated);
                                            if(!translated.equals(sources.get(i)))changed++;
                                        }
                                        while (HUD_SNAPSHOT.size() > 256) HUD_SNAPSHOT.remove(HUD_SNAPSHOT.keySet().iterator().next());
                                        setHudStatus(changed==0?Status.EMPTY:Status.COMPLETE, changed, 2500L);
                                        HUD_FAILED_UNTIL.remove(retryKey);
                                    } finally { HUD_PENDING.remove(requestKey); }
                                }
                            }
                        });
                    }
                });
    }

    public static synchronized void clearRuntimeState() {
        controllerRevision++;
        for (FrameState frame : FRAMES.values()) restoreFrame(frame);
        FRAMES.clear(); MANUAL.clear(); SCREEN_KEYS.clear(); SNAPSHOTS.clear(); PENDING.clear(); PENDING_FRAME_KEYS.clear(); FAILED_UNTIL.clear();
        LAST_ACTIVE.clear(); LAST_COUNTS.clear(); LAST_STATUS.clear();
        HUD_SNAPSHOT.clear(); HUD_PENDING.clear(); HUD_FAILED_UNTIL.clear();
        hudStatus = Status.NONE; hudStatusCount = 0; hudStatusUntil = 0L;
        activeFrame = null; tipsOverlayFrame = null; tipsSuspendedFrame = null;
        dedicatedTooltipDepth = 0; textInputDepth = 0; tipsOverlayDepth = 0;
        observedScreen = null;
        seenRuntimeRevision = SimpleTranslateForge1122.getRuntimeRevision();
        GuiLayoutProgramRenderer.clearLocalState();
    }

    /** Keeps per-screen identity/status state bounded to the currently open GUI. */
    public static synchronized void observeScreen(GuiScreen screen) {
        if (observedScreen == screen) return;
        GuiScreen previous = observedScreen;
        observedScreen = screen;
        if (previous == null) return;
        FrameState frame = FRAMES.remove(previous);
        if (frame != null) restoreFrame(frame);
        if (activeFrame == frame) activeFrame = null;
        MANUAL.remove(previous);
        SCREEN_KEYS.remove(previous);
        LAST_ACTIVE.remove(previous);
        LAST_COUNTS.remove(previous);
        LAST_STATUS.remove(previous);
    }

    public static synchronized void renderStatus(GuiScreen screen) {
        if (screen == null || !LAST_ACTIVE.contains(screen)) return;
        int count = LAST_COUNTS.containsKey(screen) ? LAST_COUNTS.get(screen).intValue() : 0;
        Status status = LAST_STATUS.containsKey(screen) ? LAST_STATUS.get(screen) : Status.COLLECTING;
        String text = status == Status.EMPTY ? I18n.format("screen.simple_translate.gui.status.empty")
                : status == Status.FAILED ? I18n.format("screen.simple_translate.gui.status.failed")
                : status == Status.TRANSLATING ? I18n.format("screen.simple_translate.gui.status.translating")
                : status == Status.COMPLETE ? I18n.format("screen.simple_translate.gui.status.complete", count)
                : I18n.format("screen.simple_translate.gui.status.collecting");
        net.minecraft.client.gui.FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        int color = status == Status.FAILED ? 0xFF7777 : status == Status.COMPLETE ? 0x55FF55 : 0xFFFFFF;
        int x = screen.width - font.getStringWidth(text) - 6;
        net.minecraft.client.gui.Gui.drawRect(x - 4, 4, screen.width - 4, 19, 0xB0101010);
        font.drawStringWithShadow(text, x, 6.0F, color);
    }

    public static synchronized void renderHudStatus() {
        if (hudStatus == Status.NONE) return;
        if (hudStatusUntil != Long.MAX_VALUE && System.currentTimeMillis() > hudStatusUntil) {
            hudStatus = Status.NONE;
            return;
        }
        String text = hudStatus == Status.EMPTY ? I18n.format("screen.simple_translate.gui.status.hud.empty")
                : hudStatus == Status.FAILED ? I18n.format("screen.simple_translate.gui.status.hud.failed")
                : hudStatus == Status.TRANSLATING ? I18n.format("screen.simple_translate.gui.status.hud.translating")
                : hudStatus == Status.COMPLETE ? I18n.format("screen.simple_translate.gui.status.hud.complete", hudStatusCount)
                : I18n.format("screen.simple_translate.gui.status.hud.collecting");
        Minecraft minecraft = Minecraft.getMinecraft();
        net.minecraft.client.gui.ScaledResolution scaled = new net.minecraft.client.gui.ScaledResolution(minecraft);
        net.minecraft.client.gui.FontRenderer font = minecraft.fontRenderer;
        int color = hudStatus == Status.FAILED ? 0xFF7777 : hudStatus == Status.COMPLETE ? 0x55FF55 : 0xFFFFFF;
        int width = scaled.getScaledWidth();
        int x = width - font.getStringWidth(text) - 6;
        net.minecraft.client.gui.Gui.drawRect(x - 4, 4, width - 4, 19, 0xB0101010);
        font.drawStringWithShadow(text, x, 6.0F, color);
    }

    private static void requestSnapshot(final FrameState state) {
        requestSnapshot(state, "gui.component.visible_frame.v3",
                "Visible screen class: " + state.screen.getClass().getName());
    }

    private static void requestSnapshot(final FrameState state, String surface, String context) {
        if (state.sources.isEmpty()) return;
        final List<String> sources = new ArrayList<String>();
        for (String source : state.sources.keySet()) if (!state.snapshot.containsKey(source)) sources.add(source);
        if (sources.isEmpty()) {
            LAST_STATUS.put(state.screen, Status.COMPLETE);
            return;
        }
        final String retryKey = state.screenKey + '\u0000' + sources.toString();
        Long retryAt = FAILED_UNTIL.get(retryKey);
        if (retryAt != null && System.currentTimeMillis() < retryAt.longValue()) {
            LAST_STATUS.put(state.screen, Status.FAILED);
            return;
        }
        FAILED_UNTIL.remove(retryKey);
        final long requestControllerRevision=controllerRevision;
        final String framePendingKey=requestControllerRevision+"\u0000"+state.screenKey;
        final String requestKey=requestControllerRevision+"\u0000"+retryKey;
        if (!PENDING_FRAME_KEYS.add(framePendingKey)) {
            LAST_STATUS.put(state.screen, Status.TRANSLATING);
            return;
        }
        PENDING.add(requestKey);
        LAST_STATUS.put(state.screen, Status.TRANSLATING);
        final long runtimeRevision = SimpleTranslateForge1122.getRuntimeRevision();
        List<ITextComponent> components = new ArrayList<ITextComponent>(sources.size());
        for (String source : sources) components.add(new TextComponentString(source));
        DirectSurfaceTranslator.translateComponentsAsync(components, surface,
                "gui-visible-frame", true, context)
                .whenComplete(new java.util.function.BiConsumer<ComponentListTranslationResult, Throwable>() {
                    @Override public void accept(final ComponentListTranslationResult result, Throwable error) {
                        Minecraft minecraft = Minecraft.getMinecraft();
                        if (minecraft == null) { synchronized (GuiTranslationController.class) { PENDING.remove(requestKey);PENDING_FRAME_KEYS.remove(framePendingKey); } return; }
                        minecraft.addScheduledTask(new Runnable() {
                            @Override public void run() {
                                synchronized (GuiTranslationController.class) {
                                    try {
                                        if(requestControllerRevision!=controllerRevision
                                                ||!SimpleTranslateForge1122.isRuntimeRevisionCurrent(runtimeRevision)) return;
                                        if (error != null || result == null || !result.translated || result.components == null
                                                || result.components.size() != sources.size()
                                                ) {
                                            if (observedScreen == state.screen) LAST_STATUS.put(state.screen, Status.FAILED);
                                            FAILED_UNTIL.put(retryKey,
                                                    Long.valueOf(System.currentTimeMillis() + 6000L));
                                            return;
                                        }
                                        Map<String, String> snapshot = SNAPSHOTS.get(state.screenKey);
                                        if (snapshot == null) snapshot = new LinkedHashMap<String, String>();
                                        else snapshot = new LinkedHashMap<String, String>(snapshot);
                                        int changed=0;
                                        for (int i = 0; i < sources.size(); i++) {
                                            String translated=result.components.get(i).getFormattedText();
                                            snapshot.put(sources.get(i),translated);
                                            if(!translated.equals(sources.get(i)))changed++;
                                        }
                                        while (snapshot.size() > MAX_TRANSLATIONS_PER_SNAPSHOT) {
                                            snapshot.remove(snapshot.keySet().iterator().next());
                                        }
                                        SNAPSHOTS.put(state.screenKey, snapshot);
                                        if (observedScreen == state.screen) {
                                            LAST_COUNTS.put(state.screen,Integer.valueOf(changed));
                                            LAST_STATUS.put(state.screen, changed==0?Status.EMPTY:Status.COMPLETE);
                                        }
                                        FAILED_UNTIL.remove(retryKey);
                                    } finally { PENDING.remove(requestKey);PENDING_FRAME_KEYS.remove(framePendingKey); }
                                }
                            }
                        });
                    }
                });
    }

    private static void restoreFrame(FrameState state) {
        for (Map.Entry<GuiButton, String> entry : state.buttons.entrySet()) entry.getKey().displayString = entry.getValue();
        for (Map.Entry<GuiLabel, List<String>> entry : state.labels.entrySet()) {
            List<String> current = ((GuiLabelAccessor) entry.getKey()).simpletranslate$getLines();
            if (current != null) { current.clear(); current.addAll(entry.getValue()); }
        }
    }

    private static void syncRuntimeRevision() {
        long current = SimpleTranslateForge1122.getRuntimeRevision();
        if (seenRuntimeRevision == current) return;
        controllerRevision++;
        SNAPSHOTS.clear(); PENDING.clear(); PENDING_FRAME_KEYS.clear(); FAILED_UNTIL.clear();
        HUD_SNAPSHOT.clear(); HUD_PENDING.clear(); HUD_FAILED_UNTIL.clear();
        seenRuntimeRevision = current;
    }

    private static void collectHudLines(List<String> lines, List<String> output) {
        if (lines == null) return;
        for (String line : lines) {
            if (output.size() >= MAX_COMPONENTS) return;
            if (!blank(line) && TranslationTextDetector.containsTranslatableText(line, 1)
                    && (SimpleTranslateForge1122.getEngine()==null
                    || !SimpleTranslateForge1122.getEngine().containsBlacklistedText(line))
                    && !output.contains(line)) output.add(line);
        }
    }

    private static void applyHudSnapshot(List<String> lines) {
        if (lines == null) return;
        for (int i = 0; i < lines.size(); i++) {
            String translated = HUD_SNAPSHOT.get(lines.get(i));
            if (translated != null) lines.set(i, translated);
        }
    }

    private static boolean excluded(GuiScreen screen) {
        if (screen == null) return true;
        String name = screen.getClass().getName();
        boolean ftb = name.startsWith("dev.ftb.") || name.startsWith("com.feed_the_beast.");
        return name.startsWith("com.yourname.simpletranslate.gui.") || screen instanceof GuiChat
                || screen instanceof GuiScreenBook || screen instanceof GuiScreenAdvancements
                || (ftb && (!ModConfig.MOD_TRANSLATION_ENABLED.get() || !ModConfig.MOD_FTB_QUESTS_ENABLED.get()));
    }

    private static String screenKey(GuiScreen screen) {
        String cached = SCREEN_KEYS.get(screen);
        if (cached != null) return cached;
        String identity = screenIdentity(screen);
        String key = screen.getClass().getName() + (identity.isEmpty() ? "" : "\n" +
                com.yourname.simpletranslate.cache.CacheKey.hash(identity));
        SCREEN_KEYS.put(screen, key);
        return key;
    }

    private static String screenIdentity(GuiScreen screen) {
        StringBuilder identity = new StringBuilder();
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try { fields = type.getDeclaredFields(); }
            catch (Throwable ignored) { continue; }
            for (java.lang.reflect.Field field : fields) {
                String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                boolean inventory = IInventory.class.isAssignableFrom(field.getType());
                boolean component = ITextComponent.class.isAssignableFrom(field.getType())
                        && (name.contains("title") || name.contains("header") || name.contains("name"));
                boolean titleString = field.getType() == String.class && name.contains("title");
                if (!inventory && !component && !titleString) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    String text = "";
                    if (value instanceof IInventory) {
                        ITextComponent display = ((IInventory) value).getDisplayName();
                        text = display == null ? "" : display.getUnformattedText();
                    } else if (value instanceof ITextComponent) {
                        text = ((ITextComponent) value).getUnformattedText();
                    } else if (value instanceof String) {
                        text = (String) value;
                    }
                    if (!blank(text)) identity.append(name).append('=').append(text).append('\n');
                } catch (Throwable ignored) { }
            }
        }
        return identity.toString();
    }

    static Set<String> persistedScreenKeys() {
        Set<String> result = new java.util.LinkedHashSet<String>();
        String raw = ModConfig.CONTENT_GUI_FRAME_SCREEN_KEYS.get();
        if (raw != null) {
            String[] parts;
            if (raw.startsWith("v2" + SCREEN_KEY_SEPARATOR)) {
                parts = raw.substring(3).split(Pattern.quote(SCREEN_KEY_SEPARATOR), -1);
            } else {
                parts = raw.indexOf('\u0000') >= 0
                        ? raw.split(Pattern.quote(SCREEN_KEY_SEPARATOR), -1) : raw.split("\\n");
            }
            for (String part : parts) if (!part.trim().isEmpty()) result.add(part.trim());
        }
        return result;
    }

    static String join(Set<String> values) {
        List<String> ordered = new ArrayList<String>(values);
        Collections.sort(ordered);
        if (ordered.isEmpty()) return "";
        StringBuilder out = new StringBuilder("v2");
        for (String value : ordered) out.append(SCREEN_KEY_SEPARATOR).append(value);
        return out.toString();
    }

    private static String fitButtonLabel(String value, int buttonWidth) {
        if (blank(value)) return value == null ? "" : value;
        net.minecraft.client.gui.FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        int maximum = Math.max(8, buttonWidth - 8);
        if (font.getStringWidth(value) <= maximum) return value;
        String ellipsis = "...";
        return font.trimStringToWidth(value, Math.max(0, maximum - font.getStringWidth(ellipsis))) + ellipsis;
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private static boolean shouldCollect(String value) {
        if (blank(value) || !TranslationTextDetector.containsTranslatableText(value, 1)) return false;
        String trimmed = TranslationTextDetector.normalizeForDetection(value).trim();
        if (trimmed.isEmpty() || URL_OR_ADDRESS.matcher(trimmed).matches()
                || NAMESPACED_ID.matcher(trimmed).matches()
                || UUID_OR_VERSION.matcher(trimmed).matches()
                || TECH_TOKEN.matcher(trimmed).matches()) return false;
        return !trimmed.startsWith("/") && !trimmed.contains("\\")
                && !trimmed.matches("^[A-Za-z]:[/\\\\].*");
    }

    private static void collectTextInputValues(GuiScreen screen, FrameState state) {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try { fields = type.getDeclaredFields(); }
            catch (Throwable ignored) { continue; }
            for (java.lang.reflect.Field field : fields) {
                if (!GuiTextField.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    GuiTextField input = (GuiTextField) field.get(screen);
                    if (input != null && !blank(input.getText())) state.inputValues.add(input.getText());
                } catch (Throwable ignored) { }
            }
        }
    }

    private static void setHudStatus(Status status, int count, long durationMillis) {
        hudStatus = status;
        hudStatusCount = count;
        hudStatusUntil = durationMillis == Long.MAX_VALUE
                ? Long.MAX_VALUE : System.currentTimeMillis() + durationMillis;
    }

    private static final class FrameState {
        final GuiScreen screen; final String screenKey; final Map<String, String> snapshot;
        final LinkedHashMap<String, Boolean> sources = new LinkedHashMap<String, Boolean>();
        final Set<String> renderedValues = new HashSet<String>();
        final Set<String> inputValues = new HashSet<String>();
        final Map<GuiButton, String> buttons = new IdentityHashMap<GuiButton, String>();
        final Map<GuiLabel, List<String>> labels = new IdentityHashMap<GuiLabel, List<String>>();
        FrameState(GuiScreen screen, String screenKey, Map<String, String> snapshot) {
            this.screen = screen; this.screenKey = screenKey;
            this.snapshot = snapshot == null ? Collections.<String, String>emptyMap() : snapshot;
        }
        void collect(String value) {
            TranslationEngine engine=SimpleTranslateForge1122.getEngine();
            if (sources.size() < MAX_COMPONENTS && shouldCollect(value)
                    && (engine==null||!engine.containsBlacklistedText(value))) sources.put(value, Boolean.TRUE);
        }
        String translation(String value) { return snapshot.get(value); }
        int translatedCount(){int count=0;for(String source:sources.keySet()){String value=snapshot.get(source);if(value!=null&&!value.equals(source))count++;}return count;}
    }

    private enum Status { NONE, COLLECTING, TRANSLATING, COMPLETE, EMPTY, FAILED }
}
