package com.yourname.simpletranslate.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Minecraft 1.19.2 has no {@code Button.builder(...)}: the fluent builder was
 * added in 1.19.3. This facade reproduces the tiny builder subset the product
 * uses ({@code builder(message, onPress).bounds(x, y, w, h).build()}) on top of
 * the 1.19.2 Button constructor so shared screen code stays source-identical
 * to the donor tree except for the receiver name.
 */
public final class ButtonCompat {
    private ButtonCompat() {
    }

    public static Builder builder(Component message, Button.OnPress onPress) {
        return new Builder(message, onPress);
    }

    public static final class Builder {
        private final Component message;
        private final Button.OnPress onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        private Builder(Component message, Button.OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public Button build() {
            return new Button(this.x, this.y, this.width, this.height, this.message, this.onPress);
        }
    }
}
