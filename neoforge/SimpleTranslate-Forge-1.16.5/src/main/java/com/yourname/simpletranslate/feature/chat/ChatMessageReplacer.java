package com.yourname.simpletranslate.feature.chat;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.feature.chat.ChatMessageIdentity;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;

import java.util.List;

/**
 * Locates and replaces displayed chat lines, either by captured identity
 * (AUTO mode) or by button click-event value (BUTTON mode / HUD history).
 */
public final class ChatMessageReplacer {
    private final ChatComponentAccess access;

    public ChatMessageReplacer(ChatComponentAccess access) {
        this.access = access;
    }

    public ChatMessageIdentity captureIdentity(ITextComponent originalComponent, String originalText) {
        List<ChatLine<ITextComponent>> allMessages = access.simpleTranslateAllMessages();
        for (ChatLine<ITextComponent> msg : allMessages) {
            if (msg.getMessage() == originalComponent) {
                return new ChatMessageIdentity(
                        originalComponent,
                        originalText,
                        msg.getAddedTime(),
                        msg.getId(),
                        SimpleTranslateMod.getRuntimeRevision());
            }
        }
        return new ChatMessageIdentity(
                originalComponent,
                originalText,
                -1,
                -1,
                SimpleTranslateMod.getRuntimeRevision());
    }

    public boolean isIdentityCurrent(ChatMessageIdentity identity) {
        if (identity == null) {
            return false;
        }
        for (ChatLine<ITextComponent> msg : access.simpleTranslateAllMessages()) {
            if (messageMatchesIdentity(msg, identity)) {
                return true;
            }
        }
        return false;
    }

    static boolean messageMatchesIdentity(ChatLine<ITextComponent> msg, ChatMessageIdentity identity) {
        if (identity == null || msg == null) {
            return false;
        }
        if (identity.messageId >= 0 && msg.getId() != identity.messageId) {
            return false;
        }
        if (identity.addedTime >= 0 && msg.getAddedTime() != identity.addedTime) {
            return false;
        }
        if (identity.originalComponent != null && msg.getMessage() == identity.originalComponent) {
            return true;
        }
        if (identity.addedTime < 0 && identity.messageId < 0) {
            return false;
        }
        String currentText = msg.getMessage() == null ? "" : msg.getMessage().getString();
        return identity.originalText == null || identity.originalText.equals(currentText);
    }

    public boolean replaceByIdentity(ChatMessageIdentity identity, ITextComponent newComponent) {
        List<ChatLine<ITextComponent>> allMessages = access.simpleTranslateAllMessages();
        try {
            for (int i = 0; i < allMessages.size(); i++) {
                ChatLine<ITextComponent> msg = allMessages.get(i);
                if (messageMatchesIdentity(msg, identity)) {
                    allMessages.set(i, new ChatLine(msg.getAddedTime(), newComponent, msg.getId()));
                    access.simpleTranslateRescale();
                    return true;
                }
            }
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to replace message by identity", e);
        }
        return false;
    }

    public boolean replaceByIdentities(List<ChatMessageIdentity> identities, List<ITextComponent> newComponents) {
        if (identities == null || newComponents == null || identities.size() != newComponents.size()) {
            return false;
        }
        List<ChatLine<ITextComponent>> allMessages = access.simpleTranslateAllMessages();
        int[] indexes = new int[identities.size()];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = -1;
        }
        try {
            for (int identityIndex = 0; identityIndex < identities.size(); identityIndex++) {
                ChatMessageIdentity identity = identities.get(identityIndex);
                ITextComponent component = newComponents.get(identityIndex);
                if (identity == null || component == null) {
                    return false;
                }
                for (int messageIndex = 0; messageIndex < allMessages.size(); messageIndex++) {
                    if (containsIndex(indexes, identityIndex, messageIndex)) {
                        continue;
                    }
                    ChatLine<ITextComponent> msg = allMessages.get(messageIndex);
                    if (messageMatchesIdentity(msg, identity)) {
                        indexes[identityIndex] = messageIndex;
                        break;
                    }
                }
                if (indexes[identityIndex] < 0) {
                    return false;
                }
            }

            for (int i = 0; i < indexes.length; i++) {
                ChatLine<ITextComponent> msg = allMessages.get(indexes[i]);
                allMessages.set(indexes[i], new ChatLine(
                        msg.getAddedTime(), newComponents.get(i), msg.getId()));
            }
            access.simpleTranslateRescale();
            return true;
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to replace chat message block", e);
        }
        return false;
    }

    private static boolean containsIndex(int[] indexes, int limit, int value) {
        for (int i = 0; i < limit && i < indexes.length; i++) {
            if (indexes[i] == value) {
                return true;
            }
        }
        return false;
    }

    /** Replaces the most recent message carrying the given click-event value. */
    public boolean replaceByClickValue(String clickValue, ITextComponent newComponent, int searchLimit) {
        List<ChatLine<ITextComponent>> allMessages = access.simpleTranslateAllMessages();
        try {
            for (int i = 0; i < Math.min(allMessages.size(), searchLimit); i++) {
                ChatLine<ITextComponent> msg = allMessages.get(i);
                if (containsClickEvent(msg.getMessage(), clickValue)) {
                    allMessages.set(i, new ChatLine(msg.getAddedTime(), newComponent, msg.getId()));
                    access.simpleTranslateRescale();
                    return true;
                }
            }
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to update message", e);
        }
        return false;
    }

    public static boolean containsClickEvent(ITextComponent component, String clickValue) {
        Style style = component.getStyle();
        if (style != null && style.getClickEvent() != null) {
            if (clickValue.equals(suggestCommandValue(style.getClickEvent()))) {
                return true;
            }
        }
        for (ITextComponent sibling : component.getSiblings()) {
            if (containsClickEvent(sibling, clickValue)) {
                return true;
            }
        }
        return false;
    }

    public static String suggestCommandValue(ClickEvent clickEvent) {
        // 1.20.1 ClickEvent is an Action/value pair, not a sealed hierarchy.
        if (clickEvent != null && clickEvent.getAction() == ClickEvent.Action.SUGGEST_COMMAND) {
            return clickEvent.getValue();
        }
        return null;
    }

    public static void runOnClientThread(Runnable action) {
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
}
