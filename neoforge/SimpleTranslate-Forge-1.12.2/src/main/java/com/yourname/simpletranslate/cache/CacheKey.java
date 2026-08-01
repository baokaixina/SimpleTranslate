package com.yourname.simpletranslate.cache;

import com.yourname.simpletranslate.core.Surface;
import com.yourname.simpletranslate.core.TranslationTextDetector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;

/**
 * Version-neutral {@code stx2} cache-key format.
 *
 * <p>The namespace identifies persistence only; it is deliberately separate
 * from the Component-JSON request protocol.</p>
 */
public final class CacheKey {
    public static final String PROTOCOL = "stx2";

    private CacheKey() {
    }

    public static String create(String surface, String source, String context, String layoutSignature) {
        return create(surface, source, context, layoutSignature, "");
    }

    public static String create(String surface, String source, String context,
                                String layoutSignature, String format) {
        return create(surface, source, context, layoutSignature, format,
                TranslationTextDetector.languagePairKey());
    }

    public static String create(String surface, String source, String context, String layoutSignature,
                                String format, String languagePair) {
        return PROTOCOL + ":" + Surface.normalize(surface) + ":" + hash(normalize(source))
                + ":ctx=" + hash(normalize(context))
                + ":layout=" + hash(normalize(layoutSignature))
                + ":fmt=" + normalizeFormat(format)
                + ":lang=" + hash(languagePair == null || languagePair.trim().isEmpty()
                ? "auto->zh_cn" : languagePair);
    }

    public static String createLegacy(String surface, String source, String context, String layoutSignature,
                                      String languagePair) {
        return PROTOCOL + ":" + Surface.normalize(surface) + ":" + hash(normalize(source))
                + ":ctx=" + hash(normalize(context))
                + ":layout=" + hash(normalize(layoutSignature))
                + ":lang=" + hash(languagePair == null || languagePair.trim().isEmpty()
                ? "auto->zh_cn" : languagePair);
    }

    public static String createLegacy(String surface, String source, String context,
                                      String layoutSignature) {
        return createLegacy(surface, source, context, layoutSignature,
                TranslationTextDetector.languagePairKey());
    }

    public static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n");
    }

    public static String hash(String value) {
        String normalized = normalize(value);
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte valueByte : bytes) result.append(String.format("%02x", Integer.valueOf(valueByte & 0xff)));
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private static String normalizeFormat(String format) {
        if (format == null || format.trim().isEmpty()) return "default";
        return format.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }
}
