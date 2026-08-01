package com.yourname.simpletranslate.feature.hud;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.TranslationTextDetector;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Ordered 1.12 sidebar row frame; measurement and drawing share one accepted snapshot. */
public final class ScoreboardTranslationController {
    private static final String SURFACE = "scoreboard.component.semantic_frame.v5";
    private static final String ROLE = "scoreboard-semantic-frame";
    private static final int MAX_ROWS = 32;
    private static final long FAILURE_RETRY_MS = 6000L;
    private static final Pattern PLAYERLIKE_TOKEN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern PURE_SCORE_OR_SYMBOL = Pattern.compile("[\\s\\d+\\-.,:：/|\\\\*#()\\[\\]{}<>]+");
    private static final Pattern SERVER_ADDRESS = Pattern.compile(
            "(?i)^(?:play\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?:[:/]\\d+)?/?$");
    private static final ThreadLocal<Frame> ACTIVE = new ThreadLocal<Frame>();
    private static volatile Map<String, String> snapshot = Collections.emptyMap();
    private static volatile String successfulSignature = "";
    private static String pendingSignature = "";
    private static String failedSignature = "";
    private static long retryAfter;
    private static long controllerRevision;

    private ScoreboardTranslationController() { }

    public static void beginFrame() {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (engine == null || !engine.isConfigured() || !engine.isSurfaceEnabled(SURFACE)
                || HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) {
            ACTIVE.remove();
            return;
        }
        ACTIVE.set(new Frame());
    }

    public static String measure(Team team, String rawName) {
        String formatted = ScorePlayerTeam.formatPlayerName(team, rawName);
        Frame frame = ACTIVE.get();
        if (frame != null && frame.rows.size() < MAX_ROWS) frame.add(rawName, formatted);
        return known(formatted);
    }

    public static String draw(Team team, String rawName) {
        return known(ScorePlayerTeam.formatPlayerName(team, rawName));
    }

    public static void endFrame() {
        final Frame frame = ACTIVE.get();
        ACTIVE.remove();
        if (frame == null || frame.rows.isEmpty()) return;
        final String signature = frame.signature();
        final List<Row> requested = frame.translatableRows();
        if (requested.isEmpty()) {
            snapshot = Collections.emptyMap();
            successfulSignature = signature;
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (ScoreboardTranslationController.class) {
            if (signature.equals(successfulSignature) || !pendingSignature.isEmpty()
                    || (signature.equals(failedSignature) && now < retryAfter)) return;
            pendingSignature = signature;
        }
        final List<ITextComponent> components = new ArrayList<ITextComponent>(requested.size());
        StringBuilder context = new StringBuilder("Visible scoreboard rows in order:");
        for (Row row : frame.rows.values()) context.append('\n').append(clean(row.formatted));
        for (Row row : requested) components.add(new TextComponentString(row.formatted));
        final long runtimeRevision = SimpleTranslateForge1122.getRuntimeRevision();
        final long requestControllerRevision;
        synchronized(ScoreboardTranslationController.class){requestControllerRevision=controllerRevision;}
        DirectSurfaceTranslator.translateComponentsAsync(components, SURFACE, ROLE, true, context.toString())
                .whenComplete(new java.util.function.BiConsumer<ComponentListTranslationResult, Throwable>() {
                    @Override public void accept(ComponentListTranslationResult result, Throwable error) {
                        synchronized (ScoreboardTranslationController.class) {
                            if (requestControllerRevision!=controllerRevision
                                    || !SimpleTranslateForge1122.isRuntimeRevisionCurrent(runtimeRevision)) return;
                            if (!signature.equals(pendingSignature)) return;
                            pendingSignature = "";
                            if (error != null || result == null || !result.translated || result.components == null
                                    || result.components.size() != requested.size()
                                    ) {
                                failedSignature = signature;
                                retryAfter = System.currentTimeMillis() + FAILURE_RETRY_MS;
                                return;
                            }
                            Map<String, String> next = new LinkedHashMap<String, String>();
                            for (int i = 0; i < requested.size(); i++) {
                                next.put(requested.get(i).formatted,
                                        result.components.get(i).getFormattedText());
                            }
                            snapshot = Collections.unmodifiableMap(next);
                            successfulSignature = signature;
                            failedSignature = "";
                            retryAfter = 0L;
                        }
                    }
                });
    }

    public static synchronized void clear() {
        controllerRevision++;
        ACTIVE.remove();
        snapshot = Collections.emptyMap();
        successfulSignature = "";
        pendingSignature = "";
        failedSignature = "";
        retryAfter = 0L;
    }

    private static String known(String source) {
        TranslationEngine engine=SimpleTranslateForge1122.getEngine();
        if (engine==null||!engine.isConfigured()||!engine.isSurfaceEnabled(SURFACE)
                ||HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) return source;
        String value = snapshot.get(source);
        return value == null ? source : value;
    }

    private static boolean shouldTranslate(Row row) {
        String visible = clean(row.formatted);
        if (visible.isEmpty() || !TranslationTextDetector.containsTranslatableText(visible, 1)
                || PURE_SCORE_OR_SYMBOL.matcher(visible).matches()
                || SERVER_ADDRESS.matcher(visible).matches()) return false;
        Minecraft minecraft = Minecraft.getMinecraft();
        TranslationEngine engine=SimpleTranslateForge1122.getEngine();
        if(engine!=null&&engine.containsBlacklistedText(visible))return false;
        if (minecraft.getConnection() != null && minecraft.getConnection().getPlayerInfo(row.rawName) != null) {
            return false;
        }
        return !(PLAYERLIKE_TOKEN.matcher(visible).matches() && looksLikePlayerName(visible));
    }

    private static String clean(String value) {
        String stripped = TextFormatting.getTextWithoutFormattingCodes(value == null ? "" : value);
        return stripped == null ? "" : stripped.trim();
    }

    private static boolean looksLikePlayerName(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.equals("score") || lower.equals("leaderboard") || lower.equals("enemies")
                || lower.equals("siege") || lower.equals("loaded")) return false;
        int upperAfterFirst = 0;
        boolean digitOrUnderscore = false;
        for (int i = 0; i < token.length(); i++) {
            char current = token.charAt(i);
            if (i > 0 && Character.isUpperCase(current)) upperAfterFirst++;
            if (Character.isDigit(current) || current == '_') digitOrUnderscore = true;
        }
        return digitOrUnderscore || upperAfterFirst > 0;
    }

    private static final class Frame {
        final LinkedHashMap<String, Row> rows = new LinkedHashMap<String, Row>();
        void add(String rawName, String formatted) {
            if (!rows.containsKey(formatted)) rows.put(formatted, new Row(rawName, formatted));
        }
        String signature() {
            StringBuilder value = new StringBuilder();
            for (Row row : rows.values()) value.append(row.rawName).append('\u001f').append(row.formatted).append('\n');
            return value.toString();
        }
        List<Row> translatableRows() {
            List<Row> values = new ArrayList<Row>();
            for (Row row : rows.values()) if (shouldTranslate(row)) values.add(row);
            return values;
        }
    }

    private static final class Row {
        final String rawName; final String formatted;
        Row(String rawName, String formatted) { this.rawName = rawName == null ? "" : rawName; this.formatted = formatted; }
    }
}
