package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Scoreboard text uses the same block-job pipeline as tooltips. It never renders
 * raw marker results directly, so scoreboard rows cannot show half-translated
 * or cache-poisoned text.
 */
public final class ScoreboardTranslationHelper {
    private static final String SCOREBOARD_COMPONENT_SURFACE = "scoreboard.component.direct";
    private static final String SCOREBOARD_COMPONENT_ROLE = "scoreboard-line";
    private static final String SCOREBOARD_STRING_SURFACE = "scoreboard.string.direct";
    private static final String SCOREBOARD_STRING_ROLE = "scoreboard-string";
    private static final Pattern PLAYERLIKE_TOKEN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern PURE_SCORE_OR_SYMBOL = Pattern.compile("[\\s\\d+\\-.,:：/|\\\\*#()\\[\\]{}<>]+");

    private ScoreboardTranslationHelper() {
    }

    public static Component translateComponent(Component component) {
        if (component == null) {
            return null;
        }
        if (!ModConfig.GLOBAL_ENABLED.get() || !ModConfig.HUD_SCOREBOARD_ENABLED.get()) {
            return component;
        }

        String original = component.getString();
        if (!shouldTranslateScoreboardText(original)) {
            return component;
        }

        DirectFormattedTranslationPipeline.ComponentResult direct =
                DirectSurfaceTranslator.translateComponent(component,
                        SCOREBOARD_COMPONENT_SURFACE, SCOREBOARD_COMPONENT_ROLE);
        return direct.handled ? direct.component : component;
    }

    public static String translateString(String text) {
        if (text == null) {
            return null;
        }
        if (!ModConfig.GLOBAL_ENABLED.get() || !ModConfig.HUD_SCOREBOARD_ENABLED.get()) {
            return text;
        }
        if (!shouldTranslateScoreboardText(text)) {
            return text;
        }

        String cached = DirectSurfaceTranslator.getCachedText(text,
                SCOREBOARD_STRING_SURFACE, SCOREBOARD_STRING_ROLE, "", "", "");
        if (cached != null) {
            return cached;
        }
        DirectSurfaceTranslator.translateTextAsync(text,
                SCOREBOARD_STRING_SURFACE, SCOREBOARD_STRING_ROLE, "", "", "");
        return text;
    }

    public static void clearLocalCache() {
        TranslationLanes.forSurface(SCOREBOARD_COMPONENT_SURFACE).clear();
        TranslationLanes.forSurface(SCOREBOARD_STRING_SURFACE).clear();
    }

    private static boolean shouldTranslateScoreboardText(String text) {
        if (text == null || text.isBlank() || !TooltipTranslationHelper.containsEnglish(text)) {
            return false;
        }
        String trimmed = text.trim();
        if (PURE_SCORE_OR_SYMBOL.matcher(trimmed).matches()) {
            return false;
        }
        if (PLAYERLIKE_TOKEN.matcher(trimmed).matches() && looksLikePlayerName(trimmed)) {
            return false;
        }
        return true;
    }

    private static boolean looksLikePlayerName(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.equals("score") || lower.equals("leaderboard") || lower.equals("enemies")
                || lower.equals("siege") || lower.equals("loaded")) {
            return false;
        }
        int upperAfterFirst = 0;
        boolean hasDigitOrUnderscore = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (i > 0 && c >= 'A' && c <= 'Z') {
                upperAfterFirst++;
            }
            if ((c >= '0' && c <= '9') || c == '_') {
                hasDigitOrUnderscore = true;
            }
        }
        return hasDigitOrUnderscore || upperAfterFirst > 0;
    }
}
