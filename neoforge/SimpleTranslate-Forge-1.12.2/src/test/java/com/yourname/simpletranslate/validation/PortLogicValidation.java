package com.yourname.simpletranslate.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.yourname.simpletranslate.api.TranslationRequest;
import com.yourname.simpletranslate.api.TranslationResult;
import com.yourname.simpletranslate.chat.ChatTranslationController;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.TranslationEngine;
import com.yourname.simpletranslate.transport.TranslationManager;
import com.yourname.simpletranslate.gui.SettingsButtonValidation;
import com.yourname.simpletranslate.gui.GuiLayoutProgramRendererValidation;
import com.yourname.simpletranslate.translation.TranslationQueueValidation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Build-only transport fixture; it never starts Minecraft. */
public final class PortLogicValidation {
    private PortLogicValidation() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("simple-translate-1122-validation-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new EchoComponentHandler());
        server.start();
        TranslationEngine engine = null;
        try {
            ModConfig.init(root.resolve("modern").toFile().toPath());
            SettingsButtonValidation.run();
            GuiLayoutProgramRendererValidation.run();
            TranslationQueueValidation.run();
            validateChatDeletionIds();
            ModConfig.API_FORMAT.set(ModConfig.ApiFormat.DEEPSEEK_CHAT);
            engine = new TranslationEngine(new File(root.toFile(), "simple_translate.properties"));

            engine.updateConfiguration("https://api.deepseek.com", "fixture-key", "deepseek-v4-flash",
                    "auto", "zh_cn");
            require("https://api.deepseek.com/chat/completions".equals(engine.getEndpoint()),
                    "DeepSeek base URL was not completed");

            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
            engine.updateConfiguration(endpoint, "fixture-key", "deepseek-v4-flash", "auto", "zh_cn");
            require(engine.verifyApiAccess().get(15, TimeUnit.SECONDS).isAvailable(),
                    "normal Component-JSON API probe failed");

            TranslationManager manager = new TranslationManager(engine);
            String managerResponse = manager.translateComponentJson(
                    "[{\"text\":\"First\"},{\"text\":\"Second\"}]", "book.pages.direct", 1,
                    "auto", "zh_cn", "two ordered book pages").get(15, TimeUnit.SECONDS);
            require(managerResponse != null
                            && new JsonParser().parse(managerResponse).getAsJsonArray().size() == 2,
                    "TranslationManager nested the top-level Component array");

            String fencedProviderText = engine.translateRawComponentDocument(
                    "[{\"text\":\"force-fenced\"}]", "chat.auto.direct", 1,
                    "auto", "zh_cn", "{}", Collections.<TranslationRequest.Term>emptyList())
                    .get(15, TimeUnit.SECONDS);
            require(fencedProviderText != null && fencedProviderText.startsWith("```json"),
                    "raw provider path consumed structural validation owned by the passthrough pipeline");

            String hover = "{\"text\":\"Visible\",\"hoverEvent\":{\"action\":\"show_text\",\"value\":{\"text\":\"Hidden\"}}}";
            TranslationRequest valid = new TranslationRequest("chat.fixture",
                    Arrays.asList(hover, "{\"text\":\"Second\"}"),
                    Collections.<TranslationRequest.Term>emptyList(), 1, "auto", "zh_cn", "fixture");
            TranslationResult translated = engine.translate(valid).get(15, TimeUnit.SECONDS);
            require(translated instanceof TranslationResult.Success, "valid Component array was rejected");
            JsonArray accepted = new JsonParser().parse(
                    ((TranslationResult.Success) translated).payload()).getAsJsonArray();
            require(accepted.size() == 2, "accepted array count changed");
            require(accepted.get(0).getAsJsonObject().has("hoverEvent"),
                    "hidden hover payload was not reattached");

            exerciseFormat(engine, endpoint, ModConfig.ApiFormat.OPENAI_CHAT_COMPAT, "gpt-4o-mini");
            exerciseFormat(engine, "http://127.0.0.1:" + server.getAddress().getPort(),
                    ModConfig.ApiFormat.OPENAI_RESPONSES, "gpt-5-mini");
            exerciseFormat(engine, "http://127.0.0.1:" + server.getAddress().getPort(),
                    ModConfig.ApiFormat.ANTHROPIC_MESSAGES, "claude-fixture");
            exerciseFormat(engine, "http://127.0.0.1:" + server.getAddress().getPort(),
                    ModConfig.ApiFormat.GEMINI_GENERATE_CONTENT, "gemini-flash-fixture");
            exerciseFormat(engine, "http://127.0.0.1:" + server.getAddress().getPort(),
                    ModConfig.ApiFormat.LOCAL_OLLAMA, "qwen-fixture");

            ModConfig.API_FORMAT.set(ModConfig.ApiFormat.DEEPSEEK_CHAT);
            engine.updateConfiguration(endpoint, "fixture-key", "deepseek-v4-flash", "auto", "zh_cn");

            TranslationRequest mismatch = new TranslationRequest("chat.fixture.mismatch",
                    Collections.singletonList("{\"text\":\"force-mismatch\"}"), "auto", "zh_cn");
            TranslationResult rejected = engine.translate(mismatch).get(20, TimeUnit.SECONDS);
            require(rejected instanceof TranslationResult.Failed,
                    "mismatched top-level response count was accepted");
            System.out.println("PORT_LOGIC_VALIDATION_OK");
        } finally {
            if (engine != null) engine.shutdown();
            server.stop(0);
            deleteTree(root.toFile());
        }
    }

