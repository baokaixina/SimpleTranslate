package com.yourname.simpletranslate.feature.chat;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.transport.TranslationManager;
import com.yourname.simpletranslate.feature.chat.ChatMessageIdentity;
import com.yourname.simpletranslate.feature.chat.ChatTranslationRuntime;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AUTO-mode chat translation: cached fast path and asynchronous translation.
 * Transport retries belong exclusively to TranslationRequestQueue so a single
 * empty model response cannot multiply into another layer of delayed requests.
 *
 * <p>When context is enabled, incoming messages are collected into a short
 * window (300ms) before translation. Multiple messages arriving within the
 * window are sent as one JSON component array so the model sees the full
 * multi-line server passage while Minecraft component structure is preserved.</p>
 */
public final class ChatAutoTranslator {
    private static final long COLLECT_WINDOW_MS = 300;
    private static final int MAX_COLLECT_BATCH = 12;
    private static final int MAX_COLLECT_JSON_CHARS = 8_000;

    private final ChatTranslationController controller;
    private final List<CollectEntry> collectBuffer = new ArrayList<>();
    private long collectDeadline = 0L;

    private record CollectEntry(Component message, String plainText,
                                 ChatMessageIdentity identity, String pendingKey) {
    }

    ChatAutoTranslator(ChatTranslationController controller) {
        this.controller = controller;
    }

