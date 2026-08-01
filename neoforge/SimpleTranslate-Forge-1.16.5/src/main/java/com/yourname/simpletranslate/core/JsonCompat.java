package com.yourname.simpletranslate.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;

/** Gson 2.8-compatible helpers used by the 1.16.5 dependency graph. */
public final class JsonCompat {
    private static final Gson GSON = new Gson();

    private JsonCompat() {
    }

    @SuppressWarnings("unchecked")
    public static <T extends JsonElement> T deepCopy(T element) {
        return element == null ? null : (T) new com.google.gson.JsonParser().parse(GSON.toJson(element));
    }

    public static Set<String> keySet(JsonObject object) {
        Set<String> keys = new LinkedHashSet<>();
        if (object != null) {
            for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }
}
