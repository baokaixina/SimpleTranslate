package com.yourname.simpletranslate.core;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Stores text segment information for translation
 * Must be outside mixin package to avoid classloading issues
 */
public class TextSegmentInfo {
    public String text;
    public Style style;
    public Component originalComponent;
    public String translatedText;

    public TextSegmentInfo(String text, Style style, Component original) {
        this.text = text;
        this.style = style;
        this.originalComponent = original;
        this.translatedText = null;
    }
}
