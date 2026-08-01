package com.yourname.simpletranslate.core;

import net.minecraft.util.text.ITextComponent;

import java.util.List;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Single JSON-component translation facade for every game surface. */
public final class DirectSurfaceTranslator {
    private DirectSurfaceTranslator() {
    }

    public static ComponentTranslationResult translateComponent(
            ITextComponent component, String surface, String role) {
        ITextComponent source = component == null ? com.yourname.simpletranslate.core.LegacyComponentFactory.empty() : component;
        String resolved = directSurface(surface);
        return JsonPassthroughPipeline.translateComponents(
                Collections.singletonList(source), resolved, role, isFixedLayoutSurface(resolved), "").asSingle(source);
    }

    public static ComponentListTranslationResult translateComponents(
            List<ITextComponent> components, String surface, String role) {
        String resolved = directSurface(surface);
        return JsonPassthroughPipeline.translateComponents(
                components, resolved, role, isFixedLayoutSurface(resolved), "");
    }

    public static ComponentListTranslationResult translateComponents(
            List<ITextComponent> components, String surface, String role, boolean fixedLayout) {
        return translateComponents(components, surface, role, fixedLayout, "");
    }

    public static ComponentListTranslationResult translateComponents(
            List<ITextComponent> components, String surface, String role, boolean fixedLayout, String context) {
        String resolved = directSurface(surface);
        return JsonPassthroughPipeline.translateComponents(components, resolved, role, fixedLayout, context);
    }

    public static CompletableFuture<ComponentListTranslationResult> translateComponentsAsync(
            List<ITextComponent> components, String surface, String role, boolean fixedLayout, String context) {
        return translateComponentsAsync(components, surface, role, fixedLayout, context, "", "");
    }

    public static CompletableFuture<ComponentListTranslationResult> translateComponentsAsync(
            List<ITextComponent> components, String surface, String role, boolean fixedLayout, String context,
            String sourceLanguage, String targetLanguage) {
        String resolved = directSurface(surface);
        return JsonPassthroughPipeline.translateComponentsAsync(
                components, resolved, role, fixedLayout, context, sourceLanguage, targetLanguage);
    }

    public static ComponentListTranslationResult getCachedComponents(
            List<ITextComponent> components, String surface, String role, boolean fixedLayout, String context) {
        String resolved = directSurface(surface);
        return JsonPassthroughPipeline.getCachedComponents(
                components, resolved, role, fixedLayout, context);
    }

    public static String directSurface(String surface) {
        return surface == null || surface.trim().isEmpty() ? "generic" : surface.trim();
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
                || value.startsWith("advancement.");
    }
}
