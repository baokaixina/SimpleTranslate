package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.SimpleTranslateMod;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Crash isolation for translation work that runs on the Minecraft client render
 * thread. SimpleTranslate is a non-invasive mod: a bug in translation, cache
 * restore, or model-response parsing must never propagate into vanilla render
 * code and crash the game. Every render-thread translation entry point wraps its
 * body in {@link #guard} so any {@link Throwable} degrades to the original text.
 */
public final class SafeTranslate {

    private static final ConcurrentHashMap<String, Long> LAST_LOG = new ConcurrentHashMap<>();
    private static final long REPEAT_MS = 60_000L;

    private SafeTranslate() {
    }

    /**
     * Runs {@code body}; if it throws anything, logs once per tag/minute and
     * returns {@code fallback} instead of letting the error reach the caller.
     */
    public static <T> T guard(Supplier<T> body, T fallback, String tag) {
        try {
            return body.get();
        } catch (Throwable error) {
            logLimited(tag, error);
            return fallback;
        }
    }

    /** Void variant for side-effecting render hooks. */
    public static void guard(Runnable body, String tag) {
        try {
            body.run();
        } catch (Throwable error) {
            logLimited(tag, error);
        }
    }

    /**
     * Rate-limited warning (once per tag/minute) for non-fatal degradation paths
     * that keep the original text. Use this instead of swallowing exceptions
     * silently or logging at {@code debug} so production logs surface the defect.
     */
    public static void logLimited(String tag, Throwable error) {
        String key = tag == null ? "unknown" : tag;
        long now = System.currentTimeMillis();
        Long previous = LAST_LOG.get(key);
        if (previous != null && now - previous < REPEAT_MS) {
            return;
        }
        LAST_LOG.put(key, now);
        SimpleTranslateMod.getLogger().warn(
                "[SimpleTranslate] translation degraded in {} (showing original text)", key, error);
    }

    /** Rate-limited warning without a throwable, for paths that detect a defect without an exception. */
    public static void logLimited(String tag, String message, Object... args) {
        String key = tag == null ? "unknown" : tag;
        long now = System.currentTimeMillis();
        Long previous = LAST_LOG.get(key);
        if (previous != null && now - previous < REPEAT_MS) {
            return;
        }
        LAST_LOG.put(key, now);
        SimpleTranslateMod.getLogger().warn("[SimpleTranslate] " + message, args);
    }
}
