package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper for full-book translation with semantic page mapping.
 */
public class BookTranslationHelper {

    private static final String TRANSLATING_TEXT = "\u7ffb\u8bd1\u4e2d...";
    private static final long CACHE_TTL_MS = 300_000L;
    private static final int MAX_PAGES_PER_DIRECT_REQUEST = 6;
    private static final int MAX_PAGE_SOURCE_CHARS_PER_REQUEST = 3200;
    private static final int BOOK_CONTEXT_RADIUS_PAGES = 2;
    private static final Set<String> pendingTranslations = ConcurrentHashMap.newKeySet();
    private static final Map<String, List<PageData>> pendingPageData = new ConcurrentHashMap<>();
    private static final Map<String, BookTranslationData> translatedBookCache = new ConcurrentHashMap<>();

    public static class PageData {
        public final String plainText;
        public final Component originalComponent;
        public final boolean needsTranslation;
        public final List<TextSegmentInfo> segments;

        public PageData(String plainText, Component originalComponent, boolean needsTranslation, List<TextSegmentInfo> segments) {
            this.plainText = plainText;
            this.originalComponent = originalComponent;
            this.needsTranslation = needsTranslation;
            this.segments = segments;
        }
    }

    private static class BookTranslationData {
        final List<Component> translatedPages;
        final long timestamp;

