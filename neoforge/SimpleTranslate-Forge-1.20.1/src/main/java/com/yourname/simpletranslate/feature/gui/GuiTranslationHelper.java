package com.yourname.simpletranslate.feature.gui;

import com.google.gson.JsonParser;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.ComponentJsonCompat;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.ComponentVisualProjection;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.ActiveFontManager;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import com.yourname.simpletranslate.core.TranslationTextDetector;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.util.FormattedCharSequence;

/**
 * Collects visible GUI Components during one screen frame and translates them
 * as one ordered Component JSON array. The model never owns layout or styling:
 * the shared visual projection rebinds semantic text into the exact local tree.
 */
public final class GuiTranslationHelper {
    private static final String GUI_SURFACE = "gui.component.visible_frame.v3";
    private static final String ITEM_TOOLTIP_SURFACE = "gui.component.visible_frame.item_tooltip.v1";
    private static final String ADVANCEMENT_SURFACE = "gui.component.visible_frame.advancement.v1";
    private static final String HUD_SURFACE = "hud.visible_frame.component.v2";
    private static final String ROLE = "gui-visible-frame";
    private static final String HUD_FRAME_KEY = "hud.visible_frame.v2";
    private static final int MAX_COMPONENTS = 96;
    private static final int MAX_CONTEXT_OCCURRENCES = 128;
    /** Shared by ordinary GUI/HUD detached frames (not item tooltips). */
    private static final int MAX_SNAPSHOTS = 24;
    /**
     * Item tooltips keep a dedicated LRU so scanning a full inventory cannot
     * evict every previously hydrated item after a few other screens/frames.
     * Disk cache still backs cold starts after process restart.
     */
    private static final int MAX_ITEM_TOOLTIP_SNAPSHOTS = 256;
    private static final int MAX_TRANSLATIONS_PER_SNAPSHOT = 2048;
    private static final int MAX_COMPOSITE_CLASSIFICATIONS = 64;
    private static final Pattern URL_OR_ADDRESS = Pattern.compile(
            "(?i)^(?:https?://|www\\.|play\\.)?[^\\s/]+\\.[a-z]{2,}(?:[/:].*)?$");
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final Pattern UUID_OR_VERSION = Pattern.compile(
            "(?i)^(?:[0-9a-f]{8}-[0-9a-f-]{27}|v?\\d+(?:\\.\\d+){1,4})$");
    private static final Pattern TECH_TOKEN = Pattern.compile(
            "(?i)^(?:API|FPS|TPS|NBT|UUID|ID|URL|HTTP|HTTPS|IP|GUI|UI|CPU|GPU|RAM|VRAM)$");
    private static final Pattern VANILLA_FORMAT_TOKEN = Pattern.compile(
            "%(?:(\\d+)\\$)?([%s])");
    private static final ResourceLocation VANILLA_EN_US =
            new ResourceLocation("minecraft", "lang/en_us.json");
    /** Detached tooltip/toast frames may temporarily nest inside a GUI/HUD frame. */
    private static final ThreadLocal<ArrayDeque<FrameCapture>> ACTIVE_FRAMES =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Integer> CAPTURE_SUPPRESSION_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<ArrayDeque<String>> DIRECT_DRAW_SCOPES =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Integer> SEMANTIC_WIDGET_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static final Map<String, FrameSnapshot> SNAPSHOTS =
            new LinkedHashMap<>(16, 0.75f, true);
    private static final Map<String, FrameSnapshot> ITEM_TOOLTIP_SNAPSHOTS =
            new LinkedHashMap<>(64, 0.75f, true);
    private static final Map<String, Boolean> COMPOSITE_PUA_WIDGETS =
            new LinkedHashMap<>(80, 0.75f, true);
    private static final Set<String> PENDING_SIGNATURES = new LinkedHashSet<>();
    private static final Map<String, Integer> PENDING_FRAME_COUNTS = new HashMap<>();
    private static final Map<String, Long> AUTO_RETRY_AFTER = new LinkedHashMap<>();
    private static final AtomicLong STATE_GENERATION = new AtomicLong();
    private static volatile String requestedScreenKey;
    private static volatile boolean requestedHudFrame;
    private static volatile Status status = Status.NONE;
    private static volatile String statusScreenKey;
    private static volatile int statusCount;
    private static volatile long statusUntil;
    private static volatile Map<String, String> vanillaEnglish;

    private GuiTranslationHelper() {
    }

    public static void beginFrame(Screen screen) {
        ACTIVE_FRAMES.remove();
        CAPTURE_SUPPRESSION_DEPTH.remove();
        if (!enabled() || excludedScreen(screen)) {
            return;
        }
        String screenKey = screenKey(screen);
        FrameSnapshot previous = lookupSnapshot(screenKey);
        boolean requested = screenKey.equals(requestedScreenKey)
                || persistedScreenKeys().contains(screenKey);
        if (!shouldOpenScreenFrame(requested, previous != null,
                ModConfig.CONTENT_GUI_MODE.get())) {
            return;
        }
        pushFrame(new FrameCapture(screenKey, screen.getClass().getSimpleName(), previous, false,
                requested, false));
        collectVisibleWidgetMessages(screen);
    }

    /**
     * Starts an optional whole-HUD frame. When a Screen is open this captures
     * only the HUD/overlay pass behind and around that Screen; the Screen owns a
     * separate frame later in the render cycle. Dedicated translation surfaces
     * remain suppressed by their render scopes, so K cannot translate them a
     * second time.
     */
    public static void beginHudFrame() {
        ACTIVE_FRAMES.remove();
        CAPTURE_SUPPRESSION_DEPTH.remove();
        Minecraft client = Minecraft.getInstance();
        if (!enabled() || client == null) {
            return;
        }
        FrameSnapshot previous;
        synchronized (SNAPSHOTS) {
            previous = SNAPSHOTS.get(HUD_FRAME_KEY);
        }
        if (previous == null && !requestedHudFrame
                && !ModConfig.CONTENT_HUD_FRAME_ACTIVE.get()) {
            return;
        }
        pushFrame(new FrameCapture(HUD_FRAME_KEY, "In-game HUD", previous, true,
                requestedHudFrame, false));
    }

    /**
     * Opens the exact same Component-JSON/drawString frame used by K for a
     * bounded render surface such as one tooltip, advancement hover, or toast.
     * The caller owns the original renderer; this helper only captures and
     * replaces text arguments while that renderer runs.
     */
    public static boolean beginDetachedFrame(String frameKey, String frameName, boolean requestTranslation) {
        FrameCapture active = activeFrame();
        if (!ModConfig.GLOBAL_ENABLED.get() || frameKey == null || frameKey.isBlank()
                || (active != null && active.detached)) {
            return false;
        }
        FrameSnapshot previous = lookupSnapshot(frameKey);
        if (previous == null && !requestTranslation) {
            return false;
        }
        pushFrame(new FrameCapture(frameKey,
                frameName == null || frameName.isBlank() ? "Rendered text" : frameName,
                previous, false, requestTranslation, true));
        return true;
    }

    /**
     * Opens an item-tooltip frame after synchronously probing the persistent
     * Component cache with the final Component rows submitted by vanilla. A
     * hover dwell or shortcut controls only cache-miss requests; it must never
     * delay an already translated tooltip.
     */
    public static boolean beginItemTooltipFrame(String frameKey, String frameName,
                                                boolean requestTranslation,
                                                List<Component> submittedRows) {
        FrameCapture active = activeFrame();
        if (!ModConfig.GLOBAL_ENABLED.get() || frameKey == null || frameKey.isBlank()
                || (active != null && active.detached)) {
            return false;
        }
        FrameSnapshot previous = lookupSnapshot(frameKey);
        FrameCapture frame = new FrameCapture(frameKey,
                frameName == null || frameName.isBlank() ? "Item tooltip" : frameName,
                previous, false, requestTranslation, true);
        pushFrame(frame);
        if (previous != null) {
            return true;
        }

        boolean cached = hydrateItemTooltipFrameFromCache(frame, submittedRows);
        clearFrameProbe(frame);
        if (cached || requestTranslation) {
            return true;
        }
        popFrame();
        return false;
    }

