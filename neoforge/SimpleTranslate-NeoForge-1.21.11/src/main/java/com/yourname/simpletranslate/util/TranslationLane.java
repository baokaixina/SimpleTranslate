package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.SimpleTranslateMod;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TranslationLane {
    private final String lane;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> failureUntil = new ConcurrentHashMap<>();

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
        Long retryAt = failureUntil.get(key);
        long now = System.currentTimeMillis();
        if (retryAt != null && now < retryAt) {
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
            failureUntil.remove(task.pendingKey());
        }
    }

    public void fail(TranslationTask task, long retryDelayMs) {
        if (task == null) {
            return;
        }
        String key = task.pendingKey();
        pending.remove(key);
        failureUntil.put(key, System.currentTimeMillis() + Math.max(0L, retryDelayMs));
    }

    public boolean isPending(TranslationTask task) {
        return task != null && pending.contains(task.pendingKey());
    }

    public void clear() {
        pending.clear();
        failureUntil.clear();
    }
}
