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
    SCOREBOARD("screen.simple_translate.hold_original.feature.scoreboard"),
    BOSSBAR("screen.simple_translate.hold_original.feature.bossbar"),
    TITLE("screen.simple_translate.hold_original.feature.title"),
    ACTIONBAR("screen.simple_translate.hold_original.feature.actionbar"),
    GUI("screen.simple_translate.hold_original.feature.gui");

    private final String translationKey;
    HoldOriginalFeature(String translationKey) { this.translationKey = translationKey; }
    public String getTranslationKey() { return translationKey; }
    public ModConfig.ConfigValue<String> getChordConfig() { return ModConfig.getHoldOriginalChord(this); }
    public KeyChord chord() { return KeyChord.parse(getChordConfig().get(), KeyChord.NONE); }
}
