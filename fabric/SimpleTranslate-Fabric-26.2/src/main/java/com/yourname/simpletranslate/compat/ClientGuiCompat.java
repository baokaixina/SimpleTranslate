package com.yourname.simpletranslate.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;

public final class ClientGuiCompat {
    private ClientGuiCompat() {
    }

    public static Screen screen(Minecraft minecraft) {
        return minecraft == null || minecraft.gui == null ? null : minecraft.gui.screen();
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        if (minecraft != null && minecraft.gui != null) {
            minecraft.gui.setScreen(screen);
        }
    }

    public static ChatComponent chat(Minecraft minecraft) {
        return minecraft == null || minecraft.gui == null || minecraft.gui.hud == null
                ? null
                : minecraft.gui.hud.getChat();
    }

    public static int guiTicks(Minecraft minecraft) {
        return minecraft == null || minecraft.gui == null || minecraft.gui.hud == null
                ? 0
                : minecraft.gui.hud.getGuiTicks();
    }
}
