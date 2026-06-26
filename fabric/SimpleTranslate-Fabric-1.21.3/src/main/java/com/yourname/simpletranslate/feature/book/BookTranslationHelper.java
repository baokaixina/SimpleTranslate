package com.yourname.simpletranslate.feature.book;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.ComponentSegmentHelper;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.TextSegmentInfo;

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
    private static final int MAX_PAGE_LINES_PER_DIRECT_REQUEST = 96;
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

    private record PageLine(int pageIndex, int lineIndex, String text, Component component,
                            boolean needsTranslation) {
    }

    private record BookChunk(int start, int end, List<PageLine> lines, List<Component> components) {
        boolean needsTranslation() {
            for (PageLine line : lines) {
                if (line != null && line.needsTranslation()) {
                    return true;
                }
            }
            return false;
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
        return SafeTranslate.guard(() -> getTranslatedPagesImpl(bookKey), null, "book.getTranslatedPages");
    }

    private static List<Component> getTranslatedPagesImpl(String bookKey) {
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

            BookChunk chunk = buildBookChunk(pages, start, end);
            if (!chunk.needsTranslation()) {
                start = end;
                continue;
            }
            ComponentListTranslationResult direct =
                    DirectSurfaceTranslator.translateComponents(
                            chunk.components(),
                            "book.page.direct",
                            "book-page-line",
                            true,
                            buildBookChunkContext(pages, start, end, chunk));
            if (!direct.handled || !direct.translated || direct.components == null
                    || direct.components.size() != chunk.components().size()) {
                allReady = false;
                start = end;
                continue;
            }

            if (!applyTranslatedBookChunk(chunk, direct.components, pages, translated)) {
                allReady = false;
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
        int lines = 0;
        int end = start;
        while (end < pages.size() && end - start < MAX_PAGES_PER_DIRECT_REQUEST) {
            PageData page = pages.get(end);
            int pageChars = page == null || page.plainText == null ? 0 : page.plainText.length();
            int pageLines = pageLineCount(page);
            if (end > start && (chars + pageChars > MAX_PAGE_SOURCE_CHARS_PER_REQUEST
                    || lines + pageLines > MAX_PAGE_LINES_PER_DIRECT_REQUEST)) {
                break;
            }
            chars += pageChars;
            lines += pageLines;
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

    private static String buildBookChunkContext(List<PageData> pages, int start, int end, BookChunk chunk) {
        if (pages == null || pages.isEmpty()) {
            return "";
        }
        int from = Math.max(0, start - BOOK_CONTEXT_RADIUS_PAGES);
        int to = Math.min(pages.size(), end + BOOK_CONTEXT_RADIUS_PAGES);
        StringBuilder context = new StringBuilder();
        context.append("Book has ").append(pages.size()).append(" pages. ");
        context.append("Translate pages ").append(start + 1).append("-").append(end)
                .append(" using nearby page context. Each submitted line is one original page-line slot; ")
                .append("keep list entries, blank lines, and page-line slots aligned.\n");
        if (chunk != null) {
            context.append("Current request has ").append(chunk.lines().size())
                    .append(" page-line slots across ").append(end - start).append(" page(s).\n");
        }
        if (from > 0) {
            context.append("... earlier pages omitted ...\n");
        }
        for (int i = from; i < to; i++) {
            PageData page = pages.get(i);
            context.append("page ").append(i + 1).append(" (")
                    .append(pageLineCount(page)).append(" line slots): ");
            String text = previewPageText(page);
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

    private static BookChunk buildBookChunk(List<PageData> pages, int start, int end) {
        List<PageLine> lines = new ArrayList<>();
        List<Component> components = new ArrayList<>();
        for (int pageIndex = start; pageIndex < end && pageIndex < pages.size(); pageIndex++) {
            for (PageLine line : splitPageLines(pages.get(pageIndex), pageIndex)) {
                lines.add(line);
                components.add(line.component());
            }
        }
        return new BookChunk(start, end, List.copyOf(lines), List.copyOf(components));
    }

    private static boolean applyTranslatedBookChunk(BookChunk chunk, List<Component> translatedLines,
                                                    List<PageData> pages, List<Component> translatedPages) {
        if (chunk == null || translatedLines == null || translatedLines.size() != chunk.lines().size()
                || pages == null || translatedPages == null) {
            return false;
        }
        List<List<Component>> byPage = new ArrayList<>();
        for (int pageIndex = chunk.start(); pageIndex < chunk.end(); pageIndex++) {
            byPage.add(new ArrayList<>());
        }

        for (int i = 0; i < chunk.lines().size(); i++) {
            PageLine line = chunk.lines().get(i);
            Component component = line.component();
            if (line.needsTranslation()) {
                component = translatedLines.get(i);
                if (!isValidTranslatedBookLine(line, component)) {
                    return false;
                }
            }
            int pageOffset = line.pageIndex() - chunk.start();
            if (pageOffset < 0 || pageOffset >= byPage.size()) {
                return false;
            }
            byPage.get(pageOffset).add(component);
        }

        for (int pageIndex = chunk.start(); pageIndex < chunk.end(); pageIndex++) {
            int pageOffset = pageIndex - chunk.start();
            if (pageOffset < 0 || pageOffset >= byPage.size() || pageIndex >= translatedPages.size()) {
                return false;
            }
            translatedPages.set(pageIndex, joinPageLines(byPage.get(pageOffset), pageBaseStyle(pages.get(pageIndex))));
        }
        return true;
    }

    private static boolean isValidTranslatedBookLine(PageLine line, Component component) {
        if (line == null || component == null) {
            return false;
        }
        String text = component.getString();
        return text != null && !text.isBlank()
                && text.indexOf('\n') < 0 && text.indexOf('\r') < 0
                && !TooltipTranslationHelper.containsBlacklistedText(text);
    }

    private static Component joinPageLines(List<Component> lines, Style newlineStyle) {
        if (lines == null || lines.isEmpty()) {
            return Component.empty();
        }
        if (lines.size() == 1) {
            return lines.get(0) == null ? Component.empty() : lines.get(0);
        }
        MutableComponent page = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            page.append(line == null ? Component.empty() : line);
            if (i + 1 < lines.size()) {
                page.append(Component.literal("\n").withStyle(newlineStyle == null ? Style.EMPTY : newlineStyle));
            }
        }
        return page;
    }

    private static List<PageLine> splitPageLines(PageData page, int pageIndex) {
        if (page == null) {
            return List.of(new PageLine(pageIndex, 0, "", Component.empty(), false));
        }
        List<TextSegmentInfo> segments = page.segments == null ? List.of() : page.segments;
        if (segments.isEmpty()) {
            return splitPlainPageLines(page, pageIndex);
        }

        List<PageLine> result = new ArrayList<>();
        MutableComponent current = Component.empty();
        StringBuilder currentText = new StringBuilder();
        boolean sawText = false;
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null || segment.text.isEmpty()) {
                continue;
            }
            sawText = true;
            Style style = segment.style == null ? Style.EMPTY : segment.style;
            String text = segment.text.replace("\r\n", "\n").replace('\r', '\n');
            int cursor = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) != '\n') {
                    continue;
                }
                if (i > cursor) {
                    String part = text.substring(cursor, i);
                    current.append(Component.literal(part).withStyle(style));
                    currentText.append(part);
                }
                result.add(buildPageLine(page, pageIndex, result.size(), currentText.toString(), current));
                current = Component.empty();
                currentText.setLength(0);
                cursor = i + 1;
            }
            if (cursor < text.length()) {
                String part = text.substring(cursor);
                current.append(Component.literal(part).withStyle(style));
                currentText.append(part);
            }
        }
        if (!sawText) {
            return splitPlainPageLines(page, pageIndex);
        }
        result.add(buildPageLine(page, pageIndex, result.size(), currentText.toString(), current));
        return List.copyOf(result);
    }

    private static List<PageLine> splitPlainPageLines(PageData page, int pageIndex) {
        String text = page == null || page.plainText == null ? "" : page.plainText;
        String[] parts = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Style style = pageBaseStyle(page);
        List<PageLine> result = new ArrayList<>(Math.max(1, parts.length));
        for (String part : parts) {
            Component component = Component.literal(part == null ? "" : part).withStyle(style);
            result.add(buildPageLine(page, pageIndex, result.size(), part == null ? "" : part, component));
        }
        return List.copyOf(result);
    }

    private static PageLine buildPageLine(PageData page, int pageIndex, int lineIndex,
                                          String text, Component component) {
        String safeText = text == null ? "" : text;
        boolean needsTranslation = page != null && page.needsTranslation
                && TooltipTranslationHelper.containsEnglish(safeText);
        return new PageLine(pageIndex, lineIndex, safeText,
                component == null ? Component.empty() : component, needsTranslation);
    }

    private static int pageLineCount(PageData page) {
        String text = page == null || page.plainText == null ? "" : page.plainText;
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1).length;
    }

    private static String previewPageText(PageData page) {
        String text = page == null || page.plainText == null ? "" : page.plainText;
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", " / ").trim();
    }

    private static Style pageBaseStyle(PageData page) {
        if (page == null || page.originalComponent == null || page.originalComponent.getStyle() == null) {
            return Style.EMPTY;
        }
        return page.originalComponent.getStyle();
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