    private static boolean hydrateItemTooltipFrameFromCache(
            FrameCapture frame, List<Component> submittedRows) {
        if (frame == null || submittedRows == null || submittedRows.isEmpty()) {
            return false;
        }
        for (Component row : submittedRows) {
            translateVisible(row);
        }
        if (frame.sources.isEmpty()) {
            return false;
        }
        List<Component> sources = List.copyOf(frame.sources.values());
        ComponentListTranslationResult cached = lookupItemTooltipCache(frame, sources);
        if (!validResult(cached, sources.size())) {
            return false;
        }
        acceptSnapshot(frame, cached);
        frame.previous = lookupSnapshot(frame.screenKey);
        return frame.previous != null;
    }

    /**
     * Item tooltips must hit the same key that was written. Prefer the stable
     * slot-only context (new writes); fall back to the older full frame context
     * so existing installations still hydrate without a second model call.
     */
    private static ComponentListTranslationResult lookupItemTooltipCache(
            FrameCapture frame, List<Component> sources) {
        String stable = buildItemTooltipStableCacheContext(sources);
        ComponentListTranslationResult cached = DirectSurfaceTranslator.getCachedComponents(
                sources, ITEM_TOOLTIP_SURFACE, ROLE, false, stable);
        if (validResult(cached, sources.size())) {
            return cached;
        }
        String full = buildFrameContext(frame, sources);
        return DirectSurfaceTranslator.getCachedComponents(
                sources, ITEM_TOOLTIP_SURFACE, ROLE, false, full);
    }

    /** Cache identity independent of draw-order occurrence streams. */
    private static String buildItemTooltipStableCacheContext(List<Component> sources) {
        StringBuilder context = new StringBuilder();
        context.append("frame_context_kind=item_tooltip\n")
                .append("item_tooltip_cache=stable_slots.v1\n")
                .append("Requested semantic slots in stable order:\n");
        appendContextLines(context, sources);
        return context.toString().stripTrailing();
    }

    private static boolean isItemTooltipSnapshotKey(String key) {
        return key != null && key.startsWith("gui.item_tooltip");
    }

    private static FrameSnapshot lookupSnapshot(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        synchronized (SNAPSHOTS) {
            if (isItemTooltipSnapshotKey(key)) {
                return ITEM_TOOLTIP_SNAPSHOTS.get(key);
            }
            return SNAPSHOTS.get(key);
        }
    }

    private static void storeSnapshot(String key, FrameSnapshot snapshot) {
        if (key == null || key.isBlank() || snapshot == null) {
            return;
        }
        synchronized (SNAPSHOTS) {
            if (isItemTooltipSnapshotKey(key)) {
                ITEM_TOOLTIP_SNAPSHOTS.put(key, snapshot);
                while (ITEM_TOOLTIP_SNAPSHOTS.size() > MAX_ITEM_TOOLTIP_SNAPSHOTS) {
                    String oldest = ITEM_TOOLTIP_SNAPSHOTS.keySet().iterator().next();
                    ITEM_TOOLTIP_SNAPSHOTS.remove(oldest);
                }
            } else {
                SNAPSHOTS.put(key, snapshot);
                while (SNAPSHOTS.size() > MAX_SNAPSHOTS) {
                    String oldest = SNAPSHOTS.keySet().iterator().next();
                    SNAPSHOTS.remove(oldest);
                }
            }
        }
    }

    /** Discards the cache probe so the real draw owns occurrence order exactly once. */
    private static void clearFrameProbe(FrameCapture frame) {
        frame.sources.clear();
        frame.contextOccurrences.clear();
        frame.reusedTranslationKeys.clear();
        frame.compositeOverlays.clear();
    }

    public static void endDetachedFrame(GuiGraphics graphics) {
        FrameCapture frame = activeFrame();
        if (frame != null && frame.detached) {
            finishFrame(graphics);
        }
    }

    private static List<Component> lastDetachedKeyRows;
    private static String lastDetachedKeyNamespace;
    private static String lastDetachedKeyResult;

    /** Stable full-document identity; modifier-key variants naturally differ. */
    public static String detachedFrameKey(String namespace, List<Component> components) {
        if (components == lastDetachedKeyRows && java.util.Objects.equals(namespace, lastDetachedKeyNamespace)) {
            return lastDetachedKeyResult;
        }
        String result = computeDetachedFrameKey(namespace, components);
        lastDetachedKeyRows = components;
        lastDetachedKeyNamespace = namespace;
        lastDetachedKeyResult = result;
        return result;
    }

