package com.yourname.simpletranslate.chat;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.transport.TranslationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.util.text.TextComponentTranslation;
import com.yourname.simpletranslate.mixin.GuiChatAccessor;
import com.yourname.simpletranslate.core.TranslationTextDetector;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ctrl+Enter outgoing translation; ordinary Enter remains untouched. */
public final class OutgoingChatTranslator {
    private static final long PENDING_TIMEOUT_MS = 45000L;
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9'’\\-]*");
    private static final Set<String> ENGLISH_MARKERS = new HashSet<String>(Arrays.asList(
            "a", "an", "and", "are", "am", "bad", "boss", "buy", "can", "come", "diamond",
            "diamonds", "did", "do", "does", "emerald", "find", "for", "found", "go", "gold",
            "good", "guild", "has", "have", "he", "hello", "help", "hi", "home", "how", "i",
            "in", "iron", "is", "it", "look", "market", "mine", "mining", "my", "need", "no",
            "not", "of", "ok", "okay", "on", "or", "party", "please", "price", "quest",
            "sell", "server", "she", "shop", "spawn", "sword", "thanks", "thank", "that",
            "the", "they", "this", "to", "trade", "want", "warp", "was", "we", "were",
            "what", "when", "where", "who", "why", "will", "with", "yes", "you", "your"));
    private static boolean pending;
    private static long pendingSince;
    private static long requestSequence;
    private static long activeRequestId;
    private static String lastFailedMessage = "";

    private OutgoingChatTranslator() { }

    public static synchronized boolean tryTranslate(final GuiChat screen, final String raw) {
        final String original = raw == null ? "" : raw.trim();
        if (!ModConfig.CHAT_OUTGOING_ENABLED.get() || original.isEmpty() || original.startsWith("/")) return false;
        if (original.equals(lastFailedMessage)) { lastFailedMessage = ""; return false; }
        final TranslationManager manager = SimpleTranslateForge1122.getTranslationManager();
        if (!ModConfig.GLOBAL_ENABLED.get()) { rememberFailure(original); status("message.simple_translate.chat_outgoing.disabled"); return true; }
        if (manager == null || !manager.isReady()) { rememberFailure(original); status("message.simple_translate.chat_outgoing.not_ready"); return true; }
        if (hasPending()) { status("message.simple_translate.chat_outgoing.pending"); return true; }
        final String localLanguage = localLanguage();
        final String serverLanguage = serverLanguage();
        if (serverLanguage.equals(localLanguage)) return false;
        final String targetLanguage = chooseTargetLanguage(original, localLanguage, serverLanguage);
        if (!TranslationTextDetector.hasLanguageSignal(original, "auto")) return false;

        final long requestId = beginRequest();
        final long revision = SimpleTranslateForge1122.getRuntimeRevision();
        status("message.simple_translate.chat_outgoing.translating",
                TranslationTextDetector.displayLanguageName(targetLanguage));
        try {
            manager.translateRaw(original, "chat.outgoing", "chat-outgoing", "auto", targetLanguage)
                .whenComplete(new java.util.function.BiConsumer<String, Throwable>() {
                    @Override public void accept(final String translated, final Throwable error) {
                        Minecraft minecraft = Minecraft.getMinecraft();
                        if (minecraft == null) { finish(requestId); return; }
                        minecraft.addScheduledTask(new Runnable() {
                            @Override public void run() {
                                if (!finish(requestId)) return;
                                if (minecraft.currentScreen != screen
                                        || !SimpleTranslateForge1122.isRuntimeRevisionCurrent(revision)) return;
                                net.minecraft.client.gui.GuiTextField field=((GuiChatAccessor)(Object)screen).simpletranslate$getInputField();
                                if(field==null||!original.equals(field.getText().trim())){status("message.simple_translate.chat_outgoing.changed");return;}
                                if (error != null || translated == null || translated.trim().isEmpty()
                                        || translated.trim().equals(original)) {
                                    rememberFailure(original);
                                    status("message.simple_translate.chat_outgoing.failed");
                                    return;
                                }
                                lastFailedMessage = "";
                                screen.sendChatMessage(translated.trim());
                                minecraft.displayGuiScreen(null);
                            }
                        });
                    }
                });
        } catch (Throwable error) {
            finish(requestId);
            rememberFailure(original);
            status("message.simple_translate.chat_outgoing.failed");
        }
        return true;
    }

    public static synchronized void clear() {
        requestSequence++;
        pending = false;
        pendingSince = 0L;
        activeRequestId = 0L;
        lastFailedMessage = "";
    }

    private static synchronized long beginRequest() {
        pending = true;
        pendingSince = System.currentTimeMillis();
        activeRequestId = ++requestSequence;
        return activeRequestId;
    }

    private static synchronized boolean finish(long requestId) {
        if (activeRequestId != requestId) return false;
        pending = false;
        pendingSince = 0L;
        activeRequestId = 0L;
        return true;
    }

    private static synchronized boolean hasPending() {
        if (!pending) return false;
        if (System.currentTimeMillis() - pendingSince > PENDING_TIMEOUT_MS) {
            pending = false;
            pendingSince = 0L;
            activeRequestId = 0L;
            return false;
        }
        return true;
    }

    private static String localLanguage() {
        String value = TranslationTextDetector.canonicalLanguageCode(ModConfig.TARGET_LANGUAGE.get());
        return value.isEmpty() || "auto".equals(value) ? "zh_cn" : value;
    }

    private static String serverLanguage() {
        String value = TranslationTextDetector.canonicalLanguageCode(ModConfig.CHAT_OUTGOING_SERVER_LANGUAGE.get());
        return value.isEmpty() || "auto".equals(value) ? "en" : value;
    }

    private static String chooseTargetLanguage(String text, String localLanguage, String serverLanguage) {
        if (TranslationTextDetector.hasLanguageSignal(text, localLanguage)) return serverLanguage;
        if (looksLikeServerLanguageOnly(text, serverLanguage)) return localLanguage;
        return serverLanguage;
    }

    private static boolean looksLikeServerLanguageOnly(String text, String serverLanguage) {
        if ("en".equals(TranslationTextDetector.canonicalLanguageCode(serverLanguage))) {
            if (TranslationTextDetector.containsTranslatableText(text, 1, "en")) return false;
            Matcher matcher = LATIN_WORD.matcher(TranslationTextDetector.normalizeForDetection(text)
                    .toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                if (ENGLISH_MARKERS.contains(matcher.group().replace('’', '\''))) return true;
            }
            return false;
        }
        return !TranslationTextDetector.containsTranslatableText(text, 1, serverLanguage);
    }

    private static synchronized void rememberFailure(String message) {
        lastFailedMessage = message == null ? "" : message;
    }

    private static void status(String key,Object...args){Minecraft mc=Minecraft.getMinecraft();if(mc.player!=null)mc.player.sendStatusMessage(new TextComponentTranslation(key,args),true);}
}
