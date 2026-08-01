package com.yourname.simpletranslate.cache;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.core.ComponentJsonCompat;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Text-node extraction and replacement for cached component JSON arrays. */
public final class ComponentJsonCacheEditor {
    private ComponentJsonCacheEditor() {
    }

    public static List<String> textNodes(String json) {
        JsonElement root = parseArray(json);
        if (root == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        collectTextNodes(root, values);
        return List.copyOf(values);
    }

    public static String replaceTextNodes(String json, List<String> replacements) {
        JsonElement root = parseArray(json);
        if (root == null || replacements == null) {
            return null;
        }
        int expected = textNodes(json).size();
        if (expected == 0 || replacements.size() != expected) {
            return null;
        }
        int[] cursor = {0};
        replaceTextNodes(root, replacements, cursor);
        return cursor[0] == replacements.size() ? root.toString() : null;
    }

    public static String encodeEditorText(List<String> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }
        List<String> encoded = new ArrayList<>(nodes.size());
        for (String node : nodes) {
            encoded.add(escape(node == null ? "" : node));
        }
        return String.join("\n", encoded);
    }

    public static List<String> decodeEditorText(String text, int expectedNodes) {
        if (text == null || expectedNodes <= 0) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lines.length != expectedNodes) {
            return List.of();
        }
        List<String> decoded = new ArrayList<>(lines.length);
        for (String line : lines) {
            String value = unescape(line);
            if (value == null) {
                return List.of();
            }
            decoded.add(value);
        }
        return List.copyOf(decoded);
    }

    public static String displayText(String json) {
        List<Component> components = components(json);
        if (components.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>(components.size());
        for (Component component : components) {
            lines.add(component.getString());
        }
        return String.join("\n", lines);
    }

    public static List<Component> components(String json) {
        JsonElement root = parseArray(json);
        if (root == null) {
            return List.of();
        }
        List<Component> components = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            try {
                Component component = ComponentJsonCompat.fromJson(element);
                if (component == null) {
                    return List.of();
                }
                components.add(component);
            } catch (Exception ignored) {
                return List.of();
            }
        }
        return List.copyOf(components);
    }

    private static JsonElement parseArray(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(json.trim());
            return root.isJsonArray() ? root : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void collectTextNodes(JsonElement element, List<String> values) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectTextNodes(child, values);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement text = object.get("text");
        if (text != null && text.isJsonPrimitive() && text.getAsJsonPrimitive().isString()) {
            values.add(text.getAsString());
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!"text".equals(entry.getKey())) {
                collectTextNodes(entry.getValue(), values);
            }
        }
    }

    private static void replaceTextNodes(JsonElement element, List<String> replacements, int[] cursor) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                replaceTextNodes(child, replacements, cursor);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement text = object.get("text");
        if (text != null && text.isJsonPrimitive() && text.getAsJsonPrimitive().isString()) {
            object.addProperty("text", replacements.get(cursor[0]++));
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!"text".equals(entry.getKey())) {
                replaceTextNodes(entry.getValue(), replacements, cursor);
            }
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (!escaped) {
                if (current == '\\') {
                    escaped = true;
                } else {
                    result.append(current);
                }
                continue;
            }
            escaped = false;
            if (current == 'n') {
                result.append('\n');
            } else if (current == 'r') {
                result.append('\r');
            } else if (current == '\\') {
                result.append('\\');
            } else {
                return null;
            }
        }
        return escaped ? null : result.toString();
    }
}
