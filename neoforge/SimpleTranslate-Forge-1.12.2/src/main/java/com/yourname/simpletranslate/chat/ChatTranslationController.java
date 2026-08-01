package com.yourname.simpletranslate.chat;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.translation.TranslationEngine;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.feature.hud.HudTranslationController;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * AUTO chat's sole path: collect a short local window, resolve cache hits,
 * then issue one Component-JSON array request for the remaining messages.
 * Results are applied only from the client tick using GuiNewChat's exact
 * 1.12.2 deletion-ID replacement API.
 */
public final class ChatTranslationController {
    /**
     * Covers the queue's three bounded HTTP attempts (30 s connect + 60 s read each, plus retry
     * delays and scheduling margin). A valid slow completion must still replace the visible line.
     */
    private static final long REQUEST_TIMEOUT_MS = 360000L;
    /** Matches the Component-JSON lane's ordinary first failure cooldown. */
    private static final long REQUEST_RETRY_MS = 6000L;
    private static final long COLLECT_WINDOW_MS = 300L;
    private static final int MAX_COLLECT_BATCH = 12;
    private static final int MAX_COLLECT_JSON_CHARS = 8000;
    private static final Map<Integer, PendingChat> PENDING = new LinkedHashMap<Integer, PendingChat>();
    private static final Map<Integer, Integer> PENDING_BY_DISPLAY_ID = new LinkedHashMap<Integer, Integer>();
    private static final Map<Integer, ITextComponent> READY = new LinkedHashMap<Integer, ITextComponent>();
    private static final Map<Integer, AutoEntry> AUTO_DISPLAYED = new LinkedHashMap<Integer, AutoEntry>();
    private static final String BUTTON_PREFIX = "simple_translate:chat:";
    private static final Map<Integer, ButtonEntry> BUTTONS = new LinkedHashMap<Integer, ButtonEntry>();
    private static final Map<Integer, ITextComponent> BUTTON_READY = new LinkedHashMap<Integer, ITextComponent>();
    private static final Set<Integer> BUTTON_FAILED = new LinkedHashSet<Integer>();
    private static final List<String> RECENT_CHAT = new ArrayList<String>();
    private static int nextId = Integer.MIN_VALUE;
    /** Keep optional-deletion IDs disjoint from ordinary mod/vanilla positive IDs. */
    private static int nextButtonId = Integer.MIN_VALUE + 1;
    private static final ThreadLocal<Integer> INTERNAL_PRINT_DEPTH = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return Integer.valueOf(0); }
    };

    private ChatTranslationController() { }

    public static boolean shouldRetain(ITextComponent original) {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        return original != null && engine != null && engine.isConfigured() && engine.isChatAutoMode()
                && engine.isSurfaceEnabled("chat.context.batch");
    }

    public static boolean shouldAttachButton(ITextComponent original) {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        return original != null && engine != null && engine.isConfigured() && !engine.isChatAutoMode()
                && engine.isSurfaceEnabled("chat.button") && !engine.isBlacklisted(original);
    }

    /** True while SimpleTranslate is replacing a line through the intercepted lower-level API. */
    public static boolean isInternalPrint() {
        return INTERNAL_PRINT_DEPTH.get().intValue() > 0;
    }

    /**
     * Prints without feeding SimpleTranslate's own replacement/history lines back through the
     * chat translation interceptor. The guard is thread-local because GuiNewChat is client-thread
     * state while async completions only publish into the synchronized handoff maps.
     */
    public static void printInternal(GuiNewChat chat, ITextComponent component, int deletionId) {
        if (chat == null || component == null) return;
        int depth = enterInternalPrint();
        try {
            chat.printChatMessageWithOptionalDeletion(component, deletionId);
        } finally {
            leaveInternalPrint(depth);
        }
    }

    /** Runs GuiNewChat's own set-line cleanup without treating it as an external deletion. */
    public static void deleteInternal(GuiNewChat chat, int deletionId) {
        if (chat == null) return;
        int depth = enterInternalPrint();
        try {
            chat.deleteChatLine(deletionId);
        } finally {
            leaveInternalPrint(depth);
        }
    }

    private static int enterInternalPrint() {
        int depth = INTERNAL_PRINT_DEPTH.get().intValue();
        INTERNAL_PRINT_DEPTH.set(Integer.valueOf(depth + 1));
        return depth;
    }

    private static void leaveInternalPrint(int previousDepth) {
        if (previousDepth == 0) INTERNAL_PRINT_DEPTH.remove();
        else INTERNAL_PRINT_DEPTH.set(Integer.valueOf(previousDepth));
    }

    public static synchronized ButtonPresentation attachButton(ITextComponent original, int requestedDeletionId) {
        int id = requestedDeletionId == 0 ? allocateButtonId() : requestedDeletionId;
        ButtonEntry entry = new ButtonEntry(original);
        // LinkedHashMap insertion order is used for the 100-line bound. Refresh
        // a caller-reused ID so a newly updated visible line is not trimmed as
        // though it were still the oldest line.
        BUTTONS.remove(Integer.valueOf(id));
        BUTTONS.put(id, entry);
        BUTTON_READY.remove(id);
        BUTTON_FAILED.remove(Integer.valueOf(id));
        recordRecent(original);
        trimButtons();
        return new ButtonPresentation(id, decorate(original, id, entry.state));
    }

    public static boolean handleButtonClick(String value) {
        if (HudTranslationController.handleHistoryClick(value)) return true;
        if (value == null || !value.startsWith(BUTTON_PREFIX)) return false;
        final int id;
        try { id = Integer.parseInt(value.substring(BUTTON_PREFIX.length())); }
        catch (Exception ignored) { return false; }
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (engine == null) return true;
        final ButtonEntry entry;
        synchronized (ChatTranslationController.class) { entry = BUTTONS.get(id); }
        if (entry == null || engine.isBlacklisted(entry.original)) return true;
        if (entry.state == ButtonEntry.TRANSLATING) return true;
        if (entry.state == ButtonEntry.TRANSLATED) {
            entry.state = ButtonEntry.ORIGINAL;
            replaceButton(id, decorate(entry.original, id, entry.state));
            return true;
        }
        ITextComponent cached = engine.getCachedComponent(entry.original, "chat.button");
        if (cached != null) {
            entry.translated = cached;
            entry.state = ButtonEntry.TRANSLATED;
            replaceButton(id, decorate(cached, id, entry.state));
            return true;
        }
        synchronized (ChatTranslationController.class) {
            if (BUTTONS.get(Integer.valueOf(id)) != entry) return true;
            entry.state = ButtonEntry.TRANSLATING;
            entry.translationStartedAt = System.currentTimeMillis();
            entry.nextAttemptAt = 0L;
            entry.requestInFlight = true;
        }
        replaceButton(id, decorate(entry.original, id, entry.state));
        dispatchButton(engine, id, entry);
        return true;
    }

    private static void dispatchButton(final TranslationEngine engine, final int id, final ButtonEntry entry) {
        final long runtimeRevision = SimpleTranslateForge1122.getRuntimeRevision();
        try {
            DirectSurfaceTranslator.translateComponentsAsync(Collections.singletonList(entry.original),
                            "chat.button", "game-text", false, "",
                            engine.getSourceLanguage(), engine.getTargetLanguage())
                .whenComplete(new java.util.function.BiConsumer<ComponentListTranslationResult, Throwable>() {
                    @Override public void accept(ComponentListTranslationResult result, Throwable error) {
                        if (!SimpleTranslateForge1122.isRuntimeRevisionCurrent(runtimeRevision)) {
                            synchronized (ChatTranslationController.class) {
                                if (BUTTONS.get(Integer.valueOf(id)) == entry) entry.requestInFlight = false;
                            }
                            return;
                        }
                        if (error != null || result == null || !result.translated
                                || result.components == null || result.components.size() != 1) {
                            scheduleButtonRetry(id, entry, result != null && !result.handled);
                            return;
                        }
                        try {
                            ITextComponent translated = result.components.get(0);
                            synchronized (ChatTranslationController.class) {
                                if (BUTTONS.get(Integer.valueOf(id)) == entry
                                        && entry.state == ButtonEntry.TRANSLATING) {
                                    entry.requestInFlight = false;
                                    BUTTON_READY.put(Integer.valueOf(id), translated);
                                }
                            }
                        } catch (Exception ignored) {
                            scheduleButtonRetry(id, entry, false);
                        }
                    }
                });
        } catch (RuntimeException launchFailure) {
            scheduleButtonRetry(id, entry, false);
        }
    }

    private static synchronized void scheduleButtonRetry(int id, ButtonEntry entry, boolean terminal) {
        if (BUTTONS.get(Integer.valueOf(id)) != entry || entry.state != ButtonEntry.TRANSLATING) return;
        entry.requestInFlight = false;
        long now = System.currentTimeMillis();
        if (terminal || now - entry.translationStartedAt >= REQUEST_TIMEOUT_MS) {
            BUTTON_FAILED.add(Integer.valueOf(id));
        } else {
            entry.nextAttemptAt = now + REQUEST_RETRY_MS;
        }
    }

    public static synchronized int retain(ITextComponent original, int requestedDeletionId) {
        int requestId = allocateAutoId();
        int displayId = requestedDeletionId == 0 ? requestId : requestedDeletionId;
        Integer previousRequest = PENDING_BY_DISPLAY_ID.put(Integer.valueOf(displayId), Integer.valueOf(requestId));
        if (previousRequest != null) {
            PENDING.remove(previousRequest);
            READY.remove(previousRequest);
        }
        AUTO_DISPLAYED.remove(Integer.valueOf(displayId));
        String contextSnapshot = recentContext();
        PENDING.put(Integer.valueOf(requestId), new PendingChat(
                original, displayId, System.currentTimeMillis(), contextSnapshot));
        recordRecent(original);
        return displayId;
    }

    /**
     * Invalidates work for an external replacement/deletion before that caller's newest line is
     * accepted. SimpleTranslate's own replacements bypass this through {@link #isInternalPrint()}.
     */
    public static synchronized void invalidateExternalReplacement(int deletionId) {
        if (deletionId == 0) return;
        Integer requestId = PENDING_BY_DISPLAY_ID.remove(Integer.valueOf(deletionId));
        if (requestId != null) {
            PENDING.remove(requestId);
            READY.remove(requestId);
        }
        AUTO_DISPLAYED.remove(Integer.valueOf(deletionId));
        BUTTONS.remove(Integer.valueOf(deletionId));
        BUTTON_READY.remove(Integer.valueOf(deletionId));
        BUTTON_FAILED.remove(Integer.valueOf(deletionId));
    }

    /** Drops world-bound chat/button handoffs before the next connection starts. */
    public static synchronized void clearRuntimeState() {
        PENDING.clear();
        PENDING_BY_DISPLAY_ID.clear();
        READY.clear();
        AUTO_DISPLAYED.clear();
        BUTTONS.clear();
        BUTTON_READY.clear();
        BUTTON_FAILED.clear();
        RECENT_CHAT.clear();
        nextId = Integer.MIN_VALUE;
        nextButtonId = Integer.MIN_VALUE + 1;
    }

    /**
     * Cancels old-mode work without leaving clickable text in GuiNewChat whose
     * backing entry was discarded. Existing AUTO lines are restored to their
     * originals; BUTTON lines remain live only when button mode is still active.
     */
    public static synchronized void resetForSettings() {
        Minecraft minecraft = Minecraft.getMinecraft();
        GuiNewChat chat = minecraft == null || minecraft.ingameGUI == null
                ? null : minecraft.ingameGUI.getChatGUI();
        if (chat != null) {
            for (Map.Entry<Integer, AutoEntry> value : AUTO_DISPLAYED.entrySet()) {
                printInternal(chat, value.getValue().original, value.getKey().intValue());
            }
            java.util.Iterator<Map.Entry<Integer, ButtonEntry>> iterator = BUTTONS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, ButtonEntry> value = iterator.next();
                ButtonEntry button = value.getValue();
                if (shouldAttachButton(button.original)) {
                    button.translated = null;
                    button.state = ButtonEntry.ORIGINAL;
                    button.showingOriginal = false;
                    button.requestInFlight = false;
                    button.nextAttemptAt = 0L;
                    button.translationStartedAt = 0L;
                    printInternal(chat,
                            decorate(button.original, value.getKey().intValue(), ButtonEntry.ORIGINAL),
                            value.getKey().intValue());
                } else {
                    printInternal(chat, button.original, value.getKey().intValue());
                    iterator.remove();
                }
            }
        } else {
            BUTTONS.clear();
        }
        PENDING.clear();
        PENDING_BY_DISPLAY_ID.clear();
        READY.clear();
        AUTO_DISPLAYED.clear();
        BUTTON_READY.clear();
        BUTTON_FAILED.clear();
        RECENT_CHAT.clear();
    }

    public static void tick() {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        Minecraft minecraft = Minecraft.getMinecraft();
        if (engine == null || minecraft == null || minecraft.ingameGUI == null) return;
        GuiNewChat chat = minecraft.ingameGUI.getChatGUI();
        applyReady(chat);
        applyButtonReady(chat);
        applyButtonFailures(chat);
        retryButtons(engine);
        refreshHoldOriginal(chat, engine);

        List<BatchEntry> toTranslate = new ArrayList<BatchEntry>();
        long now = System.currentTimeMillis();
        boolean contextual = ModConfig.CHAT_CONTEXT_ENABLED.get();
        String surface = contextual ? "chat.context.batch" : "chat.auto.direct";
        int batchJsonChars = 0;
        synchronized (ChatTranslationController.class) {
            java.util.Iterator<Map.Entry<Integer, PendingChat>> iterator = PENDING.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, PendingChat> entry = iterator.next();
                PendingChat pending = entry.getValue();
                if (now - pending.createdAt > REQUEST_TIMEOUT_MS) {
                    removePendingDisplayMapping(entry.getKey(), pending);
                    iterator.remove();
                    continue;
                }
                if (engine.isBlacklisted(pending.original)) {
                    removePendingDisplayMapping(entry.getKey(), pending);
                    iterator.remove();
                    continue;
                }
                if (pending.dispatched || now < pending.nextAttemptAt
                        || (contextual && now - pending.createdAt < COLLECT_WINDOW_MS)) continue;
                ITextComponent cached = engine.getCachedComponent(pending.original, surface);
                if (cached != null) {
                    READY.put(entry.getKey(), cached);
                    continue;
                }
                int jsonChars = ITextComponent.Serializer.componentToJson(pending.original).length();
                if (contextual && !toTranslate.isEmpty()
                        && (toTranslate.size() >= MAX_COLLECT_BATCH || batchJsonChars + jsonChars > MAX_COLLECT_JSON_CHARS)) {
                    continue;
                }
                pending.dispatched = true;
                toTranslate.add(new BatchEntry(entry.getKey(), pending.original, pending.contextSnapshot));
                batchJsonChars += jsonChars;
            }
        }
        if (!toTranslate.isEmpty()) dispatch(engine, toTranslate, surface, contextual);
    }

    private static void dispatch(final TranslationEngine engine, final List<BatchEntry> entries,
                                 final String surface, boolean contextual) {
        final long runtimeRevision = SimpleTranslateForge1122.getRuntimeRevision();
        List<ITextComponent> document = new ArrayList<ITextComponent>(entries.size());
        for (BatchEntry entry : entries) document.add(entry.original);
        String promptContext = contextual ? entries.get(0).contextSnapshot : "";
        try {
            DirectSurfaceTranslator.translateComponentsAsync(document, surface, "game-text", false,
                            promptContext, engine.getSourceLanguage(), engine.getTargetLanguage())
                .whenComplete(new java.util.function.BiConsumer<ComponentListTranslationResult, Throwable>() {
                    @Override public void accept(ComponentListTranslationResult result, Throwable error) {
                        if (!SimpleTranslateForge1122.isRuntimeRevisionCurrent(runtimeRevision)) {
                            synchronized (ChatTranslationController.class) {
                                for (BatchEntry entry : entries) dropPending(entry.id);
                            }
                            return;
                        }
                        if (result != null && !result.handled) {
                            synchronized (ChatTranslationController.class) {
                                for (BatchEntry entry : entries) dropPending(entry.id);
                            }
                            return;
                        }
                        if (error != null || result == null || !result.translated
                                || result.components == null || result.components.size() != entries.size()) {
                            synchronized (ChatTranslationController.class) {
                                for (BatchEntry entry : entries) retryPending(entry.id);
                            }
                            return;
                        }
                        try {
                            synchronized (ChatTranslationController.class) {
                                for (int i = 0; i < entries.size(); i++) {
                                    BatchEntry entry = entries.get(i);
                                    PendingChat pending = PENDING.get(Integer.valueOf(entry.id));
                                    if (pending != null && ownsDisplayId(entry.id, pending)) {
                                        READY.put(Integer.valueOf(entry.id), result.components.get(i));
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                            synchronized (ChatTranslationController.class) {
                                for (BatchEntry entry : entries) retryPending(entry.id);
                            }
                        }
                    }
                });
        } catch (RuntimeException launchFailure) {
            synchronized (ChatTranslationController.class) {
                for (BatchEntry entry : entries) retryPending(entry.id);
            }
        }
    }

    private static void applyReady(GuiNewChat chat) {
        synchronized (ChatTranslationController.class) {
            java.util.Iterator<Map.Entry<Integer, ITextComponent>> iterator = READY.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, ITextComponent> entry = iterator.next();
                PendingChat pending = PENDING.remove(entry.getKey());
                if (pending != null) {
                    Integer currentRequest = PENDING_BY_DISPLAY_ID.get(Integer.valueOf(pending.displayId));
                    if (currentRequest == null || currentRequest.intValue() != entry.getKey().intValue()) {
                        iterator.remove();
                        continue;
                    }
                    PENDING_BY_DISPLAY_ID.remove(Integer.valueOf(pending.displayId));
                    AutoEntry displayed = new AutoEntry(pending.original, entry.getValue());
                    AUTO_DISPLAYED.put(Integer.valueOf(pending.displayId), displayed);
                    trimAutoDisplayed();
                    printInternal(chat, entry.getValue(), pending.displayId);
                }
                iterator.remove();
            }
        }
    }

    private static void applyButtonReady(GuiNewChat chat) {
        synchronized (ChatTranslationController.class) {
            java.util.Iterator<Map.Entry<Integer, ITextComponent>> iterator = BUTTON_READY.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, ITextComponent> ready = iterator.next();
                ButtonEntry entry = BUTTONS.get(ready.getKey());
                if (entry != null) {
                    entry.translated = ready.getValue();
                    entry.state = ButtonEntry.TRANSLATED;
                    entry.requestInFlight = false;
                    entry.nextAttemptAt = 0L;
                    ITextComponent visible = HoldOriginalState.isHolding(HoldOriginalFeature.CHAT) ? entry.original : entry.translated;
                    printInternal(chat, decorate(visible, ready.getKey(),
                            visible == entry.original ? ButtonEntry.ORIGINAL : entry.state), ready.getKey());
                }
                iterator.remove();
            }
        }
    }

    private static void applyButtonFailures(GuiNewChat chat) {
        synchronized (ChatTranslationController.class) {
            java.util.Iterator<Integer> iterator = BUTTON_FAILED.iterator();
            while (iterator.hasNext()) {
                Integer id = iterator.next();
                ButtonEntry entry = BUTTONS.get(id);
                if (entry != null && entry.state == ButtonEntry.TRANSLATING) {
                    entry.state = ButtonEntry.ORIGINAL;
                    entry.requestInFlight = false;
                    entry.nextAttemptAt = 0L;
                    printInternal(chat, decorate(entry.original, id.intValue(), entry.state), id.intValue());
                }
                iterator.remove();
            }
        }
    }

    private static void retryButtons(TranslationEngine engine) {
        List<ButtonDispatch> launches = new ArrayList<ButtonDispatch>();
        long now = System.currentTimeMillis();
        synchronized (ChatTranslationController.class) {
            for (Map.Entry<Integer, ButtonEntry> value : BUTTONS.entrySet()) {
                int id = value.getKey().intValue();
                ButtonEntry entry = value.getValue();
                if (entry.state != ButtonEntry.TRANSLATING || entry.requestInFlight
                        || now < entry.nextAttemptAt) continue;
                if (now - entry.translationStartedAt >= REQUEST_TIMEOUT_MS) {
                    BUTTON_FAILED.add(Integer.valueOf(id));
                    continue;
                }
                ITextComponent cached = engine.getCachedComponent(entry.original, "chat.button");
                if (cached != null) {
                    BUTTON_READY.put(Integer.valueOf(id), cached);
                    continue;
                }
                entry.requestInFlight = true;
                launches.add(new ButtonDispatch(id, entry));
            }
        }
        for (ButtonDispatch launch : launches) dispatchButton(engine, launch.id, launch.entry);
    }

    private static void replaceButton(int id, ITextComponent component) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft != null && minecraft.ingameGUI != null) {
            printInternal(minecraft.ingameGUI.getChatGUI(), component, id);
        }
    }

    private static void refreshHoldOriginal(GuiNewChat chat, TranslationEngine engine) {
        synchronized (ChatTranslationController.class) {
            boolean holding = HoldOriginalState.isHolding(HoldOriginalFeature.CHAT);
            for (Map.Entry<Integer, AutoEntry> entry : AUTO_DISPLAYED.entrySet()) {
                AutoEntry displayed = entry.getValue();
                if (displayed.showingOriginal != holding) {
                    displayed.showingOriginal = holding;
                    printInternal(chat, holding ? displayed.original : displayed.translated, entry.getKey());
                }
            }
            for (Map.Entry<Integer, ButtonEntry> entry : BUTTONS.entrySet()) {
                ButtonEntry button = entry.getValue();
                if (button.translated == null || button.showingOriginal == holding) continue;
                button.showingOriginal = holding;
                ITextComponent visible = holding ? button.original : button.translated;
                printInternal(chat, decorate(visible, entry.getKey(),
                        holding ? ButtonEntry.ORIGINAL : ButtonEntry.TRANSLATED), entry.getKey());
            }
        }
    }

    private static int allocateAutoId() {
        int candidate;
        do {
            candidate = nextId;
            nextId += 2;
        } while (candidate == 0 || PENDING.containsKey(Integer.valueOf(candidate))
                || PENDING_BY_DISPLAY_ID.containsKey(Integer.valueOf(candidate))
                || AUTO_DISPLAYED.containsKey(Integer.valueOf(candidate))
                || BUTTONS.containsKey(Integer.valueOf(candidate)));
        return candidate;
    }

    private static int allocateButtonId() {
        int candidate;
        do {
            candidate = nextButtonId;
            nextButtonId += 2;
        } while (candidate == 0 || BUTTONS.containsKey(Integer.valueOf(candidate))
                || PENDING.containsKey(Integer.valueOf(candidate))
                || PENDING_BY_DISPLAY_ID.containsKey(Integer.valueOf(candidate))
                || AUTO_DISPLAYED.containsKey(Integer.valueOf(candidate)));
        return candidate;
    }

    private static boolean ownsDisplayId(int requestId, PendingChat pending) {
        Integer owner = PENDING_BY_DISPLAY_ID.get(Integer.valueOf(pending.displayId));
        return owner != null && owner.intValue() == requestId;
    }

    private static void removePendingDisplayMapping(int requestId, PendingChat pending) {
        if (pending != null && ownsDisplayId(requestId, pending)) {
            PENDING_BY_DISPLAY_ID.remove(Integer.valueOf(pending.displayId));
        }
        READY.remove(Integer.valueOf(requestId));
    }

    private static void dropPending(int requestId) {
        PendingChat pending = PENDING.remove(Integer.valueOf(requestId));
        removePendingDisplayMapping(requestId, pending);
    }

    private static void retryPending(int requestId) {
        PendingChat pending = PENDING.get(Integer.valueOf(requestId));
        if (pending == null || !ownsDisplayId(requestId, pending)) return;
        READY.remove(Integer.valueOf(requestId));
        pending.dispatched = false;
        pending.nextAttemptAt = System.currentTimeMillis() + REQUEST_RETRY_MS;
    }

    private static ITextComponent decorate(ITextComponent content, int id, int state) {
        String key = state == ButtonEntry.TRANSLATING ? "chat.simple_translate.button.translating"
                : state == ButtonEntry.TRANSLATED ? "chat.simple_translate.button.original"
                : "chat.simple_translate.button.translate";
        TextFormatting color = state == ButtonEntry.TRANSLATING ? TextFormatting.GRAY : state == ButtonEntry.TRANSLATED ? TextFormatting.YELLOW : TextFormatting.AQUA;
        ITextComponent button = new TextComponentTranslation(key);
        button.setStyle(new Style().setColor(color).setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, BUTTON_PREFIX + id)));
        return content.createCopy().appendSibling(button);
    }

    private static void trimButtons() {
        while (BUTTONS.size() > 100) {
            Integer id = BUTTONS.keySet().iterator().next();
            BUTTONS.remove(id); BUTTON_READY.remove(id); BUTTON_FAILED.remove(id);
        }
    }

    private static void trimAutoDisplayed() {
        while (AUTO_DISPLAYED.size() > 100) AUTO_DISPLAYED.remove(AUTO_DISPLAYED.keySet().iterator().next());
    }

    private static void recordRecent(ITextComponent component) {
        if (component == null) return;
        String text = component.getUnformattedText();
        if (text == null || text.trim().isEmpty()) return;
        RECENT_CHAT.add(text);
        while (RECENT_CHAT.size() > 20) RECENT_CHAT.remove(0);
    }

    private static String recentContext() {
        int count = Math.max(0, Math.min(20, ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get()));
        if (count == 0 || RECENT_CHAT.isEmpty()) return "";
        StringBuilder value = new StringBuilder("Recent chat messages before this batch:");
        int start = Math.max(0, RECENT_CHAT.size() - count);
        for (int i = start; i < RECENT_CHAT.size(); i++) value.append("\n- ").append(RECENT_CHAT.get(i));
        return value.toString();
    }

    public static final class ButtonPresentation {
        public final int id; public final ITextComponent message;
        private ButtonPresentation(int id, ITextComponent message) { this.id = id; this.message = message; }
    }

    private static final class ButtonEntry {
        static final int ORIGINAL = 0, TRANSLATING = 1, TRANSLATED = 2;
        private final ITextComponent original; private ITextComponent translated; private int state = ORIGINAL; private boolean showingOriginal;
        private boolean requestInFlight; private long translationStartedAt; private long nextAttemptAt;
        private ButtonEntry(ITextComponent original) { this.original = original; }
    }

    private static final class ButtonDispatch {
        private final int id;
        private final ButtonEntry entry;
        private ButtonDispatch(int id, ButtonEntry entry) { this.id = id; this.entry = entry; }
    }

    private static final class AutoEntry {
        private final ITextComponent original; private final ITextComponent translated; private boolean showingOriginal;
        private AutoEntry(ITextComponent original, ITextComponent translated) { this.original = original; this.translated = translated; }
    }

    private static final class PendingChat {
        private final ITextComponent original;
        private final int displayId;
        private final long createdAt;
        private final String contextSnapshot;
        private boolean dispatched;
        private long nextAttemptAt;
        private PendingChat(ITextComponent original, int displayId, long createdAt, String contextSnapshot) {
            this.original = original; this.displayId = displayId;
            this.createdAt = createdAt;
            this.contextSnapshot = contextSnapshot;
        }
    }

    private static final class BatchEntry {
        private final int id;
        private final ITextComponent original;
        private final String contextSnapshot;
        private BatchEntry(int id, ITextComponent original, String contextSnapshot) {
            this.id = id; this.original = original; this.contextSnapshot = contextSnapshot;
        }
    }
}