        BookTranslationData(List<Component> translatedPages) {
            this.translatedPages = translatedPages;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static List<PageData> buildPageDataFromFormatted(List<FormattedText> pages) {
        List<PageData> result = new ArrayList<>();
        for (FormattedText page : pages) {
            String text = page != null ? page.getString() : "";
            Component component = (page instanceof Component) ? (Component) page : Component.literal(text);
            boolean needsTranslation = TooltipTranslationHelper.containsEnglish(text);
            List<TextSegmentInfo> segments = new ArrayList<>();
            ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
            result.add(new PageData(text, component, needsTranslation, segments));
        }
        return result;
    }

    public static List<PageData> buildPageDataFromStrings(List<String> pages) {
        List<PageData> result = new ArrayList<>();
        for (String page : pages) {
            String text = page != null ? page : "";
            Component component = Component.literal(text);
            boolean needsTranslation = TooltipTranslationHelper.containsEnglish(text);
            List<TextSegmentInfo> segments = new ArrayList<>();
            ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
            result.add(new PageData(text, component, needsTranslation, segments));
        }
        return result;
    }

    public static String buildBookKey(List<String> pageTexts) {
        StringBuilder sb = new StringBuilder("book:" + TranslationCacheKeys.PROTOCOL + ":");
        sb.append(pageTexts.size()).append(":");
        for (String text : pageTexts) {
            String safe = text != null ? text : "";
            sb.append(TranslationCacheKeys.normalizeSource(safe)).append('\u0001');
        }
        return TranslationCacheKeys.key("book.document.direct", sb.toString());
    }

    public static boolean isTranslating(String bookKey) {
        if (bookKey == null || !pendingTranslations.contains(bookKey)) {
            return false;
        }
        List<PageData> pages = pendingPageData.get(bookKey);
        if (pages != null && tryResolveSemanticBook(bookKey, pages)) {
            pendingTranslations.remove(bookKey);
            pendingPageData.remove(bookKey);
            return false;
        }
        return pendingTranslations.contains(bookKey);
    }

    public static List<Component> getTranslatedPages(String bookKey) {
        BookTranslationData data = bookKey != null ? translatedBookCache.get(bookKey) : null;
        if (data == null && bookKey != null && pendingTranslations.contains(bookKey)) {
            List<PageData> pages = pendingPageData.get(bookKey);
            if (pages != null && tryResolveSemanticBook(bookKey, pages)) {
                pendingTranslations.remove(bookKey);
                pendingPageData.remove(bookKey);
                data = translatedBookCache.get(bookKey);
            }
        }
        if (data == null) {
            return null;
        }
        if (containsBlacklistedComponent(data.translatedPages)) {
            translatedBookCache.remove(bookKey);
            pendingTranslations.remove(bookKey);
            pendingPageData.remove(bookKey);
            return null;
        }
        return data.translatedPages;
    }

    public static void clearCache() {
        pendingTranslations.clear();
        pendingPageData.clear();
        translatedBookCache.clear();
    }

    public static Component buildTranslatingComponent(Component original) {
        MutableComponent translating = Component.literal(TRANSLATING_TEXT);
        if (original != null) {
            Style style = original.getStyle();
            if (style != null && !style.isEmpty()) {
                translating = translating.withStyle(style);
            }
        }
        return translating;
    }

    public static String getTranslatingText() {
        return TRANSLATING_TEXT;
    }

    public static void requestTranslation(String bookKey, List<PageData> pages) {
        if (bookKey == null || pages == null || pages.isEmpty()) {
            return;
        }

        boolean needsTranslation = false;
        for (PageData page : pages) {
            if (page != null && page.needsTranslation) {
                needsTranslation = true;
            }
            if (page != null && TooltipTranslationHelper.isBlacklisted(page.plainText)) {
                pendingTranslations.remove(bookKey);
                pendingPageData.remove(bookKey);
                translatedBookCache.remove(bookKey);
                return;
            }
        }
        if (!needsTranslation || translatedBookCache.containsKey(bookKey)) {
            return;
        }

        pendingPageData.put(bookKey, List.copyOf(pages));
        if (tryResolveSemanticBook(bookKey, pages)) {
            pendingTranslations.remove(bookKey);
            pendingPageData.remove(bookKey);
            return;
        }
        pendingTranslations.add(bookKey);
    }

    private static boolean tryResolveSemanticBook(String bookKey, List<PageData> pages) {
        if (bookKey == null || pages == null || pages.isEmpty()) {
            return false;
        }

        List<Component> originals = new ArrayList<>();
        for (PageData page : pages) {
            Component original = page != null && page.originalComponent != null
                    ? page.originalComponent
                    : Component.literal(page == null ? "" : page.plainText);
            originals.add(original);
        }

        List<Component> translated = new ArrayList<>();
        for (Component original : originals) {
            translated.add(original);
        }
        boolean allReady = true;
        for (int start = 0; start < pages.size(); ) {
            int end = nextChunkEnd(pages, start);
            if (!chunkNeedsTranslation(pages, start, end)) {
                start = end;
                continue;
            }

            List<Component> chunkOriginals = new ArrayList<>(originals.subList(start, end));
            DirectFormattedTranslationPipeline.ComponentListResult direct =
                    DirectSurfaceTranslator.translateComponents(
                            chunkOriginals,
                            "book.page.direct",
                            "book-page",
                            false,
                            buildBookChunkContext(pages, start, end));
            if (!direct.handled || !direct.translated || direct.components == null
                    || direct.components.size() != chunkOriginals.size()) {
                allReady = false;
                start = end;
                continue;
            }

            for (int local = 0; local < direct.components.size(); local++) {
                int pageIndex = start + local;
                PageData page = pages.get(pageIndex);
                Component component = direct.components.get(local);
                if (page == null || !page.needsTranslation) {
                    continue;
                }

                if (component == null) {
                    allReady = false;
                    continue;
                }

                String originalText = page.plainText == null ? "" : page.plainText;
                String translatedText = component.getString();
                if (translatedText == null || translatedText.isBlank()
                        || TooltipTranslationHelper.containsBlacklistedText(translatedText)) {
                    allReady = false;
                    continue;
                }
                translated.set(pageIndex, component);
            }
            start = end;
        }

        if (!allReady || containsBlacklistedComponent(translated)) {
            return false;
        }

        storeTranslation(bookKey, translated);
        return true;
    }

    private static int nextChunkEnd(List<PageData> pages, int start) {
        int chars = 0;
        int end = start;
        while (end < pages.size() && end - start < MAX_PAGES_PER_DIRECT_REQUEST) {
            PageData page = pages.get(end);
            int pageChars = page == null || page.plainText == null ? 0 : page.plainText.length();
            if (end > start && chars + pageChars > MAX_PAGE_SOURCE_CHARS_PER_REQUEST) {
                break;
            }
            chars += pageChars;
            end++;
        }
        return Math.max(start + 1, end);
    }

    private static boolean chunkNeedsTranslation(List<PageData> pages, int start, int end) {
        for (int i = start; i < end && i < pages.size(); i++) {
            PageData page = pages.get(i);
            if (page != null && page.needsTranslation) {
                return true;
            }
        }
        return false;
    }

    private static String buildBookChunkContext(List<PageData> pages, int start, int end) {
        if (pages == null || pages.isEmpty()) {
            return "";
        }
        int from = Math.max(0, start - BOOK_CONTEXT_RADIUS_PAGES);
        int to = Math.min(pages.size(), end + BOOK_CONTEXT_RADIUS_PAGES);
        StringBuilder context = new StringBuilder();
        context.append("Book has ").append(pages.size()).append(" pages. ");
        context.append("Translate pages ").append(start + 1).append("-").append(end)
                .append(" using nearby page context; keep page boundaries from <st-doc>.\n");
        if (from > 0) {
            context.append("... earlier pages omitted ...\n");
        }
        for (int i = from; i < to; i++) {
            PageData page = pages.get(i);
            context.append("page ").append(i + 1).append(": ");
            String text = page == null || page.plainText == null ? "" : page.plainText.replace('\n', ' ').trim();
            if (text.length() > 900) {
                text = text.substring(0, 450) + " ... " + text.substring(text.length() - 350);
            }
            context.append(text).append('\n');
        }
        if (to < pages.size()) {
            context.append("... later pages omitted ...");
        }
        return context.toString();
    }

    private static void storeTranslation(String bookKey, List<Component> translatedPages) {
        if (translatedPages == null || translatedPages.isEmpty()) {
            return;
        }
        if (containsBlacklistedComponent(translatedPages)) {
            translatedBookCache.remove(bookKey);
            return;
        }

        translatedBookCache.put(bookKey, new BookTranslationData(List.copyOf(translatedPages)));
        if (translatedBookCache.size() > 40) {
            long cutoff = System.currentTimeMillis() - CACHE_TTL_MS;
            translatedBookCache.entrySet().removeIf(entry -> entry.getValue().timestamp < cutoff);
        }
    }

    private static boolean containsBlacklistedComponent(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return false;
        }
        for (Component component : components) {
            String text = component == null ? "" : component.getString();
            if (TooltipTranslationHelper.containsBlacklistedText(text)) {
                return true;
            }
        }
        return false;
    }
}
