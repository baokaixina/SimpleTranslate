package com.yourname.simpletranslate.feature.advancement;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.ComponentTranslationResult;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.core.ComponentRenderSafety;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advancement translation adapter.
 *
 * Keep advancement entry points on Component JSON arrays. Legacy marker/raw
 * cache lanes are intentionally bypassed here because advancement titles,
 * descriptions, toasts and optional plaques can all share text but need their
 * own component styles.
 */
public final class AdvancementTranslationHelper {

    private static final String DOCUMENT_SURFACE = "advancement.document.direct";
    private static final String TITLE_SURFACE = "advancement.title.component.direct";
    private static final String DESCRIPTION_SURFACE = "advancement.description.component.direct";

    private static final Map<String, Component> componentCache = new ConcurrentHashMap<>();
    private static final Map<String, String> stringCache = new ConcurrentHashMap<>();
    private static final Set<String> pendingDocuments = ConcurrentHashMap.newKeySet();
    private static final Set<String> pendingComponentKeys = ConcurrentHashMap.newKeySet();

    private AdvancementTranslationHelper() {
    }

    public static void ensureTranslation(String advancementId, Component title, Component description) {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return;
        }
        title = ComponentRenderSafety.sanitize(title);
        description = ComponentRenderSafety.sanitize(description);
        String titleText = title.getString();
        String descText = description.getString();
        if (TooltipTranslationHelper.isBlacklisted(titleText) || TooltipTranslationHelper.isBlacklisted(descText)) {
            clearAdvancement(advancementId, titleText, descText);
            return;
        }
        if (!TooltipTranslationHelper.containsEnglish(titleText) && !TooltipTranslationHelper.containsEnglish(descText)) {
            return;
        }

        String documentKey = documentKey(advancementId, titleText, descText);
        if (!pendingDocuments.add(documentKey)) {
            return;
        }
        String titleKey = componentKey(TITLE_SURFACE, titleText);
        String descKey = componentKey(DESCRIPTION_SURFACE, descText);
        if (!titleText.isEmpty()) {
            pendingComponentKeys.add(titleKey);
        }
        if (!descText.isEmpty()) {
            pendingComponentKeys.add(descKey);
        }

        DirectSurfaceTranslator.translateComponentsAsync(List.of(
                        title,
                        description
                ), DOCUMENT_SURFACE, "advancement", false, "")
                .whenComplete((result, error) -> {
                    try {
                        if (error != null) {
                            SimpleTranslateMod.getLogger().warn("Advancement document translation failed id={} error={}",
                                    documentKey, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                            return;
                        }
                        if (result != null && result.translated && result.components != null && result.components.size() == 2) {
                            Component titleComponent = result.components.get(0);
                            Component descComponent = result.components.get(1);
                            if (titleComponent != null) {
                                storeComponent(titleText, result.components.get(0), TITLE_SURFACE);
                            }
                            if (descComponent != null) {
                                storeComponent(descText, result.components.get(1), DESCRIPTION_SURFACE);
                            }
                            SimpleTranslateMod.getLogger().debug(
                                    "Advancement document cached id={} title='{}' description='{}'",
                                    documentKey, safeSummary(titleComponent == null ? "" : titleComponent.getString()),
                                    safeSummary(descComponent == null ? "" : descComponent.getString()));
                        }
                    } finally {
                        pendingDocuments.remove(documentKey);
                        pendingComponentKeys.remove(titleKey);
                        pendingComponentKeys.remove(descKey);
                    }
                });
    }

    public static Component translateComponent(Component original, String surface, String role) {
        if (original == null) {
            return null;
        }
        return SafeTranslate.guard(() -> translateComponentImpl(original, surface, role), original,
                "advancement.translateComponent");
    }

