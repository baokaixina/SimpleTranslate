package com.yourname.simpletranslate.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

final class ButtonCompat {
    private ButtonCompat() {
    }

    static Builder builder(Component message, Button.OnPress onPress) {
        return new Builder(message, onPress);
    }

    static final class Builder {
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

        Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        Button build() {
            return new Button(this.x, this.y, this.width, this.height, this.message, this.onPress);
        }
    }
}


