package com.yourname.simpletranslate.core;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;

/** ITextComponent factory for Minecraft 1.18.2's pre-1.19 chat component API. */
public final class LegacyComponentFactory {
    private LegacyComponentFactory() {
    }

    public static IFormattableTextComponent literal(String text) {
        return new StringTextComponent(text == null ? "" : text);
    }

    public static IFormattableTextComponent translatable(String key, Object... args) {
        return new TranslationTextComponent(key == null ? "" : key, args == null ? new Object[0] : args);
    }

    public static IFormattableTextComponent empty() {
        return new StringTextComponent("");
    }
}
