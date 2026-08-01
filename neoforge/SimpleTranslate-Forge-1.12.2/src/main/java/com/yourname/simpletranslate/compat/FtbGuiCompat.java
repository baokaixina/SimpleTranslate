package com.yourname.simpletranslate.compat;

import com.yourname.simpletranslate.translation.TranslationEngine;
import com.yourname.simpletranslate.gui.GuiTranslationController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact 1.12.2 FTB bridge. The optional classes are reached only by reflection;
 * the inspected FTBLib-5.4.7.2 jar exposes GuiWrapper#getGui, Panel#widgets,
 * TextField#text and TextField#setText(String), so the base mod has no hard
 * dependency on FTB Library or FTB Quests.
 */
public final class FtbGuiCompat {
    private FtbGuiCompat() {
    }

    public static void translateVisibleWidgets(Object screen, TranslationEngine engine, Map<Object, String> changed) {
        try {
            Method getGui = screen.getClass().getMethod("getGui");
            Object gui = getGui.invoke(screen);
            visit(gui, engine, changed, 0);
        } catch (Throwable ignored) {
        }
    }

    private static void visit(Object value, TranslationEngine engine, Map<Object, String> changed, int depth) {
        if (value == null || depth > 12) return;
        try {
            Method shouldDraw = value.getClass().getMethod("shouldDraw");
            Object visible = shouldDraw.invoke(value);
            if (visible instanceof Boolean && !((Boolean) visible).booleanValue()) return;
        } catch (NoSuchMethodException ignored) {
            // Panels/containers without shouldDraw are traversed normally.
        } catch (Throwable ignored) {
            return;
        }
        // Duck-typed button detection: FTB GUIs build buttons as anonymous
        // subclasses (GuiFoo$1 extends SimpleTextButton), so a class-name
        // suffix check misses them (observed live 2026-07-27 on the My Team
        // GUI). In FTBLib-5.4.7.2 only Button and subclasses declare
        // setTitle(String) (javap-verified: Widget has getTitle only), so the
        // setTitle lookup is the discriminator.
        translateButton(value, engine, changed);
        try {
            Field widgets = findField(value.getClass(), "widgets");
            if (widgets != null) {
                widgets.setAccessible(true);
                Object children = widgets.get(value);
                if (children instanceof Iterable) for (Object child : (Iterable<?>) children) visit(child, engine, changed, depth + 1);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void translateButton(Object button, TranslationEngine engine, Map<Object, String> changed) {
        try {
            Method setTitle;
            try {
                setTitle = button.getClass().getMethod("setTitle", String.class);
            } catch (NoSuchMethodException notAButton) {
                return;
            }
            Method getTitle = button.getClass().getMethod("getTitle");
            Object raw = getTitle.invoke(button);
            if (!(raw instanceof String)) return;
            String source = (String) raw;
            if (source.isEmpty()) return;
            String translated = GuiTranslationController.transformVisibleText(source);
            if (!source.equals(translated)) {
                setTitle.invoke(button, translated);
                changed.put(button, source);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void restore(Object value, String original) {
        try {
            value.getClass().getMethod("setTitle", String.class).invoke(value, original);
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
}
