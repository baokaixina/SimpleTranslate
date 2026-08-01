package net.minecraft.client.gui.narration;

import net.minecraft.network.chat.Component;

/** No-op narration sink used by widgets on the pre-1.19 screen API. */
public interface NarrationElementOutput {
    default void add(NarratedElementType type, Component component) {
    }
}