    private static String computeDetachedFrameKey(String namespace, List<Component> components) {
        String prefix = namespace == null || namespace.isBlank() ? "render" : namespace;
        if (components == null || components.isEmpty()) {
            return prefix;
        }
        if (prefix.startsWith("gui.item_tooltip")) {
            ComponentVisualProjection projection = JsonPassthroughPipeline.projectLiveComponents(
                    components, ModConfig.TARGET_LANGUAGE.get());
            if (projection != null && projection.hasSlots()) {
                return prefix + "\nsemantic=" + TranslationTextDetector.languagePairKey()
                        + ':' + TranslationCacheKeys.hashSource(projection.semanticJson());
            }
        }
        return prefix + "\n" + components.stream()
                .map(GuiTranslationHelper::identityKey)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    public static void endFrame(GuiGraphics graphics) {
        FrameCapture frame = activeFrame();
        if (frame != null && !frame.detached && !frame.hudFrame) {
            finishFrame(graphics);
        }
    }

    /** Completes the optional in-world HUD frame started from Gui.render. */
    public static void endHudFrame(GuiGraphics graphics) {
        FrameCapture frame = activeFrame();
        if (frame != null && frame.hudFrame && !frame.detached) {
            finishFrame(graphics);
        }
    }

    private static void finishFrame(GuiGraphics graphics) {
        FrameCapture frame = popFrame();
        if (frame == null) {
            return;
        }
        boolean requested = frame.detached || frame.hudFrame
                ? frame.manualRequest : frame.screenKey.equals(requestedScreenKey);
        if (frame.sources.isEmpty()) {
            if (requested) {
                clearRequest(frame);
                if (shouldShowStatus(frame)) {
                    int reused = frame.reusedTranslationKeys.size();
                    showStatus(frame.screenKey, reused > 0 ? Status.COMPLETE : Status.EMPTY,
                            reused, 2400L);
                }
            }
            if (shouldShowStatus(frame)) {
                renderStatus(graphics, frame.screenKey);
            }
            return;
        }
        List<Component> sources = List.copyOf(frame.sources.values());
        String surface = surfaceForFrame(frame);
        // Item tooltips write/read a stable slot-only context so hydrate after
        // inventory reopen or process restart matches the disk entry without
        // depending on draw-order occurrence streams.
        String context = isItemTooltipFrame(frame)
                ? buildItemTooltipStableCacheContext(sources)
                : buildFrameContext(frame, sources);
        String signature = surface + "\n" + frame.screenKey + "\n" + sources.stream()
                .map(GuiTranslationHelper::identityKey).reduce((a, b) -> a + "\n" + b).orElse("");
        ComponentListTranslationResult cached = isItemTooltipFrame(frame)
                ? lookupItemTooltipCache(frame, sources)
                : DirectSurfaceTranslator.getCachedComponents(
                        sources, surface, ROLE, false, context);
        if (validResult(cached, sources.size())) {
            acceptSnapshot(frame, cached);
            if (requested) {
                clearRequest(frame);
                if (shouldShowStatus(frame)) {
                    showStatus(frame.screenKey, Status.COMPLETE,
                            changedCount(sources, cached.components), 2200L);
                }
            }
        } else {
            boolean automatic = shouldAutomaticallyRequest(
                    frame.hudFrame, frame.detached, frame.previous != null,
                    ModConfig.CONTENT_GUI_MODE.get());
            boolean retryReady = !frame.detached || autoRetryReady(signature);
            boolean framePending = isFrameTranslationPending(frame.screenKey);
            boolean requestReady = (requested && retryReady)
                    || (automatic && autoRetryReady(signature));
            if (!framePending && requestReady && markPending(signature)) {
                clearRequest(frame);
                if (shouldShowStatus(frame)) {
                    showStatus(frame.screenKey, Status.TRANSLATING, 0, Long.MAX_VALUE);
                }
                requestAsync(frame, sources, surface, context, signature);
            } else if (requested && (framePending || isPending(signature))) {
                clearRequest(frame);
                if (shouldShowStatus(frame)) {
                    showStatus(frame.screenKey, Status.TRANSLATING, 0, Long.MAX_VALUE);
                }
            }
        }
        if (shouldShowStatus(frame)) {
            renderStatus(graphics, frame.screenKey);
        }
    }

    private static void requestAsync(FrameCapture frame, List<Component> sources,
                                     String surface, String context, String signature) {
        long generation = STATE_GENERATION.get();
        CompletableFuture<ComponentListTranslationResult> future;
        try {
            future = DirectSurfaceTranslator.translateComponentsAsync(
                    sources, surface, ROLE, false, context);
            if (future == null) {
                throw new IllegalStateException("translation request returned no future");
            }
        } catch (Throwable error) {
            logManualFrameFailure(frame, sources.size(), surface, null, error, "start");
            synchronized (PENDING_SIGNATURES) {
                PENDING_SIGNATURES.remove(signature);
            }
            synchronized (AUTO_RETRY_AFTER) {
                AUTO_RETRY_AFTER.put(signature, System.nanoTime() + 5_000_000_000L);
                while (AUTO_RETRY_AFTER.size() > 32) {
                    AUTO_RETRY_AFTER.remove(AUTO_RETRY_AFTER.keySet().iterator().next());
                }
            }
            if (shouldShowStatus(frame)) {
                showStatus(frame.screenKey, Status.FAILED, 0, 3200L);
            }
            return;
        }
        markFramePending(frame.screenKey);
        future.whenComplete((result, error) -> {
            Minecraft client = Minecraft.getInstance();
            Runnable finish = () -> {
                if (STATE_GENERATION.get() != generation) {
                    return;
                }
                synchronized (PENDING_SIGNATURES) {
                    PENDING_SIGNATURES.remove(signature);
                }
                clearFramePending(frame.screenKey);
                if (error == null && validResult(result, sources.size())) {
                    synchronized (AUTO_RETRY_AFTER) {
                        AUTO_RETRY_AFTER.remove(signature);
                    }
                    acceptSnapshot(frame, result);
                    if (shouldShowStatus(frame)) {
                        showStatus(frame.screenKey, Status.COMPLETE,
                                changedCount(sources, result.components), 2400L);
                    }
                } else {
                    logManualFrameFailure(frame, sources.size(), surface, result, error, "completion");
                    synchronized (AUTO_RETRY_AFTER) {
                        AUTO_RETRY_AFTER.put(signature, System.nanoTime() + 5_000_000_000L);
                        while (AUTO_RETRY_AFTER.size() > 32) {
                            AUTO_RETRY_AFTER.remove(AUTO_RETRY_AFTER.keySet().iterator().next());
                        }
                    }
                    if (shouldShowStatus(frame)) {
                        showStatus(frame.screenKey, Status.FAILED, 0, 3200L);
                    }
                }
            };
            if (client != null) {
                client.execute(finish);
            } else {
                finish.run();
            }
        });
    }

    /** Logs request state without exposing visible text, prompts, or credentials. */
    private static void logManualFrameFailure(
            FrameCapture frame, int sourceCount, String surface,
            ComponentListTranslationResult result, Throwable error, String stage) {
        if (frame == null || !frame.manualRequest) {
            return;
        }
        int resultCount = result == null || result.components == null
                ? -1 : result.components.size();
        SimpleTranslateMod.getLogger().warn(
                "Whole-frame Component translation rejected stage={} frame={} surface={} "
                        + "sourceComponents={} handled={} translated={} resultComponents={} error={}",
                stage, frame.screenKey, surface, sourceCount,
                result != null && result.handled,
                result != null && result.translated,
                resultCount,
                error == null ? "none" : error.getClass().getSimpleName());
    }

    private static boolean markPending(String signature) {
        synchronized (PENDING_SIGNATURES) {
            return PENDING_SIGNATURES.add(signature);
        }
    }

    private static boolean isPending(String signature) {
        synchronized (PENDING_SIGNATURES) {
            return PENDING_SIGNATURES.contains(signature);
        }
    }

    private static boolean autoRetryReady(String signature) {
        synchronized (AUTO_RETRY_AFTER) {
            return AUTO_RETRY_AFTER.getOrDefault(signature, 0L) <= System.nanoTime();
        }
    }

    /**
     * K is the opt-in for an in-world HUD frame. The opt-in is persisted, and
     * an accepted snapshot also keeps newly appearing overlay text automatic;
     * reconnects and changing objectives therefore need no second K press.
     */
    static boolean shouldAutomaticallyRequest(boolean hudFrame, boolean detached,
                                              boolean hasPreviousSnapshot,
                                              ModConfig.GuiTranslationMode guiMode) {
        if (detached) {
            return false;
        }
        if (hudFrame) {
            return hasPreviousSnapshot || ModConfig.CONTENT_HUD_FRAME_ACTIVE.get();
        }
        return hasPreviousSnapshot || guiMode == ModConfig.GuiTranslationMode.AUTO;
    }

    static boolean shouldOpenScreenFrame(boolean manualRequest,
                                         boolean hasPreviousSnapshot,
                                         ModConfig.GuiTranslationMode guiMode) {
        return manualRequest || shouldAutomaticallyRequest(
                false, false, hasPreviousSnapshot, guiMode);
    }

    private static boolean validResult(ComponentListTranslationResult result, int size) {
        return result != null && result.translated && result.components != null
                && result.components.size() == size && result.components.stream().noneMatch(java.util.Objects::isNull);
    }

    private static int changedCount(List<Component> sources, List<Component> translated) {
        int count = 0;
        for (int i = 0; i < Math.min(sources.size(), translated.size()); i++) {
            if (!identityKey(sources.get(i)).equals(identityKey(translated.get(i)))) {
                count++;
            }
        }
        return count;
    }

    private static void acceptSnapshot(FrameCapture frame, ComponentListTranslationResult result) {
        LinkedHashMap<String, Component> translated = new LinkedHashMap<>();
        LinkedHashMap<String, List<Component>> semanticTranslations = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, Component> entry : frame.sources.entrySet()) {
            Component value = result.components.get(index++);
            if (value == null) {
                return;
            }
            translated.put(entry.getKey(), value);
            if (isItemTooltipFrame(frame)) {
                ComponentVisualProjection projection = itemTooltipProjection(entry.getValue());
                String semanticKey = itemTooltipSemanticKey(projection);
                List<Component> translatedSlots = itemTooltipTranslatedSlots(projection, value);
                if (semanticKey != null && translatedSlots != null) {
                    semanticTranslations.putIfAbsent(semanticKey, translatedSlots);
                }
            }
        }
        FrameSnapshot existing = lookupSnapshot(frame.screenKey);
        Map<String, Component> merged = mergeTranslations(
                existing == null ? Map.of() : existing.translations,
                translated, MAX_TRANSLATIONS_PER_SNAPSHOT);
        Map<String, List<Component>> mergedSemantic = mergeSemanticTranslations(
                existing == null ? Map.of() : existing.semanticTranslations,
                semanticTranslations, MAX_TRANSLATIONS_PER_SNAPSHOT);
        LinkedHashSet<String> mergedTranslatedKeys = new LinkedHashSet<>();
        for (Map.Entry<String, Component> entry : merged.entrySet()) {
            String translatedKey = identityKey(entry.getValue());
            if (!translatedKey.equals(entry.getKey())) {
                mergedTranslatedKeys.add(translatedKey);
            }
        }
        FrameSnapshot replacement = new FrameSnapshot(
                merged, Collections.unmodifiableSet(mergedTranslatedKeys), mergedSemantic);
        if (!replacement.equals(existing)) {
            storeSnapshot(frame.screenKey, replacement);
        } else if (existing != null) {
            // Re-touch LRU so a freshly used item stays hot while scanning.
            storeSnapshot(frame.screenKey, existing);
        }
    }

