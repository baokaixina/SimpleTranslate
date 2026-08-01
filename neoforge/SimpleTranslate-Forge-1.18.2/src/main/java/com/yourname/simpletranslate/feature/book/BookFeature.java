package com.yourname.simpletranslate.feature.book;

import com.yourname.simpletranslate.feature.book.BookTranslationHelper;
import com.yourname.simpletranslate.feature.book.BookTranslationHelper.PageData;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Shared translation session used by both vanilla book screens. */
public final class BookFeature {
    private boolean active;
    private String bookKey;
    private List<Boolean> pageNeedsTranslation = List.of();
    private boolean lastTranslating;
    private boolean lastHasTranslation;

    public boolean start(List<PageData> pages) {
        if (pages == null || pages.isEmpty()) {
            reset();
            return false;
        }
        List<String> texts = new ArrayList<>(pages.size());
        List<Boolean> needs = new ArrayList<>(pages.size());
        boolean hasTranslatablePage = false;
        for (PageData page : pages) {
            texts.add(page == null ? "" : page.plainText);
            boolean translatable = page != null && page.needsTranslation;
            needs.add(translatable);
            hasTranslatablePage |= translatable;
        }
        if (!hasTranslatablePage) {
            reset();
            return false;
        }
        bookKey = BookTranslationHelper.buildBookKey(texts);
        pageNeedsTranslation = List.copyOf(needs);
        active = true;
        lastTranslating = false;
        lastHasTranslation = false;
        BookTranslationHelper.requestTranslation(bookKey, pages);
        return true;
    }

    public void reset() {
        active = false;
        bookKey = null;
        pageNeedsTranslation = List.of();
        lastTranslating = false;
        lastHasTranslation = false;
    }

    public boolean stateChanged() {
        if (!active || bookKey == null) {
            return false;
        }
        boolean translating = BookTranslationHelper.isTranslating(bookKey);
        boolean translated = BookTranslationHelper.getTranslatedPages(bookKey) != null;
        if (translating == lastTranslating && translated == lastHasTranslation) {
            return false;
        }
        lastTranslating = translating;
        lastHasTranslation = translated;
        return true;
    }

    public boolean active() {
        return active;
    }

    public boolean translating() {
        return active && bookKey != null && BookTranslationHelper.isTranslating(bookKey);
    }

    public boolean pageNeedsTranslation(int index) {
        return index >= 0 && index < pageNeedsTranslation.size() && pageNeedsTranslation.get(index);
    }

    public List<Component> translatedPages() {
        return bookKey == null ? null : BookTranslationHelper.getTranslatedPages(bookKey);
    }
}
