package com.yourname.simpletranslate.chat;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.translation.TranslationManager;
import com.yourname.simpletranslate.util.ChatMessageIdentity;
import com.yourname.simpletranslate.util.ChatTranslationRuntime;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AUTO-mode chat translation: cached fast path, async translation, and a
 * bounded automatic retry for displayed lines whose first attempt failed
 * (previously they stayed untranslated forever).
 */
public final class ChatAutoTranslator {
    private static final int MAX_AUTO_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = { 8000L, 16000L, 32000L };
    private static final ScheduledExecutorService RETRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "SimpleTranslate-ChatRetry");
                thread.setDaemon(true);
                return thread;
            });

    private final ChatTranslationController controller;

    ChatAutoTranslator(ChatTranslationController controller) {
        this.controller = controller;
    }

    void handleIncomingMessage(Component message, String plainText, TranslationManager manager) {
        if (tryApplyCachedTranslation(message, plainText)) {
            return;
        }
        if (ModConfig.CHAT_CONTEXT_ENABLED.get()) {
            ChatContextBatchTranslator.enqueueVisibleUntranslated(this, controller);
            return;
        }
        handleAutoMode(message, plainText, 0);
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
        if (!isUsableAutoTranslation(translatedComponent, plainText)) {
            return false;
        }
        applyAutoTranslationIfCurrent(identity, translatedComponent, message, pendingKey);
        return true;
    }

    // ------------------------------------------------------------------
    // Async translation with bounded retry
    // ------------------------------------------------------------------

    private void handleAutoMode(Component message, String plainText, int attempt) {
        if (ChatBlacklistGuard.hasBlacklistedSourceText(message, plainText)) {
            return;
        }

        if (ModConfig.CHAT_CONTEXT_ENABLED.get()) {
            ChatContextBatchTranslator.enqueueVisibleUntranslated(this, controller);
            return;
        }

        ChatMessageIdentity identity = controller.replacer().captureIdentity(message, plainText);
        String pendingKey = ChatMessageStore.messageKey(identity, plainText);
        if (!ChatMessageStore.markPending(pendingKey)) {
            return;
        }

        translateAutoDirect(message, plainText, identity, pendingKey, attempt);
    }

    private void translateAutoDirect(Component message,
                                     String plainText,
                                     ChatMessageIdentity identity,
                                     String pendingKey,
                                     int attempt) {
        ChatTranslationRuntime.translateAutoMessage(message).thenAccept(translatedComponent -> {
            if (!isUsableAutoTranslation(translatedComponent, plainText)
                    || ChatBlacklistGuard.hasBlacklistedSourceText(message, plainText)) {
                ChatMessageStore.removePending(pendingKey);
                boolean failed = translatedComponent == null || translatedComponent.getString().equals(plainText);
                if (failed && !ChatBlacklistGuard.hasBlacklistedSourceText(message, plainText)) {
                    scheduleRetry(identity, message, plainText, attempt);
                }
                return;
            }
            applyAutoTranslationIfCurrent(identity, translatedComponent, message, pendingKey);
        });
    }

    /**
     * Re-attempts a failed displayed line after the lane cooldown, up to
     * {@link #MAX_AUTO_RETRIES} times, as long as the line is still visible
     * and AUTO mode is still active.
     */
    private void scheduleRetry(ChatMessageIdentity identity, Component message, String plainText, int attempt) {
        if (attempt >= MAX_AUTO_RETRIES) {
            return;
        }
        long delay = RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)];
        RETRY_EXECUTOR.schedule(() -> ChatMessageReplacer.runOnClientThread(() -> {
            if (!ModConfig.CHAT_ENABLED.get()
                    || ModConfig.CHAT_MODE.get() != ModConfig.TranslationMode.AUTO
                    || !SimpleTranslateMod.isRuntimeRevisionCurrent(identity.runtimeRevision)
                    || !controller.replacer().isIdentityCurrent(identity)) {
                return;
            }
            TranslationManager manager = SimpleTranslateMod.getTranslationManager();
            if (manager == null || !manager.isReady()) {
                return;
            }
            SimpleTranslateMod.getLogger().debug("Chat auto retry attempt={} source={}", attempt + 1,
                    plainText.length() > 60 ? plainText.substring(0, 57) + "..." : plainText);
            if (tryApplyCachedTranslation(message, plainText)) {
                return;
            }
            handleAutoMode(message, plainText, attempt + 1);
        }), delay, TimeUnit.MILLISECONDS);
    }

    void applyBatchTranslation(ChatMessageIdentity identity,
                               Component translatedComponent,
                               Component originalComponent,
                               String pendingKey) {
        applyAutoTranslationIfCurrent(identity, translatedComponent, originalComponent, pendingKey);
    }

    void scheduleBatchRetry(ChatContextBatchTranslator.PendingEntry entry) {
        if (entry == null || entry.attempts < ChatContextBatchTranslator.MAX_BATCH_RETRIES) {
            return;
        }
        synchronized (ChatContextBatchTranslator.class) {
            ChatContextBatchTranslator.removeEntry(entry);
        }
        ChatMessageStore.removePending(entry.pendingKey);
        fallbackBatchToDirect(entry);
    }

    void fallbackBatchToDirect(ChatContextBatchTranslator.PendingEntry entry) {
        if (entry == null || ChatBlacklistGuard.hasBlacklistedSourceText(entry.message, entry.plainText)) {
            return;
        }
        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            return;
        }
        if (!controller.replacer().isIdentityCurrent(entry.identity)) {
            return;
        }
        if (!ChatMessageStore.markPending(entry.pendingKey)) {
            return;
        }
        int attempt = Math.max(0, entry.attempts - 1);
        translateAutoDirect(entry.message, entry.plainText, entry.identity, entry.pendingKey, attempt);
    }

    private boolean isUsableAutoTranslation(Component translatedComponent, String plainText) {
        return translatedComponent != null
                && !translatedComponent.getString().isBlank()
                && !translatedComponent.getString().equals(plainText)
                && !ChatBlacklistGuard.containsBlacklistedText(translatedComponent.getString());
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
        if (!ModConfig.CHAT_ENABLED.get() || ModConfig.CHAT_MODE.get() != ModConfig.TranslationMode.AUTO) {
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
