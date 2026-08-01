package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.cache.CacheKey;
import com.yourname.simpletranslate.core.Surface;

import java.text.Normalizer;

public final class TranslationCacheKeys {
    public static final String PROTOCOL = CacheKey.PROTOCOL;
    /** Current marker-free Component projection cache format. */
    public static final String COMPONENT_JSON_FORMAT = "component_json_v1";
    public static final String COMPONENT_JSON_FORMAT_V5 = "component_json_v5";
    public static final String COMPONENT_JSON_FORMAT_V4 = "component_json_v4";
    public static final String COMPONENT_JSON_FORMAT_V3 = "component_json_v3";
    public static final String COMPONENT_JSON_FORMAT_V2 = "component_json_v2";
    public static final String COMPONENT_JSON_FORMAT_V1 = "component_json_v1";

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

    public static String componentJsonKey(String surface, String sourceJson, String context,
                                          String sourceLanguage, String targetLanguage) {
        return CacheKey.create(surface, sourceJson, context, "", COMPONENT_JSON_FORMAT,
                TranslationTextDetector.languagePairKey(sourceLanguage, targetLanguage));
    }

    public static String legacyComponentJsonKey(String surface, String sourceJson) {
        return CacheKey.createLegacy("json." + Surface.normalize(surface), sourceJson, "", "");
    }

    public static String legacyComponentJsonKey(String surface, String sourceJson,
                                                String sourceLanguage, String targetLanguage) {
        return CacheKey.createLegacy("json." + Surface.normalize(surface), sourceJson, "", "",
                TranslationTextDetector.languagePairKey(sourceLanguage, targetLanguage));
    }

    /** Key an entry would have used under the v4 cache format (for lazy migration). */
    public static String componentJsonV4Key(String surface, String sourceJson, String context,
                                            String sourceLanguage, String targetLanguage) {
        return CacheKey.create(surface, sourceJson, context, "", COMPONENT_JSON_FORMAT_V4,
                TranslationTextDetector.languagePairKey(sourceLanguage, targetLanguage));
    }

    /** Key an entry would have used under the v3 cache format (for lazy migration). */
    public static String componentJsonV3Key(String surface, String sourceJson, String context,
                                            String sourceLanguage, String targetLanguage) {
        return CacheKey.create(surface, sourceJson, context, "", COMPONENT_JSON_FORMAT_V3,
                TranslationTextDetector.languagePairKey(sourceLanguage, targetLanguage));
    }

    /** Key an entry would have used under the v2 cache format (for lazy migration). */
    public static String componentJsonV2Key(String surface, String sourceJson, String context,
                                            String sourceLanguage, String targetLanguage) {
        return CacheKey.create(surface, sourceJson, context, "", COMPONENT_JSON_FORMAT_V2,
                TranslationTextDetector.languagePairKey(sourceLanguage, targetLanguage));
    }

    /** Key an entry would have used under the v1 cache format (for lazy migration). */
    public static String componentJsonV1Key(String surface, String sourceJson, String context,
                                            String sourceLanguage, String targetLanguage) {
        return CacheKey.create(surface, sourceJson, context, "", COMPONENT_JSON_FORMAT_V1,
                TranslationTextDetector.languagePairKey(sourceLanguage, targetLanguage));
    }

    public static boolean isComponentJsonKey(String key) {
        return key != null
                && (key.contains(":fmt=" + COMPONENT_JSON_FORMAT + ":")
                || key.contains(":fmt=" + COMPONENT_JSON_FORMAT_V5 + ":")
                || key.contains(":fmt=" + COMPONENT_JSON_FORMAT_V4 + ":")
                || key.contains(":fmt=" + COMPONENT_JSON_FORMAT_V3 + ":")
                || key.contains(":fmt=" + COMPONENT_JSON_FORMAT_V2 + ":")
                || key.contains(":fmt=" + COMPONENT_JSON_FORMAT_V1 + ":"));
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
        if (parts.length < 3 || parts[1].trim().isEmpty()) {
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
