package com.yourname.simpletranslate.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;

public final class TranslationCacheKeys {
    public static final String PROTOCOL = "direct:v18-mintag";

    private TranslationCacheKeys() {
    }

    public static String key(String surface, String source) {
        return PROTOCOL + ":" + sanitizeSurface(surface) + ":" + hashSource(source)
                + ":lang=" + hashSource(TranslationTextDetector.languagePairKey());
    }

    public static String key(String surface, String source, String context, String slotSignature,
                             String styleSignature) {
        String sanitizedSurface = sanitizeSurface(surface);
        String sourceHash = hashSource(source);
        String contextHash = hashSource(context);
        String slotHash = hashSource(slotSignature);
        String styleHash = hashSource(styleSignature);
        String languageHash = hashSource(TranslationTextDetector.languagePairKey());
        return PROTOCOL + ":" + sanitizedSurface + ":" + sourceHash
                + ":ctx=" + contextHash
                + ":slot=" + slotHash
                + ":style=" + styleHash
                + ":lang=" + languageHash;
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
        return sha256(normalizeSource(source));
    }

    public static boolean isCurrentProtocolKey(String key) {
        return key != null && key.startsWith(PROTOCOL + ":");
    }

    public static String surfaceFromKey(String key) {
        if (!isCurrentProtocolKey(key)) {
            return "legacy";
        }
        String[] parts = key.split(":", 4);
        if (parts.length < 3 || parts[2].isBlank()) {
            return "generic";
        }
        return sanitizeSurface(parts[2]);
    }

    public static String laneFromSurface(String surface) {
        String sanitized = sanitizeSurface(surface);
        if (sanitized.startsWith("tooltip.")) {
            return "tooltip";
        }
        if (sanitized.startsWith("hover.")) {
            return "hover";
        }
        if (sanitized.startsWith("chat.")) {
            return "chat";
        }
        if (sanitized.startsWith("hud.")) {
            return "hud";
        }
        if (sanitized.startsWith("sign.")) {
            return "sign";
        }
        if (sanitized.startsWith("book")) {
            return "book";
        }
        if (sanitized.startsWith("advancement.")) {
            return "advancement";
        }
        if (sanitized.startsWith("scoreboard")) {
            return "scoreboard";
        }
        if (sanitized.startsWith("entity.")) {
            return "entity";
        }
        if (sanitized.startsWith("text_display.")) {
            return "text_display";
        }
        if (sanitized.startsWith("bossbar.")) {
            return "bossbar";
        }
        if (sanitized.equals("manager") || sanitized.startsWith("manager.")) {
            return "manager";
        }
        if (sanitized.startsWith("ocr.")) {
            return "ocr";
        }
        return sanitized.isBlank() ? "generic" : sanitized;
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
        String value = sanitizeSurface(surface);
        if (value.startsWith("sign.manual")) {
            return "sign_manual";
        }
        if (value.startsWith("sign.")) {
            return "sign_auto";
        }
        if (value.startsWith("hud.title") || value.startsWith("hud.subtitle") || value.startsWith("title.")) {
            return "hud_title";
        }
        if (value.startsWith("hud.actionbar") || value.startsWith("actionbar.")) {
            return "hud_actionbar";
        }
        if (value.startsWith("ocr.")) {
            return "ocr";
        }
        String lane = laneFromSurface(value);
        switch (lane) {
            case "tooltip":
            case "hover":
                return "tooltip_hover";
            case "entity":
            case "text_display":
                return "entity_text_display";
            case "chat":
            case "hud":
            case "scoreboard":
            case "bossbar":
            case "book":
            case "advancement":
            case "ocr":
                return lane;
            default:
                return "background";
        }
    }

    public static String sourceHashFromKey(String key) {
        if (!isCurrentProtocolKey(key)) {
            return "";
        }
        String[] parts = key.split(":", 5);
        return parts.length >= 4 ? parts[3] : "";
    }

    private static String sanitizeSurface(String surface) {
        if (surface == null || surface.isBlank()) {
            return "generic";
        }
        return surface.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