    /**
     * Merges newly visible rows into the screen document without losing old
     * viewports. The first accepted value for one exact source identity wins
     * until the runtime state is explicitly cleared. This prevents slower,
     * older whole-frame requests from changing a translation that is already
     * visible.
     */
    static Map<String, Component> mergeTranslations(Map<String, Component> existing,
                                                    Map<String, Component> current,
                                                    int maximumSize) {
        LinkedHashMap<String, Component> merged = new LinkedHashMap<>();
        if (existing != null) {
            existing.forEach((key, value) -> {
                if (key != null && value != null) {
                    merged.put(key, value);
                }
            });
        }
        if (current != null) {
            current.forEach((key, value) -> {
                if (key != null && value != null) {
                    merged.putIfAbsent(key, value);
                }
            });
        }
        int limit = Math.max(1, maximumSize);
        while (merged.size() > limit) {
            merged.remove(merged.keySet().iterator().next());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(merged));
    }

    private static Map<String, List<Component>> mergeSemanticTranslations(
            Map<String, List<Component>> existing,
            Map<String, List<Component>> current,
            int maximumSize) {
        LinkedHashMap<String, List<Component>> merged = new LinkedHashMap<>();
        if (existing != null) {
            existing.forEach((key, value) -> {
                if (key != null && value != null && !value.isEmpty()) {
                    merged.put(key, List.copyOf(value));
                }
            });
        }
        if (current != null) {
            current.forEach((key, value) -> {
                if (key != null && value != null && !value.isEmpty()) {
                    merged.putIfAbsent(key, List.copyOf(value));
                }
            });
        }
        int limit = Math.max(1, maximumSize);
        while (merged.size() > limit) {
            merged.remove(merged.keySet().iterator().next());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(merged));
    }

    /** Arms the current Screen plus its visible non-dedicated HUD/overlay pass. */
    public static void requestCurrentScreenAndOverlayTranslation() {
        Minecraft client = Minecraft.getInstance();
        Screen screen = client == null ? null : client.screen;
        if (!enabled() || excludedScreen(screen)) {
            return;
        }
        String key = screenKey(screen);
        requestedScreenKey = key;
        rememberRequestedScreenKey(key);
        // The HUD pass is rendered separately before the Screen. Arm one
        // non-persistent pass so overlays visible around a GUI are included in
        // the same K action without turning on continuous in-world HUD capture.
        requestedHudFrame = true;
        showStatus(key, Status.COLLECTING, 0, Long.MAX_VALUE);
    }

