package com.yourname.simpletranslate.transport;

import com.yourname.simpletranslate.SimpleTranslateMod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TranslationLane {
    private static final long MAX_BACKOFF_MS = 600_000L;
    private static final int MAX_FAILURE_ENTRIES = 4096;

    private final String lane;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, FailureState> failures = new LinkedHashMap<>(64, 0.75f, true);

    TranslationLane(String lane) {
        this.lane = lane;
    }

    public String lane() {
        return lane;
    }

    public boolean begin(String key, long retryDelayMs) {
        String normalized = normalizeKey(key);
        long now = System.currentTimeMillis();
        synchronized (failures) {
            FailureState failure = failures.get(normalized);
            if (failure != null && now < failure.retryAt()) {
                SimpleTranslateMod.getLogger().debug(
                        "Translation lane {} throttled key {}", lane, shortKey(normalized));
                return false;
            }
        }
        boolean added = pending.add(normalized);
        if (!added) {
            SimpleTranslateMod.getLogger().debug(
                    "Translation lane {} skipped duplicate key {}", lane, shortKey(normalized));
        }
        return added;
    }

    public void finish(String key) {
        String normalized = normalizeKey(key);
        pending.remove(normalized);
        synchronized (failures) {
            failures.remove(normalized);
        }
    }

    public void fail(String key, long retryDelayMs) {
        String normalized = normalizeKey(key);
        pending.remove(normalized);
        synchronized (failures) {
            FailureState previous = failures.get(normalized);
            int count = previous == null ? 1 : previous.failures() + 1;
            long delay = Math.max(0L, retryDelayMs);
            for (int i = 1; i < count && delay < MAX_BACKOFF_MS; i++) {
                delay = Math.min(MAX_BACKOFF_MS, delay * 2L);
            }
            failures.put(normalized, new FailureState(count, System.currentTimeMillis() + delay));
            while (failures.size() > MAX_FAILURE_ENTRIES) {
                String eldest = failures.keySet().iterator().next();
                failures.remove(eldest);
            }
            if (count > 1) {
                SimpleTranslateMod.getLogger().debug(
                        "Translation lane {} backing off key {} failures={} nextRetryMs={}",
                        lane, shortKey(normalized), count, delay);
            }
        }
    }

    public boolean isPending(String key) {
        return pending.contains(normalizeKey(key));
    }

    public boolean isThrottled(String key) {
        String normalized = normalizeKey(key);
        synchronized (failures) {
            FailureState failure = failures.get(normalized);
            return failure != null && System.currentTimeMillis() < failure.retryAt();
        }
    }

    public void clear() {
        pending.clear();
        synchronized (failures) {
            failures.clear();
        }
    }

    private static String normalizeKey(String key) {
        return key == null || key.isBlank() ? "anonymous" : key;
    }

    private static String shortKey(String key) {
        return Integer.toHexString(normalizeKey(key).hashCode());
    }

    private record FailureState(int failures, long retryAt) {
    }
}
