package com.yourname.simpletranslate.core;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Single JSON-component translation facade for every game surface. */
public final class DirectSurfaceTranslator {
    private DirectSurfaceTranslator() {
    }

    public static ComponentTranslationResult translateComponent(
            Component component, String surface, String role) {
        Component source = component == null ? Component.empty() : component;
        String resolved = directSurface(surface);
        return JsonPassthroughPipeline.translateComponents(
                List.of(source), resolved, role, isFixedLayoutSurface(resolved), "").asSingle(source);
    }

    public static ComponentListTranslationResult translateComponents(
            List<Component> components, String surface, String role) {
        String resolved = directSurface(surface);
        return JsonPassthroughPipeline.translateComponents(
                components, resolved, role, isFixedLayoutSurface(resolved), "");
    }

    public static ComponentListTranslationResult translateComponents(
            List<Component> components, String surface, String role, boolean fixedLayout) {
        return translateComponents(components, surface, role, fixedLayout, "");
    }

    public static ComponentListTranslationResult translateComponents(
            List<Component> components, String surface, String role, boolean fixedLayout, String context) {
        String resolved = directSurface(surface);
        return JsonPassthroughPipeline.translateComponents(components, resolved, role, fixedLayout, context);
    }

    public static CompletableFuture<ComponentListTranslationResult> translateComponentsAsync(
            List<Component> components, String surface, String role, boolean fixedLayout, String context) {
        String resolved = directSurface(surface);
        return JsonPassthroughPipeline.translateComponentsAsync(
                components, resolved, role, fixedLayout, context);
    }

    public static ComponentListTranslationResult getCachedComponents(
            List<Component> components, String surface, String role, boolean fixedLayout, String context) {
        String resolved = directSurface(surface);
        return JsonPassthroughPipeline.getCachedComponents(
                components, resolved, role, fixedLayout, context);
    }

    public static String directSurface(String surface) {
        return surface == null || surface.isBlank() ? "generic" : surface.trim();
    }

    public static boolean isFixedLayoutSurface(String surface) {
        String value = directSurface(surface).toLowerCase(Locale.ROOT);
        return value.startsWith("sign.")
                || value.startsWith("hud.")
                || value.startsWith("title.")
                || value.startsWith("actionbar.")
                || value.startsWith("scoreboard.")
                || value.startsWith("bossbar.")
                || value.startsWith("entity.")
                || value.startsWith("text_display.")
                || value.startsWith("advancement.");
    }
}
