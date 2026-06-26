package com.yourname.simpletranslate.feature.hud;
import com.yourname.simpletranslate.core.ComponentTranslationResult;
import com.yourname.simpletranslate.transport.TranslationLanes;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.DynamicTextTemplate;
import net.minecraft.network.chat.Component;

import java.util.List;
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
        return SafeTranslate.guard(() -> {
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

            DynamicTextTemplate template = DynamicTextTemplate.capture(component);
            Component request = template.hasValues() ? template.normalized() : component;
            ComponentTranslationResult direct =
                    DirectSurfaceTranslator.translateComponent(request,
                            SCOREBOARD_COMPONENT_SURFACE, SCOREBOARD_COMPONENT_ROLE);
            if (!direct.handled || !direct.translated || direct.component == null) {
                return component;
            }
            if (!template.hasValues()) {
                return direct.component;
            }
            Component restored = template.restore(direct.component);
            return restored == null ? component : restored;
        }, component, "scoreboard.translateComponent");
    }

    public static String translateString(String text) {
        return SafeTranslate.guard(() -> {
            if (text == null) {
                return null;
            }
            if (!ModConfig.GLOBAL_ENABLED.get() || !ModConfig.HUD_SCOREBOARD_ENABLED.get()) {
                return text;
            }
            if (!shouldTranslateScoreboardText(text)) {
                return text;
            }

            DynamicTextTemplate template = DynamicTextTemplate.captureText(text);
            String requestText = template.hasValues() ? template.normalizedText() : text;
            Component request = Component.literal(requestText);
            var cached = DirectSurfaceTranslator.getCachedComponents(
                    List.of(request), SCOREBOARD_STRING_SURFACE, SCOREBOARD_STRING_ROLE, false, "");
            if (cached != null && cached.translated && cached.components != null && cached.components.size() == 1) {
                String translated = cached.components.get(0).getString();
                if (!template.hasValues()) {
                    return translated;
                }
                String restored = template.restoreText(translated);
                return restored == null ? text : restored;
            }
            DirectSurfaceTranslator.translateComponentsAsync(
                    List.of(request), SCOREBOARD_STRING_SURFACE, SCOREBOARD_STRING_ROLE, false, "");
            return text;
        }, text, "scoreboard.translateString");
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
