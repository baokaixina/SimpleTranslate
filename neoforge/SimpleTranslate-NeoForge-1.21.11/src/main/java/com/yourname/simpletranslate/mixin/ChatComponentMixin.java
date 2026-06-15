package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.chat.ChatMessageStore;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalAware;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.translation.TranslationManager;
import com.yourname.simpletranslate.util.BlacklistRefreshAware;
import com.yourname.simpletranslate.util.ButtonMessageData;
import com.yourname.simpletranslate.util.ChatMessageIdentity;
import com.yourname.simpletranslate.util.ChatButtonClickHandler;
import com.yourname.simpletranslate.util.ChatTranslationRuntime;
import com.yourname.simpletranslate.util.HudHistoryChatData;
import com.yourname.simpletranslate.util.HudHistoryChatBridge;
import com.yourname.simpletranslate.util.HudTranslationHistory;
import com.yourname.simpletranslate.util.TextSegmentInfo;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Mixin to intercept and modify chat messages for translation
 * Handles both AUTO and BUTTON modes
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin implements ChatButtonClickHandler, HudHistoryChatBridge, HoldOriginalAware, BlacklistRefreshAware {

    @Shadow
    @Final
    private List<GuiMessage> allMessages;

    @Shadow
    public abstract void rescaleChat();

    // Track which messages we've already processed
    @Unique
    private static final Map<Integer, Boolean> simple_translate$processedMessages = new ConcurrentHashMap<>();

    // Store message data for BUTTON mode
    @Unique
    private static final Map<UUID, ButtonMessageData> simple_translate$buttonMessages = new ConcurrentHashMap<>();

    @Unique
    private static final String simple_translate$chatSegmentSurface = ChatTranslationRuntime.CHAT_SEGMENT_SURFACE;

    @Unique
    private static final String simple_translate$chatContextSurface = ChatTranslationRuntime.CHAT_CONTEXT_SURFACE;

    // Custom click event action identifier
    @Unique
    private static final String TRANSLATE_CLICK_PREFIX = "simple_translate:";

    @Unique
    private static final String HUD_HISTORY_CLICK_PREFIX = "simple_translate:hud_history:";

    @Unique
    private static final int simple_translate$maxHudHistoryChatMessages = 40;

    // Track last translation mode to detect mode changes
    @Unique
    private static ModConfig.TranslationMode simple_translate$lastMode = null;

    // Map from a translated (AUTO-mode) Component back to its original
    @Unique
    private static final Map<Component, Component> simple_translate$autoPeerMap =
            Collections.synchronizedMap(new IdentityHashMap<>());

    // Indices currently swapped to original during a hold, keyed to the translated Component we need to restore
    @Unique
    private final Map<Integer, Component> simple_translate$holdSwappedTranslated = new HashMap<>();

    @Unique
    private Map<String, HudHistoryChatData> simple_translate$hudHistoryChatMessages;

    @Unique
    private long simple_translate$seenBlacklistRevision = -1L;

    /**
     * Inject at the end of addMessage to handle translation
     */
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("TAIL"))
    private void onAddMessage(Component message, net.minecraft.network.chat.MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        simple_translate$syncBlacklistRevision();
        String plainText = message.getString();

        if (simple_translate$isHudHistoryChatMessage(message)) {
            return;
        }

        // Check if chat translation is enabled
        if (!ModConfig.CHAT_ENABLED.get()) {
            return;
        }

        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            return;
        }

        ModConfig.TranslationMode currentMode = ModConfig.CHAT_MODE.get();

        // Detect mode change and clear processed messages cache
        if (simple_translate$lastMode != null && simple_translate$lastMode != currentMode) {
            simple_translate$processedMessages.clear();
        }
        simple_translate$lastMode = currentMode;

        int messageHash = System.identityHashCode(message);
        if (simple_translate$processedMessages.containsKey(messageHash)) {
            return;
        }

        if (simple_translate$hasBlacklistedSourceText(message, plainText)) {
            return;
        }

        if (!ChatMessageStore.containsEnglish(plainText)) {
            return;
        }

        // AUTO mode: skip if already translated in ChatMessageStore
        // BUTTON mode: always show button, let user decide
        if (currentMode == ModConfig.TranslationMode.AUTO) {
            // Check if we have cached translation for segments
            if (simple_translate$tryApplyCachedTranslation(message, plainText)) {
                return;
            }
            simple_translate$handleAutoMode(message, plainText, manager);
        } else if (currentMode == ModConfig.TranslationMode.BUTTON) {
            simple_translate$handleButtonMode(message, plainText);
        }
    }

    /**
     * Try to apply cached translation if all segments are cached
     * @return true if cached translation was applied, false otherwise
     */
    @Unique
    private boolean simple_translate$tryApplyCachedTranslation(Component message, String plainText) {
        if (simple_translate$hasBlacklistedSourceText(message, plainText)) {
            return false;
        }

        if (!simple_translate$hasChatBodyPrefix(plainText)) {
            return simple_translate$tryApplyCachedSystemTranslation(message, plainText);
        }

        // The old guard `ModConfig.CHAT_CONTEXT_ENABLED.get() && !simple_translate$hasChatBodyPrefix(plainText)`
        // made player-prefixed chat skip the context lane entirely.
        if (ModConfig.CHAT_CONTEXT_ENABLED.get()) {
            return simple_translate$tryApplyCachedContextTranslation(message, plainText);
        }

        Component translatedComponent = ChatTranslationRuntime.buildCachedSegmentTranslation(message, plainText);
        if (translatedComponent == null) {
            return false;
        }

        simple_translate$applyAutoTranslationIfCurrent(
                simple_translate$captureMessageIdentity(message, plainText), translatedComponent, message);

        return true;
    }

    @Unique
    private boolean simple_translate$tryApplyCachedSystemTranslation(Component message, String plainText) {
        Component translatedComponent = ChatTranslationRuntime.buildCachedSystemTranslation(message);
        if (translatedComponent == null) {
            return false;
        }

        simple_translate$applyAutoTranslationIfCurrent(
                simple_translate$captureMessageIdentity(message, plainText), translatedComponent, message);

        return true;
    }

    @Unique
    private boolean simple_translate$tryApplyCachedContextTranslation(Component message, String plainText) {
        if (simple_translate$hasBlacklistedSourceText(message, plainText)) {
            return false;
        }

        int[] targetIndexHolder = {0};
        List<String> contextLines = simple_translate$collectContextLines(message, plainText, targetIndexHolder);
        Component translatedComponent = ChatTranslationRuntime.buildCachedContextTranslation(
                message,
                plainText,
                contextLines,
                targetIndexHolder[0]);
        if (translatedComponent == null) {
            return false;
        }

        simple_translate$applyAutoTranslationIfCurrent(
                simple_translate$captureMessageIdentity(message, plainText), translatedComponent, message);

        return true;
    }

    /**
     * Handle AUTO mode - translate immediately
     */
    @Unique
    private void simple_translate$handleAutoMode(Component message, String plainText, TranslationManager manager) {
        if (simple_translate$hasBlacklistedSourceText(message, plainText)) {
            return;
        }

        if (!simple_translate$hasChatBodyPrefix(plainText)) {
            if (!ChatMessageStore.markPending(plainText)) {
                return;
            }
            simple_translate$handleAutoModeSystemDirect(message, plainText);
            return;
        }

        if (ModConfig.CHAT_CONTEXT_ENABLED.get()) {
            simple_translate$handleAutoModeWithContext(message, plainText, manager);
            return;
        }

        if (!ChatMessageStore.markPending(plainText)) {
            return;
        }

        simple_translate$translateSegmentsAndApply(
                message,
                plainText,
                manager,
                simple_translate$captureMessageIdentity(message, plainText),
                plainText);
    }

    @Unique
    private void simple_translate$translateSegmentsAndApply(Component message,
                                                            String plainText,
                                                            TranslationManager manager,
                                                            ChatMessageIdentity identity,
                                                            String pendingKey) {
        String storeKey = pendingKey == null ? plainText : pendingKey;

        List<TextSegmentInfo> segments = new ArrayList<>();
        simple_translate$extractSegments(message, segments, Style.EMPTY);

        List<String> textsToTranslate = new ArrayList<>();
        List<Integer> translateIndices = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            TextSegmentInfo seg = segments.get(i);
            String sourceText = simple_translate$getTranslatableChatSegmentText(segments, i, plainText);
            if (sourceText != null && simple_translate$containsEnglish(sourceText)) {
                textsToTranslate.add(sourceText);
                translateIndices.add(i);
            }
        }

        if (textsToTranslate.isEmpty()) {
            ChatMessageStore.removePending(storeKey);
            return;
        }

        final String originalPlainText = plainText;
        final String pendingStoreKey = storeKey;
        final Component originalMessage = message;
        final ChatMessageIdentity capturedIdentity = identity;

        simple_translate$translateBatch(textsToTranslate, manager).thenAccept(translations -> {
            if (translations == null || translations.size() != textsToTranslate.size()) {
                ChatMessageStore.removePending(pendingStoreKey);
                return;
            }

            boolean anyTranslated = false;
            for (int i = 0; i < translateIndices.size(); i++) {
                int segIndex = translateIndices.get(i);
                String originalSegment = textsToTranslate.get(i);
                String translated = translations.get(i);
                if (translated != null && !translated.isEmpty()) {
                    TextSegmentInfo segment = segments.get(segIndex);
                    segments.get(segIndex).translatedText = simple_translate$applyChatSegmentTranslation(
                            segment.text, originalSegment, translated);
                    if (!translated.equals(originalSegment)) {
                        anyTranslated = true;
                    }
                }
            }

            if (!anyTranslated) {
                ChatMessageStore.removePending(pendingStoreKey);
                return;
            }

            Component translatedComponent = simple_translate$rebuildComponent(originalMessage, segments);
            simple_translate$applyAutoTranslationIfCurrent(capturedIdentity, translatedComponent, originalMessage, pendingStoreKey);
        });
    }

    @Unique
    private void simple_translate$handleAutoModeSystemDirect(Component message, String plainText) {
        final ChatMessageIdentity identity = simple_translate$captureMessageIdentity(message, plainText);
        ChatTranslationRuntime.translateSystemMessage(message).thenAccept(translatedComponent -> {
            if (translatedComponent == null
                    || translatedComponent.getString().isBlank()
                    || translatedComponent.getString().equals(plainText)
                    || simple_translate$hasBlacklistedSourceText(message, plainText)
                    || simple_translate$containsBlacklistedText(translatedComponent.getString())) {
                ChatMessageStore.removePending(plainText);
                return;
            }

            simple_translate$applyAutoTranslationIfCurrent(identity, translatedComponent, message);
        });
    }

    @Unique
    private void simple_translate$handleAutoModeWithContext(Component message, String plainText, TranslationManager manager) {
        if (simple_translate$hasBlacklistedSourceText(message, plainText)) {
            return;
        }

        int[] targetIndexHolder = {0};
        List<String> contextLines = simple_translate$collectContextLines(message, plainText, targetIndexHolder);
        if (contextLines.isEmpty()) {
            return;
        }

        int targetIndex = targetIndexHolder[0];
        String pendingKey = simple_translate$contextRequestKey(plainText, contextLines, targetIndex);
        if (!ChatMessageStore.markPending(pendingKey)) {
            return;
        }

        final ChatMessageIdentity identity = simple_translate$captureMessageIdentity(message, plainText);
        ChatTranslationRuntime.translateWithContext(plainText, contextLines, targetIndex, manager).thenAccept(translated -> {
            if (translated == null || translated.isEmpty() || translated.equals(plainText)) {
                simple_translate$translateSegmentsAndApply(message, plainText, manager, identity, pendingKey);
                return;
            }

            Component translatedComponent = simple_translate$applyFullTranslation(message, translated);
            if (translatedComponent == null) {
                simple_translate$translateSegmentsAndApply(message, plainText, manager, identity, pendingKey);
                return;
            }

            simple_translate$applyAutoTranslationIfCurrent(identity, translatedComponent, message, pendingKey);
        });
    }

    /**
     * Handle BUTTON mode - add translate button
     */
    @Unique
    private void simple_translate$handleButtonMode(Component message, String plainText) {
        UUID messageId = UUID.randomUUID();

        // Store message data
        ButtonMessageData data = new ButtonMessageData(message, plainText, SimpleTranslateMod.getRuntimeRevision());
        simple_translate$buttonMessages.put(messageId, data);

        // Create message with [缈昏瘧] button
        Component messageWithButton = simple_translate$createMessageWithButton(message, messageId, ButtonMessageData.State.ORIGINAL);

        simple_translate$processedMessages.put(System.identityHashCode(messageWithButton), true);

        ChatMessageIdentity identity = simple_translate$captureMessageIdentity(message, plainText);
        simple_translate$runOnClientThread(() -> {
            if (simple_translate$isMessageIdentityCurrent(identity)) {
                simple_translate$replaceMessageIdentity(identity, messageWithButton);
            }
        });

        simple_translate$cleanupOldButtonData();
    }

    /**
     * Create message with appropriate button based on state
     */
    @Unique
    private Component simple_translate$createMessageWithButton(Component content, UUID messageId, ButtonMessageData.State state) {
        String buttonText;
        ChatFormatting buttonColor;
        String hoverText;

        switch (state) {
            case TRANSLATING:
                buttonText = " [翻译中...]";
                buttonColor = ChatFormatting.GRAY;
                hoverText = "正在翻译，请稍候";
                break;
            case TRANSLATED:
                buttonText = " [原文]";
                buttonColor = ChatFormatting.YELLOW;
                hoverText = "点击显示原文";
                break;
            default: // ORIGINAL
                buttonText = " [翻译]";
                buttonColor = ChatFormatting.AQUA;
                hoverText = "点击翻译此消息";
                break;
        }

        MutableComponent button = Component.literal(buttonText)
                .withStyle(style -> style
                        .withColor(buttonColor)
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hoverText)))
                        .withClickEvent(new ClickEvent.SuggestCommand(TRANSLATE_CLICK_PREFIX + messageId.toString())));

        return content.copy().append(button);
    }

    @Override
    public boolean simple_translate$handleButtonClickEvent(String clickValue) {
        simple_translate$syncBlacklistRevision();
        if (clickValue != null && clickValue.startsWith(HUD_HISTORY_CLICK_PREFIX)) {
            return simple_translate$toggleHudHistoryChatMessage(clickValue);
        }
        if (clickValue == null || !clickValue.startsWith(TRANSLATE_CLICK_PREFIX)) {
            return false;
        }

        String uuidStr = clickValue.substring(TRANSLATE_CLICK_PREFIX.length());
        try {
            UUID messageId = UUID.fromString(uuidStr);
            simple_translate$handleButtonClick(messageId);
            return true;
        } catch (IllegalArgumentException e) {
            SimpleTranslateMod.getLogger().error("Invalid translation UUID: {}", uuidStr);
            return false;
        }
    }

    @Override
    public boolean simple_translate$showVisibleOriginalMessages() {
        List<UUID> visibleMessageIds = new ArrayList<>();
        for (int i = 0; i < Math.min(allMessages.size(), 100); i++) {
            UUID messageId = simple_translate$extractMessageId(allMessages.get(i).content());
            if (messageId != null) {
                visibleMessageIds.add(messageId);
            }
        }

        boolean changed = false;
        for (UUID messageId : visibleMessageIds) {
            ButtonMessageData data = simple_translate$buttonMessages.get(messageId);
            if (data == null || data.state() != ButtonMessageData.State.TRANSLATED) {
                continue;
            }

            simple_translate$showOriginal(messageId, data);
            changed = true;
        }

        return changed;
    }

    /**
     * Handle button click - toggle between original and translated
     */
    @Unique
    private void simple_translate$handleButtonClick(UUID messageId) {
        ButtonMessageData data = simple_translate$buttonMessages.get(messageId);
        if (data == null) {
            return;
        }

        switch (data.state()) {
            case ORIGINAL:
                // Start translation
                simple_translate$startTranslation(messageId, data);
                break;
            case TRANSLATED:
                // Switch back to original
                simple_translate$showOriginal(messageId, data);
                break;
            case TRANSLATING:
                // Do nothing while translating
                break;
        }
    }

    /**
     * Start translation for a message
     */
    @Unique
    private void simple_translate$startTranslation(UUID messageId, ButtonMessageData data) {
        if (simple_translate$hasBlacklistedSourceText(data.originalMessage(), data.originalPlainText())) {
            simple_translate$showOriginal(messageId, data);
            return;
        }
        // Check if we already have cached translation
        if (data.translatedMessage() != null) {
            if (simple_translate$containsBlacklistedText(data.translatedMessage().getString())) {
                data.setTranslatedMessage(null);
                simple_translate$showOriginal(messageId, data);
                return;
            }
            if (!simple_translate$canApplyButtonTranslation(messageId, data, data.translatedMessage())) {
                data.setState(ButtonMessageData.State.ORIGINAL);
                return;
            }
            // Use cached translation
            data.setState(ButtonMessageData.State.TRANSLATED);
            Component translatedMsg = simple_translate$createMessageWithButton(
                    data.translatedMessage(), messageId, ButtonMessageData.State.TRANSLATED);
            simple_translate$processedMessages.put(System.identityHashCode(translatedMsg), true);
            simple_translate$updateMessageById(messageId, translatedMsg);
            return;
        }

        if (simple_translate$tryApplyCachedButtonTranslation(messageId, data)) {
            return;
        }

        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            return;
        }

        // Update state to translating
        data.setState(ButtonMessageData.State.TRANSLATING);

        // Update button to show [翻译中...]
        Component translatingMsg = simple_translate$createMessageWithButton(
                data.originalMessage(), messageId, ButtonMessageData.State.TRANSLATING);
        simple_translate$processedMessages.put(System.identityHashCode(translatingMsg), true);
        simple_translate$updateMessageById(messageId, translatingMsg);

        if (!simple_translate$hasChatBodyPrefix(data.originalPlainText())) {
            simple_translate$startSystemDirectTranslation(messageId, data);
            return;
        }

        if (ModConfig.CHAT_CONTEXT_ENABLED.get()) {
            simple_translate$translateWithContext(data.originalMessage(), data.originalPlainText(), manager).thenAccept(translated -> {
                if (simple_translate$hasBlacklistedSourceText(data.originalMessage(), data.originalPlainText())
                        || simple_translate$containsBlacklistedText(translated)) {
                    data.setTranslatedMessage(null);
                    data.setState(ButtonMessageData.State.ORIGINAL);
                    simple_translate$runOnClientThread(() -> simple_translate$showOriginal(messageId, data));
                    return;
                }
                if (translated == null || translated.isEmpty() || translated.equals(data.originalPlainText())) {
                    simple_translate$startButtonSegmentTranslation(messageId, data, manager);
                    return;
                }

                Component translatedContent = simple_translate$applyFullTranslation(data.originalMessage(), translated);
                if (translatedContent == null) {
                    simple_translate$startButtonSegmentTranslation(messageId, data, manager);
                    return;
                }

                if (!simple_translate$canApplyButtonTranslation(messageId, data, translatedContent)) {
                    data.setTranslatedMessage(null);
                    data.setState(ButtonMessageData.State.ORIGINAL);
                    simple_translate$runOnClientThread(() -> simple_translate$showOriginal(messageId, data));
                    return;
                }
                data.setTranslatedMessage(translatedContent);
                data.setState(ButtonMessageData.State.TRANSLATED);

                simple_translate$runOnClientThread(() -> {
                    Component translatedMsg = simple_translate$createMessageWithButton(
                            translatedContent, messageId, ButtonMessageData.State.TRANSLATED);
                    simple_translate$processedMessages.put(System.identityHashCode(translatedMsg), true);
                    simple_translate$updateMessageById(messageId, translatedMsg);
                });
            });
            return;
        }

        simple_translate$startButtonSegmentTranslation(messageId, data, manager);
    }

    @Unique
    private void simple_translate$startButtonSegmentTranslation(UUID messageId,
                                                               ButtonMessageData data,
                                                               TranslationManager manager) {
        List<TextSegmentInfo> segments = new ArrayList<>();
        simple_translate$extractSegments(data.originalMessage(), segments, Style.EMPTY);

        List<String> textsToTranslate = new ArrayList<>();
        List<Integer> translateIndices = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            TextSegmentInfo seg = segments.get(i);
            String sourceText = simple_translate$getTranslatableChatSegmentText(segments, i, data.originalPlainText());
            if (sourceText != null && simple_translate$containsEnglish(sourceText)) {
                textsToTranslate.add(sourceText);
                translateIndices.add(i);
            }
        }

        if (textsToTranslate.isEmpty()) {
            data.setState(ButtonMessageData.State.ORIGINAL);
            Component originalMsg = simple_translate$createMessageWithButton(
                    data.originalMessage(), messageId, ButtonMessageData.State.ORIGINAL);
            simple_translate$updateMessageById(messageId, originalMsg);
            return;
        }

        simple_translate$translateBatch(textsToTranslate, manager).thenAccept(translations -> {
            if (simple_translate$hasBlacklistedSourceText(data.originalMessage(), data.originalPlainText())) {
                data.setTranslatedMessage(null);
                data.setState(ButtonMessageData.State.ORIGINAL);
                simple_translate$runOnClientThread(() -> simple_translate$showOriginal(messageId, data));
                return;
            }
            if (translations == null || translations.size() != textsToTranslate.size()) {
                // Translation failed, revert to original state
                data.setState(ButtonMessageData.State.ORIGINAL);
                simple_translate$runOnClientThread(() -> {
                    Component originalMsg = simple_translate$createMessageWithButton(
                            data.originalMessage(), messageId, ButtonMessageData.State.ORIGINAL);
                    simple_translate$processedMessages.put(System.identityHashCode(originalMsg), true);
                    simple_translate$updateMessageById(messageId, originalMsg);
                });
                return;
            }

            // Apply translations
            boolean anyTranslated = false;
            for (int i = 0; i < translateIndices.size(); i++) {
                int segIndex = translateIndices.get(i);
                String originalSegment = textsToTranslate.get(i);
                String translated = translations.get(i);
                if (simple_translate$isBlacklisted(originalSegment) || simple_translate$containsBlacklistedText(translated)) {
                    continue;
                }
                if (translated != null && !translated.isEmpty()) {
                    TextSegmentInfo segment = segments.get(segIndex);
                    segments.get(segIndex).translatedText = simple_translate$applyChatSegmentTranslation(
                            segment.text, originalSegment, translated);
                    if (!translated.equals(originalSegment)) {
                        anyTranslated = true;
                    }
                }
            }

            if (!anyTranslated) {
                data.setState(ButtonMessageData.State.ORIGINAL);
                simple_translate$runOnClientThread(() -> {
                    Component originalMsg = simple_translate$createMessageWithButton(
                            data.originalMessage(), messageId, ButtonMessageData.State.ORIGINAL);
                    simple_translate$processedMessages.put(System.identityHashCode(originalMsg), true);
                    simple_translate$updateMessageById(messageId, originalMsg);
                });
                return;
            }

            // Rebuild translated component (without button)
            Component translatedContent = simple_translate$rebuildComponent(data.originalMessage(), segments);
            if (!simple_translate$canApplyButtonTranslation(messageId, data, translatedContent)) {
                data.setTranslatedMessage(null);
                data.setState(ButtonMessageData.State.ORIGINAL);
                simple_translate$runOnClientThread(() -> simple_translate$showOriginal(messageId, data));
                return;
            }
            data.setTranslatedMessage(translatedContent);
            data.setState(ButtonMessageData.State.TRANSLATED);

            simple_translate$runOnClientThread(() -> {
                // Create message with [鍘熸枃] button
                Component translatedMsg = simple_translate$createMessageWithButton(
                        translatedContent, messageId, ButtonMessageData.State.TRANSLATED);
                simple_translate$processedMessages.put(System.identityHashCode(translatedMsg), true);
                simple_translate$updateMessageById(messageId, translatedMsg);
            });
        });
    }

    @Unique
    private boolean simple_translate$tryApplyCachedButtonTranslation(UUID messageId, ButtonMessageData data) {
        Component cached = simple_translate$getCachedButtonTranslation(data);
        if (cached == null
                || cached.getString().isBlank()
                || cached.getString().equals(data.originalPlainText())
                || simple_translate$containsBlacklistedText(cached.getString())
                || !simple_translate$canApplyButtonTranslation(messageId, data, cached)) {
            return false;
        }

        data.setTranslatedMessage(cached);
        data.setState(ButtonMessageData.State.TRANSLATED);
        Component translatedMsg = simple_translate$createMessageWithButton(
                cached, messageId, ButtonMessageData.State.TRANSLATED);
        simple_translate$processedMessages.put(System.identityHashCode(translatedMsg), true);
        simple_translate$updateMessageById(messageId, translatedMsg);
        return true;
    }

    @Unique
    private Component simple_translate$getCachedButtonTranslation(ButtonMessageData data) {
        Component original = data.originalMessage();
        String plainText = data.originalPlainText();
        if (simple_translate$hasBlacklistedSourceText(original, plainText)) {
            return null;
        }
        if (!simple_translate$hasChatBodyPrefix(plainText)) {
            return ChatTranslationRuntime.buildCachedSystemTranslation(original);
        }
        if (ModConfig.CHAT_CONTEXT_ENABLED.get()) {
            int[] targetIndexHolder = {0};
            List<String> contextLines = simple_translate$collectContextLines(original, plainText, targetIndexHolder);
            return ChatTranslationRuntime.buildCachedContextTranslation(original, plainText, contextLines, targetIndexHolder[0]);
        }
        return ChatTranslationRuntime.buildCachedSegmentTranslation(original, plainText);
    }
    @Unique
    private void simple_translate$startSystemDirectTranslation(UUID messageId, ButtonMessageData data) {
        ChatTranslationRuntime.translateSystemMessage(data.originalMessage()).thenAccept(translatedContent -> {
            if (translatedContent == null
                    || translatedContent.getString().isBlank()
                    || translatedContent.getString().equals(data.originalPlainText())
                    || simple_translate$hasBlacklistedSourceText(data.originalMessage(), data.originalPlainText())
                    || simple_translate$containsBlacklistedText(translatedContent.getString())) {
                data.setTranslatedMessage(null);
                data.setState(ButtonMessageData.State.ORIGINAL);
                simple_translate$runOnClientThread(() -> simple_translate$showOriginal(messageId, data));
                return;
            }

            if (!simple_translate$canApplyButtonTranslation(messageId, data, translatedContent)) {
                data.setTranslatedMessage(null);
                data.setState(ButtonMessageData.State.ORIGINAL);
                simple_translate$runOnClientThread(() -> simple_translate$showOriginal(messageId, data));
                return;
            }
            data.setTranslatedMessage(translatedContent);
            data.setState(ButtonMessageData.State.TRANSLATED);

            simple_translate$runOnClientThread(() -> {
                Component translatedMsg = simple_translate$createMessageWithButton(
                        translatedContent, messageId, ButtonMessageData.State.TRANSLATED);
                simple_translate$processedMessages.put(System.identityHashCode(translatedMsg), true);
                simple_translate$updateMessageById(messageId, translatedMsg);
            });
        });
    }

    /**
     * Show original message
     */
    @Unique
    private void simple_translate$showOriginal(UUID messageId, ButtonMessageData data) {
        data.setState(ButtonMessageData.State.ORIGINAL);

        Component originalMsg = simple_translate$createMessageWithButton(
                data.originalMessage(), messageId, ButtonMessageData.State.ORIGINAL);
        simple_translate$processedMessages.put(System.identityHashCode(originalMsg), true);
        simple_translate$updateMessageById(messageId, originalMsg);
    }

    /**
     * Update a message by its UUID (search for the button click event)
     */
    @Unique
    private void simple_translate$updateMessageById(UUID messageId, Component newComponent) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && !minecraft.isSameThread()) {
            minecraft.execute(() -> simple_translate$updateMessageById(messageId, newComponent));
            return;
        }
        String searchPrefix = TRANSLATE_CLICK_PREFIX + messageId.toString();

        try {
            for (int i = 0; i < Math.min(allMessages.size(), 100); i++) {
                GuiMessage msg = allMessages.get(i);
                if (simple_translate$containsClickEvent(msg.content(), searchPrefix)) {
                    GuiMessage newMessage = new GuiMessage(
                            msg.addedTime(),
                            newComponent,
                            msg.signature(),
                            msg.tag());

                    allMessages.set(i, newMessage);
                    this.rescaleChat();
                    return;
                }
            }
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to update message", e);
        }
    }

    @Override
    public void simple_translate$upsertHudHistoryCaption(HudTranslationHistory.Entry entry) {
        if (entry == null
                || !ModConfig.HUD_HISTORY_CHAT_ENABLED.get()
                || entry.historyKey() == null
                || entry.historyKey().isBlank()
                || entry.translatedText() == null
                || entry.translatedText().isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && !minecraft.isSameThread()) {
            minecraft.execute(() -> simple_translate$upsertHudHistoryCaption(entry));
            return;
        }

        Map<String, HudHistoryChatData> messages = simple_translate$hudHistoryChatMessages();
        HudHistoryChatData data = messages.get(entry.historyKey());
        if (data == null) {
            data = new HudHistoryChatData(entry, false);
            messages.put(entry.historyKey(), data);
        } else {
            data.setEntry(entry);
        }

        Component message = simple_translate$createHudHistoryChatMessage(data);
        simple_translate$processedMessages.put(System.identityHashCode(message), true);
        String clickValue = simple_translate$hudHistoryClickValue(entry.historyKey());
        if (!simple_translate$replaceHudHistoryMessage(clickValue, message)) {
            int ticks = minecraft == null || minecraft.gui == null ? 0 : minecraft.gui.getGuiTicks();
            allMessages.add(0, new GuiMessage(ticks, message, null, null));
        }
        simple_translate$trimHudHistoryChatMessages(messages);
        this.rescaleChat();
    }

    @Unique
    private Map<String, HudHistoryChatData> simple_translate$hudHistoryChatMessages() {
        if (this.simple_translate$hudHistoryChatMessages == null) {
            this.simple_translate$hudHistoryChatMessages = new LinkedHashMap<>();
        }
        return this.simple_translate$hudHistoryChatMessages;
    }

    @Unique
    private Component simple_translate$createHudHistoryChatMessage(HudHistoryChatData data) {
        boolean showingOriginal = data.showingOriginal();
        HudTranslationHistory.Entry entry = data.entry();
        String bodyText = showingOriginal ? entry.originalText() : entry.translatedText();
        if (bodyText == null) {
            bodyText = "";
        }
        ChatFormatting typeColor = switch (entry.type()) {
            case TITLE -> ChatFormatting.GOLD;
            case SUBTITLE -> ChatFormatting.YELLOW;
            case ACTIONBAR -> ChatFormatting.AQUA;
        };
        MutableComponent content = Component.empty()
                .append(Component.literal(entry.type().label() + " ").withStyle(typeColor))
                .append(Component.literal(bodyText).withStyle(ChatFormatting.WHITE));
        String buttonText = showingOriginal ? " [译文]" : " [原文]";
        String hoverText = showingOriginal ? "点击显示译文" : "点击显示原文";
        ChatFormatting buttonColor = showingOriginal ? ChatFormatting.AQUA : ChatFormatting.YELLOW;
        content.append(Component.literal(buttonText)
                .withStyle(style -> style
                        .withColor(buttonColor)
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hoverText)))
                        .withClickEvent(new ClickEvent.SuggestCommand(
                                simple_translate$hudHistoryClickValue(entry.historyKey())))));
        return content;
    }

    @Unique
    private boolean simple_translate$toggleHudHistoryChatMessage(String clickValue) {
        String historyKey = simple_translate$decodeHudHistoryClickValue(clickValue);
        if (historyKey == null || historyKey.isBlank()) {
            return false;
        }
        HudHistoryChatData data = simple_translate$hudHistoryChatMessages().get(historyKey);
        if (data == null) {
            return true;
        }
        data.toggleShowingOriginal();
        Component message = simple_translate$createHudHistoryChatMessage(data);
        simple_translate$processedMessages.put(System.identityHashCode(message), true);
        simple_translate$replaceHudHistoryMessage(clickValue, message);
        return true;
    }

    @Unique
    private boolean simple_translate$replaceHudHistoryMessage(String clickValue, Component newComponent) {
        try {
            for (int i = 0; i < Math.min(allMessages.size(), 120); i++) {
                GuiMessage msg = allMessages.get(i);
                if (simple_translate$containsClickEvent(msg.content(), clickValue)) {
                    allMessages.set(i, new GuiMessage(msg.addedTime(), newComponent, msg.signature(), msg.tag()));
                    this.rescaleChat();
                    return true;
                }
            }
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to update HUD history chat message", e);
        }
        return false;
    }

    @Unique
    private void simple_translate$trimHudHistoryChatMessages(Map<String, HudHistoryChatData> messages) {
        while (messages.size() > simple_translate$maxHudHistoryChatMessages) {
            Iterator<Map.Entry<String, HudHistoryChatData>> iterator = messages.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            String historyKey = iterator.next().getKey();
            iterator.remove();
            String clickValue = simple_translate$hudHistoryClickValue(historyKey);
            for (int i = allMessages.size() - 1; i >= 0; i--) {
                if (simple_translate$containsClickEvent(allMessages.get(i).content(), clickValue)) {
                    allMessages.remove(i);
                    break;
                }
            }
        }
    }

    @Unique
    private boolean simple_translate$isHudHistoryChatMessage(Component component) {
        if (component == null) {
            return false;
        }
        Style style = component.getStyle();
        if (style != null && style.getClickEvent() != null) {
            String value = simple_translate$getSuggestCommandValue(style.getClickEvent());
            if (value != null && value.startsWith(HUD_HISTORY_CLICK_PREFIX)) {
                return true;
            }
        }
        for (Component sibling : component.getSiblings()) {
            if (simple_translate$isHudHistoryChatMessage(sibling)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private String simple_translate$hudHistoryClickValue(String historyKey) {
        return HUD_HISTORY_CLICK_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(historyKey.getBytes(StandardCharsets.UTF_8));
    }

    @Unique
    private String simple_translate$decodeHudHistoryClickValue(String clickValue) {
        if (clickValue == null || !clickValue.startsWith(HUD_HISTORY_CLICK_PREFIX)) {
            return null;
        }
        try {
            String encoded = clickValue.substring(HUD_HISTORY_CLICK_PREFIX.length());
            int padding = encoded.length() % 4;
            if (padding != 0) {
                encoded = encoded + "=".repeat(4 - padding);
            }
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Check if a component contains a specific click event value
     */
    @Unique
    private boolean simple_translate$containsClickEvent(Component component, String clickValue) {
        Style style = component.getStyle();
        if (style != null && style.getClickEvent() != null) {
            if (clickValue.equals(simple_translate$getSuggestCommandValue(style.getClickEvent()))) {
                return true;
            }
        }

        for (Component sibling : component.getSiblings()) {
            if (simple_translate$containsClickEvent(sibling, clickValue)) {
                return true;
            }
        }

        return false;
    }

    @Unique
    private UUID simple_translate$extractMessageId(Component component) {
        if (component == null) {
            return null;
        }

        Style style = component.getStyle();
        if (style != null && style.getClickEvent() != null) {
            ClickEvent clickEvent = style.getClickEvent();
            String value = simple_translate$getSuggestCommandValue(clickEvent);
            if (value != null && value.startsWith(TRANSLATE_CLICK_PREFIX)) {
                String uuidStr = value.substring(TRANSLATE_CLICK_PREFIX.length());
                try {
                    return UUID.fromString(uuidStr);
                } catch (IllegalArgumentException ignored) {
                    // Ignore invalid click values in chat components.
                }
            }
        }

        for (Component sibling : component.getSiblings()) {
            UUID messageId = simple_translate$extractMessageId(sibling);
            if (messageId != null) {
                return messageId;
            }
        }

        return null;
    }

    @Unique
    private static String simple_translate$getSuggestCommandValue(ClickEvent clickEvent) {
        if (clickEvent instanceof ClickEvent.SuggestCommand suggestCommand) {
            return suggestCommand.command();
        }
        return null;
    }

    /**
     * Cleanup old button data
     */
    @Unique
    private void simple_translate$cleanupOldButtonData() {
        if (simple_translate$buttonMessages.size() > 50) {
            var iterator = simple_translate$buttonMessages.entrySet().iterator();
            int toRemove = simple_translate$buttonMessages.size() - 30;
            while (toRemove > 0 && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
                toRemove--;
            }
        }
    }

    @Unique
    private boolean simple_translate$containsEnglish(String text) {
        return TooltipTranslationHelper.containsEnglish(text);
    }

    @Unique
    private String simple_translate$getTranslatableChatSegmentText(List<TextSegmentInfo> segments, int segmentIndex, String plainText) {
        String candidate = ChatTranslationRuntime.getTranslatableChatSegmentText(
                segments, segmentIndex, plainText, simple_translate$findChatBodyStart(plainText));
        if (candidate == null) {
            return null;
        }
        candidate = candidate.trim();
        if (candidate.isEmpty() || simple_translate$isKnownPlayerName(candidate) || simple_translate$isBlacklisted(candidate)) {
            return null;
        }
        return candidate;
    }

    @Unique
    private String simple_translate$applyChatSegmentTranslation(String originalSegment, String sourceText, String translated) {
        return ChatTranslationRuntime.applyChatSegmentTranslation(originalSegment, sourceText, translated);
    }

    @Unique
    private boolean simple_translate$hasChatBodyPrefix(String plainText) {
        return simple_translate$findChatBodyStart(plainText) > 0;
    }

    @Unique
    private int simple_translate$findChatBodyStart(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return -1;
        }

        int angleIndex = plainText.indexOf('>');
        if (plainText.startsWith("<") && angleIndex > 1 && angleIndex + 1 < plainText.length()) {
            return simple_translate$skipWhitespace(plainText, angleIndex + 1);
        }

        int colonIndex = simple_translate$firstChatColonIndex(plainText);
        if (colonIndex > 0 && colonIndex < 80 && colonIndex + 1 < plainText.length()) {
            String prefix = plainText.substring(0, colonIndex);
            if (simple_translate$prefixContainsKnownPlayerName(prefix)) {
                return simple_translate$skipWhitespace(plainText, colonIndex + 1);
            }
        }

        return -1;
    }

    @Unique
    private int simple_translate$firstChatColonIndex(String text) {
        int ascii = text.indexOf(':');
        int fullWidth = text.indexOf('\uff1a');
        if (ascii < 0) {
            return fullWidth;
        }
        if (fullWidth < 0) {
            return ascii;
        }
        return Math.min(ascii, fullWidth);
    }

    @Unique
    private int simple_translate$skipWhitespace(String text, int index) {
        int i = Math.max(0, index);
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    @Unique
    private boolean simple_translate$looksLikeChatNamePrefix(String prefix) {
        if (prefix == null) {
            return false;
        }
        String candidate = simple_translate$normalizeNameCandidate(prefix);
        return candidate.length() >= 3 && candidate.length() <= 32
                && candidate.matches("[A-Za-z0-9_]+");
    }

    @Unique
    private boolean simple_translate$prefixContainsKnownPlayerName(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }

        String[] parts = prefix.split("[\\s\\[\\]()<>{}:：|]+");
        for (String part : parts) {
            if (simple_translate$isKnownPlayerName(part)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private boolean simple_translate$isKnownPlayerName(String text) {
        String candidate = simple_translate$normalizeNameCandidate(text);
        if (candidate.isEmpty()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return false;
        }

        for (var playerInfo : minecraft.getConnection().getOnlinePlayers()) {
            if (playerInfo != null
                    && playerInfo.getProfile() != null
                    && candidate.equalsIgnoreCase(playerInfo.getProfile().name())) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private String simple_translate$normalizeNameCandidate(String text) {
        if (text == null) {
            return "";
        }

        String candidate = text.trim();
        while (candidate.length() >= 2
                && ((candidate.charAt(0) == '<' && candidate.charAt(candidate.length() - 1) == '>')
                || (candidate.charAt(0) == '[' && candidate.charAt(candidate.length() - 1) == ']')
                || (candidate.charAt(0) == '(' && candidate.charAt(candidate.length() - 1) == ')'))) {
            candidate = candidate.substring(1, candidate.length() - 1).trim();
        }
        return candidate;
    }

    /**
     * Translate full chat text with the configured previous-message context and return the target translation.
     */
    @Unique
    private CompletableFuture<String> simple_translate$translateWithContext(Component message, String plainText, TranslationManager manager) {
        int[] targetIndexHolder = {0};
        List<String> contextLines = simple_translate$collectContextLines(message, plainText, targetIndexHolder);
        if (contextLines.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        int targetIndex = targetIndexHolder[0];
        return ChatTranslationRuntime.translateWithContext(plainText, contextLines, targetIndex, manager);
    }

    @Unique
    private List<String> simple_translate$collectContextLines(Component message, String plainText, int[] targetIndexHolder) {
        List<String> context = new ArrayList<>();
        targetIndexHolder[0] = 0;

        if (allMessages == null || allMessages.isEmpty()) {
            context.add(plainText);
            return context;
        }

        int index = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            GuiMessage msg = allMessages.get(i);
            if (simple_translate$isHudHistoryChatMessage(msg.content())) {
                continue;
            }
            if (msg.content() == message) {
                index = i;
                break;
            }
        }

        if (index < 0) {
            for (int i = 0; i < allMessages.size(); i++) {
                GuiMessage msg = allMessages.get(i);
                Component content = msg.content();
                if (simple_translate$isHudHistoryChatMessage(content)) {
                    continue;
                }
                String text = simple_translate$getOriginalContextText(content);
                if (plainText.equals(text)) {
                    index = i;
                    break;
                }
            }
        }

        if (index < 0) {
            context.add(plainText);
            return context;
        }

        int contextLimit = Math.max(0, ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get());
        int end = Math.min(allMessages.size() - 1, index + contextLimit);

        for (int i = end; i > index; i--) {
            Component content = allMessages.get(i).content();
            if (simple_translate$isHudHistoryChatMessage(content)) {
                continue;
            }
            String text = simple_translate$getOriginalContextText(content);
            if (text != null && !text.isBlank()) {
                context.add(text);
            }
        }

        targetIndexHolder[0] = context.size();
        context.add(plainText);

        if (context.isEmpty() || targetIndexHolder[0] < 0 || targetIndexHolder[0] >= context.size()) {
            context.clear();
            context.add(plainText);
            targetIndexHolder[0] = 0;
        }

        return context;
    }

    @Unique
    private String simple_translate$getOriginalContextText(Component content) {
        if (content == null) {
            return "";
        }

        Component originalPeer = simple_translate$autoPeerMap.get(content);
        if (originalPeer != null) {
            return simple_translate$stripChatButtonSuffix(originalPeer.getString());
        }

        UUID messageId = simple_translate$extractMessageId(content);
        if (messageId != null) {
            ButtonMessageData data = simple_translate$buttonMessages.get(messageId);
            if (data != null && data.originalPlainText() != null && !data.originalPlainText().isBlank()) {
                return data.originalPlainText();
            }
        }

        return simple_translate$stripChatButtonSuffix(content.getString());
    }
    @Unique
    private String simple_translate$contextRequestKey(String plainText, List<String> contextLines, int targetIndex) {
        String context = ChatTranslationRuntime.contextText(contextLines, targetIndex);
        int contextHash = context.hashCode();
        return (plainText == null ? "" : plainText)
                + "\u001Fchat-context:v2:"
                + targetIndex
                + ":"
                + (contextLines == null ? 0 : contextLines.size())
                + ":"
                + Integer.toHexString(contextHash);
    }

    @Unique
    private String simple_translate$stripChatButtonSuffix(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String trimmed = text;
        String[] suffixes = {
                " [翻译中...]",
                " [翻译]",
                " [原文]",
                " [Translating...]",
                " [Translate]",
                " [Original]"
        };

        for (String suffix : suffixes) {
            if (trimmed.endsWith(suffix)) {
                return trimmed.substring(0, trimmed.length() - suffix.length());
            }
        }

        return trimmed;
    }

    @Unique
    private Component simple_translate$applyFullTranslation(Component message, String translatedText) {
        return ChatTranslationRuntime.applyFullTranslation(message, translatedText);
    }

    @Unique
    private Style simple_translate$firstVisibleStyle(List<TextSegmentInfo> segments, Style fallback) {
        for (TextSegmentInfo segment : segments) {
            if (segment != null && segment.text != null && !segment.text.isBlank()
                    && segment.style != null && !segment.style.isEmpty()) {
                return segment.style;
            }
        }
        return fallback == null ? Style.EMPTY : fallback;
    }

    @Unique
    private String simple_translate$getCachedMappingTranslation(String surface, String text, String context,
                                                               String layoutSignature, String styleSignature) {
        return ChatTranslationRuntime.getCachedMappingTranslation(surface, text, context, layoutSignature, styleSignature);
    }

    /**
     * Batch translate multiple texts with cache support
     */
    @Unique
    private CompletableFuture<List<String>> simple_translate$translateBatch(List<String> texts, TranslationManager manager) {
        return ChatTranslationRuntime.translateBatch(texts, manager);
    }

    @Unique
    private String simple_translate$stripInternalMarkers(String text) {
        return ChatTranslationRuntime.stripInternalMarkers(text);
    }

    /**
     * Extract all text segments from component tree
     */
    @Unique
    private void simple_translate$extractSegments(Component component, List<TextSegmentInfo> segments, Style parentStyle) {
        ChatTranslationRuntime.extractSegments(component, segments);
    }

    @Unique
    private Style simple_translate$mergeStyles(Style parent, Style child) {
        if (child == null || child.isEmpty()) {
            return parent;
        }
        if (parent == null || parent.isEmpty()) {
            return child;
        }
        return parent.applyTo(child);
    }

    /**
     * Rebuild the component tree with translated text
     */
    @Unique
    private Component simple_translate$rebuildComponent(Component original, List<TextSegmentInfo> segments) {
        return ChatTranslationRuntime.rebuildComponent(original, segments);
    }

    @Unique
    private ChatMessageIdentity simple_translate$captureMessageIdentity(Component originalComponent, String originalText) {
        for (int i = 0; i < allMessages.size(); i++) {
            GuiMessage msg = allMessages.get(i);
            if (msg.content() == originalComponent) {
                return new ChatMessageIdentity(
                        originalComponent,
                        originalText,
                        msg.signature(),
                        msg.tag(),
                        msg.addedTime(),
                        SimpleTranslateMod.getRuntimeRevision());
            }
        }
        return new ChatMessageIdentity(
                originalComponent,
                originalText,
                null,
                null,
                -1,
                SimpleTranslateMod.getRuntimeRevision());
    }

    @Unique
    private void simple_translate$applyAutoTranslationIfCurrent(ChatMessageIdentity identity,
                                                               Component translatedComponent,
                                                               Component originalComponent) {
        simple_translate$applyAutoTranslationIfCurrent(identity, translatedComponent, originalComponent,
                identity == null ? null : identity.originalText);
    }

    @Unique
    private void simple_translate$applyAutoTranslationIfCurrent(ChatMessageIdentity identity,
                                                               Component translatedComponent,
                                                               Component originalComponent,
                                                               String pendingKey) {
        String storeKey = pendingKey == null && identity != null ? identity.originalText : pendingKey;
        simple_translate$runOnClientThread(() -> {
            if (!simple_translate$canApplyAutoTranslation(identity, translatedComponent)) {
                if (storeKey != null) {
                    ChatMessageStore.removePending(storeKey);
                }
                return;
            }

            simple_translate$processedMessages.put(System.identityHashCode(translatedComponent), true);
            if (simple_translate$replaceMessageIdentity(identity, translatedComponent)) {
                simple_translate$autoPeerMap.put(translatedComponent, originalComponent);
                if (storeKey != null) {
                    ChatMessageStore.markTranslated(storeKey, translatedComponent.getString());
                }
            } else {
                if (storeKey != null) {
                    ChatMessageStore.removePending(storeKey);
                }
            }
        });
    }

    @Unique
    private boolean simple_translate$canApplyAutoTranslation(ChatMessageIdentity identity, Component translatedComponent) {
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
        if (simple_translate$hasBlacklistedSourceText(identity.originalComponent, identity.originalText)
                || simple_translate$containsBlacklistedText(translatedComponent.getString())) {
            return false;
        }
        return simple_translate$isMessageIdentityCurrent(identity);
    }

    @Unique
    private boolean simple_translate$canApplyButtonTranslation(UUID messageId, ButtonMessageData data, Component translatedComponent) {
        if (messageId == null || data == null || translatedComponent == null) {
            return false;
        }
        if (simple_translate$buttonMessages.get(messageId) != data) {
            return false;
        }
        if (!SimpleTranslateMod.isRuntimeRevisionCurrent(data.runtimeRevision())) {
            return false;
        }
        if (!ModConfig.CHAT_ENABLED.get() || ModConfig.CHAT_MODE.get() != ModConfig.TranslationMode.BUTTON) {
            return false;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.CHAT)) {
            return false;
        }
        return !simple_translate$hasBlacklistedSourceText(data.originalMessage(), data.originalPlainText())
                && !simple_translate$containsBlacklistedText(translatedComponent.getString());
    }

    @Unique
    private boolean simple_translate$isMessageIdentityCurrent(ChatMessageIdentity identity) {
        if (identity == null || identity.originalComponent == null) {
            return false;
        }
        for (int i = 0; i < allMessages.size(); i++) {
            GuiMessage msg = allMessages.get(i);
            if (msg.content() == identity.originalComponent
                    && (identity.addedTime < 0 || msg.addedTime() == identity.addedTime)
                    && msg.signature() == identity.signature
                    && msg.tag() == identity.tag) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private boolean simple_translate$replaceMessageIdentity(ChatMessageIdentity identity, Component newComponent) {
        try {
            for (int i = 0; i < allMessages.size(); i++) {
                GuiMessage msg = allMessages.get(i);
                if (msg.content() == identity.originalComponent
                        && (identity.addedTime < 0 || msg.addedTime() == identity.addedTime)
                        && msg.signature() == identity.signature
                        && msg.tag() == identity.tag) {
                    allMessages.set(i, new GuiMessage(msg.addedTime(), newComponent, msg.signature(), msg.tag()));
                    this.rescaleChat();
                    return true;
                }
            }
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to replace message by identity", e);
        }
        return false;
    }

    @Unique
    private void simple_translate$runOnClientThread(Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        if (minecraft.isSameThread()) {
            action.run();
        } else {
            minecraft.execute(action);
        }
    }

    @Unique
    private void simple_translate$syncBlacklistRevision() {
        long revision = SimpleTranslateMod.getBlacklistRevision();
        if (simple_translate$seenBlacklistRevision == revision) {
            return;
        }
        simple_translate$seenBlacklistRevision = revision;

        simple_translate$processedMessages.clear();
        simple_translate$holdSwappedTranslated.clear();

        try {
            boolean changed = false;
            for (int i = 0; i < allMessages.size(); i++) {
                GuiMessage msg = allMessages.get(i);
                Component content = msg.content();
                Component originalPeer = simple_translate$autoPeerMap.get(content);
                if (originalPeer != null && (simple_translate$hasBlacklistedSourceText(originalPeer, originalPeer.getString())
                        || simple_translate$containsBlacklistedText(content.getString()))) {
                    allMessages.set(i, new GuiMessage(msg.addedTime(), originalPeer, msg.signature(), msg.tag()));
                    simple_translate$autoPeerMap.remove(content);
                    changed = true;
                    continue;
                }

                UUID messageId = simple_translate$extractMessageId(content);
                if (messageId == null) {
                    continue;
                }
                ButtonMessageData data = simple_translate$buttonMessages.get(messageId);
                if (data == null) {
                    continue;
                }
                boolean blocked = simple_translate$hasBlacklistedSourceText(data.originalMessage(), data.originalPlainText())
                        || simple_translate$containsBlacklistedText(content.getString())
                        || (data.translatedMessage() != null
                        && simple_translate$containsBlacklistedText(data.translatedMessage().getString()));
                if (blocked) {
                    data.setTranslatedMessage(null);
                    data.setState(ButtonMessageData.State.ORIGINAL);
                    Component originalWithButton = simple_translate$createMessageWithButton(
                            data.originalMessage(), messageId, ButtonMessageData.State.ORIGINAL);
                    simple_translate$processedMessages.put(System.identityHashCode(originalWithButton), true);
                    allMessages.set(i, new GuiMessage(msg.addedTime(), originalWithButton, msg.signature(), msg.tag()));
                    changed = true;
                }
            }
            if (changed) {
                this.rescaleChat();
            }
        } catch (Throwable t) {
            SimpleTranslateMod.getLogger().error("Failed to refresh chat after blacklist change", t);
        }
    }

    @Unique
    private boolean simple_translate$isBlacklisted(String text) {
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        return blacklist != null && blacklist.isBlacklisted(text);
    }

    @Unique
    private boolean simple_translate$containsBlacklistedText(String text) {
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        return blacklist != null && blacklist.containsBlacklistedEntry(text);
    }

    @Unique
    private boolean simple_translate$hasBlacklistedSourceText(Component message, String plainText) {
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist == null) {
            return false;
        }

        if (blacklist.isBlacklisted(plainText)) {
            return true;
        }

        if (message != null) {
            List<TextSegmentInfo> segments = new ArrayList<>();
            simple_translate$extractSegments(message, segments, Style.EMPTY);
            for (TextSegmentInfo segment : segments) {
                if (segment != null && blacklist.isBlacklisted(segment.text)) {
                    return true;
                }
            }
        }

        if (plainText == null || plainText.isBlank()) {
            return false;
        }

        int angleIndex = plainText.lastIndexOf('>');
        if (angleIndex >= 0 && angleIndex + 1 < plainText.length()
                && blacklist.isBlacklisted(plainText.substring(angleIndex + 1))) {
            return true;
        }

        int colonIndex = Math.max(plainText.lastIndexOf(':'), plainText.lastIndexOf('\uff1a'));
        if (colonIndex >= 0 && colonIndex + 1 < plainText.length()
                && blacklist.isBlacklisted(plainText.substring(colonIndex + 1))) {
            return true;
        }

        return false;
    }

    @Override
    public boolean simple_translate$refreshBlacklistedTranslations() {
        simple_translate$seenBlacklistRevision = -1L;
        simple_translate$syncBlacklistRevision();
        return true;
    }

    @Override
    public void simple_translate$onHoldOriginalChanged(HoldOriginalFeature feature, boolean holding) {
        if (feature != HoldOriginalFeature.CHAT) {
            return;
        }
        try {
            if (holding) {
                simple_translate$applyChatHold();
            } else {
                simple_translate$releaseChatHold();
            }
        } catch (Throwable t) {
            SimpleTranslateMod.getLogger().error("Chat hold toggle failed", t);
        }
    }

    @Unique
    private void simple_translate$applyChatHold() {
        simple_translate$holdSwappedTranslated.clear();
        boolean changed = false;

        for (int i = 0; i < allMessages.size(); i++) {
            GuiMessage msg = allMessages.get(i);
            Component content = msg.content();

            Component originalPeer = simple_translate$autoPeerMap.get(content);
            if (originalPeer != null) {
                simple_translate$holdSwappedTranslated.put(i, content);
                allMessages.set(i, new GuiMessage(msg.addedTime(), originalPeer, msg.signature(), msg.tag()));
                changed = true;
                continue;
            }

            UUID messageId = simple_translate$extractMessageId(content);
            if (messageId != null) {
                ButtonMessageData data = simple_translate$buttonMessages.get(messageId);
                if (data != null && data.state() == ButtonMessageData.State.TRANSLATED) {
                    Component originalWithButton = simple_translate$createMessageWithButton(
                            data.originalMessage(), messageId, ButtonMessageData.State.ORIGINAL);
                    simple_translate$processedMessages.put(System.identityHashCode(originalWithButton), true);
                    simple_translate$holdSwappedTranslated.put(i, content);
                    allMessages.set(i, new GuiMessage(msg.addedTime(), originalWithButton, msg.signature(), msg.tag()));
                    changed = true;
                }
            }
        }

        if (changed) {
            this.rescaleChat();
        }
    }

    @Unique
    private void simple_translate$releaseChatHold() {
        if (simple_translate$holdSwappedTranslated.isEmpty()) {
            return;
        }
        boolean changed = false;

        for (Map.Entry<Integer, Component> entry : simple_translate$holdSwappedTranslated.entrySet()) {
            int index = entry.getKey();
            Component translated = entry.getValue();
            if (index < 0 || index >= allMessages.size()) {
                continue;
            }
            GuiMessage current = allMessages.get(index);
            allMessages.set(index, new GuiMessage(current.addedTime(), translated, current.signature(), current.tag()));
            changed = true;
        }
        simple_translate$holdSwappedTranslated.clear();

        if (changed) {
            this.rescaleChat();
        }
    }
}
