package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.core.RecoveryPolicy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TranslationLanes {
    private static final Map<String, TranslationLane> LANES = new ConcurrentHashMap<>();

    private TranslationLanes() {
    }

    public static TranslationLane get(String lane) {
        String normalized = lane == null || lane.isBlank() ? "generic" : lane.toLowerCase();
        return LANES.computeIfAbsent(normalized, TranslationLane::new);
    }

    public static TranslationLane forSurface(String surface) {
        return get(TranslationCacheKeys.laneFromSurface(surface));
    }

    public static void clearAll() {
        LANES.values().forEach(TranslationLane::clear);
        RecoveryPolicy.clearAll();
        DirectFormattedTranslationPipeline.clearRuntimeState();
    }
}
