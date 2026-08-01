package com.yourname.simpletranslate.core;

import java.util.Locale;

/** Surface classification used by component JSON cache and request lanes. */
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
    ENTITY("entity.", "entity", "entity_text_display", true),
    TEXT_DISPLAY("text_display.", "text_display", "entity_text_display", true),
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
        String value = normalize(raw);
        for (Surface surface : values()) {
            if (surface != GENERIC && value.startsWith(surface.prefix)) {
                return surface;
            }
        }
        return GENERIC;
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "generic";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    public String cacheLane() {
        return cacheLane;
    }

    public String requestLane() {
        return requestLane;
    }

    public static boolean directBatchCandidate(String rawSurface) {
        String value = normalize(rawSurface);
        return classify(value).directBatchCandidate || value.startsWith("chat.system");
    }
}
