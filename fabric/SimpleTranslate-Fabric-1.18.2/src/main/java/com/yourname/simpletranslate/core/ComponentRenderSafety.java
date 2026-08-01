package com.yourname.simpletranslate.core;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Repairs malformed text components at render boundaries.
 *
 * <p>Some runtime language replacements can return a null translated string.
 * Minecraft accepts that value until font layout reaches String.length(), where
 * it crashes the render thread. This helper converts only malformed components
 * to safe literals and leaves normal components untouched.</p>
 */
public final class ComponentRenderSafety {
    private ComponentRenderSafety() {
    }

    public static Component sanitize(Component component) {
        return sanitize(component, "");
    }

    public static Component sanitize(Component component, String fallbackText) {
        String fallback = safeString(fallbackText);
        if (component == null) {
            return com.yourname.simpletranslate.core.LegacyComponentFactory.literal(fallback);
        }
        try {
            if (isRenderable(component)) {
                return component;
            }

            MutableComponent repaired = com.yourname.simpletranslate.core.LegacyComponentFactory.literal(recoveryText(component, fallback))
                    .withStyle(component.getStyle() == null ? Style.EMPTY : component.getStyle());
            for (Component sibling : component.getSiblings()) {
                try {
                    repaired.append(sanitize(sibling));
                } catch (Throwable siblingError) {
                    SafeTranslate.logLimited("render-safety.sanitizeSibling", siblingError);
                    repaired.append(com.yourname.simpletranslate.core.LegacyComponentFactory.literal(fallback));
                }
            }
            return repaired;
        } catch (Throwable error) {
            SafeTranslate.logLimited("render-safety.sanitize", error);
            return com.yourname.simpletranslate.core.LegacyComponentFactory.literal(fallback);
        }
    }

    public static String safeString(String text) {
        return text == null ? "" : text;
    }

    static boolean isRenderable(Component component) {
        if (component == null) {
            return false;
        }
        try {
            AtomicBoolean nullText = new AtomicBoolean(false);
            component.visit((style, text) -> {
                if (text == null) {
                    nullText.set(true);
                }
                return java.util.Optional.empty();
            }, Style.EMPTY);
            if (nullText.get()) {
                return false;
            }
            component.getVisualOrderText();
            return true;
        } catch (Throwable error) {
            SafeTranslate.logLimited("render-safety.isRenderable", error);
            return false;
        }
    }

    private static String recoveryText(Component component, String fallback) {
        if (component instanceof TextComponent literal) {
            return literal.getText() == null ? fallback : literal.getText();
        }
        if (component instanceof TranslatableComponent translatable) {
            if (translatable.getKey() != null && !translatable.getKey().isBlank()) {
                return translatable.getKey();
            }
        }
        return fallback;
    }
}
