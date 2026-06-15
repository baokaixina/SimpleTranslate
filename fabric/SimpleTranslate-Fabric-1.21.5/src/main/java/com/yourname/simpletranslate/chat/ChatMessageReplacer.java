package com.yourname.simpletranslate.chat;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.util.ChatMessageIdentity;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

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

    public ChatMessageIdentity captureIdentity(Component originalComponent, String originalText) {
        List<GuiMessage> allMessages = access.simpleTranslateAllMessages();
        for (GuiMessage msg : allMessages) {
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

    public boolean isIdentityCurrent(ChatMessageIdentity identity) {
        if (identity == null) {
            return false;
        }
        for (GuiMessage msg : access.simpleTranslateAllMessages()) {
            if (messageMatchesIdentity(msg, identity)) {
                return true;
            }
        }
        return false;
    }

    static boolean messageMatchesIdentity(GuiMessage msg, ChatMessageIdentity identity) {
        if (identity == null || msg == null) {
            return false;
        }
        if (identity.originalComponent != null && msg.content() == identity.originalComponent) {
            if (identity.addedTime < 0 || msg.addedTime() == identity.addedTime) {
                return true;
            }
        }
        if (identity.addedTime < 0
                || msg.addedTime() != identity.addedTime
                || msg.signature() != identity.signature
                || msg.tag() != identity.tag) {
            return false;
        }
        String currentText = msg.content() == null ? "" : msg.content().getString();
        return identity.originalText == null || identity.originalText.equals(currentText);
    }

    public boolean replaceByIdentity(ChatMessageIdentity identity, Component newComponent) {
        List<GuiMessage> allMessages = access.simpleTranslateAllMessages();
        try {
            for (int i = 0; i < allMessages.size(); i++) {
                GuiMessage msg = allMessages.get(i);
                if (messageMatchesIdentity(msg, identity)) {
                    allMessages.set(i, new GuiMessage(msg.addedTime(), newComponent, msg.signature(), msg.tag()));
                    access.simpleTranslateRescale();
                    return true;
                }
            }
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to replace message by identity", e);
        }
        return false;
    }

    /** Replaces the most recent message carrying the given click-event value. */
    public boolean replaceByClickValue(String clickValue, Component newComponent, int searchLimit) {
        List<GuiMessage> allMessages = access.simpleTranslateAllMessages();
        try {
            for (int i = 0; i < Math.min(allMessages.size(), searchLimit); i++) {
                GuiMessage msg = allMessages.get(i);
                if (containsClickEvent(msg.content(), clickValue)) {
                    allMessages.set(i, new GuiMessage(msg.addedTime(), newComponent, msg.signature(), msg.tag()));
                    access.simpleTranslateRescale();
                    return true;
                }
            }
        } catch (Exception e) {
            SimpleTranslateMod.getLogger().error("Failed to update message", e);
        }
        return false;
    }

    public static boolean containsClickEvent(Component component, String clickValue) {
        Style style = component.getStyle();
        if (style != null && style.getClickEvent() != null) {
            if (clickValue.equals(suggestCommandValue(style.getClickEvent()))) {
                return true;
            }
        }
        for (Component sibling : component.getSiblings()) {
            if (containsClickEvent(sibling, clickValue)) {
                return true;
            }
        }
        return false;
    }

    public static String suggestCommandValue(ClickEvent clickEvent) {
        if (clickEvent instanceof ClickEvent.SuggestCommand suggestCommand) {
            return suggestCommand.command();
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