    private static Component translateComponentImpl(Component original, String surface, String role) {
        original = ComponentRenderSafety.sanitize(original);
        String text = original.getString();
        if (text == null || text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
            return original;
        }
        if (TooltipTranslationHelper.isBlacklisted(text)) {
            componentCache.remove(componentKey(surface, text));
            stringCache.remove(componentKey(surface, text));
            return original;
        }

        String key = componentKey(surface, text);
        Component cached = componentCache.get(key);
        if (cached != null && !isRejected(text, cached.getString())) {
            return cached;
        }

        Component documentCached = getDocumentCachedComponent(original, text, role);
        if (documentCached != null) {
            componentCache.put(key, documentCached);
            stringCache.put(key, documentCached.getString());
            return documentCached;
        }
        if (isPendingComponent(text)) {
            return original;
        }

            ComponentTranslationResult direct =
                DirectSurfaceTranslator.translateComponent(original, surface, role);
        if (!direct.handled || direct.component == null) {
            return original;
        }

        String translatedText = direct.component.getString();
        if (isRejected(text, translatedText)) {
            componentCache.remove(key);
            stringCache.remove(key);
            return original;
        }

        componentCache.put(key, direct.component);
        stringCache.put(key, translatedText);
        return direct.component;
    }

    private static Component getDocumentCachedComponent(Component original, String text, String role) {
        String preferredSurface = preferredSurfaceForRole(role);
        Component preferredCached = componentCache.get(componentKey(preferredSurface, text));
        if (preferredCached != null && !isRejected(text, preferredCached.getString())) {
            return preferredCached;
        }
        Component preferred = rebuildFromDocumentCache(original, text, preferredSurface);
        if (preferred != null) {
            return preferred;
        }
        String alternateSurface = DESCRIPTION_SURFACE.equals(preferredSurface) ? TITLE_SURFACE : DESCRIPTION_SURFACE;
        Component alternateCached = componentCache.get(componentKey(alternateSurface, text));
        if (alternateCached != null && !isRejected(text, alternateCached.getString())) {
            return alternateCached;
        }
        return rebuildFromDocumentCache(original, text, alternateSurface);
    }

    public static Component getCachedTitleComponent(Component original) {
        if (original == null) {
            return null;
        }
        return SafeTranslate.guard(() -> getCachedDocumentComponent(original, TITLE_SURFACE), null,
                "advancement.getCachedTitleComponent");
    }

    public static Component getCachedDescriptionComponent(Component title, Component description) {
        if (description == null) {
            return null;
        }
        return SafeTranslate.guard(() -> getCachedDescriptionComponentImpl(title, description), null,
                "advancement.getCachedDescriptionComponent");
    }

    private static Component getCachedDescriptionComponentImpl(Component title, Component description) {
        title = ComponentRenderSafety.sanitize(title);
        description = ComponentRenderSafety.sanitize(description);
        String descText = description.getString();
        if (descText == null || descText.isEmpty() || !TooltipTranslationHelper.containsEnglish(descText)) {
            return null;
        }

        Component cached = componentCache.get(componentKey(DESCRIPTION_SURFACE, descText));
        if (cached != null && !isRejected(descText, cached.getString())) {
            return cached;
        }

        ComponentListTranslationResult document =
                DirectSurfaceTranslator.getCachedComponents(List.of(
                        title,
                        description
                ), DOCUMENT_SURFACE, "advancement", false, "");
        if (document.handled && document.translated && document.components != null && document.components.size() == 2) {
            Component translatedTitle = document.components.get(0);
            Component translatedDescription = document.components.get(1);
            if (translatedTitle != null) {
                storeComponent(title.getString(), translatedTitle, TITLE_SURFACE);
            }
            if (translatedDescription != null && !isRejected(descText, translatedDescription.getString())) {
                storeComponent(descText, translatedDescription, DESCRIPTION_SURFACE);
                return translatedDescription;
            }
        }

        return getCachedDocumentComponent(description, DESCRIPTION_SURFACE);
    }

    public static Component getCachedDescriptionComponent(Component original) {
        if (original == null) {
            return null;
        }
        return SafeTranslate.guard(() -> getCachedDocumentComponent(original, DESCRIPTION_SURFACE), null,
                "advancement.getCachedDescriptionComponent");
    }

    private static Component getCachedDocumentComponent(Component original, String surface) {
        if (original == null) {
            return null;
        }
        original = ComponentRenderSafety.sanitize(original);
        String text = original.getString();
        if (text == null || text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
            return null;
        }
        Component cached = componentCache.get(componentKey(surface, text));
        if (cached != null && !isRejected(text, cached.getString())) {
            return cached;
        }
        Component rebuilt = rebuildFromDocumentCache(original, text, surface);
        if (rebuilt != null) {
            componentCache.put(componentKey(surface, text), rebuilt);
            stringCache.put(componentKey(surface, text), rebuilt.getString());
            return rebuilt;
        }
        return null;
    }

