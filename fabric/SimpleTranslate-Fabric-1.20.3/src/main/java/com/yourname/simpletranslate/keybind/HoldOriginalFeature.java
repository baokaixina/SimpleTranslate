package com.yourname.simpletranslate.keybind;

import com.yourname.simpletranslate.config.ModConfig;

public enum HoldOriginalFeature {
    CHAT("screen.simple_translate.hold_original.feature.chat"),
    TOOLTIP_ITEM("screen.simple_translate.hold_original.feature.tooltip_item"),
    TOOLTIP_HOVER("screen.simple_translate.hold_original.feature.tooltip_hover"),
    BOOK("screen.simple_translate.hold_original.feature.book"),
    SIGN("screen.simple_translate.hold_original.feature.sign"),
    ADVANCEMENT("screen.simple_translate.hold_original.feature.advancement"),
    ENTITY_NAME("screen.simple_translate.hold_original.feature.entity_name"),
    TEXT_DISPLAY("screen.simple_translate.hold_original.feature.text_display"),
    SCOREBOARD("screen.simple_translate.hold_original.feature.scoreboard"),
    BOSSBAR("screen.simple_translate.hold_original.feature.bossbar"),
    TITLE("screen.simple_translate.hold_original.feature.title"),
    ACTIONBAR("screen.simple_translate.hold_original.feature.actionbar");

    private final String translationKey;

    HoldOriginalFeature(String translationKey) {
        this.translationKey = translationKey;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    public ModConfig.IntValue getKeyConfig() {
        return ModConfig.getHoldOriginalKey(this);
    }
}
