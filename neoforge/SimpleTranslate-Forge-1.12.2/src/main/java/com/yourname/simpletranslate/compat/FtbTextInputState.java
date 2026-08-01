package com.yourname.simpletranslate.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import java.util.Map;
import java.util.WeakHashMap;

/** Optional FTBLib TextBox focus bridge without a compile-time FTBLib dependency. */
public final class FtbTextInputState {
    private static final Map<Object, Boolean> FOCUSED = new WeakHashMap<Object, Boolean>();
    private static GuiScreen ownerScreen;

    private FtbTextInputState() { }

    public static synchronized void setFocused(Object textBox, boolean focused) {
        GuiScreen current = currentScreen();
        if (ownerScreen != current) {
            FOCUSED.clear();
            ownerScreen = current;
        }
        if (textBox == null) return;
        if (focused) FOCUSED.put(textBox, Boolean.TRUE);
        else FOCUSED.remove(textBox);
    }

    public static synchronized boolean hasFocused() {
        GuiScreen current = currentScreen();
        if (ownerScreen != current) {
            FOCUSED.clear();
            ownerScreen = current;
        }
        return !FOCUSED.isEmpty();
    }

    public static synchronized void clear() {
        FOCUSED.clear();
        ownerScreen = null;
    }

    private static GuiScreen currentScreen() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft == null ? null : minecraft.currentScreen;
    }
}
