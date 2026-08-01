package com.yourname.simpletranslate.feature.chat;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.feature.chat.ButtonMessageData;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.util.text.ITextComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Hold-Original support: temporarily swaps translated chat lines back to originals. */
public final class ChatHoldController {
    private final ChatTranslationController controller;
    private final List<HeldSwap> holdSwappedTranslated = new ArrayList<>();

    ChatHoldController(ChatTranslationController controller) {
        this.controller = controller;
    }

    void clearSwapState() {
        holdSwappedTranslated.clear();
    }

    void restoreTranslatedMessages() {
        holdSwappedTranslated.clear();
        boolean changed = false;
        List<ChatLine<ITextComponent>> allMessages = controller.access().simpleTranslateAllMessages();

        for (int i = 0; i < allMessages.size(); i++) {
            ChatLine<ITextComponent> msg = allMessages.get(i);
            ITextComponent content = msg.getMessage();

            ITextComponent originalPeer = controller.autoPeerMap().get(content);
            if (originalPeer != null) {
                allMessages.set(i, new ChatLine(msg.getAddedTime(), originalPeer, msg.getId()));
                changed = true;
                continue;
            }

            UUID messageId = ChatButtonController.extractMessageId(content);
            if (messageId == null) {
                continue;
            }
            ButtonMessageData data = controller.buttonMessages().get(messageId);
            if (data == null) {
                continue;
            }
            data.setState(ButtonMessageData.State.ORIGINAL);
            allMessages.set(i, new ChatLine(
                    msg.getAddedTime(), data.originalMessage(), msg.getId()));
            changed = true;
        }

        if (changed) {
            controller.access().simpleTranslateRescale();
        }
    }

    void applyChatHold() {
        holdSwappedTranslated.clear();
        boolean changed = false;
        List<ChatLine<ITextComponent>> allMessages = controller.access().simpleTranslateAllMessages();

        for (int i = 0; i < allMessages.size(); i++) {
            ChatLine<ITextComponent> msg = allMessages.get(i);
            ITextComponent content = msg.getMessage();

            ITextComponent originalPeer = controller.autoPeerMap().get(content);
            if (originalPeer != null) {
                allMessages.set(i, new ChatLine(msg.getAddedTime(), originalPeer, msg.getId()));
                holdSwappedTranslated.add(new HeldSwap(
                        new ChatMessageIdentity(originalPeer, originalPeer.getString(), msg.getAddedTime(), msg.getId(),
                                SimpleTranslateMod.getRuntimeRevision()),
                        content));
                changed = true;
                continue;
            }

            UUID messageId = ChatButtonController.extractMessageId(content);
            if (messageId != null) {
                ButtonMessageData data = controller.buttonMessages().get(messageId);
                if (data != null && data.state() == ButtonMessageData.State.TRANSLATED) {
                    ITextComponent originalWithButton = controller.buttons().createMessageWithButton(
                            data.originalMessage(), messageId, ButtonMessageData.State.ORIGINAL);
                    controller.markProcessed(originalWithButton);
                    allMessages.set(i, new ChatLine(msg.getAddedTime(), originalWithButton, msg.getId()));
                    holdSwappedTranslated.add(new HeldSwap(
                            new ChatMessageIdentity(originalWithButton, originalWithButton.getString(), msg.getAddedTime(),
                                    msg.getId(), SimpleTranslateMod.getRuntimeRevision()),
                            content));
                    changed = true;
                }
            }
        }

        if (changed) {
            controller.access().simpleTranslateRescale();
        }
    }

    void releaseChatHold() {
        if (holdSwappedTranslated.isEmpty()) {
            return;
        }
        List<HeldSwap> swaps = List.copyOf(holdSwappedTranslated);
        holdSwappedTranslated.clear();
        for (HeldSwap swap : swaps) {
            controller.replacer().replaceByIdentity(swap.originalIdentity(), swap.translated());
        }
    }

    private record HeldSwap(ChatMessageIdentity originalIdentity, ITextComponent translated) {
    }
}
