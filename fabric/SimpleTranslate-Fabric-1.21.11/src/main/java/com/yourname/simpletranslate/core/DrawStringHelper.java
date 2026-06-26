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
        Component rendered = translated == null ? original : translated;
        graphics.drawString(font, rendered, x, y, color, shadow);
        return x + font.width(rendered);
    }

    public static int text(GuiGraphics graphics, Font font, String original,
            int x, int y, int color, boolean shadow,
            Function<String, String> translator) {
        String translated = translator == null ? original : translator.apply(original);
        String rendered = translated == null ? original : translated;
        graphics.drawString(font, rendered, x, y, color, shadow);
        return x + font.width(rendered);
    }

    public static int sequence(GuiGraphics graphics, Font font, FormattedCharSequence original,
            int x, int y, int color, boolean shadow,
            FormattedCharSequence replacement) {
        FormattedCharSequence rendered = replacement == null ? original : replacement;
        graphics.drawString(font, rendered, x, y, color, shadow);
        return x + font.width(rendered);
    }
}
