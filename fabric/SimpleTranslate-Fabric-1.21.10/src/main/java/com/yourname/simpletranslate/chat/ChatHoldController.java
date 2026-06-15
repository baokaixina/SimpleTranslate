package com.yourname.simpletranslate.chat;

import com.yourname.simpletranslate.util.ButtonMessageData;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Hold-Original support: temporarily swaps translated chat lines back to originals. */
public final class ChatHoldController {
    private final ChatTranslationController controller;
    private final Map<Integer, Component> holdSwappedTranslated = new HashMap<>();

    ChatHoldController(ChatTranslationController controller) {
        this.controller = controller;
    }

    void clearSwapState() {
        holdSwappedTranslated.clear();
    }

    void applyChatHold() {
        holdSwappedTranslated.clear();
        boolean changed = false;
        List<GuiMessage> allMessages = controller.access().simpleTranslateAllMessages();

        for (int i = 0; i < allMessages.size(); i++) {
            GuiMessage msg = allMessages.get(i);
            Component content = msg.content();

            Component originalPeer = controller.autoPeerMap().get(content);
            if (originalPeer != null) {
                holdSwappedTranslated.put(i, content);
                allMessages.set(i, new GuiMessage(msg.addedTime(), originalPeer, msg.signature(), msg.tag()));
                changed = true;
                continue;
            }

            UUID messageId = ChatButtonController.extractMessageId(content);
            if (messageId != null) {
                ButtonMessageData data = controller.buttonMessages().get(messageId);
                if (data != null && data.state() == ButtonMessageData.State.TRANSLATED) {
                    Component originalWithButton = controller.buttons().createMessageWithButton(
                            data.originalMessage(), messageId, ButtonMessageData.State.ORIGINAL);
                    controller.markProcessed(originalWithButton);
                    holdSwappedTranslated.put(i, content);
                    allMessages.set(i, new GuiMessage(msg.addedTime(), originalWithButton, msg.signature(), msg.tag()));
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
        boolean changed = false;
        List<GuiMessage> allMessages = controller.access().simpleTranslateAllMessages();

        for (Map.Entry<Integer, Component> entry : holdSwappedTranslated.entrySet()) {
            int index = entry.getKey();
            Component translated = entry.getValue();
            if (index < 0 || index >= allMessages.size()) {
                continue;
            }
            GuiMessage current = allMessages.get(index);
            allMessages.set(index, new GuiMessage(current.addedTime(), translated, current.signature(), current.tag()));
            changed = true;
        }
        holdSwappedTranslated.clear();

        if (changed) {
            controller.access().simpleTranslateRescale();
        }
    }
}
