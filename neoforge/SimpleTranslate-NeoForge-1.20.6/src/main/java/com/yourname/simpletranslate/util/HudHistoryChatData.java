package com.yourname.simpletranslate.util;

public final class HudHistoryChatData {
    private HudTranslationHistory.Entry entry;
    private boolean showingOriginal;

    public HudHistoryChatData(HudTranslationHistory.Entry entry, boolean showingOriginal) {
        this.entry = entry;
        this.showingOriginal = showingOriginal;
    }

    public HudTranslationHistory.Entry entry() {
        return entry;
    }

    public void setEntry(HudTranslationHistory.Entry entry) {
        this.entry = entry;
    }

    public boolean showingOriginal() {
        return showingOriginal;
    }

    public void toggleShowingOriginal() {
        this.showingOriginal = !this.showingOriginal;
    }
}
