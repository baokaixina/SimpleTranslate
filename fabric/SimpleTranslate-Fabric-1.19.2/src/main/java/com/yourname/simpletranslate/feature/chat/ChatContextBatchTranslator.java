package com.yourname.simpletranslate.feature.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Thin registry for AUTO chat JSON collection.
 *
 * <p>The old pending-entry chat batch pipeline was removed. AUTO chat now lives
 * in {@link ChatAutoTranslator}: it collects short visible bursts, sends
 * Component JSON arrays, and applies each returned Component directly.</p>
 */
public final class ChatContextBatchTranslator {
    private static final Map<ChatTranslationController, ChatAutoTranslator> CONTROLLERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ChatContextBatchTranslator() {
    }

    public static void register() {
        // Registration is owned by SimpleTranslateMod's client tick hook.
    }

    static void trackController(ChatAutoTranslator autoTranslator, ChatTranslationController controller) {
        if (autoTranslator == null || controller == null) {
            return;
        }
        CONTROLLERS.put(controller, autoTranslator);
    }

    public static void tickAllCollectors() {
        List<ChatAutoTranslator> snapshot;
        synchronized (CONTROLLERS) {
            snapshot = new ArrayList<>(CONTROLLERS.values());
        }
        for (ChatAutoTranslator autoTranslator : snapshot) {
            if (autoTranslator != null) {
                autoTranslator.tick();
            }
        }
    }

    public static void clear() {
        synchronized (CONTROLLERS) {
            for (ChatAutoTranslator autoTranslator : CONTROLLERS.values()) {
                if (autoTranslator != null) {
                    autoTranslator.clearRuntimeState();
                }
            }
        }
    }

    public static void restoreVisibleOriginalMessages() {
        List<ChatTranslationController> snapshot;
        synchronized (CONTROLLERS) {
            snapshot = new ArrayList<>(CONTROLLERS.keySet());
        }
        for (ChatTranslationController controller : snapshot) {
            if (controller != null) {
                controller.restoreVisibleOriginalMessages();
            }
        }
    }
}


