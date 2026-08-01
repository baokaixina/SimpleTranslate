package com.yourname.simpletranslate.core;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

/** Component factory for Minecraft 1.18.2's pre-1.19 chat component API. */
public final class LegacyComponentFactory {
    private LegacyComponentFactory() {
    }

    public static MutableComponent literal(String text) {
        return new TextComponent(text == null ? "" : text);
    }

    public static MutableComponent translatable(String key, Object... args) {
        return new TranslatableComponent(key == null ? "" : key, args == null ? new Object[0] : args);
    }

    public static MutableComponent empty() {
        return new TextComponent("");
    }
}