    private static Component rebuildFromDocumentCache(Component original, String text, String surface) {
        String translated = stringCache.get(componentKey(surface, text));
        if (translated == null || isRejected(text, translated)) {
            return null;
        }
        ComponentListTranslationResult cached =
                DirectSurfaceTranslator.getCachedComponents(List.of(original), surface, "advancement", false, "");
        if (cached != null && cached.translated && cached.components != null && cached.components.size() == 1) {
            Component result = cached.components.get(0);
            if (result != null && !isRejected(text, result.getString())) {
                return result;
            }
        }
        return null;
    }

    public static String getCachedTranslation(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String cached = stringCache.get(componentKey(TITLE_SURFACE, text));
        if (cached != null && !isRejected(text, cached)) {
            return cached;
        }

        Component translated = translateComponent(Component.literal(text), TITLE_SURFACE, "advancement-title");
        return translated == null ? text : translated.getString();
    }

    public static String getTranslatedDescription(String advancementId, String originalText) {
        if (originalText == null || originalText.isEmpty()) {
            return originalText;
        }
        String cached = stringCache.get(componentKey(DESCRIPTION_SURFACE, originalText));
        if (cached != null && !isRejected(originalText, cached)) {
            return cached;
        }
        Component translated = translateComponent(Component.literal(originalText), DESCRIPTION_SURFACE, "advancement-description");
        return translated == null ? originalText : translated.getString();
    }

    public static boolean containsEnglish(String text) {
        return TooltipTranslationHelper.containsEnglish(text);
    }

    public static void clearCache() {
        componentCache.clear();
        stringCache.clear();
        pendingDocuments.clear();
        pendingComponentKeys.clear();
    }

    private static void storeComponent(String sourceText, Component component, String surface) {
        if (sourceText == null || sourceText.isEmpty() || component == null) {
            return;
        }
        component = ComponentRenderSafety.sanitize(component, sourceText);
        String translatedText = component.getString();
        if (isRejected(sourceText, translatedText)) {
            return;
        }
        String key = componentKey(surface, sourceText);
        componentCache.put(key, component);
        stringCache.put(key, translatedText);
    }

    private static boolean isRejected(String sourceText, String translatedText) {
        if (translatedText == null || translatedText.isBlank()) {
            return true;
        }
        return TooltipTranslationHelper.isBlacklisted(sourceText)
                || TooltipTranslationHelper.containsBlacklistedText(translatedText);
    }

    private static void clearAdvancement(String advancementId, String titleText, String descText) {
        if (advancementId != null) {
            pendingDocuments.remove(documentKey(advancementId, titleText, descText));
        }
        pendingComponentKeys.remove(componentKey(TITLE_SURFACE, titleText));
        pendingComponentKeys.remove(componentKey(DESCRIPTION_SURFACE, descText));
        componentCache.remove(componentKey(TITLE_SURFACE, titleText));
        componentCache.remove(componentKey(DESCRIPTION_SURFACE, descText));
        stringCache.remove(componentKey(TITLE_SURFACE, titleText));
        stringCache.remove(componentKey(DESCRIPTION_SURFACE, descText));
    }

    private static boolean isPendingComponent(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return pendingComponentKeys.contains(componentKey(TITLE_SURFACE, text))
                || pendingComponentKeys.contains(componentKey(DESCRIPTION_SURFACE, text));
    }

    private static String preferredSurfaceForRole(String role) {
        return role != null && role.toLowerCase(java.util.Locale.ROOT).contains("description")
                ? DESCRIPTION_SURFACE
                : TITLE_SURFACE;
    }

    private static String safeSummary(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() <= 80 ? compact : compact.substring(0, 80) + "...";
    }

    private static String componentKey(String surface, String text) {
        return TranslationCacheKeys.key(
                DirectSurfaceTranslator.directSurface(surface == null || surface.isBlank() ? TITLE_SURFACE : surface),
                text == null ? "" : text);
    }

    private static String documentKey(String advancementId, String titleText, String descText) {
        if (advancementId != null && !advancementId.isBlank()) {
            return advancementId.startsWith("advancement:") ? advancementId : "advancement:" + advancementId;
        }
        return "advancement:document:" + (titleText == null ? "" : titleText).hashCode()
                + ":" + (descText == null ? "" : descText).hashCode();
    }
}
