package com.yourname.simpletranslate.feature.book;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.Font;
import com.yourname.simpletranslate.compat.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class BookBookmarkControl {
    private static final int BOOK_WIDTH = 192;
    private static final int BOOK_HEIGHT = 192;
    private static final int BOOK_TOP = 2;
    private static final int WIDTH = 14;
    private static final int HEIGHT = 20;

    private BookBookmarkControl() {
    }

    public static void render(GuiGraphics graphics, Font font, int screenWidth, int mouseX, int mouseY,
                              boolean active, boolean translating) {
        int x = getX(screenWidth);
        int y = getY();
        boolean hovered = isMouseOver(screenWidth, mouseX, mouseY);

        int fill = translating ? 0xFF5F7FB8 : (active ? 0xFF5E9B62 : 0xFFD2A24A);
        if (hovered) {
            fill = translating ? 0xFF7694CC : (active ? 0xFF74B678 : 0xFFE1B45D);
        }

        graphics.fill(x + 2, y + 2, x + WIDTH + 2, y + HEIGHT + 2, 0x66000000);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT - 4, 0xFF3F2817);
        graphics.fill(x + 1, y + 1, x + WIDTH - 1, y + HEIGHT - 5, fill);
        graphics.fill(x + 3, y + HEIGHT - 5, x + WIDTH / 2, y + HEIGHT - 1, fill);
        graphics.fill(x + WIDTH / 2, y + HEIGHT - 1, x + WIDTH - 3, y + HEIGHT - 5, fill);
        graphics.drawString(font, "T", x + 5, y + 5, 0xFFFFFFFF, false);

        if (hovered) {
            renderInternalTooltip(graphics, font,
                    Component.translatable(active
                            ? "screen.simple_translate.book.original_bookmark"
                            : "screen.simple_translate.book.translate_bookmark"),
                    screenWidth, mouseX, mouseY);
        }
    }

    private static void renderInternalTooltip(GuiGraphics graphics, Font font, Component label, int screenWidth,
                                              int mouseX, int mouseY) {
        int padding = 4;
        int width = font.width(label) + padding * 2;
        int height = 8 + padding * 2;
        int x = Math.min(mouseX + 12, Math.max(4, screenWidth - width - 4));
        int y = mouseY - 12;
        if (y < 4) {
            y = mouseY + 12;
        }

        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xF0100010);
        graphics.fill(x, y, x + width, y + height, 0xF0100010);
        graphics.drawString(font, label, x + padding, y + padding, 0xFFFFFFFF, false);
    }

    public static boolean isMouseOver(int screenWidth, double mouseX, double mouseY) {
        int x = getX(screenWidth);
        int y = getY();
        return mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
    }

    private static int getX(int screenWidth) {
        return (screenWidth - BOOK_WIDTH) / 2 + getOffsetX();
    }

    private static int getY() {
        return BOOK_TOP + getOffsetY();
    }

    private static int getOffsetX() {
        int offsetX = ModConfig.CONTENT_BOOK_BOOKMARK_OFFSET_X.get();
        int clamped = Math.max(0, Math.min(offsetX, BOOK_WIDTH - WIDTH));
        if (clamped != offsetX) {
            ModConfig.CONTENT_BOOK_BOOKMARK_OFFSET_X.set(clamped);
        }
        return clamped;
    }

    private static int getOffsetY() {
        int offsetY = ModConfig.CONTENT_BOOK_BOOKMARK_OFFSET_Y.get();
        int clamped = Math.max(0, Math.min(offsetY, BOOK_HEIGHT - HEIGHT));
        if (clamped != offsetY) {
            ModConfig.CONTENT_BOOK_BOOKMARK_OFFSET_Y.set(clamped);
        }
        return clamped;
    }
}
