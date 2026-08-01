package com.yourname.simpletranslate.core;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;

/** Component factory for Minecraft 1.12.2's mutable text API. */
public final class LegacyComponentFactory {
    private LegacyComponentFactory() {}

    public static ITextComponent literal(String text) {
        return new TextComponentString(text == null ? "" : text);
    }

    public static ITextComponent translatable(String key, Object... args) {
        return new TextComponentTranslation(key == null ? "" : key,
                args == null ? new Object[0] : args);
    }

    public static ITextComponent empty() { return new TextComponentString(""); }
}
