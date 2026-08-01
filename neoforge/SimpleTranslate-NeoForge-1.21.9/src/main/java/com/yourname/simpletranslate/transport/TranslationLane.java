package com.yourname.simpletranslate.transport;

import com.yourname.simpletranslate.SimpleTranslateMod;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TranslationLane {
    private static final long MAX_BACKOFF_MS = 600_000L;
    private static final int MAX_FAILURE_ENTRIES = 4096;

    private final String lane;
    private final Object stateLock = new Object();
    private final Map<String, Lease> pending = new HashMap<>();
    private final Map<String, FailureState> failures = new LinkedHashMap<>(64, 0.75f, true);
    private long epoch;
    private long sequence;

    TranslationLane(String lane) {
        this.lane = lane;
    }

    public String lane() {
        return lane;
    }

    public Lease begin(String key, long retryDelayMs) {
        String normalized = normalizeKey(key);
        long now = System.currentTimeMillis();
        synchronized (stateLock) {
            FailureState failure = failures.get(normalized);
            if (failure != null && now < failure.retryAt()) {
                SimpleTranslateMod.getLogger().debug(
                        "Translation lane {} throttled key {}", lane, shortKey(normalized));
                return null;
            }
            Lease lease = new Lease(normalized, epoch, ++sequence);
            Lease existing = pending.putIfAbsent(normalized, lease);
            if (existing != null) {
                SimpleTranslateMod.getLogger().debug(
                        "Translation lane {} skipped duplicate key {}", lane, shortKey(normalized));
                return null;
            }
            return lease;
        }
    }

    public void finish(Lease lease) {
        synchronized (stateLock) {
            if (!releaseLocked(lease)) {
                return;
            }
            failures.remove(lease.key());
        }
    }

    public void fail(Lease lease, long retryDelayMs) {
        synchronized (stateLock) {
            if (!releaseLocked(lease)) {
                return;
            }
            recordFailureLocked(lease.key(), retryDelayMs);
        }
    }

    /** Records a cooldown for callers that do not own a lane lease. */
    public void recordFailure(String key, long retryDelayMs) {
        String normalized = normalizeKey(key);
        synchronized (stateLock) {
            recordFailureLocked(normalized, retryDelayMs);
        }
    }

    public boolean isPending(String key) {
        synchronized (stateLock) {
            return pending.containsKey(normalizeKey(key));
        }
    }

    public boolean isThrottled(String key) {
        String normalized = normalizeKey(key);
        synchronized (stateLock) {
            FailureState failure = failures.get(normalized);
            return failure != null && System.currentTimeMillis() < failure.retryAt();
        }
    }

    public void clear() {
        synchronized (stateLock) {
            epoch++;
            pending.clear();
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

    public record Lease(String key, long epoch, long sequence) {
    }

    private boolean releaseLocked(Lease lease) {
        return lease != null && lease.epoch() == epoch
                && pending.remove(lease.key(), lease);
    }

    private void recordFailureLocked(String normalized, long retryDelayMs) {
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
