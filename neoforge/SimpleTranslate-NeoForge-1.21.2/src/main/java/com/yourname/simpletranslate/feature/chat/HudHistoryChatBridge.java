package com.yourname.simpletranslate.feature.chat;
import com.yourname.simpletranslate.feature.hud.HudTranslationHistory;

import net.minecraft.client.Minecraft;

/**
 * Bridge implemented by ChatComponentMixin so HUD caption history can appear in
 * chat without re-entering vanilla addMessage or the normal chat translation path.
 */
public interface HudHistoryChatBridge {
    void simple_translate$upsertHudHistoryCaption(HudTranslationHistory.Entry entry);

    static boolean publish(HudTranslationHistory.Entry entry) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gui == null || minecraft.gui.getChat() == null) {
            return false;
        }
        if (!(minecraft.gui.getChat() instanceof HudHistoryChatBridge bridge)) {
            return false;
        }
        Runnable task = () -> bridge.simple_translate$upsertHudHistoryCaption(entry);
        if (minecraft.isSameThread()) {
            task.run();
        } else {
            minecraft.execute(task);
        }
        return true;
    }
}
