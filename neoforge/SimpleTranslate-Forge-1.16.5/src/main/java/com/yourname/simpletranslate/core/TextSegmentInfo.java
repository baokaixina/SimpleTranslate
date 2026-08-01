package com.yourname.simpletranslate.core;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;

/**
 * Stores text segment information for translation
 * Must be outside mixin package to avoid classloading issues
 */
public class TextSegmentInfo {
    public String text;
    public Style style;
    public ITextComponent originalComponent;
    public String translatedText;

    public TextSegmentInfo(String text, Style style, ITextComponent original) {
        this.text = text;
        this.style = style;
        this.originalComponent = original;
        this.translatedText = null;
    }
}
