package com.yourname.simpletranslate.cache;

import com.yourname.simpletranslate.core.Surface;
import com.yourname.simpletranslate.core.TranslationTextDetector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;

public final class CacheKey {
    public static final String PROTOCOL = "stx2";

    private CacheKey() {
    }

    public static String create(String surface, String source, String context, String layoutSignature) {
        return create(surface, source, context, layoutSignature, "");
    }

    public static String create(String surface, String source, String context, String layoutSignature,
                                String format) {
        return PROTOCOL + ":" + Surface.normalize(surface) + ":" + hash(normalize(source))
                + ":ctx=" + hash(normalize(context))
                + ":layout=" + hash(normalize(layoutSignature))
                + ":fmt=" + normalizeFormat(format)
                + ":lang=" + hash(TranslationTextDetector.languagePairKey());
    }

    public static String createLegacy(String surface, String source, String context, String layoutSignature) {
        return PROTOCOL + ":" + Surface.normalize(surface) + ":" + hash(normalize(source))
                + ":ctx=" + hash(normalize(context))
                + ":layout=" + hash(normalize(layoutSignature))
                + ":lang=" + hash(TranslationTextDetector.languagePairKey());
    }

    private static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "default";
        }
        return format.toLowerCase().replaceAll("[^a-z0-9_.-]+", "_");
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(normalized.hashCode());
        }
    }
}
