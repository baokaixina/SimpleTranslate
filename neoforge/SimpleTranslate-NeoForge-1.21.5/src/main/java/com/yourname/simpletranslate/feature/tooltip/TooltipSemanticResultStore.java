package com.yourname.simpletranslate.feature.tooltip;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small session-level handoff between an asynchronous tooltip request and the
 * render thread.  It stores translated semantic Components, never a rendered
 * tooltip: every frame still rebuilds against that frame's original icons,
 * styles, progress values and spacing atoms.
 */
public final class TooltipSemanticResultStore {
    private static final int MAX_READY_RESULTS = 4096;
    private static final AtomicLong REVISION = new AtomicLong();
    private static final Map<String, List<Component>> READY =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75F, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Component>> eldest) {
                    return size() > MAX_READY_RESULTS;
                }
            });

    private TooltipSemanticResultStore() {
    }

    @Nullable
    public static List<Component> get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        List<Component> value = READY.get(key);
        return value == null ? null : List.copyOf(value);
    }

    public static void put(String key, List<Component> translatedSemantic) {
        if (key == null || key.isBlank() || translatedSemantic == null
                || translatedSemantic.isEmpty() || translatedSemantic.stream().anyMatch(java.util.Objects::isNull)) {
            return;
        }
        READY.put(key, List.copyOf(translatedSemantic));
        REVISION.incrementAndGet();
    }

    public static void remove(String key) {
        if (key != null && !key.isBlank()) {
            READY.remove(key);
            REVISION.incrementAndGet();
        }
    }

    public static void clear() {
        READY.clear();
        REVISION.incrementAndGet();
    }

    /** Store version for render-thread memos that must never freeze live updates. */
    public static long revision() {
        return REVISION.get();
    }

    static int sizeForTesting() {
        return READY.size();
    }
}
