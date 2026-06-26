package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.cache.CacheKey;
import com.yourname.simpletranslate.core.Surface;

import java.text.Normalizer;

public final class TranslationCacheKeys {
    public static final String PROTOCOL = CacheKey.PROTOCOL;
    public static final String COMPONENT_JSON_FORMAT = "component_json_v1";

    private TranslationCacheKeys() {
    }

    public static String key(String surface, String source) {
        return CacheKey.create(surface, source, "", "");
    }

    public static String key(String surface, String source, String context, String slotSignature,
                             String styleSignature) {
        return CacheKey.create(surface, source, context, slotSignature);
    }

    public static String componentJsonKey(String surface, String sourceJson) {
        return CacheKey.create(surface, sourceJson, "", "", COMPONENT_JSON_FORMAT);
    }

    public static String componentJsonKey(String surface, String sourceJson, String context) {
        return CacheKey.create(surface, sourceJson, context, "", COMPONENT_JSON_FORMAT);
    }

    public static String legacyComponentJsonKey(String surface, String sourceJson) {
        return CacheKey.createLegacy("json." + Surface.normalize(surface), sourceJson, "", "");
    }

    public static boolean isComponentJsonKey(String key) {
        return key != null && key.contains(":fmt=" + COMPONENT_JSON_FORMAT + ":");
    }

    public static String debugKey(String surface, String source) {
        String normalized = normalizeSource(source);
        String preview = normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
        preview = preview.replace('\n', ' ').replace('\r', ' ').replace(':', '_');
        return key(surface, source) + ":" + preview;
    }

    public static String normalizeSource(String source) {
        if (source == null) {
            return "";
        }
        String normalized = Normalizer.normalize(source, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        normalized = normalized.replaceAll("[ \\t]+", " ");
        normalized = normalized.replaceAll(" *\\n *", "\n");
        return normalized;
    }

    public static String hashSource(String source) {
        return CacheKey.hash(normalizeSource(source));
    }

    /** Hash shared by chat/hover/item text regardless of visual wrapping. */
    public static String semanticHash(String source) {
        return CacheKey.hash(normalizeSemanticSource(source));
    }

    public static String normalizeSemanticSource(String source) {
        if (source == null) {
            return "";
        }
        return Normalizer.normalize(source, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
                .replaceAll("\\s+", " ");
    }

    public static boolean isCurrentProtocolKey(String key) {
        return key != null && key.startsWith(PROTOCOL + ":");
    }

    public static String surfaceFromKey(String key) {
        if (!isCurrentProtocolKey(key)) {
            return "legacy";
        }
        String[] parts = key.split(":", 4);
        if (parts.length < 3 || parts[1].isBlank()) {
            return "generic";
        }
        return Surface.normalize(parts[1]);
    }

    public static String laneFromSurface(String surface) {
        return Surface.classify(surface).cacheLane();
    }

    public static String laneFromKey(String key) {
        return laneFromSurface(surfaceFromKey(key));
    }

    /**
     * HTTP request-queue lane for a surface. Single source of truth shared with
     * the business pending/cooldown lanes ({@link #laneFromSurface}); request
     * lanes only refine surfaces whose scheduling differs (manual vs auto signs,
     * title vs actionbar, merged tooltip/hover concurrency).
     */
    public static String requestLaneFromSurface(String surface) {
        return Surface.classify(surface).requestLane();
    }

    public static String sourceHashFromKey(String key) {
        if (!isCurrentProtocolKey(key)) {
            return "";
        }
        String[] parts = key.split(":", 4);
        return parts.length >= 3 ? parts[2] : "";
    }

    private static String sanitizeSurface(String surface) {
        return Surface.normalize(surface);
    }
}
