package com.yourname.simpletranslate.feature.hud;
import com.yourname.simpletranslate.core.ComponentTranslationResult;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.ComponentJsonCompat;
import com.yourname.simpletranslate.transport.TranslationLanes;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Scoreboard text uses one ordered Component-array job per visible frame. Each
 * translated array entry maps to one captured row, while visual/data-only atoms
 * remain isolated local children inside that row.
 */
public final class ScoreboardTranslationHelper {
    /**
     * One cache entry represents the complete visible semantic sidebar, not an
     * individual physical row.  The version is intentionally separate from the
     * old row-array cache: those entries were translated without enough context
     * and must never be reused by this projection.
     */
    private static final String SCOREBOARD_COMPONENT_SURFACE = "scoreboard.component.semantic_frame.v5";
    private static final String SCOREBOARD_COMPONENT_ROLE = "scoreboard-semantic-frame";
    private static final String SCOREBOARD_LIST_SURFACE = "scoreboard.component.list.v1";
    private static final String SCOREBOARD_LIST_ROLE = "scoreboard-list";
    private static final String SCOREBOARD_BELOW_NAME_SURFACE = "scoreboard.component.below_name.v1";
    private static final String SCOREBOARD_BELOW_NAME_ROLE = "scoreboard-below-name";
    private static final String SCOREBOARD_STRING_SURFACE = "scoreboard.string.direct";
    private static final String SCOREBOARD_STRING_ROLE = "scoreboard-string";
    private static final Pattern PLAYERLIKE_TOKEN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern PURE_SCORE_OR_SYMBOL = Pattern.compile("[\\s\\d+\\-.,:：/|\\\\*#()\\[\\]{}<>]+");
    private static final Pattern SERVER_ADDRESS = Pattern.compile(
            "(?i)^(?:play\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?:[:/]\\d+)?/?$");
    private static final Pattern COORDINATE_FRAGMENT = Pattern.compile(
            "^[\\s+\\-\\d.,:()\\[\\]{}<>]+$");
    private static final Pattern LEGACY_FORMAT = Pattern.compile("§[0-9A-FK-ORa-fk-or]");
    private static final ThreadLocal<FrameCapture> ACTIVE_FRAME = new ThreadLocal<>();
    private static volatile FrameSnapshot lastFrame;

    private ScoreboardTranslationHelper() {
    }

    /** Starts one ordered sidebar capture on the render thread. */
    public static void beginFrame() {
        ACTIVE_FRAME.set(new FrameCapture(lastFrame));
    }

    /**
     * Collects a rendered row and returns the matching row from the last
     * successfully translated snapshot. The current frame remains original
     * while its asynchronous block request is pending.
     */
    public static Component translateFrameComponent(Component component) {
        FrameCapture frame = ACTIVE_FRAME.get();
        if (frame == null) {
            return translateComponent(component);
        }
        return frame.collect(component);
    }

    /**
     * Returns the cached translation for layout measurement without mutating
     * the current frame capture. Sidebar background width, title centering and
     * right-aligned scores must measure the same Component that is rendered.
     */
    public static Component translateKnownComponent(Component component) {
        if (component == null || !ModConfig.GLOBAL_ENABLED.get()
                || !ModConfig.HUD_SCOREBOARD_ENABLED.get()) {
            return component;
        }
        FrameSnapshot snapshot = lastFrame;
        if (snapshot == null) {
            return component;
        }
        Map<String, Component> known = knownTranslationsByKey(snapshot);
        Component translated = known.get(componentKey(component));
        return translated == null ? component : translated;
    }

    private static FrameSnapshot knownMapSnapshot;
    private static Map<String, Component> knownMap;

    private static Map<String, Component> knownTranslationsByKey(FrameSnapshot snapshot) {
        if (snapshot != knownMapSnapshot) {
            Map<String, Component> map = new HashMap<>();
            for (int index = 0; index < snapshot.sourceKeys.size(); index++) {
                Component translated = snapshot.translated.get(index);
                if (translated != null) {
                    map.put(snapshot.sourceKeys.get(index), translated);
                }
            }
            knownMapSnapshot = snapshot;
            knownMap = map;
        }
        return knownMap;
    }

