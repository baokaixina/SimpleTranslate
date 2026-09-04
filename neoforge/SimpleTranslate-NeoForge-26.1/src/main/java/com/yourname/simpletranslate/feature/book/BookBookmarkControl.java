package com.yourname.simpletranslate.feature.book;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class BookBookmarkControl {
    private static final int BOOK_WIDTH = 192;
    private static final int BOOK_HEIGHT = 192;
    private static final int BOOK_TOP = 2;
    private static final int WIDTH = 14;
    private static final int HEIGHT = 20;
    /** Clears the page border so the tab reads as sticking out of the book. */
    private static final int EDGE_TAB_GAP = 2;

    private BookBookmarkControl() {
    }

    public static void render(GuiGraphicsExtractor graphics, Font font, int screenWidth, int mouseX, int mouseY,
                              boolean active, boolean translating) {
        draw(graphics, font, getX(screenWidth), getY(), screenWidth, mouseX, mouseY,
                isMouseOver(screenWidth, mouseX, mouseY), active, translating);
    }

    /**
     * Draws the bookmark as a tab on the outer edge of a third-party book.
     *
     * <p>The vanilla book is one page with a wide margin, so the configured
     * offset lands the bookmark on empty parchment. A double-page book has no
     * such margin: the same relative position sits on top of the text. Hanging
     * the tab off the right edge keeps the page clear at any page size, and it
     * is where a reader expects a bookmark to stick out anyway. Scholar also
     * puts its own export button in that column.</p>
     *
     * <p>Only the vertical position stays adjustable, mapped from the
     * configured offset into the part of the edge below whatever tool buttons
     * the host book owns. The offset itself is never rewritten, so a vanilla
     * book keeps the exact spot the player chose.</p>
     */
    public static void renderEdgeTab(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight,
                                     int bookWidth, int bookHeight, int reservedTop,
                                     int mouseX, int mouseY, boolean active, boolean translating) {
        draw(graphics, font,
                getEdgeTabX(screenWidth, bookWidth),
                getEdgeTabY(screenHeight, bookHeight, reservedTop),
                screenWidth, mouseX, mouseY,
                isMouseOverEdgeTab(screenWidth, screenHeight, bookWidth, bookHeight, reservedTop,
                        mouseX, mouseY),
                active, translating);
    }

    /**
     * The bookmark is the mod's own control, so the whole-frame capture must
     * not read its label back and send it to the model. Book screens now take
     * part in that capture, which puts this control inside it.
     */
    private static void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int screenWidth,
                             int mouseX, int mouseY, boolean hovered,
                             boolean active, boolean translating) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            drawControl(graphics, font, x, y, screenWidth, mouseX, mouseY, hovered,
                    active, translating);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

    private static void drawControl(GuiGraphicsExtractor graphics, Font font, int x, int y, int screenWidth,
                                    int mouseX, int mouseY, boolean hovered,
                                    boolean active, boolean translating) {
        int fill = translating ? 0xFF5F7FB8 : (active ? 0xFF5E9B62 : 0xFFD2A24A);
        if (hovered) {
            fill = translating ? 0xFF7694CC : (active ? 0xFF74B678 : 0xFFE1B45D);
        }

        graphics.fill(x + 2, y + 2, x + WIDTH + 2, y + HEIGHT + 2, 0x66000000);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT - 4, 0xFF3F2817);
        graphics.fill(x + 1, y + 1, x + WIDTH - 1, y + HEIGHT - 5, fill);
        graphics.fill(x + 3, y + HEIGHT - 5, x + WIDTH / 2, y + HEIGHT - 1, fill);
        graphics.fill(x + WIDTH / 2, y + HEIGHT - 1, x + WIDTH - 3, y + HEIGHT - 5, fill);
        graphics.text(font, "T", x + 5, y + 5, 0xFFFFFFFF, false);

        if (hovered) {
            renderInternalTooltip(graphics, font,
                    Component.translatable(active
                            ? "screen.simple_translate.book.original_bookmark"
                            : "screen.simple_translate.book.translate_bookmark"),
                    screenWidth, mouseX, mouseY);
        }
    }

    private static void renderInternalTooltip(GuiGraphicsExtractor graphics, Font font, Component label, int screenWidth,
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
        graphics.text(font, label, x + padding, y + padding, 0xFFFFFFFF, false);
    }

    public static boolean isMouseOver(int screenWidth, double mouseX, double mouseY) {
        return contains(getX(screenWidth), getY(), mouseX, mouseY);
    }

    public static boolean isMouseOverEdgeTab(int screenWidth, int screenHeight,
                                             int bookWidth, int bookHeight, int reservedTop,
                                             double mouseX, double mouseY) {
        return contains(getEdgeTabX(screenWidth, bookWidth),
                getEdgeTabY(screenHeight, bookHeight, reservedTop), mouseX, mouseY);
    }

    private static boolean contains(int x, int y, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
    }

    /**
     * Just past the book's right edge, but never off screen: at a large GUI
     * scale the book can reach within a few pixels of the window, and a tab the
     * player cannot click is worse than one that overlaps the binding.
     */
    private static int getEdgeTabX(int screenWidth, int bookWidth) {
        int outside = (screenWidth + bookWidth) / 2 + EDGE_TAB_GAP;
        return Math.max(0, Math.min(outside, screenWidth - WIDTH - EDGE_TAB_GAP));
    }

    private static int getEdgeTabY(int screenHeight, int bookHeight, int reservedTop) {
        int top = Math.max(0, reservedTop);
        int bottom = Math.max(top, bookHeight - HEIGHT);
        return (screenHeight - bookHeight) / 2
                + top + scale(getOffsetY(), BOOK_HEIGHT - HEIGHT, bottom - top);
    }

    private static int scale(int offset, int vanillaRange, int range) {
        if (vanillaRange <= 0 || range <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(range, Math.round(offset * (float) range / vanillaRange)));
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