    /** Build-only regression for mod-supplied replacement IDs and stale chat requests. */
    private static void validateChatDeletionIds() throws Exception {
        ChatTranslationController.clearRuntimeState();
        require(ChatTranslationController.retain(
                        new net.minecraft.util.text.TextComponentString("first"), 31415) == 31415,
                "AUTO chat discarded the caller deletion ID");
        require(ChatTranslationController.retain(
                        new net.minecraft.util.text.TextComponentString("newer"), 31415) == 31415,
                "AUTO chat changed a reused caller deletion ID");
        require(privateMapSize("PENDING") == 1 && privateMapSize("PENDING_BY_DISPLAY_ID") == 1,
                "AUTO chat kept a stale request for a reused deletion ID");

        int generatedA = ChatTranslationController.retain(
                new net.minecraft.util.text.TextComponentString("generated-a"), 0);
        int generatedB = ChatTranslationController.retain(
                new net.minecraft.util.text.TextComponentString("generated-b"), 0);
        require(generatedA != 0 && generatedB != 0 && generatedA != generatedB,
                "AUTO chat generated an invalid or duplicate internal deletion ID");

        ChatTranslationController.clearRuntimeState();
        ChatTranslationController.ButtonPresentation first = ChatTranslationController.attachButton(
                new net.minecraft.util.text.TextComponentString("button-first"), 27182);
        ChatTranslationController.ButtonPresentation newer = ChatTranslationController.attachButton(
                new net.minecraft.util.text.TextComponentString("button-newer"), 27182);
        require(first.id == 27182 && newer.id == 27182 && privateMapSize("BUTTONS") == 1,
                "button chat did not replace the previous entry for a caller deletion ID");
        ChatTranslationController.ButtonPresentation generated = ChatTranslationController.attachButton(
                new net.minecraft.util.text.TextComponentString("button-generated"), 0);
        require(generated.id != 0 && generated.id != 27182,
                "button chat generated an invalid internal deletion ID");

        ChatTranslationController.clearRuntimeState();
        ChatTranslationController.retain(
                new net.minecraft.util.text.TextComponentString("replace-me"), 99);
        ChatTranslationController.attachButton(
                new net.minecraft.util.text.TextComponentString("replace-button"), 99);
        ChatTranslationController.invalidateExternalReplacement(99);
        require(privateMapSize("PENDING_BY_DISPLAY_ID") == 0
                        && privateMapSize("PENDING") == 0
                        && privateMapSize("BUTTONS") == 0,
                "external replacement did not invalidate older chat work");
        ChatTranslationController.clearRuntimeState();
    }

