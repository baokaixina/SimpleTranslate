package com.yourname.simpletranslate.legacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Java-8-compatible runtime shared by the legacy Fabric rendering hook. */
public final class LegacyFabricRuntime {
    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 256; }
    };
    private static String endpoint = "https://api.deepseek.com/chat/completions";
    private static String apiKey = "";
    private static String model = "deepseek-chat";

    private LegacyFabricRuntime() { }

    public static synchronized void initialize() {
        File config = new File(FabricLoader.getInstance().getConfigDir().toFile(), "simple_translate.properties");
        load(config);
        saveTemplate(config);
    }

    public static synchronized String translate(String text) {
        if (text == null || text.trim().isEmpty() || apiKey.trim().isEmpty()) return text;
        if (CACHE.containsKey(text)) return CACHE.get(text);
        HttpURLConnection connection = null;
        try {
            JsonObject request = new JsonObject();
            request.addProperty("model", model);
            request.addProperty("stream", false);
            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", "Translate Minecraft text to Chinese. Return only the translation and preserve formatting markers.");
            messages.add(system);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", text);
            messages.add(user);
            request.add("messages", messages);
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream output = connection.getOutputStream();
            output.write(body);
            output.close();
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) return text;
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            reader.close();
            JsonObject json = new JsonParser().parse(response.toString()).getAsJsonObject();
            String translated = json.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString().trim();
            if (!translated.isEmpty()) CACHE.put(text, translated);
            return translated.isEmpty() ? text : translated;
        } catch (Exception ignored) {
            return text;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void load(File file) {
        if (!file.exists()) return;
        java.util.Properties properties = new java.util.Properties();
        try {
            InputStream input = new FileInputStream(file);
            properties.load(input);
            input.close();
            endpoint = properties.getProperty("endpoint", endpoint);
            apiKey = properties.getProperty("apiKey", apiKey);
            model = properties.getProperty("model", model);
        } catch (Exception ignored) { }
    }

    private static void saveTemplate(File file) {
        if (file.exists()) return;
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try {
            java.util.Properties properties = new java.util.Properties();
            properties.setProperty("endpoint", endpoint);
            properties.setProperty("apiKey", apiKey);
            properties.setProperty("model", model);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            properties.store(writer, "SimpleTranslate 1.12.2 Legacy Fabric configuration");
            writer.close();
        } catch (Exception ignored) { }
    }
}
