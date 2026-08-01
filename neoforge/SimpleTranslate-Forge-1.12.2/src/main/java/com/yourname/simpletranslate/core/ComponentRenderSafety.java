package com.yourname.simpletranslate.core;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;

/** Repairs malformed 1.12.2 components at render boundaries. */
public final class ComponentRenderSafety {
    private ComponentRenderSafety() {}

    public static ITextComponent sanitize(ITextComponent component) { return sanitize(component, ""); }

    public static ITextComponent sanitize(ITextComponent component, String fallbackText) {
        String fallback = safeString(fallbackText);
        if (component == null) return LegacyComponentFactory.literal(fallback);
        try {
            if (isRenderable(component)) return component;
            ITextComponent repaired = LegacyComponentFactory.literal(recoveryText(component, fallback));
            repaired.setStyle(component.getStyle() == null ? new Style() : component.getStyle().createShallowCopy());
            for (ITextComponent sibling : component.getSiblings()) {
                repaired.appendSibling(sanitize(sibling, fallback));
            }
            return repaired;
        } catch (Throwable error) {
            SafeTranslate.logLimited("render-safety.sanitize", error);
            return LegacyComponentFactory.literal(fallback);
        }
    }

    public static String safeString(String text) { return text == null ? "" : text; }

    static boolean isRenderable(ITextComponent component) {
        if (component == null) return false;
        try {
            String json = ITextComponent.Serializer.componentToJson(component);
            return json != null && component.getUnformattedText() != null;
        } catch (Throwable error) {
            SafeTranslate.logLimited("render-safety.isRenderable", error);
            return false;
        }
    }

    private static String recoveryText(ITextComponent component, String fallback) {
        if (component instanceof TextComponentString) {
            String text = ((TextComponentString) component).getText();
            return text == null ? fallback : text;
        }
        if (component instanceof TextComponentTranslation) {
            String key = ((TextComponentTranslation) component).getKey();
            if (key != null && !key.trim().isEmpty()) return key;
        }
        return fallback;
    }
}
