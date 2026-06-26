package com.yourname.simpletranslate.core;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.function.Function;

public final class DrawStringHelper {
    private DrawStringHelper() {
    }

    public static int component(GuiGraphics graphics, Font font, Component original,
            int x, int y, int color, boolean shadow,
            Function<Component, Component> translator) {
        Component translated = translator == null ? original : translator.apply(original);
        return graphics.drawString(font, translated == null ? original : translated, x, y, color, shadow);
    }

    public static int text(GuiGraphics graphics, Font font, String original,
            int x, int y, int color, boolean shadow,
            Function<String, String> translator) {
        String translated = translator == null ? original : translator.apply(original);
        return graphics.drawString(font, translated == null ? original : translated, x, y, color, shadow);
    }

    public static int sequence(GuiGraphics graphics, Font font, FormattedCharSequence original,
            int x, int y, int color, boolean shadow,
            FormattedCharSequence replacement) {
        return graphics.drawString(font, replacement == null ? original : replacement, x, y, color, shadow);
    }
}