    /** Arms K for the current in-world HUD without opening a Screen. */
    public static void requestCurrentHudTranslation() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled() || client == null || client.screen != null) {
            return;
        }
        requestedHudFrame = true;
        showStatus(HUD_FRAME_KEY, Status.COLLECTING, 0, Long.MAX_VALUE);
        if (!ModConfig.CONTENT_HUD_FRAME_ACTIVE.get()) {
            ModConfig.CONTENT_HUD_FRAME_ACTIVE.set(true);
            ModConfig.save();
        }
    }

    /**
     * Existing installations predate the persisted HUD opt-in. Infer their
     * previous K choice from an accepted whole-HUD cache entry without deleting
     * or rewriting any user cache.
     */
    public static void migrateLegacyHudFrameActivation(TranslationCache cache) {
        if (cache == null
                || ModConfig.hasPersistedKey(ModConfig.CONTENT_HUD_FRAME_ACTIVE.getKey())) {
            return;
        }
        boolean previouslyActivated = cache.hasSurface(HUD_SURFACE);
        if (previouslyActivated) {
            ModConfig.CONTENT_HUD_FRAME_ACTIVE.set(true);
            ModConfig.save();
        }
    }

    /** True only while the current Screen is handling an explicit K request. */
    public static boolean isCurrentScreenTranslationRequested() {
        FrameCapture frame = activeFrame();
        return frame != null && !frame.hudFrame && !frame.detached
                && frame.screenKey.equals(requestedScreenKey);
    }

    public static boolean hasFrameSnapshot(String frameKey) {
        return lookupSnapshot(frameKey) != null;
    }

    public static boolean isFrameTranslationPending(String frameKey) {
        if (frameKey == null || frameKey.isBlank()) {
            return false;
        }
        synchronized (PENDING_FRAME_COUNTS) {
            return PENDING_FRAME_COUNTS.getOrDefault(frameKey, 0) > 0;
        }
    }

    public static Component translatePlainText(String text) {
        FrameCapture frame = activeFrame();
        if (frame == null || CAPTURE_SUPPRESSION_DEPTH.get() > 0
                || text == null || text.isBlank() || frame.inputValues.contains(text)) {
            return Component.literal(text == null ? "" : text);
        }
        return translateVisible(Component.literal(text));
    }

    public static FormattedCharSequence translateFormattedSequence(FormattedCharSequence sequence) {
        if (sequence == null || !isActive() || CAPTURE_SUPPRESSION_DEPTH.get() > 0) {
            return sequence;
        }
        return translateVisible(componentFromFormattedSequence(sequence)).getVisualOrderText();
    }

    /** Rebuilds a style-preserving Component for visual tooltip rows and GUI capture. */
    public static Component componentFromFormattedSequence(FormattedCharSequence sequence) {
        MutableComponent source = Component.empty();
        if (sequence == null) {
            return source;
        }
        final Style[] lastStyle = {null};
        final StringBuilder run = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            Style safeStyle = style == null ? Style.EMPTY : style;
            if (lastStyle[0] != null && !lastStyle[0].equals(safeStyle)) {
                source.append(Component.literal(run.toString()).withStyle(lastStyle[0]));
                run.setLength(0);
            }
            lastStyle[0] = safeStyle;
            run.appendCodePoint(codePoint);
            return true;
        });
        if (!run.isEmpty()) {
            source.append(Component.literal(run.toString()).withStyle(
                    lastStyle[0] == null ? Style.EMPTY : lastStyle[0]));
        }
        return source;
    }

    public static FormattedText translateFormattedText(FormattedText text) {
        if (text == null || !isActive() || CAPTURE_SUPPRESSION_DEPTH.get() > 0) {
            return text;
        }
        if (text instanceof Component component) {
            return translateVisible(component);
        }
        MutableComponent source = Component.empty();
        text.visit((style, value) -> {
            if (value != null && !value.isEmpty()) {
                source.append(Component.literal(value).withStyle(style == null ? Style.EMPTY : style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return translateVisible(source);
    }

    /** Returns a cached translation and records the exact original for this frame. */
    public static Component translateVisible(Component component) {
        if (component == null || activeFrame() == null
                || CAPTURE_SUPPRESSION_DEPTH.get() > 0) {
            return component;
        }
        FrameCapture frame = activeFrame();
        if (frame != null && frame.previous != null
                && frame.previous.translatedKeys.contains(identityKey(component))) {
            return component;
        }
        return translateVisible(component, materializeVisibleComponent(component), null);
    }

    /**
     * Widget-aware entry point. A server resource pack may resolve one vanilla
     * translation key to a bitmap glyph that contains both the button label and
     * pixels far outside the button (logos, panels, decorations). Such a glyph
     * must remain the render source; only its in-button label is overlaid later.
     */
    public static Component translateWidgetMessage(AbstractWidget widget, Component component) {
        if (component == null || activeFrame() == null
                || CAPTURE_SUPPRESSION_DEPTH.get() > 0) {
            return component;
        }
        Component visible = materializeVisibleComponent(component);
        if (shouldTranslate(visible)) {
            return translateVisible(component, visible, null);
        }
        if (!isPurePuaTranslateKey(component, visible)
                || !isCompositePuaWidget(widget, visible)) {
            return component;
        }
        Component semantic = semanticVisibleComponent(component);
        return translateVisible(component, semantic, widget);
    }

    /** Returns the accepted in-button overlay for one composite glyph widget. */
    public static Component compositeWidgetOverlay(AbstractWidget widget) {
        FrameCapture frame = activeFrame();
        if (frame == null || widget == null) {
            return null;
        }
        return frame.compositeOverlays.get(widget);
    }

    private static Component translateVisible(
            Component component, Component semanticSource, AbstractWidget compositeWidget) {
        FrameCapture frame = activeFrame();
        if (frame == null || component == null || semanticSource == null) {
            return component;
        }
        if (CAPTURE_SUPPRESSION_DEPTH.get() > 0) {
            return component;
        }
        if (SEMANTIC_WIDGET_DEPTH.get() > 0) {
            return component;
        }
        // Chat/book hover translation remains a dedicated hidden-hover path.
        // Item tooltips and advancements now use this frame directly.
        if (TooltipTranslationHelper.isMarkedTranslatedTooltip(component)) {
            return component;
        }
        String key = identityKey(component);
        if (frame.previous != null && frame.previous.translatedKeys.contains(key)) {
            return component;
        }
        if (!shouldTranslate(semanticSource)) {
            return component;
        }
        // Compass/coordinate telemetry is not language. Letting it participate
        // in a whole-HUD document makes every movement create a new context and
        // can continuously resubmit otherwise stable objective rows.
        if (frame.hudFrame && isHudTelemetryComponent(semanticSource)) {
            return component;
        }
        if (frame.contextOccurrences.size() < MAX_CONTEXT_OCCURRENCES) {
            frame.contextOccurrences.add(semanticSource);
        }
        if (frame.previous != null) {
            Component translated = frame.previous.translations.get(key);
            if (translated != null) {
                if (!identityKey(translated).equals(identityKey(semanticSource))) {
                    frame.reusedTranslationKeys.add(key);
                }
                if (compositeWidget != null) {
                    if (!identityKey(translated).equals(identityKey(semanticSource))) {
                        // Keep the oversized source glyph so its out-of-widget pixels
                        // survive. Button.Plain redraws only the button rectangle and
                        // paints this semantic translation over that rectangle.
                        frame.compositeOverlays.put(compositeWidget,
                                Component.literal(translated.getString()));
                    }
                    return component;
                }
                if (isItemTooltipFrame(frame)) {
                    TooltipTranslationHelper.markTranslatedTooltip(translated);
                }
                return translated;
            }
            if (isItemTooltipFrame(frame)) {
                Component rebound = rebindItemTooltipSemanticTranslation(
                        frame.previous, component, semanticSource);
                if (rebound != null) {
                    frame.reusedTranslationKeys.add(key);
                    return rebound;
                }
            }
        }
        // The request document contains only unresolved semantic identities.
        // Resolved rows above still remain in occurrence context so newly
        // appearing fragments can be translated with the whole visible frame.
        if (frame.sources.size() < MAX_COMPONENTS || frame.sources.containsKey(key)) {
            frame.sources.putIfAbsent(key, semanticSource);
        }
        return component;
    }

    private static boolean isItemTooltipFrame(FrameCapture frame) {
        return frame != null && frame.detached
                && frame.screenKey.startsWith("gui.item_tooltip");
    }

    private static ComponentVisualProjection itemTooltipProjection(Component component) {
        return component == null ? null : JsonPassthroughPipeline.projectLiveComponents(
                List.of(component), ModConfig.TARGET_LANGUAGE.get());
    }

    private static String itemTooltipSemanticKey(ComponentVisualProjection projection) {
        if (projection == null || !projection.hasSlots()) {
            return null;
        }
        return TranslationTextDetector.languagePairKey() + ':'
                + TranslationCacheKeys.hashSource(projection.semanticJson());
    }

    private static List<Component> itemTooltipTranslatedSlots(
            ComponentVisualProjection projection, Component translated) {
        if (projection == null || translated == null) {
            return null;
        }
        String translatedJson = JsonPassthroughPipeline.serializeProjectionSource(
                List.of(translated));
        if (translatedJson == null || translatedJson.isBlank()) {
            return null;
        }
        try {
            List<String> translatedTexts = projection.alignedTranslatedSlotTexts(
                    JsonParser.parseString(translatedJson));
            if (translatedTexts == null || translatedTexts.size() != projection.slotCount()) {
                return null;
            }
            return translatedTexts.stream()
                    .map(text -> (Component) Component.literal(text))
                    .toList();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Component rebindItemTooltipSemanticTranslation(
            FrameSnapshot snapshot, Component current, Component semanticSource) {
        if (snapshot == null || current == null || semanticSource == null) {
            return null;
        }
        ComponentVisualProjection projection = itemTooltipProjection(semanticSource);
        String semanticKey = itemTooltipSemanticKey(projection);
        if (semanticKey == null) {
            return null;
        }
        List<Component> translatedSlots = snapshot.semanticTranslations.get(semanticKey);
        if (translatedSlots == null) {
            return null;
        }
        return rebuildItemTooltipSemanticTranslation(
                current, semanticSource, translatedSlots);
    }

    private static Component rebuildItemTooltipSemanticTranslation(
            Component current, Component semanticSource,
            List<Component> translatedSlots) {
        if (current == null || semanticSource == null
                || translatedSlots == null || translatedSlots.isEmpty()) {
            return null;
        }
        ComponentVisualProjection projection = itemTooltipProjection(semanticSource);
        if (projection == null || projection.slotCount() != translatedSlots.size()) {
            return null;
        }
        List<Component> rebuilt = projection.rebuildComponentList(translatedSlots);
        if (rebuilt == null || rebuilt.size() != 1) {
            return null;
        }
        rebuilt = JsonPassthroughPipeline.reattachOriginalHoverEventsForRender(
                rebuilt, List.of(current));
        if (rebuilt == null || rebuilt.size() != 1) {
            return null;
        }
        Component result = rebuilt.get(0);
        TooltipTranslationHelper.markTranslatedTooltip(result);
        return result;
    }

    public static boolean isActive() {
        return activeFrame() != null;
    }

    /** Prevents an outer K frame from claiming any independently owned translation surface. */
    public static void beginCaptureSuppression() {
        CAPTURE_SUPPRESSION_DEPTH.set(CAPTURE_SUPPRESSION_DEPTH.get() + 1);
    }

    public static void endCaptureSuppression() {
        int depth = CAPTURE_SUPPRESSION_DEPTH.get() - 1;
        if (depth <= 0) {
            CAPTURE_SUPPRESSION_DEPTH.remove();
        } else {
            CAPTURE_SUPPRESSION_DEPTH.set(depth);
        }
    }

    /** Suppresses visual-line recollection after a mod widget supplied its whole semantic Component. */
    public static void beginSemanticWidgetDraw() {
        SEMANTIC_WIDGET_DEPTH.set(SEMANTIC_WIDGET_DEPTH.get() + 1);
    }

    public static void endSemanticWidgetDraw() {
        int depth = SEMANTIC_WIDGET_DEPTH.get() - 1;
        if (depth <= 0) SEMANTIC_WIDGET_DEPTH.remove();
        else SEMANTIC_WIDGET_DEPTH.set(depth);
    }

    /**
     * GuiGraphics overloads delegate to one another. Only the outermost
     * public draw call may collect/translate semantic text; nested overloads
     * receive the already translated argument unchanged.
     */
    public static boolean enterDirectDraw(String methodId) {
        if (activeFrame() == null || CAPTURE_SUPPRESSION_DEPTH.get() > 0) {
            return false;
        }
        ArrayDeque<String> scopes = DIRECT_DRAW_SCOPES.get();
        boolean outermost = scopes.isEmpty();
        scopes.push(methodId);
        return outermost;
    }

    public static void leaveDirectDraw() {
        ArrayDeque<String> scopes = DIRECT_DRAW_SCOPES.get();
        if (!scopes.isEmpty()) {
            scopes.pop();
        }
        if (scopes.isEmpty()) {
            DIRECT_DRAW_SCOPES.remove();
        }
    }

    /** Balances a cancellable HEAD only when its own ModifyVariable already entered. */
    public static void leaveDirectDrawIfTop(String methodId) {
        ArrayDeque<String> scopes = DIRECT_DRAW_SCOPES.get();
        if (!scopes.isEmpty() && methodId.equals(scopes.peek())) {
            scopes.pop();
        }
        if (scopes.isEmpty()) {
            DIRECT_DRAW_SCOPES.remove();
        }
    }

    public static void clearLocalState() {
        STATE_GENERATION.incrementAndGet();
        synchronized (IDENTITY_KEY_MEMO) {
            IDENTITY_KEY_MEMO.clear();
        }
        ACTIVE_FRAMES.remove();
        CAPTURE_SUPPRESSION_DEPTH.remove();
        DIRECT_DRAW_SCOPES.remove();
        SEMANTIC_WIDGET_DEPTH.remove();
        requestedScreenKey = null;
        requestedHudFrame = false;
        status = Status.NONE;
        statusScreenKey = null;
        synchronized (PENDING_SIGNATURES) {
            PENDING_SIGNATURES.clear();
        }
        synchronized (PENDING_FRAME_COUNTS) {
            PENDING_FRAME_COUNTS.clear();
        }
        synchronized (AUTO_RETRY_AFTER) {
            AUTO_RETRY_AFTER.clear();
        }
        GuiLayoutProgramRenderer.clearLocalState();
        synchronized (SNAPSHOTS) {
            SNAPSHOTS.clear();
            ITEM_TOOLTIP_SNAPSHOTS.clear();
        }
        synchronized (COMPOSITE_PUA_WIDGETS) {
            COMPOSITE_PUA_WIDGETS.clear();
        }
        vanillaEnglish = null;
    }

    private static boolean enabled() {
        return ModConfig.GLOBAL_ENABLED.get() && ModConfig.CONTENT_GUI_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.GUI);
    }

    private static boolean excludedScreen(Screen screen) {
        if (screen == null) {
            return true;
        }
        String className = screen.getClass().getName();
        boolean ftbScreen = className.startsWith("dev.ftb.") || className.startsWith("com.feed_the_beast.");
        return className.startsWith("com.yourname.simpletranslate.gui.")
                || screen instanceof ChatScreen
                || screen instanceof BookViewScreen
                || screen instanceof BookEditScreen
                || screen instanceof AdvancementsScreen
                || (ftbScreen && (!ModConfig.MOD_TRANSLATION_ENABLED.get()
                || !ModConfig.MOD_FTB_QUESTS_ENABLED.get()));
    }

    private static boolean shouldTranslate(Component component) {
        String text = component.getString();
        if (!TranslationTextDetector.containsTranslatableText(text, 1)) {
            return false;
        }
        String trimmed = TranslationTextDetector.normalizeForDetection(text);
        if (trimmed.isBlank() || URL_OR_ADDRESS.matcher(trimmed).matches()
                || NAMESPACED_ID.matcher(trimmed).matches()
                || UUID_OR_VERSION.matcher(trimmed).matches()
                || TECH_TOKEN.matcher(trimmed).matches()) {
            return false;
        }
        return !trimmed.startsWith("/") && !trimmed.contains("\\")
                && !trimmed.matches("^[A-Za-z]:[/\\\\].*");
    }

    /** True for numeric/compass HUD telemetry, including custom-font PUA streams. */
    static boolean isHudTelemetryComponent(Component component) {
        return component != null && isHudTelemetryText(component.getString());
    }

    static boolean isHudTelemetryText(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        StringBuilder direction = new StringBuilder(2);
        boolean telemetrySignal = false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isPrivateUseCodePoint(codePoint) || Character.isDigit(codePoint)) {
                telemetrySignal = true;
                continue;
            }
            if (Character.isWhitespace(codePoint)
                    || "+-.,:/()[]{}%°|_".indexOf(codePoint) >= 0) {
                continue;
            }
            int upper = Character.toUpperCase(codePoint);
            if (upper == 'N' || upper == 'E' || upper == 'S' || upper == 'W') {
                telemetrySignal = true;
                direction.appendCodePoint(upper);
                if (direction.length() > 2) {
                    return false;
                }
                continue;
            }
            return false;
        }
        String token = direction.toString();
        return telemetrySignal && (token.isEmpty() || token.equals("N") || token.equals("NE")
                || token.equals("E") || token.equals("SE") || token.equals("S")
                || token.equals("SW") || token.equals("W") || token.equals("NW"));
    }

    private static boolean isPrivateUseCodePoint(int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xC0000 && codePoint <= 0xDFFFF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }

    private static String componentKey(Component component) {
        if (component == null) {
            return "";
        }
        try {
            return ComponentJsonCompat.toJson(component);
        } catch (RuntimeException ignored) {
            return component.getString();
        }
    }

    /**
     * A translate-key Component does not necessarily contain a JSON text leaf.
     * Materialize the currently visible language/resource-pack result into
     * styled literal runs before it enters the shared Component JSON pipeline.
     */
    private static Component materializeVisibleComponent(Component component) {
        MutableComponent visible = Component.empty();
        component.visit((style, text) -> {
            if (text != null && !text.isEmpty()) {
                visible.append(Component.literal(text).withStyle(
                        style == null ? Style.EMPTY : style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return visible.getSiblings().isEmpty() ? Component.literal(component.getString()) : visible;
    }

    /**
     * Some server packs replace a translatable label with one PUA bitmap glyph
     * whose texture already contains English words. Recover the vanilla English
     * meaning locally, then send that literal meaning through Component JSON;
     * the translation key itself is never exposed to the model.
     */
    private static Component semanticVisibleComponent(Component component) {
        Component visible = materializeVisibleComponent(component);
        if (shouldTranslate(visible)
                || !(component.getContents() instanceof TranslatableContents translatable)
                || !containsPrivateUseGlyph(visible.getString())) {
            return visible;
        }
        String template = vanillaEnglish().get(translatable.getKey());
        if (template == null || template.isBlank()) {
            template = translatable.getFallback();
        }
        String semantic = formatVanillaTemplate(template, translatable.getArgs());
        if (semantic == null || !TranslationTextDetector.containsTranslatableText(semantic, 1)) {
            return visible;
        }
        MutableComponent result = Component.literal(semantic).withStyle(component.getStyle());
        for (Component sibling : component.getSiblings()) {
            result.append(semanticVisibleComponent(sibling));
        }
        return result;
    }

    private static boolean isPurePuaTranslateKey(Component component, Component visible) {
        if (!(component.getContents() instanceof TranslatableContents translatable)
                || translatable.getArgs().length != 0
                || !component.getSiblings().isEmpty()) {
            return false;
        }
        String text = visible == null ? "" : visible.getString();
        return containsPrivateUseGlyph(text)
                && !TranslationTextDetector.containsTranslatableText(text, 1);
    }

    /**
     * Uses the current font's real glyph quad, not its cursor advance. Bitmap
     * fonts can have a perfectly ordinary 204px advance while drawing a 90px
     * header or 108px footer around a 20px button.
     */
    private static boolean isCompositePuaWidget(AbstractWidget widget, Component visible) {
        if (widget.getClass() != Button.class || visible == null) {
            return false;
        }
        String cacheKey = identityKey(visible) + "\nwidget="
                + widget.getWidth() + "x" + widget.getHeight()
                + "\nfontRevision=" + ActiveFontManager.resourceRevision();
        synchronized (COMPOSITE_PUA_WIDGETS) {
            Boolean cached = COMPOSITE_PUA_WIDGETS.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        boolean composite = false;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            Font font = minecraft == null ? null : minecraft.font;
            if (font != null) {
                int textWidth = font.width(visible);
                int originX = (widget.getWidth() - textWidth) / 2;
                int originY = (widget.getHeight() - font.lineHeight) / 2 + 1;
                // 1.20.1 has no Font#prepareText bounds query; the advance box
                // approximates glyph bounds (bearings outside the advance box
                // stay unclassified and are never replaced anyway).
                composite = originX < 0 || originX + textWidth > widget.getWidth()
                        || originY < 0 || originY + font.lineHeight > widget.getHeight();
            }
        } catch (RuntimeException ignored) {
            // Unknown bitmap metrics stay opaque and are never replaced.
            composite = false;
        }
        synchronized (COMPOSITE_PUA_WIDGETS) {
            COMPOSITE_PUA_WIDGETS.put(cacheKey, composite);
            while (COMPOSITE_PUA_WIDGETS.size() > MAX_COMPOSITE_CLASSIFICATIONS) {
                COMPOSITE_PUA_WIDGETS.remove(
                        COMPOSITE_PUA_WIDGETS.keySet().iterator().next());
            }
        }
        return composite;
    }

    private static Map<String, String> vanillaEnglish() {
        Map<String, String> cached = vanillaEnglish;
        if (cached != null) {
            return cached;
        }
        synchronized (GuiTranslationHelper.class) {
            if (vanillaEnglish != null) {
                return vanillaEnglish;
            }
            Map<String, String> values = new HashMap<>();
            try {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft != null && minecraft.getResourceManager() != null) {
                    Resource vanilla = null;
                    for (Resource resource : minecraft.getResourceManager()
                            .getResourceStack(VANILLA_EN_US)) {
                        if ("vanilla".equalsIgnoreCase(resource.sourcePackId())) {
                            vanilla = resource;
                            break;
                        }
                    }
                    if (vanilla != null) {
                        try (InputStream stream = vanilla.open()) {
                            Language.loadFromJson(stream, values::put);
                        }
                    }
                }
            } catch (Exception ignored) {
                // Missing vanilla semantic text keeps the original glyph.
            }
            vanillaEnglish = Map.copyOf(values);
            return vanillaEnglish;
        }
    }

    private static String formatVanillaTemplate(String template, Object[] args) {
        if (template == null) {
            return null;
        }
        Object[] safeArgs = args == null ? new Object[0] : args;
        StringBuilder result = new StringBuilder();
        var matcher = VANILLA_FORMAT_TOKEN.matcher(template);
        int cursor = 0;
        int implicitIndex = 0;
        while (matcher.find()) {
            String between = template.substring(cursor, matcher.start());
            if (between.indexOf('%') >= 0) {
                return null;
            }
            result.append(between);
            if ("%".equals(matcher.group(2))) {
                result.append('%');
            } else {
                int index = matcher.group(1) == null
                        ? implicitIndex++ : Integer.parseInt(matcher.group(1)) - 1;
                if (index < 0 || index >= safeArgs.length) {
                    return null;
                }
                Object argument = safeArgs[index];
                result.append(argument instanceof Component value
                        ? value.getString() : String.valueOf(argument));
            }
            cursor = matcher.end();
        }
        String tail = template.substring(cursor);
        if (tail.indexOf('%') >= 0) {
            return null;
        }
        result.append(tail);
        return result.toString();
    }

    private static boolean containsPrivateUseGlyph(String text) {
        if (text == null) {
            return false;
        }
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (Character.getType(codePoint) == Character.PRIVATE_USE) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    /**
     * Widget implementations may cache their visual text before the Screen
     * render body. Capture every visible non-input message at frame start so a
     * pause menu always produces one stable whole-screen request/snapshot.
     */
    private static void collectVisibleWidgetMessages(Screen screen) {
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget root) {
                root.visitWidgets(widget -> {
                    if (widget.visible && widget instanceof EditBox editBox) {
                        String value = editBox.getValue();
                        if (value != null && !value.isBlank()) {
                            FrameCapture frame = activeFrame();
                            if (frame != null) {
                                frame.inputValues.add(value);
                            }
                        }
                    } else if (widget.visible) {
                        widget.getMessage();
                    }
                });
            }
        }
    }

    /** Keeps snapshots distinct across language/resource-pack resolution changes. */
    private static final Map<Component, String> IDENTITY_KEY_MEMO = new IdentityHashMap<>();

    private static String identityKey(Component component) {
        synchronized (IDENTITY_KEY_MEMO) {
            String cached = IDENTITY_KEY_MEMO.get(component);
            if (cached != null) {
                return cached;
            }
        }
        String key = componentKey(component) + "\nresolved="
                + (component == null ? "" : component.getString());
        synchronized (IDENTITY_KEY_MEMO) {
            if (IDENTITY_KEY_MEMO.size() >= 512) {
                IDENTITY_KEY_MEMO.clear();
            }
            IDENTITY_KEY_MEMO.put(component, key);
        }
        return key;
    }

    private static String buildFrameContext(FrameCapture frame, List<Component> sources) {
        StringBuilder context = new StringBuilder();
        context.append("frame_context_kind=")
                .append(frameContextKind(frame))
                .append('\n')
                .append(frame.detached ? "Visible bounded render surface: "
                        : frame.hudFrame ? "Visible in-game HUD and overlays: "
                        : "Visible GUI screen: ")
                .append(frame.screenName)
                .append("\nTreat the ordered labels below as one coherent visible document. "
                        + "The entries are physical draw rows, not guaranteed sentence boundaries. "
                        + "Reconstruct clauses that continue across adjacent rows before translating them. "
                        + "Use neighboring labels to resolve sentence scope, fragments, menu context, and terminology; "
                        + "return one Component for each request slot without merging or reordering.\n")
                .append("Requested semantic slots in stable order:\n");
        appendContextLines(context, sources);
        if (!frame.contextOccurrences.isEmpty()) {
            context.append("Visual occurrence context in draw order (repeated draws are context only):\n");
            appendContextLines(context, frame.contextOccurrences);
            appendReadingStream(context, frame.contextOccurrences);
        }
        return context.toString().stripTrailing();
    }

    private static String frameContextKind(FrameCapture frame) {
        if (frame == null) {
            return "unknown";
        }
        if (frame.detached) {
            if (frame.screenKey.startsWith("gui.item_tooltip")) {
                return "item_tooltip";
            }
            if (frame.screenKey.startsWith("gui.advancement.")) {
                return "advancement";
            }
            return "detached_render";
        }
        return frame.hudFrame ? "hud_frame" : "gui_screen";
    }

    /** Same Component/draw-frame pipeline, with cache domains isolated by product surface. */
    private static String surfaceForFrame(FrameCapture frame) {
        if (frame == null) {
            return GUI_SURFACE;
        }
        if (frame.detached) {
            if (frame.screenKey.startsWith("gui.item_tooltip")) {
                return ITEM_TOOLTIP_SURFACE;
            }
            if (frame.screenKey.startsWith("gui.advancement.")) {
                return ADVANCEMENT_SURFACE;
            }
        }
        return frame.hudFrame ? HUD_SURFACE : GUI_SURFACE;
    }

    /** Exact draw-order text repeated as one reading stream; no local regrouping or remapping. */
    private static void appendReadingStream(StringBuilder context, List<Component> components) {
        context.append("Continuous reading stream; <visual-row> marks only a draw wrap, not a sentence break:\n");
        boolean wrote = false;
        for (Component component : components) {
            String value = component == null ? "" : component.getString();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (wrote) {
                context.append(" <visual-row> ");
            }
            context.append(value.replace('\r', ' ').replace('\n', ' '));
            wrote = true;
        }
        context.append('\n');
    }

    private static void appendContextLines(StringBuilder context, List<Component> components) {
        int index = 0;
        for (Component component : components) {
            if (component == null) {
                continue;
            }
            String value = component.getString();
            if (value == null || value.isBlank()) {
                continue;
            }
            context.append(index++).append(": ").append(value).append('\n');
        }
    }

    private static String screenKey(Screen screen) {
        return screen.getClass().getName() + "\n" + identityKey(screen.getTitle());
    }

    private static final int MAX_PERSISTED_SCREEN_KEYS = 16;

    /** K's opt-in survives restarts: the persisted screens behave as requested. */
    private static String persistedScreenKeysRaw = "";
    private static java.util.Set<String> persistedScreenKeysSet = java.util.Set.of();

    private static java.util.Set<String> persistedScreenKeys() {
        String raw = ModConfig.CONTENT_GUI_FRAME_SCREEN_KEYS.get();
        if (raw == null) {
            raw = "";
        }
        if (!raw.equals(persistedScreenKeysRaw)) {
            persistedScreenKeysRaw = raw;
            persistedScreenKeysSet = raw.isBlank()
                    ? java.util.Set.of()
                    : new java.util.LinkedHashSet<>(java.util.List.of(raw.split("\u0000")));
        }
        return persistedScreenKeysSet;
    }

    private static void rememberRequestedScreenKey(String screenKey) {
        if (screenKey == null || screenKey.isBlank()) {
            return;
        }
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(persistedScreenKeys());
        keys.remove(screenKey);
        keys.add(screenKey);
        while (keys.size() > MAX_PERSISTED_SCREEN_KEYS) {
            keys.remove(0);
        }
        ModConfig.CONTENT_GUI_FRAME_SCREEN_KEYS.set(String.join("\u0000", keys));
        ModConfig.save();
    }

    private static void clearRequest(FrameCapture frame) {
        if (frame.detached) {
            return;
        }
        if (frame.hudFrame) {
            requestedHudFrame = false;
        } else if (frame.screenKey.equals(requestedScreenKey)) {
            requestedScreenKey = null;
        }
    }

    private static FrameCapture activeFrame() {
        return ACTIVE_FRAMES.get().peek();
    }

    private static void pushFrame(FrameCapture frame) {
        ACTIVE_FRAMES.get().push(frame);
    }

    private static FrameCapture popFrame() {
        ArrayDeque<FrameCapture> frames = ACTIVE_FRAMES.get();
        FrameCapture frame = frames.poll();
        if (frames.isEmpty()) {
            ACTIVE_FRAMES.remove();
        }
        return frame;
    }

    private static void markFramePending(String frameKey) {
        synchronized (PENDING_FRAME_COUNTS) {
            PENDING_FRAME_COUNTS.merge(frameKey, 1, Integer::sum);
        }
    }

    private static void clearFramePending(String frameKey) {
        synchronized (PENDING_FRAME_COUNTS) {
            int remaining = PENDING_FRAME_COUNTS.getOrDefault(frameKey, 0) - 1;
            if (remaining <= 0) {
                PENDING_FRAME_COUNTS.remove(frameKey);
            } else {
                PENDING_FRAME_COUNTS.put(frameKey, remaining);
            }
        }
    }

    private static void showStatus(String screenKey, Status next, int count, long durationMillis) {
        statusScreenKey = screenKey;
        status = next;
        statusCount = count;
        statusUntil = durationMillis == Long.MAX_VALUE
                ? Long.MAX_VALUE : System.currentTimeMillis() + durationMillis;
    }

    private static boolean shouldShowStatus(FrameCapture frame) {
        if (frame == null || frame.detached) {
            return false;
        }
        if (!frame.hudFrame) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        return client != null && client.screen == null;
    }

    private static void renderStatus(GuiGraphics graphics, String screenKey) {
        if (graphics == null || status == Status.NONE || !screenKey.equals(statusScreenKey)) {
            return;
        }
        if (statusUntil != Long.MAX_VALUE && System.currentTimeMillis() > statusUntil) {
            status = Status.NONE;
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null) {
            return;
        }
        boolean hudStatus = HUD_FRAME_KEY.equals(screenKey);
        String statusPrefix = hudStatus
                ? "screen.simple_translate.gui.status.hud."
                : "screen.simple_translate.gui.status.";
        Component message = switch (status) {
            case COLLECTING -> Component.translatable(statusPrefix + "collecting");
            case TRANSLATING -> Component.translatable(statusPrefix + "translating");
            case COMPLETE -> Component.translatable(statusPrefix + "complete", statusCount);
            case EMPTY -> Component.translatable(statusPrefix + "empty");
            case FAILED -> Component.translatable(statusPrefix + "failed");
            case NONE -> Component.empty();
        };
        int width = client.font.width(message);
        int guiWidth = graphics.guiWidth();
        int x = Math.max(4, guiWidth - width - 8);
        graphics.fill(x - 4, 4, guiWidth - 4, 19, 0xB0101010);
        graphics.drawString(client.font, message, x, 7,
                status == Status.FAILED ? 0xFFFF7777 : 0xFFFFFFFF, true);
    }

    private static final class FrameCapture {
        private final String screenKey;
        private final String screenName;
        private FrameSnapshot previous;
        private final boolean hudFrame;
        private final boolean manualRequest;
        private final boolean detached;
        private final LinkedHashMap<String, Component> sources = new LinkedHashMap<>();
        private final List<Component> contextOccurrences = new ArrayList<>();
        private final Set<String> reusedTranslationKeys = new LinkedHashSet<>();
        private final Set<String> inputValues = new LinkedHashSet<>();
        private final Map<AbstractWidget, Component> compositeOverlays =
                new java.util.IdentityHashMap<>();

        private FrameCapture(String screenKey, String screenName, FrameSnapshot previous,
                             boolean hudFrame, boolean manualRequest, boolean detached) {
            this.screenKey = screenKey;
            this.screenName = screenName;
            this.previous = previous;
            this.hudFrame = hudFrame;
            this.manualRequest = manualRequest;
            this.detached = detached;
        }
    }

    private record FrameSnapshot(Map<String, Component> translations,
                                 Set<String> translatedKeys,
                                 Map<String, List<Component>> semanticTranslations) {
    }

    private enum Status {
        NONE,
        COLLECTING,
        TRANSLATING,
        COMPLETE,
        EMPTY,
        FAILED
    }
}
