package com.yourname.simpletranslate.keybind;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.Minecraft;

import java.util.EnumMap;

/** Per-surface hold-to-view-original state for the 1.12.2 client tick. */
public final class HoldOriginalState {
    private static final EnumMap<HoldOriginalFeature, Boolean> CURRENT =
            new EnumMap<HoldOriginalFeature, Boolean>(HoldOriginalFeature.class);

    static {
        clear();
    }

    private HoldOriginalState() { }

    public static void tick(Minecraft minecraft) {
        boolean enabled = minecraft != null && ModConfig.HOLD_ORIGINAL_ENABLED.get();
        for (HoldOriginalFeature feature : HoldOriginalFeature.values()) {
            CURRENT.put(feature, Boolean.valueOf(enabled && feature.chord().isDown(minecraft)));
        }
    }

    public static boolean isHolding(HoldOriginalFeature feature) {
        if (!ModConfig.HOLD_ORIGINAL_ENABLED.get()) return false;
        Boolean value = CURRENT.get(feature);
        return value != null && value.booleanValue();
    }

    public static boolean isHoldingSurface(String surface) {
        if (surface == null || surface.isEmpty()) return false;
        String normalized = surface.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("tooltip") || normalized.contains("hover")) {
            return isHolding(normalized.contains("hover")
                    ? HoldOriginalFeature.TOOLTIP_HOVER : HoldOriginalFeature.TOOLTIP_ITEM);
        }
        if (normalized.contains("chat") || normalized.equals("received_chat") || normalized.equals("sent_chat")) {
            return isHolding(HoldOriginalFeature.CHAT);
        }
        if (normalized.contains("book")) return isHolding(HoldOriginalFeature.BOOK);
        if (normalized.contains("sign")) return isHolding(HoldOriginalFeature.SIGN);
        if (normalized.contains("advancement")) return isHolding(HoldOriginalFeature.ADVANCEMENT);
        if (normalized.contains("entity")) return isHolding(HoldOriginalFeature.ENTITY_NAME);
        if (normalized.contains("scoreboard") || normalized.contains("player_tab") || normalized.equals("score")) return isHolding(HoldOriginalFeature.SCOREBOARD);
        if (normalized.contains("boss")) return isHolding(HoldOriginalFeature.BOSSBAR);
        if (normalized.contains("actionbar") || normalized.contains("overlay")) return isHolding(HoldOriginalFeature.ACTIONBAR);
        if (normalized.contains("title") || normalized.contains("subtitle")) return isHolding(HoldOriginalFeature.TITLE);
        if (normalized.contains("gui") || normalized.contains("ftb")) return isHolding(HoldOriginalFeature.GUI);
        return false;
    }

    public static void clear() {
        for (HoldOriginalFeature feature : HoldOriginalFeature.values()) {
            CURRENT.put(feature, Boolean.FALSE);
        }
    }
}
