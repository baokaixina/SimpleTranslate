package com.yourname.simpletranslate.core;

import com.google.gson.JsonElement;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;

public final class ComponentJsonCompat {
    private static final HolderLookup.Provider LOOKUP_PROVIDER = RegistryAccess.EMPTY;

    private ComponentJsonCompat() {
    }

    public static String toJson(Component component) {
        return Component.Serializer.toJson(component, LOOKUP_PROVIDER);
    }

    public static Component fromJson(String json) {
        return Component.Serializer.fromJson(json, LOOKUP_PROVIDER);
    }

    public static Component fromJson(JsonElement element) {
        return Component.Serializer.fromJson(element, LOOKUP_PROVIDER);
    }
}
