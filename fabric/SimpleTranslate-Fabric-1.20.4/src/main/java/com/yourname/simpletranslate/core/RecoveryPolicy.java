package com.yourname.simpletranslate.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Negative cache for repeatedly rejected translations.
 *
 * <p>The old pipeline retried the same persistently-invalid request forever
 * (lane backoff only delays it), burning tokens. After a few consecutive
 * rejections the key is frozen for a longer hold period.</p>
 */
public final class RecoveryPolicy {
    private static final int MAX_CONSECUTIVE_REJECTIONS = 4;
    private static final long FREEZE_MS = 30L * 60L * 1000L;
    private static final int MAX_ENTRIES = 4096;

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private RecoveryPolicy() {
    }

    public static boolean shouldAttempt(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return true;
        }
        State state = STATES.get(cacheKey);
        if (state == null) {
            return true;
        }
        if (state.frozenUntil > 0 && System.currentTimeMillis() < state.frozenUntil) {
            return false;
        }
        return true;
    }

    public static void recordRejected(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return;
        }
        if (STATES.size() > MAX_ENTRIES) {
            STATES.clear();
        }
        STATES.compute(cacheKey, (ignored, previous) -> {
            int count = previous == null ? 1 : previous.rejections + 1;
            long frozenUntil = count >= MAX_CONSECUTIVE_REJECTIONS
                    ? System.currentTimeMillis() + FREEZE_MS
                    : 0L;
            return new State(count, frozenUntil);
        });
    }

    public static void recordSuccess(String cacheKey) {
        if (cacheKey != null) {
            STATES.remove(cacheKey);
        }
    }

    public static void clearAll() {
        STATES.clear();
    }

    private record State(int rejections, long frozenUntil) {
    }
}
