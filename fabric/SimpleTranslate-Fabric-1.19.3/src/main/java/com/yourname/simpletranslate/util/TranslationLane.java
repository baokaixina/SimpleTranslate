package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.SimpleTranslateMod;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TranslationLane {
    private static final long MAX_BACKOFF_MS = 600_000L;
    private static final int MAX_FAILURE_ENTRIES = 8192;

    private final String lane;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, FailureState> failures = new ConcurrentHashMap<>();

    TranslationLane(String lane) {
        this.lane = lane;
    }

    public String lane() {
        return lane;
    }

    public boolean begin(TranslationTask task, long retryDelayMs) {
        if (task == null) {
            return false;
        }
        String key = task.pendingKey();
        FailureState failure = failures.get(key);
        long now = System.currentTimeMillis();
        if (failure != null && now < failure.retryAt) {
            SimpleTranslateMod.getLogger().debug("Translation lane {} throttled task {}", lane, task.taskId());
            return false;
        }
        boolean added = pending.add(key);
        if (!added) {
            SimpleTranslateMod.getLogger().debug("Translation lane {} skipped duplicate task {}", lane, task.taskId());
        }
        return added;
    }

    public void finish(TranslationTask task) {
        if (task != null) {
            pending.remove(task.pendingKey());
            failures.remove(task.pendingKey());
        }
    }

    public void fail(TranslationTask task, long retryDelayMs) {
        if (task == null) {
            return;
        }
        String key = task.pendingKey();
        pending.remove(key);
        if (failures.size() > MAX_FAILURE_ENTRIES) {
            failures.clear();
        }
        long base = Math.max(0L, retryDelayMs);
        failures.compute(key, (ignored, previous) -> {
            int count = previous == null ? 1 : previous.failures + 1;
            long delay = base;
            for (int i = 1; i < count && delay < MAX_BACKOFF_MS; i++) {
                delay *= 2L;
            }
            delay = Math.min(delay, MAX_BACKOFF_MS);
            if (count > 1) {
                SimpleTranslateMod.getLogger().debug(
                        "Translation lane {} backing off task {} failures={} nextRetryMs={}",
                        lane, task.taskId(), count, delay);
            }
            return new FailureState(count, System.currentTimeMillis() + delay);
        });
    }

    public boolean isPending(TranslationTask task) {
        return task != null && pending.contains(task.pendingKey());
    }

    public boolean isThrottled(TranslationTask task) {
        if (task == null) {
            return false;
        }
        FailureState failure = failures.get(task.pendingKey());
        return failure != null && System.currentTimeMillis() < failure.retryAt;
    }

    public void clear() {
        pending.clear();
        failures.clear();
    }

    private record FailureState(int failures, long retryAt) {
    }
}
