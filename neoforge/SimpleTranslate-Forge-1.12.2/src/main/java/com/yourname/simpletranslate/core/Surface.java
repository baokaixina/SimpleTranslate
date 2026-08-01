package com.yourname.simpletranslate.core;

import java.util.Locale;

/** Complete baseline surface classification with 1.12.2 stable-name aliases. */
public enum Surface {
    CHAT_BATCH("chat.context.batch", "chat_batch", "chat_batch", false),
    CHAT("chat.", "chat", "chat", false),
    ITEM_TOOLTIP("tooltip.item_context", "tooltip", "tooltip_hover", false),
    TOOLTIP("tooltip.", "tooltip", "tooltip_hover", false),
    HOVER("hover.", "hover", "tooltip_hover", false),
    SIGN_MANUAL("sign.manual", "sign", "sign_manual", false),
    SIGN("sign.", "sign", "sign_auto", false),
    BOOK("book", "book", "book", false),
    HUD_TITLE("hud.title", "hud", "hud_title", false),
    HUD_SUBTITLE("hud.subtitle", "hud", "hud_title", false),
    TITLE("title.", "hud", "hud_title", false),
    HUD_ACTIONBAR("hud.actionbar", "hud", "hud_actionbar", false),
    ACTIONBAR("actionbar.", "hud", "hud_actionbar", false),
    HUD("hud.", "hud", "hud", false),
    SCOREBOARD("scoreboard", "scoreboard", "scoreboard", true),
    BOSSBAR("bossbar.", "bossbar", "bossbar", true),
    ADVANCEMENT("advancement.", "advancement", "advancement", true),
    ENTITY("entity.", "entity", "entity_name", true),
    MANAGER("manager", "manager", "background", false),
    GENERIC("", "generic", "background", false);

    private final String prefix;
    private final String cacheLane;
    private final String requestLane;
    private final boolean directBatchCandidate;

    Surface(String prefix, String cacheLane, String requestLane, boolean directBatchCandidate) {
        this.prefix = prefix;
        this.cacheLane = cacheLane;
        this.requestLane = requestLane;
        this.directBatchCandidate = directBatchCandidate;
    }

    public static Surface classify(String raw) {
        String value = normalizeAlias(normalize(raw));
        for (Surface surface : values()) {
            if (surface != GENERIC && value.startsWith(surface.prefix)) return surface;
        }
        return GENERIC;
    }

    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "generic";
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    public String cacheLane() { return cacheLane; }
    public String requestLane() { return requestLane; }

    public static boolean directBatchCandidate(String rawSurface) {
        String value = normalizeAlias(normalize(rawSurface));
        return classify(value).directBatchCandidate || value.startsWith("chat.system");
    }

    /** Maps pre-baseline 1.12 hook names without changing persisted source keys. */
    private static String normalizeAlias(String value) {
        if ("chat".equals(value)) return "chat.message";
        if ("item_tooltip".equals(value)) return "tooltip.item_context";
        if ("hover_text".equals(value)) return "hover.text";
        if ("sign".equals(value)) return "sign.auto";
        if ("hud_title".equals(value)) return "hud.title";
        if ("hud_subtitle".equals(value)) return "hud.subtitle";
        if ("hud_actionbar".equals(value)) return "hud.actionbar";
        if ("hud_scoreboard".equals(value)) return "scoreboard";
        if ("hud_bossbar".equals(value)) return "bossbar.value";
        if ("advancement".equals(value)) return "advancement.entry";
        if ("entity_name".equals(value)) return "entity.name";
        if (value.startsWith("ftb")) return "gui.ftb";
        if ("hud".equals(value)) return "hud.frame";
        return value;
    }
}
