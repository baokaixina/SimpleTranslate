package com.yourname.simpletranslate.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** A primary keyboard/mouse input plus an exact Ctrl/Alt/Shift modifier set. */
public record KeyChord(InputType type, int code, int modifiers) {
    public static final int CTRL = 1;
    public static final int ALT = 2;
    public static final int SHIFT = 4;
    // InputConstants.UNKNOWN is KEYSYM -1 on 1.21.11. Keep this sentinel
    // literal so headless Component-JSON fixtures do not initialize GLFW just
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
        // InputConstants here: its static initializer loads GLFW natives even
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
                ? GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), code) == GLFW.GLFW_PRESS
                : InputConstants.isKeyDown(minecraft.getWindow(), code);
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
            InputConstants.Key key = InputConstants.getKey(inputName);
            InputType type = key.getType() == InputConstants.Type.MOUSE ? InputType.MOUSE : InputType.KEYBOARD;
            return new KeyChord(type, key.getValue(), 0);
        } catch (RuntimeException ignored) {
            return NONE;
        }
    }

    public Component displayName() {
        if (!isBound()) {
            return Component.translatable("screen.simple_translate.shortcuts.none").withStyle(ChatFormatting.GRAY);
        }
        MutableComponent result = Component.empty();
        if ((modifiers & CTRL) != 0) result.append(Component.translatable("screen.simple_translate.shortcuts.modifier.ctrl")).append("+");
        if ((modifiers & ALT) != 0) result.append(Component.translatable("screen.simple_translate.shortcuts.modifier.alt")).append("+");
        if ((modifiers & SHIFT) != 0) result.append(Component.translatable("screen.simple_translate.shortcuts.modifier.shift")).append("+");
        try {
            result.append((type == InputType.MOUSE ? InputConstants.Type.MOUSE : InputConstants.Type.KEYSYM)
                    .getOrCreate(code).getDisplayName());
        } catch (Throwable ignored) {
            result.append(Component.literal(type.id.toUpperCase(Locale.ROOT) + " " + code));
        }
        return result.withStyle(ChatFormatting.WHITE);
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
        return InputConstants.isKeyDown(minecraft.getWindow(), keyCode);
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
