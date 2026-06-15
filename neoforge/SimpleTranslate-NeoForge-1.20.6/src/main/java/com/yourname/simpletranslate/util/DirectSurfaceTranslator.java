package com.yourname.simpletranslate.util;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Single formatted-translation facade for Minecraft surfaces.
 *
 * <p>Game render paths should enter the direct formatted pipeline through this
 * class. Raw text translation is intentionally kept outside this facade for API
 * checks and plain-text tools only.</p>
 */
public final class DirectSurfaceTranslator {
    private DirectSurfaceTranslator() {
    }

    public static DirectFormattedTranslationPipeline.ComponentResult translateComponent(
            Component component, String surface, String role) {
        String resolvedSurface = directSurface(surface);
        return DirectFormattedTranslationPipeline.translateComponent(
                component, resolvedSurface, role, isFixedLayoutSurface(resolvedSurface));
    }

    public static DirectFormattedTranslationPipeline.ComponentListResult translateComponents(
            List<Component> components, String surface, String role) {
        String directSurface = directSurface(surface);
        return DirectFormattedTranslationPipeline.translateComponents(
                components, directSurface, role, isFixedLayoutSurface(directSurface));
    }

    public static DirectFormattedTranslationPipeline.ComponentListResult translateComponents(
            List<Component> components, String surface, String role, boolean fixedLayout) {
        return DirectFormattedTranslationPipeline.translateComponents(
                components, directSurface(surface), role, fixedLayout);
    }

    public static DirectFormattedTranslationPipeline.ComponentListResult translateComponents(
            List<Component> components, String surface, String role, boolean fixedLayout, String context) {
        return DirectFormattedTranslationPipeline.translateComponents(
                components, directSurface(surface), role, fixedLayout, context);
    }

    public static CompletableFuture<DirectFormattedTranslationPipeline.ComponentListResult> translateComponentsAsync(
            List<Component> components, String surface, String role, boolean fixedLayout, String context) {
        return DirectFormattedTranslationPipeline.translateComponentsAsync(
                components, directSurface(surface), role, fixedLayout, context);
    }

    public static DirectFormattedTranslationPipeline.ComponentListResult getCachedComponents(
            List<Component> components, String surface, String role, boolean fixedLayout, String context) {
        return DirectFormattedTranslationPipeline.getCachedComponents(
                components, directSurface(surface), role, fixedLayout, context);
    }

    public static CompletableFuture<String> translateTextAsync(String text, String surface, String role,
                                                               String context, String layoutSignature,
                                                               String styleSignature) {
        return DirectFormattedTranslationPipeline.translateTextAsync(
                text, directSurface(surface), role, context, layoutSignature, styleSignature);
    }

    public static String getCachedText(String text, String surface, String role, String context,
                                       String layoutSignature, String styleSignature) {
        return DirectFormattedTranslationPipeline.getCachedText(
                text, directSurface(surface), role, context, layoutSignature, styleSignature);
    }

    public static String directSurface(String surface) {
        String value = surface == null || surface.isBlank() ? "generic" : surface.trim();
        return value.replace(".semantic", ".direct")
                .replace(".block_job", ".direct")
                .replace(".block", ".direct")
                .replace(".mapping", ".direct");
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
