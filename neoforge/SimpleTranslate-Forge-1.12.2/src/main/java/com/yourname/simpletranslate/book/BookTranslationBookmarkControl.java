package com.yourname.simpletranslate.book;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;
import com.yourname.simpletranslate.config.ModConfig;

/**
 * Visible translate/original control for a vanilla 1.12.2 book screen.
 *
 * <p>It intentionally mirrors the small, left-edge {@code T} bookmark from
 * the current product while using only the exact legacy Gui/FontRenderer
 * drawing API.</p>
 */
public final class BookTranslationBookmarkControl {
    private static final int BOOK_WIDTH = 192;
    private static final int BOOK_HEIGHT = 192;
    private static final int BOOK_TOP = 2;
    private static final int WIDTH = 14;
    private static final int HEIGHT = 20;

    private BookTranslationBookmarkControl() {
    }

    public static void render(FontRenderer font, int screenWidth, int mouseX, int mouseY,
                              boolean active, boolean translating) {
        int x = getX(screenWidth);
        int y = getY();
        boolean hovered = isMouseOver(screenWidth, mouseX, mouseY);
        int fill = translating ? 0xFF5F7FB8 : (active ? 0xFF5E9B62 : 0xFFD2A24A);
        if (hovered) fill = translating ? 0xFF7694CC : (active ? 0xFF74B678 : 0xFFE1B45D);

        Gui.drawRect(x + 2, y + 2, x + WIDTH + 2, y + HEIGHT + 2, 0x66000000);
        Gui.drawRect(x, y, x + WIDTH, y + HEIGHT - 4, 0xFF3F2817);
        Gui.drawRect(x + 1, y + 1, x + WIDTH - 1, y + HEIGHT - 5, fill);
        Gui.drawRect(x + 3, y + HEIGHT - 5, x + WIDTH / 2, y + HEIGHT - 1, fill);
        Gui.drawRect(x + WIDTH / 2, y + HEIGHT - 1, x + WIDTH - 3, y + HEIGHT - 5, fill);
        font.drawString("T", x + 5, y + 5, 0xFFFFFFFF);

        if (hovered) renderTooltip(font, screenWidth, mouseX, mouseY, active);
    }

    public static boolean isMouseOver(int screenWidth, int mouseX, int mouseY) {
        int x = getX(screenWidth);
        int y = getY();
        return mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
    }

    private static int getX(int screenWidth) {
        int offset = Math.max(0, Math.min(ModConfig.CONTENT_BOOK_BOOKMARK_OFFSET_X.get(), BOOK_WIDTH - WIDTH));
        return (screenWidth - BOOK_WIDTH) / 2 + offset;
    }

    private static int getY() {
        int offset = Math.max(0, Math.min(ModConfig.CONTENT_BOOK_BOOKMARK_OFFSET_Y.get(), BOOK_HEIGHT - HEIGHT));
        return BOOK_TOP + offset;
    }

    private static void renderTooltip(FontRenderer font, int screenWidth, int mouseX, int mouseY, boolean active) {
        String label = I18n.format(active
                ? "screen.simple_translate.book.original_bookmark"
                : "screen.simple_translate.book.translate_bookmark");
        int padding = 4;
        int width = font.getStringWidth(label) + padding * 2;
        int height = font.FONT_HEIGHT + padding * 2;
        int x = Math.min(mouseX + 12, Math.max(4, screenWidth - width - 4));
        int y = Math.max(4, mouseY - height - 4);
        Gui.drawRect(x - 1, y - 1, x + width + 1, y + height + 1, 0xF0100010);
        Gui.drawRect(x, y, x + width, y + height, 0xF0100010);
        font.drawStringWithShadow(label, x + padding, y + padding, 0xFFFFFFFF);
    }
}
