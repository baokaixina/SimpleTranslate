package com.yourname.simpletranslate.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Negative cache for repeatedly rejected translations.
 *
 * <p>The old pipeline retried the same persistently-invalid request forever
 * (lane backoff only delays it), burning tokens. After a few consecutive
 * rejections the key receives a short hold, while the lane remains responsible
 * for exponential backoff. A transient provider/account recovery must not leave
 * visible natural language frozen in the source language for half an hour.</p>
 */
public final class RecoveryPolicy {
    private static final int MAX_CONSECUTIVE_REJECTIONS = 4;
    private static final long FREEZE_MS = 60_000L;
    private static final int MAX_ENTRIES = 4096;

    private static final Map<String, State> STATES = new LinkedHashMap<>(64, 0.75f, true);

    private RecoveryPolicy() {
    }

    public static boolean shouldAttempt(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return true;
        }
        synchronized (STATES) {
            State state = STATES.get(cacheKey);
            if (state == null || state.frozenUntil() <= 0) {
                return true;
            }
            if (System.currentTimeMillis() >= state.frozenUntil()) {
                // A completed hold starts a fresh failure window. Keeping the old
                // rejection count would make the very next transient failure
                // freeze this key again immediately.
                STATES.remove(cacheKey);
                return true;
            }
            return false;
        }
    }

    public static void recordRejected(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return;
        }
        synchronized (STATES) {
            State previous = STATES.get(cacheKey);
            int count = previous == null ? 1 : previous.rejections() + 1;
            long frozenUntil = count >= MAX_CONSECUTIVE_REJECTIONS
                    ? System.currentTimeMillis() + FREEZE_MS
                    : 0L;
            STATES.put(cacheKey, new State(count, frozenUntil));
            while (STATES.size() > MAX_ENTRIES) {
                String eldest = STATES.keySet().iterator().next();
                STATES.remove(eldest);
            }
        }
    }

    public static void recordSuccess(String cacheKey) {
        if (cacheKey != null) {
            synchronized (STATES) {
                STATES.remove(cacheKey);
            }
        }
    }

    public static void clearAll() {
        synchronized (STATES) {
            STATES.clear();
        }
    }

    private record State(int rejections, long frozenUntil) {
    }
}
