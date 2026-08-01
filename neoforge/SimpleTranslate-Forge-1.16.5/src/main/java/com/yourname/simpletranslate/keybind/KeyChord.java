package com.yourname.simpletranslate.keybind;

import net.minecraft.client.util.InputMappings;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.IFormattableTextComponent;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** A primary keyboard/mouse input plus an exact Ctrl/Alt/Shift modifier set. */
public record KeyChord(InputType type, int code, int modifiers) {
    public static final int CTRL = 1;
    public static final int ALT = 2;
    public static final int SHIFT = 4;
    // InputMappings.UNKNOWN is KEYSYM -1 on 1.21.11. Keep this sentinel
    // literal so headless ITextComponent-JSON fixtures do not initialize GLFW just
    // by loading the configuration/key-chord classes.
    public static final KeyChord NONE = new KeyChord(InputType.KEYBOARD, -1, 0);

    public KeyChord {
        type = type == null ? InputType.KEYBOARD : type;
        modifiers &= CTRL | ALT | SHIFT;
    }

    public static KeyChord keyboard(int code) {
        return new KeyChord(InputType.KEYBOARD, code, 0);
    }

    public boolean isBound() {
        // NONE is deliberately the literal -1 above. Avoid touching
        // InputMappings here: its static initializer loads GLFW natives even
        // for headless config/fixture checks that only inspect a binding.
        return code >= 0;
    }

    public boolean isDown(Minecraft minecraft) {
        if (!isBound() || minecraft == null || minecraft.getWindow() == null) {
            return false;
        }
        return isPrimaryDown(minecraft) && modifiers == currentModifiers(minecraft);
    }

    public boolean isPrimaryDown(Minecraft minecraft) {
        if (!isBound() || minecraft == null || minecraft.getWindow() == null) return false;
        return type == InputType.MOUSE
                ? GLFW.glfwGetMouseButton(minecraft.getWindow().getWindow(), code) == GLFW.GLFW_PRESS
                : InputMappings.isKeyDown(minecraft.getWindow().getWindow(), code);
    }

    public boolean matchesKeyboardEvent(int keyCode, Minecraft minecraft) {
        return type == InputType.KEYBOARD && code == keyCode && modifiers == currentModifiers(minecraft);
    }

    public String serialize() {
        return type.id + ":" + code + ":" + modifiers;
    }

    public static KeyChord parse(String raw, KeyChord fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback == null ? NONE : fallback;
        }
        try {
            String[] parts = raw.trim().split(":", -1);
            if (parts.length == 1) {
                return keyboard(Integer.parseInt(parts[0]));
            }
            if (parts.length != 3) {
                return fallback == null ? NONE : fallback;
            }
            return new KeyChord(InputType.fromId(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (RuntimeException ignored) {
            return fallback == null ? NONE : fallback;
        }
    }

    public static KeyChord fromInputName(String inputName) {
        try {
            InputMappings.Input key = InputMappings.getKey(inputName);
            InputType type = key.getType() == InputMappings.Type.MOUSE ? InputType.MOUSE : InputType.KEYBOARD;
            return new KeyChord(type, key.getValue(), 0);
        } catch (RuntimeException ignored) {
            return NONE;
        }
    }

    public ITextComponent displayName() {
        if (!isBound()) {
            return com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.shortcuts.none").withStyle(TextFormatting.GRAY);
        }
        IFormattableTextComponent result = com.yourname.simpletranslate.core.LegacyComponentFactory.empty();
        if ((modifiers & CTRL) != 0) result.append(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.shortcuts.modifier.ctrl")).append("+");
        if ((modifiers & ALT) != 0) result.append(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.shortcuts.modifier.alt")).append("+");
        if ((modifiers & SHIFT) != 0) result.append(com.yourname.simpletranslate.core.LegacyComponentFactory.translatable("screen.simple_translate.shortcuts.modifier.shift")).append("+");
        try {
            result.append((type == InputType.MOUSE ? InputMappings.Type.MOUSE : InputMappings.Type.KEYSYM)
                    .getOrCreate(code).getDisplayName());
        } catch (Throwable ignored) {
            result.append(com.yourname.simpletranslate.core.LegacyComponentFactory.literal(type.id.toUpperCase(Locale.ROOT) + " " + code));
        }
        return result.withStyle(TextFormatting.WHITE);
    }

    public static int currentModifiers(Minecraft minecraft) {
        if (minecraft == null || minecraft.getWindow() == null) return 0;
        int mask = 0;
        if (down(minecraft, GLFW.GLFW_KEY_LEFT_CONTROL) || down(minecraft, GLFW.GLFW_KEY_RIGHT_CONTROL)) mask |= CTRL;
        if (down(minecraft, GLFW.GLFW_KEY_LEFT_ALT) || down(minecraft, GLFW.GLFW_KEY_RIGHT_ALT)) mask |= ALT;
        if (down(minecraft, GLFW.GLFW_KEY_LEFT_SHIFT) || down(minecraft, GLFW.GLFW_KEY_RIGHT_SHIFT)) mask |= SHIFT;
        return mask;
    }

    public static boolean isModifierKey(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_LEFT_CONTROL || keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL
                || keyCode == GLFW.GLFW_KEY_LEFT_ALT || keyCode == GLFW.GLFW_KEY_RIGHT_ALT
                || keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT;
    }

    private static boolean down(Minecraft minecraft, int keyCode) {
        return InputMappings.isKeyDown(minecraft.getWindow().getWindow(), keyCode);
    }

    public enum InputType {
        KEYBOARD("keyboard"), MOUSE("mouse");
        private final String id;
        InputType(String id) { this.id = id; }
        static InputType fromId(String id) {
            return "mouse".equalsIgnoreCase(id) ? MOUSE : KEYBOARD;
        }
    }
}