    void handleIncomingMessage(Component message, String plainText, TranslationManager manager) {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return;
        }
        if (!ChatAutoTranslationFilter.shouldAutoTranslate(plainText)) {
            return;
        }
        if (tryApplyCachedTranslation(message, plainText)) {
            return;
        }
        // AUTO chat now enters the component-list facade. Chat surfaces route
        // through JSON passthrough there, so no styled prose position guessing
        // is needed.
        handleAutoMode(message, plainText);
    }

    // ------------------------------------------------------------------
    // Cached fast paths
    // ------------------------------------------------------------------

    private boolean tryApplyCachedTranslation(Component message, String plainText) {
        if (ChatBlacklistGuard.hasBlacklistedSourceText(message, plainText)) {
            return false;
        }
        ChatMessageIdentity identity = controller.replacer().captureIdentity(message, plainText);
        return tryApplyCachedAutoMessage(identity, message, plainText, null);
    }

    boolean tryApplyCachedAutoMessage(ChatMessageIdentity identity,
                                      Component message,
                                      String plainText,
                                      String pendingKey) {
        if (identity == null || ChatBlacklistGuard.hasBlacklistedSourceText(message, plainText)) {
            return false;
        }
        Component translatedComponent = ChatTranslationRuntime.buildCachedAutoMessageTranslation(message);
        if (translatedComponent == null) {
            return false;
        }
        applyAutoTranslationIfCurrent(identity, translatedComponent, message, pendingKey);
        return true;
    }

    // ------------------------------------------------------------------
    // Async translation
    // ------------------------------------------------------------------

    private void handleAutoMode(Component message, String plainText) {
        if (!ModConfig.GLOBAL_ENABLED.get()
                || ChatBlacklistGuard.hasBlacklistedSourceText(message, plainText)) {
            return;
        }
        if (!ChatAutoTranslationFilter.shouldAutoTranslate(plainText)) {
            return;
        }

        ChatMessageIdentity identity = controller.replacer().captureIdentity(message, plainText);
        String pendingKey = ChatMessageStore.messageKey(identity, plainText);
        if (!ChatMessageStore.markPending(pendingKey)) {
            return;
        }

        if (ModConfig.CHAT_CONTEXT_ENABLED.get()) {
            // Collect into a short window so multi-line server passages
            // (announcements, lists, instructions) are sent as one JSON
            // component array. The tick() method flushes the buffer when the
            // deadline expires.
            List<CollectEntry> readyBatch = null;
            CollectEntry entry = new CollectEntry(message, plainText, identity, pendingKey);
            synchronized (collectBuffer) {
                if (!collectBuffer.isEmpty() && shouldFlushBeforeAddingLocked(entry)) {
                    readyBatch = drainCollectBufferLocked();
                }
                if (collectBuffer.isEmpty()) {
                    collectDeadline = System.currentTimeMillis() + COLLECT_WINDOW_MS;
                }
                collectBuffer.add(entry);
                if (collectBuffer.size() >= MAX_COLLECT_BATCH
                        || collectBufferJsonCharsLocked() >= MAX_COLLECT_JSON_CHARS) {
                    readyBatch = mergeBatches(readyBatch, drainCollectBufferLocked());
                }
            }
            flushCollectedBatch(readyBatch);
        } else {
            translateAutoDirect(message, plainText, identity, pendingKey);
        }
    }

    private void translateAutoDirect(Component message,
                                     String plainText,
                                     ChatMessageIdentity identity,
                                     String pendingKey) {
        ChatTranslationRuntime.translateAutoMessage(message).thenAccept(translatedComponent -> {
            if (translatedComponent == null
                    || ChatBlacklistGuard.hasBlacklistedSourceText(message, plainText)) {
                ChatMessageStore.removePending(pendingKey);
                return;
            }
            applyAutoTranslationIfCurrent(identity, translatedComponent, message, pendingKey);
        });
    }

    private void translateAutoDirectWithContext(Component message,
                                                String plainText,
                                                ChatMessageIdentity identity,
                                                String pendingKey) {
        String context = ChatBatchContextBuilder.buildDirectContext(
                controller, plainText, ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get());
        ChatTranslationRuntime.translateAutoMessageWithContext(message, context).thenAccept(translatedComponent -> {
            if (translatedComponent == null
                    || ChatBlacklistGuard.hasBlacklistedSourceText(message, plainText)) {
                ChatMessageStore.removePending(pendingKey);
                return;
            }
            applyAutoTranslationIfCurrent(identity, translatedComponent, message, pendingKey);
        });
    }

    // ------------------------------------------------------------------
    // Collect window — multi-line passage translation
    // ------------------------------------------------------------------

    /**
     * Called every client tick by ChatTranslationController. Flushes the
     * collect buffer when the deadline expires, sending accumulated messages
     * as one ordered Component JSON array.
     */
    void tick() {
        if (collectBuffer.isEmpty()) {
            return;
        }
        if (collectDeadline > 0 && System.currentTimeMillis() < collectDeadline) {
            return;
        }
        flushCollectBuffer();
    }

    void clearRuntimeState() {
        synchronized (collectBuffer) {
            collectBuffer.clear();
            collectDeadline = 0L;
        }
    }

    private void flushCollectBuffer() {
        List<CollectEntry> batch;
        synchronized (collectBuffer) {
            if (collectBuffer.isEmpty()) {
                return;
            }
            batch = drainCollectBufferLocked();
        }
        flushCollectedBatch(batch);
    }

    private void flushCollectedBatch(List<CollectEntry> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        if (batch.size() == 1) {
            CollectEntry entry = batch.get(0);
            translateAutoDirectWithContext(
                    entry.message(), entry.plainText(), entry.identity(), entry.pendingKey());
            return;
        }

        // Multi-message batch: build context excluding the batch's own messages,
        // then send all messages as one JSON component array.
        Set<String> excludeTexts = new HashSet<>();
        List<Component> messages = new ArrayList<>(batch.size());
        for (CollectEntry entry : batch) {
            messages.add(entry.message());
            excludeTexts.add(entry.plainText());
        }
        // Use the first message's plain text as the "current" for context
        // building (it determines what prior history to exclude).
        String context = ChatBatchContextBuilder.buildDirectContext(
                controller, batch.get(0).plainText(),
                ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get(), excludeTexts);

        ChatTranslationRuntime.translateAutoMessagesWithContext(messages, context)
                .thenAccept(translatedComponents -> {
                    if (translatedComponents == null || translatedComponents.size() != batch.size()) {
                        for (CollectEntry entry : batch) {
                            ChatMessageStore.markFailedCooldown(entry.pendingKey());
                        }
                        return;
                    }
                    for (int i = 0; i < batch.size(); i++) {
                        CollectEntry entry = batch.get(i);
                        Component translated = translatedComponents.get(i);
                        if (translated == null
                                || ChatBlacklistGuard.hasBlacklistedSourceText(
                                        entry.message(), entry.plainText())) {
                            ChatMessageStore.removePending(entry.pendingKey());
                            continue;
                        }
                        applyAutoTranslationIfCurrent(
                                entry.identity(), translated, entry.message(), entry.pendingKey());
                    }
                });
    }

    private boolean shouldFlushBeforeAddingLocked(CollectEntry entry) {
        if (collectBuffer.size() >= MAX_COLLECT_BATCH) {
            return true;
        }
        int chars = collectBufferJsonCharsLocked();
        chars += componentJsonLength(entry.message());
        return chars >= MAX_COLLECT_JSON_CHARS;
    }

    private List<CollectEntry> drainCollectBufferLocked() {
        if (collectBuffer.isEmpty()) {
            return List.of();
        }
        List<CollectEntry> batch = new ArrayList<>(collectBuffer);
        collectBuffer.clear();
        collectDeadline = 0L;
        return batch;
    }

    private int collectBufferJsonCharsLocked() {
        int chars = 0;
        for (CollectEntry entry : collectBuffer) {
            chars += componentJsonLength(entry.message());
        }
        return chars;
    }

    private static int componentJsonLength(Component component) {
        String json = JsonPassthroughPipeline.serializeComponents(List.of(component));
        return json == null ? 0 : json.length();
    }

    private static List<CollectEntry> mergeBatches(List<CollectEntry> first, List<CollectEntry> second) {
        if (first == null || first.isEmpty()) {
            return second;
        }
        if (second == null || second.isEmpty()) {
            return first;
        }
        List<CollectEntry> merged = new ArrayList<>(first.size() + second.size());
        merged.addAll(first);
        merged.addAll(second);
        return merged;
    }

    // ------------------------------------------------------------------
    // Application
    // ------------------------------------------------------------------

    private void applyAutoTranslationIfCurrent(ChatMessageIdentity identity,
                                               Component translatedComponent,
                                               Component originalComponent,
                                               String pendingKey) {
        String storeKey = pendingKey == null && identity != null ? identity.originalText : pendingKey;
        ChatMessageReplacer.runOnClientThread(() -> {
            if (!canApplyAutoTranslation(identity, translatedComponent)) {
                if (storeKey != null) {
                    ChatMessageStore.removePending(storeKey);
                }
                return;
            }

            controller.markProcessed(translatedComponent);
            if (controller.replacer().replaceByIdentity(identity, translatedComponent)) {
                controller.autoPeerMap().put(translatedComponent, originalComponent);
                if (storeKey != null) {
                    ChatMessageStore.markTranslated(storeKey, translatedComponent.getString());
                }
            } else if (storeKey != null) {
                ChatMessageStore.removePending(storeKey);
            }
        });
    }

    private boolean canApplyAutoTranslation(ChatMessageIdentity identity, Component translatedComponent) {
        if (identity == null || translatedComponent == null) {
            return false;
        }
        if (!SimpleTranslateMod.isRuntimeRevisionCurrent(identity.runtimeRevision)) {
            return false;
        }
        if (!ModConfig.GLOBAL_ENABLED.get()
                || !ModConfig.CHAT_ENABLED.get()
                || ModConfig.CHAT_MODE.get() != ModConfig.TranslationMode.AUTO) {
            return false;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.CHAT)) {
            return false;
        }
        if (ChatBlacklistGuard.hasBlacklistedSourceText(identity.originalComponent, identity.originalText)
                || ChatBlacklistGuard.containsBlacklistedText(translatedComponent.getString())) {
            return false;
        }
        return controller.replacer().isIdentityCurrent(identity);
    }

}
