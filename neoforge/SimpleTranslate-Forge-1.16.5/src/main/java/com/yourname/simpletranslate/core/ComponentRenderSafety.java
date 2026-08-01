package com.yourname.simpletranslate.core;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;

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

    public static ITextComponent sanitize(ITextComponent component) {
        return sanitize(component, "");
    }

    public static ITextComponent sanitize(ITextComponent component, String fallbackText) {
        String fallback = safeString(fallbackText);
        if (component == null) {
            return com.yourname.simpletranslate.core.LegacyComponentFactory.literal(fallback);
        }
        try {
            if (isRenderable(component)) {
                return component;
            }

            IFormattableTextComponent repaired = com.yourname.simpletranslate.core.LegacyComponentFactory.literal(recoveryText(component, fallback))
                    .withStyle(component.getStyle() == null ? Style.EMPTY : component.getStyle());
            for (ITextComponent sibling : component.getSiblings()) {
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

    static boolean isRenderable(ITextComponent component) {
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

    private static String recoveryText(ITextComponent component, String fallback) {
        if (component instanceof StringTextComponent literal) {
            return literal.getText() == null ? fallback : literal.getText();
        }
        if (component instanceof TranslationTextComponent translatable) {
            if (translatable.getKey() != null && !translatable.getKey().isBlank()) {
                return translatable.getKey();
            }
        }
        return fallback;
    }
}
