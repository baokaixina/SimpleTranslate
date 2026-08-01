package com.yourname.simpletranslate.keybind;

import com.yourname.simpletranslate.config.ModConfig;
import org.lwjgl.input.Keyboard;

public enum ShortcutAction {
    TOGGLE_GLOBAL_TRANSLATION("screen.simple_translate.shortcuts.action.toggle_global_translation", KeyChord.NONE),
    TOGGLE_CHAT_MODE("screen.simple_translate.shortcuts.action.toggle_chat_mode", KeyChord.NONE),
    TRANSLATE_GUI("screen.simple_translate.shortcuts.action.translate_gui", KeyChord.keyboard(Keyboard.KEY_K)),
    TRANSLATE_TOOLTIP("screen.simple_translate.shortcuts.action.translate_tooltip", KeyChord.keyboard(Keyboard.KEY_V)),
    SIGN_SELECT("screen.simple_translate.shortcuts.action.sign_select", KeyChord.keyboard(Keyboard.KEY_G)),
    SIGN_SUBMIT("screen.simple_translate.shortcuts.action.sign_submit", KeyChord.keyboard(Keyboard.KEY_H));

    private final String translationKey;
    private final KeyChord defaultChord;
    ShortcutAction(String translationKey, KeyChord defaultChord) {
        this.translationKey = translationKey;
        this.defaultChord = defaultChord;
    }
    public String translationKey() { return translationKey; }
    public KeyChord defaultChord() { return defaultChord; }
    public KeyChord chord() { return KeyChord.parse(ModConfig.getShortcutChord(this).get(), defaultChord); }
}