    /** Finishes the frame and submits all visible rows as one ordered request. */
    public static void endFrame() {
        FrameCapture frame = ACTIVE_FRAME.get();
        ACTIVE_FRAME.remove();
        if (frame == null || frame.sources.isEmpty()
                || !ModConfig.GLOBAL_ENABLED.get() || !ModConfig.HUD_SCOREBOARD_ENABLED.get()) {
            return;
        }
        List<Component> sources = List.copyOf(frame.sources);
        if (frame.matchesPrevious()) {
            return;
        }
        FrameProjection projection = FrameProjection.project(sources);
        if (projection == null) {
            lastFrame = null;
            return;
        }
        ComponentListTranslationResult result = DirectSurfaceTranslator.translateComponents(
                projection.requests(), SCOREBOARD_COMPONENT_SURFACE,
                SCOREBOARD_COMPONENT_ROLE, true, projection.context());
        if (result != null && result.translated && result.components != null
                && result.components.size() == projection.requests().size()) {
            List<Component> mapped = projection.bind(result.components);
            if (mapped != null && mapped.size() == sources.size()) {
                lastFrame = FrameSnapshot.of(frame.sourceKeys, mapped);
            }
        }
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

            return translateDirect(component, SCOREBOARD_COMPONENT_SURFACE,
                    SCOREBOARD_COMPONENT_ROLE);
        }, component, "scoreboard.translateComponent");
    }

    /** Translates textual custom score formats shown in the Tab player list. */
    private static Component lastListSource;
    private static Component lastListResult;
    private static long nextListRetryAtNanos;

    public static Component translateListComponent(Component component) {
        if (component == null || !ModConfig.GLOBAL_ENABLED.get()
                || !ModConfig.HUD_SCOREBOARD_ENABLED.get()) {
            return component;
        }
        if (component == lastListSource && System.nanoTime() < nextListRetryAtNanos) {
            return lastListResult;
        }
        Component result = SafeTranslate.guard(() -> translateDirect(component,
                        SCOREBOARD_LIST_SURFACE, SCOREBOARD_LIST_ROLE),
                component, "scoreboard.translateListComponent");
        lastListSource = component;
        lastListResult = result;
        nextListRetryAtNanos = result != component
                ? Long.MAX_VALUE : System.nanoTime() + 1_000_000_000L;
        return result;
    }

    /** Translates the objective label in the score line rendered below an entity name. */
    public static Component translateBelowNameComponent(Component component) {
        return SafeTranslate.guard(() -> translateDirect(component,
                        SCOREBOARD_BELOW_NAME_SURFACE, SCOREBOARD_BELOW_NAME_ROLE),
                component, "scoreboard.translateBelowNameComponent");
    }

    private static Component translateDirect(Component component, String surface, String role) {
        if (component == null || !ModConfig.GLOBAL_ENABLED.get()
                || !ModConfig.HUD_SCOREBOARD_ENABLED.get()
                || !shouldTranslateScoreboardText(component.getString())) {
            return component;
        }
        ComponentTranslationResult direct =
                DirectSurfaceTranslator.translateComponent(component, surface, role);
        if (!direct.handled || !direct.translated || direct.component == null) {
            return component;
        }
        return direct.component;
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

            Component request = Component.literal(text);
            var cached = DirectSurfaceTranslator.getCachedComponents(
                    List.of(request), SCOREBOARD_STRING_SURFACE, SCOREBOARD_STRING_ROLE, false, "");
            if (cached != null && cached.translated && cached.components != null && cached.components.size() == 1) {
                return cached.components.get(0).getString();
            }
            DirectSurfaceTranslator.translateComponentsAsync(
                    List.of(request), SCOREBOARD_STRING_SURFACE, SCOREBOARD_STRING_ROLE, false, "");
            return text;
        }, text, "scoreboard.translateString");
    }

    public static void clearLocalCache() {
        TranslationLanes.forSurface(SCOREBOARD_COMPONENT_SURFACE).clear();
        TranslationLanes.forSurface(SCOREBOARD_LIST_SURFACE).clear();
        TranslationLanes.forSurface(SCOREBOARD_BELOW_NAME_SURFACE).clear();
        TranslationLanes.forSurface(SCOREBOARD_STRING_SURFACE).clear();
        ACTIVE_FRAME.remove();
        lastFrame = null;
        knownMapSnapshot = null;
        knownMap = null;
        lastListSource = null;
        lastListResult = null;
        nextListRetryAtNanos = 0L;
        synchronized (COMPONENT_KEY_MEMO) {
            COMPONENT_KEY_MEMO.clear();
            COMPONENT_KEY_BY_TEXT.clear();
        }
    }

    private static final Map<Component, String> COMPONENT_KEY_MEMO = new IdentityHashMap<>();

    private static String componentKey(Component component) {
        if (component == null) {
            return "";
        }
        synchronized (COMPONENT_KEY_MEMO) {
            String cached = COMPONENT_KEY_MEMO.get(component);
            if (cached != null) {
                return cached;
            }
            String textKey = COMPONENT_KEY_BY_TEXT.get(component.getString());
            if (textKey != null) {
                COMPONENT_KEY_MEMO.put(component, textKey);
                return textKey;
            }
        }
        String key;
        try {
            key = ComponentJsonCompat.toJson(component);
        } catch (RuntimeException ignored) {
            key = component.getString();
        }
        synchronized (COMPONENT_KEY_MEMO) {
            if (COMPONENT_KEY_MEMO.size() >= 64) {
                COMPONENT_KEY_MEMO.clear();
                COMPONENT_KEY_BY_TEXT.clear();
            }
            COMPONENT_KEY_MEMO.put(component, key);
            COMPONENT_KEY_BY_TEXT.put(component.getString(), key);
        }
        return key;
    }

    private static final Map<String, String> COMPONENT_KEY_BY_TEXT =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 64;
                }
            };

    static boolean sameOrderedFrameKeys(List<String> current, List<String> previous) {
        return current != null && current.equals(previous);
    }

    private static final class FrameCapture {
        private final FrameSnapshot previous;
        private final List<Component> sources = new ArrayList<>();
        private final List<String> sourceKeys = new ArrayList<>();

        private FrameCapture(FrameSnapshot previous) {
            this.previous = previous;
        }

        private Component collect(Component component) {
            Component source = component == null ? Component.empty() : component;
            String key = componentKey(source);
            int index = sources.size();
            sources.add(source);
            sourceKeys.add(key);
            if (previous == null || index >= previous.sourceKeys.size()
                    || index >= previous.translated.size()
                    || !Objects.equals(key, previous.sourceKeys.get(index))) {
                return source;
            }
            Component translated = previous.translated.get(index);
            return translated == null ? source : translated;
        }

        private boolean matchesPrevious() {
            return previous != null && sameOrderedFrameKeys(sourceKeys, previous.sourceKeys);
        }
    }

    private record FrameSnapshot(List<String> sourceKeys, List<Component> translated) {
        private static FrameSnapshot of(List<String> sourceKeys, List<Component> translated) {
            return new FrameSnapshot(List.copyOf(sourceKeys), List.copyOf(translated));
        }
    }

    /**
     * Projects the complete ordered sidebar into one top-level Component array.
     *
     * <p>Every translatable physical row is one array entry. The model therefore
     * receives the complete ordered frame in one request and can use neighbouring
     * rows as context, while the response has an exact ordinal mapping back to the
     * captured render rows. No newline document is created and no translated prose
     * is split or width-guessed after the response.</p>
     */
    private static final class FrameProjection {
        private final List<Component> sources;
        private final List<Integer> rowIndexes;
        private final List<Component> requests;
        private final String context;

        private FrameProjection(List<Component> sources, List<Integer> rowIndexes,
                                List<Component> requests, String context) {
            this.sources = sources;
            this.rowIndexes = rowIndexes;
            this.requests = requests;
            this.context = context;
        }

        private static FrameProjection project(List<Component> sources) {
            if (sources == null || sources.isEmpty()) {
                return null;
            }
            List<Integer> indexes = new ArrayList<>();
            List<Component> requests = new ArrayList<>();
            for (int index = 0; index < sources.size(); index++) {
                Component component = sources.get(index);
                String visible = cleanVisibleRow(component == null ? "" : component.getString());
                if (visible.isBlank() || isLocalOnlyRow(visible)
                        || !shouldTranslateScoreboardText(visible)) {
                    continue;
                }
                indexes.add(index);
                // Keep the exact source Component tree. The shared visual
                // projection strips only model-invisible atoms and rebinds
                // translated prose into this tree, preserving colors, bold,
                // fonts, events and sibling boundaries without reconstruction.
                requests.add(component);
            }
            if (indexes.isEmpty()) {
                return null;
            }
            StringBuilder context = new StringBuilder("Visible scoreboard rows in order:\n");
            for (Component source : sources) {
                String row = cleanVisibleRow(source == null ? "" : source.getString());
                if (!row.isBlank()) {
                    context.append(row).append('\n');
                }
            }
            return new FrameProjection(List.copyOf(sources), List.copyOf(indexes),
                    List.copyOf(requests), context.toString().stripTrailing());
        }

        private List<Component> requests() {
            return requests;
        }

        private String context() {
            return context;
        }

        private List<Component> bind(List<Component> translatedRows) {
            if (translatedRows == null || translatedRows.size() != rowIndexes.size()) {
                return null;
            }
            List<Component> mapped = new ArrayList<>(sources);
            for (int ordinal = 0; ordinal < rowIndexes.size(); ordinal++) {
                int sourceIndex = rowIndexes.get(ordinal);
                Component translated = translatedRows.get(ordinal);
                if (translated == null) {
                    return null;
                }
                mapped.set(sourceIndex, translated);
            }
            return List.copyOf(mapped);
        }
    }

    private static String cleanVisibleRow(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return LEGACY_FORMAT.matcher(text).replaceAll("").strip();
    }

    private static boolean isLocalOnlyRow(String visible) {
        if (visible == null || visible.isBlank()) {
            return true;
        }
        String trimmed = visible.trim();
        if (SERVER_ADDRESS.matcher(trimmed).matches()
                || COORDINATE_FRAGMENT.matcher(trimmed).matches()) {
            return true;
        }
        return trimmed.codePoints().noneMatch(codePoint ->
                (codePoint >= 'A' && codePoint <= 'Z')
                        || (codePoint >= 'a' && codePoint <= 'z'));
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