    private static int privateMapSize(String fieldName) throws Exception {
        Field field = ChatTranslationController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(null)).size();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void exerciseFormat(TranslationEngine engine, String endpoint,
                                       ModConfig.ApiFormat format, String model) throws Exception {
        ModConfig.API_FORMAT.set(format);
        engine.updateConfiguration(endpoint, "fixture-key", model, "auto", "zh_cn");
        TranslationRequest request = new TranslationRequest("chat.fixture." + format.name().toLowerCase(),
                Collections.singletonList("{\"text\":\"Provider " + format.name() + "\"}"),
                "auto", "zh_cn");
        TranslationResult result = engine.translate(request).get(15, TimeUnit.SECONDS);
        require(result instanceof TranslationResult.Success, format + " provider fixture failed");
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) file.deleteOnExit();
    }

    private static final class EchoComponentHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) {
            try {
                String path = exchange.getRequestURI().getPath();
                boolean anthropic = path.endsWith("/messages");
                boolean gemini = path.contains(":generateContent");
                boolean ollama = path.endsWith("/chat/completions")
                        && exchange.getRequestHeaders().getFirst("Authorization") == null;
                if (anthropic) {
                    require("fixture-key".equals(exchange.getRequestHeaders().getFirst("x-api-key")),
                            "Anthropic API key missing");
                } else if (gemini) {
                    require(exchange.getRequestURI().getQuery() != null
                                    && exchange.getRequestURI().getQuery().contains("key=fixture-key"),
                            "Gemini query API key missing");
                } else if (!ollama) {
                    require("Bearer fixture-key".equals(exchange.getRequestHeaders().getFirst("Authorization")),
                            "Bearer authorization missing");
                }
                JsonObject request = new JsonParser().parse(read(exchange.getRequestBody())).getAsJsonObject();
                String document;
                if (path.endsWith("/responses")) {
                    document = request.getAsJsonArray("input").get(0).getAsJsonObject()
                            .getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
                } else if (gemini) {
                    document = request.getAsJsonArray("contents").get(0).getAsJsonObject()
                            .getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString();
                } else {
                    JsonArray messages = request.getAsJsonArray("messages");
                    document = messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
                }
                JsonElement parsed = new JsonParser().parse(document);
                require(parsed.isJsonArray(), "wire request was not a top-level Component array");
                require(parsed.getAsJsonArray().size() == 0
                                || !parsed.getAsJsonArray().get(0).isJsonArray(),
                        "wire request nested the top-level Component array");
                require(!document.contains("Hidden"), "ordinary request leaked hidden hover text");
                String content = document.contains("force-mismatch") ? "[]" : document;
                if (document.contains("force-fenced")) content = "```json\n" + content + "\n```";
                JsonObject response = new JsonObject();
                if (path.endsWith("/responses")) {
                    response.addProperty("output_text", content);
                } else if (anthropic) {
                    JsonObject part = new JsonObject();
                    part.addProperty("text", content);
                    JsonArray parts = new JsonArray();
                    parts.add(part);
                    response.add("content", parts);
                } else if (gemini) {
                    JsonObject part = new JsonObject();
                    part.addProperty("text", content);
                    JsonArray parts = new JsonArray();
                    parts.add(part);
                    JsonObject candidateContent = new JsonObject();
                    candidateContent.add("parts", parts);
                    JsonObject candidate = new JsonObject();
                    candidate.add("content", candidateContent);
                    JsonArray candidates = new JsonArray();
                    candidates.add(candidate);
                    response.add("candidates", candidates);
                } else {
                    JsonObject message = new JsonObject();
                    message.addProperty("content", content);
                    JsonObject choice = new JsonObject();
                    choice.add("message", message);
                    JsonArray choices = new JsonArray();
                    choices.add(choice);
                    response.add("choices", choices);
                }
                JsonObject usage = new JsonObject();
                usage.addProperty("prompt_tokens", 7);
                usage.addProperty("completion_tokens", 5);
                usage.addProperty("total_tokens", 12);
                response.add("usage", usage);
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream output = exchange.getResponseBody();
                output.write(bytes);
                output.close();
            } catch (Throwable error) {
                try {
                    byte[] bytes = error.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(500, bytes.length);
                    OutputStream output = exchange.getResponseBody();
                    output.write(bytes);
                    output.close();
                } catch (Exception ignored) { }
            } finally {
                exchange.close();
            }
        }

        private static String read(InputStream input) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
