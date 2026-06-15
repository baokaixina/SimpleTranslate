package com.yourname.simpletranslate.gui;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

final class UiCompat {
    private UiCompat() {
    }

    static ButtonBuilder buttonBuilder(Component message, Button.OnPress onPress) {
        return new ButtonBuilder(message, onPress);
    }

    static void setHint(EditBox editBox, Component hint) {
        if (editBox != null && hint != null) {
            editBox.setSuggestion(hint.getString());
        }
    }

    static int getX(AbstractWidget widget) {
        return widget == null ? 0 : widget.x;
    }

    static int getY(AbstractWidget widget) {
        return widget == null ? 0 : widget.y;
    }

    static void setX(AbstractWidget widget, int x) {
        if (widget != null) {
            widget.x = x;
        }
    }

    static void setY(AbstractWidget widget, int y) {
        if (widget != null) {
            widget.y = y;
        }
    }

    static final class ButtonBuilder {
        private final Component message;
        private final Button.OnPress onPress;
        private int x;
        private int y;
        private int width = Button.DEFAULT_WIDTH;
        private int height = Button.DEFAULT_HEIGHT;

        private ButtonBuilder(Component message, Button.OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        ButtonBuilder bounds(int x, int y, int width, int height) {
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

