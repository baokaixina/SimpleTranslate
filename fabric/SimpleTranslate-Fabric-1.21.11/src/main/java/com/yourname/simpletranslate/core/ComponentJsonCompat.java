package com.yourname.simpletranslate.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

public final class ComponentJsonCompat {
    private ComponentJsonCompat() {
    }

    public static String toJson(Component component) {
        return ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, component).getOrThrow().toString();
    }

    public static Component fromJson(String json) {
        return fromJson(JsonParser.parseString(json));
    }

    public static Component fromJson(JsonElement element) {
        return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
    }
}
