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
        return create(surface, source, context, layoutSignature, format,
                TranslationTextDetector.languagePairKey());
    }

    public static String create(String surface, String source, String context, String layoutSignature,
                                String format, String languagePair) {
        return PROTOCOL + ":" + Surface.normalize(surface) + ":" + hash(normalize(source))
                + ":ctx=" + hash(normalize(context))
                + ":layout=" + hash(normalize(layoutSignature))
                + ":fmt=" + normalizeFormat(format)
                + ":lang=" + hash(languagePair == null || languagePair.isBlank()
                ? TranslationTextDetector.languagePairKey()
                : languagePair);
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

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    });

    public static String hash(String value) {
        String normalized = normalize(value);
        MessageDigest digest = SHA256.get();
        if (digest == null) {
            return Integer.toHexString(normalized.hashCode());
        }
        return java.util.HexFormat.of().formatHex(
                digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
    }
}
