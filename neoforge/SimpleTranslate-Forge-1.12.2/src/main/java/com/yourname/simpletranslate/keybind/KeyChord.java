package com.yourname.simpletranslate.keybind;

import com.yourname.simpletranslate.core.LegacyComponentFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.Locale;

/** Keyboard/mouse input plus an exact Ctrl/Alt/Shift set for LWJGL 2. */
public final class KeyChord {
    public static final int CTRL = 1;
    public static final int ALT = 2;
    public static final int SHIFT = 4;
    public static final KeyChord NONE = new KeyChord(InputType.KEYBOARD, Keyboard.KEY_NONE, 0);

    private final InputType type;
    private final int code;
    private final int modifiers;

    public KeyChord(InputType type, int code, int modifiers) {
        this.type = type == null ? InputType.KEYBOARD : type;
        this.code = code;
        this.modifiers = modifiers & (CTRL | ALT | SHIFT);
    }

    public InputType type() { return type; }
    public int code() { return code; }
    public int modifiers() { return modifiers; }
    public static KeyChord keyboard(int code) { return new KeyChord(InputType.KEYBOARD, code, 0); }
    public boolean isBound() {
        return type == InputType.MOUSE ? code >= 0 : code > Keyboard.KEY_NONE;
    }

    public boolean isDown(Minecraft minecraft) {
        return isPrimaryDown(minecraft) && modifiers == currentModifiers();
    }

    public boolean isPrimaryDown(Minecraft minecraft) {
        if (!isBound()) return false;
        return type == InputType.MOUSE ? Mouse.isButtonDown(code) : Keyboard.isKeyDown(code);
    }

    public boolean matchesKeyboardEvent(int keyCode, Minecraft minecraft) {
        return type == InputType.KEYBOARD && code == keyCode && modifiers == currentModifiers();
    }

    public String serialize() { return type.id + ":" + code + ":" + modifiers; }

    public static KeyChord parse(String raw, KeyChord fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback == null ? NONE : fallback;
        try {
            String[] parts = raw.trim().split(":", -1);
            if (parts.length == 1) return keyboard(Integer.parseInt(parts[0]));
            if (parts.length != 3) return fallback == null ? NONE : fallback;
            return new KeyChord(InputType.fromId(parts[0]),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (RuntimeException ignored) {
            return fallback == null ? NONE : fallback;
        }
    }

    public static KeyChord fromInputName(String inputName) {
        if (inputName == null) return NONE;
        String value = inputName.trim();
        try {
            if (value.startsWith("mouse:")) {
                return new KeyChord(InputType.MOUSE, Integer.parseInt(value.substring(6)), 0);
            }
            return keyboard(Integer.parseInt(value));
        } catch (RuntimeException ignored) {
            return NONE;
        }
    }

    public ITextComponent displayName() {
        if (!isBound()) return colored(
                LegacyComponentFactory.translatable("screen.simple_translate.shortcuts.none"), TextFormatting.GRAY);
        ITextComponent result = LegacyComponentFactory.empty();
        if ((modifiers & CTRL) != 0) result.appendSibling(LegacyComponentFactory.translatable(
                "screen.simple_translate.shortcuts.modifier.ctrl")).appendText("+");
        if ((modifiers & ALT) != 0) result.appendSibling(LegacyComponentFactory.translatable(
                "screen.simple_translate.shortcuts.modifier.alt")).appendText("+");
        if ((modifiers & SHIFT) != 0) result.appendSibling(LegacyComponentFactory.translatable(
                "screen.simple_translate.shortcuts.modifier.shift")).appendText("+");
        String name = type == InputType.MOUSE ? "MOUSE " + code : Keyboard.getKeyName(code);
        if (name == null || name.isEmpty()) name = type.id.toUpperCase(Locale.ROOT) + " " + code;
        result.appendSibling(LegacyComponentFactory.literal(name));
        return colored(result, TextFormatting.WHITE);
    }

    private static ITextComponent colored(ITextComponent value, TextFormatting color) {
        value.setStyle(new Style().setColor(color));
        return value;
    }

    public static int currentModifiers() {
        int mask = 0;
        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) mask |= CTRL;
        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)) mask |= ALT;
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) mask |= SHIFT;
        return mask;
    }

    public static int currentModifiers(Minecraft ignored) { return currentModifiers(); }

    public static boolean isModifierKey(int keyCode) {
        return keyCode == Keyboard.KEY_LCONTROL || keyCode == Keyboard.KEY_RCONTROL
                || keyCode == Keyboard.KEY_LMENU || keyCode == Keyboard.KEY_RMENU
                || keyCode == Keyboard.KEY_LSHIFT || keyCode == Keyboard.KEY_RSHIFT;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof KeyChord)) return false;
        KeyChord value = (KeyChord) other;
        return type == value.type && code == value.code && modifiers == value.modifiers;
    }

    @Override public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + code;
        return 31 * result + modifiers;
    }

    public enum InputType {
        KEYBOARD("keyboard"), MOUSE("mouse");
        private final String id;
        InputType(String id) { this.id = id; }
        static InputType fromId(String id) { return "mouse".equalsIgnoreCase(id) ? MOUSE : KEYBOARD; }
    }
}
