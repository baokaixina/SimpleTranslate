package com.yourname.simpletranslate.feature.tooltip;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;

import java.util.List;

/** Java-8/LWJGL2 adapter for the baseline's animated pending-tooltip halo. */
public final class TooltipTranslationGlowRenderer {
    private static final double HIGHLIGHT_RADIUS = 0.18D;
    private static final double BASE_INTENSITY = 0.20D;
    private static volatile String visibleRequestSignature;

    private TooltipTranslationGlowRenderer() { }

    public static void observe(String requestSignature) {
        visibleRequestSignature = requestSignature;
    }

    public static void clear() { visibleRequestSignature = null; }

    public static void renderPending(GuiScreen screen, FontRenderer font, List<String> lines, int mouseX, int mouseY) {
        String signature = visibleRequestSignature;
        visibleRequestSignature = null;
        if (!TooltipRequestTriggerState.isPending(signature) || screen == null || font == null
                || lines == null || lines.isEmpty() || !ModConfig.TOOLTIP_GLOW_ENABLED.get()) return;

        int width = 0;
        for (String line : lines) if (line != null) width = Math.max(width, font.getStringWidth(line));
        int height = lines.size() == 1 ? 8 : 10 + (lines.size() - 1) * 10;
        int left = mouseX + 12;
        int top = mouseY - 12;
        if (left + width + 6 > screen.width) left = mouseX - 28 - width;
        if (top + height + 6 > screen.height) top = screen.height - height - 6;
        drawBounds(left - 3, top - 3, left + width + 3, top + height + 3);
    }

    private static void drawBounds(int left, int top, int right, int bottom) {
        int width = Math.max(1, right - left);
        int height = Math.max(1, bottom - top);
        int perimeter = Math.max(1, 2 * (width + height));
        int cycle = Math.max(1, ModConfig.TOOLTIP_GLOW_CYCLE_MS.get());
        double moving = (System.currentTimeMillis() % cycle) / (double) cycle;
        drawHorizontal(left, right, top, false, 0, perimeter, moving);
        drawVertical(right, top, bottom, false, width, perimeter, moving);
        drawHorizontal(left, right, bottom, true, width + height, perimeter, moving);
        drawVertical(left, top, bottom, true, width + height + width, perimeter, moving);
    }

    private static void drawHorizontal(int left, int right, int y, boolean bottom,
                                       int offset, int perimeter, double moving) {
        int length = Math.max(1, right - left);
        int segments = Math.min(96, Math.max(16, length));
        for (int segment = 0; segment < segments; segment++) {
            int start = length * segment / segments;
            int end = Math.max(start + 1, length * (segment + 1) / segments);
            int x0 = bottom ? right - end : left + start;
            int x1 = bottom ? right - start : left + end;
            double phase = (offset + (start + end) * 0.5D) / perimeter;
            int alpha = animatedAlpha(phase, moving);
            int line = ModConfig.TOOLTIP_GLOW_LINE_WIDTH.get();
            Gui.drawRect(x0, bottom ? y - line : y, x1, bottom ? y : y + line, color(phase, moving, alpha));
            int spread = ModConfig.TOOLTIP_GLOW_SPREAD.get();
            for (int layer = 1; layer <= spread; layer++) {
                int glowY = bottom ? y + layer - 1 : y - layer;
                Gui.drawRect(x0, glowY, x1, glowY + 1, color(phase, moving, glowAlpha(alpha, layer, spread)));
            }
        }
    }

    private static void drawVertical(int x, int top, int bottom, boolean left,
                                     int offset, int perimeter, double moving) {
        int length = Math.max(1, bottom - top);
        int segments = Math.min(96, Math.max(16, length));
        for (int segment = 0; segment < segments; segment++) {
            int start = length * segment / segments;
            int end = Math.max(start + 1, length * (segment + 1) / segments);
            int y0 = left ? bottom - end : top + start;
            int y1 = left ? bottom - start : top + end;
            double phase = (offset + (start + end) * 0.5D) / perimeter;
            int alpha = animatedAlpha(phase, moving);
            int line = ModConfig.TOOLTIP_GLOW_LINE_WIDTH.get();
            Gui.drawRect(left ? x : x - line, y0, left ? x + line : x, y1, color(phase, moving, alpha));
            int spread = ModConfig.TOOLTIP_GLOW_SPREAD.get();
            for (int layer = 1; layer <= spread; layer++) {
                int glowX = left ? x - layer : x + layer - 1;
                Gui.drawRect(glowX, y0, glowX + 1, y1, color(phase, moving, glowAlpha(alpha, layer, spread)));
            }
        }
    }

    private static int animatedAlpha(double phase, double moving) {
        double distance = Math.abs(phase - moving);
        distance = Math.min(distance, 1.0D - distance);
        double highlight = Math.max(0.0D, 1.0D - distance / HIGHLIGHT_RADIUS);
        highlight = highlight * highlight * (3.0D - 2.0D * highlight);
        return clamp((int) Math.round(ModConfig.TOOLTIP_GLOW_OPACITY.get()
                * (BASE_INTENSITY + (1.0D - BASE_INTENSITY) * highlight)));
    }

    private static int glowAlpha(int alpha, int layer, int spread) {
        if (spread <= 0) return 0;
        double falloff = 1.0D - layer / (double) (spread + 1);
        return clamp((int) Math.round(alpha * 0.42D * falloff * falloff));
    }

    private static int color(double phase, double moving, int alpha) {
        int[] palette = palette();
        double wrapped = phase + moving * 0.35D;
        wrapped -= Math.floor(wrapped);
        double scaled = wrapped * palette.length;
        int index = Math.min(palette.length - 1, (int) scaled);
        int next = (index + 1) % palette.length;
        int rgb = blend(palette[index], palette[next], scaled - index);
        return (clamp(alpha) << 24) | rgb;
    }

    private static int[] palette() {
        switch (ModConfig.TOOLTIP_GLOW_THEME.get()) {
            case OCEAN: return new int[]{0x45D6D0, 0x4A9FE8, 0x456FE8};
            case AURORA: return new int[]{0x57E389, 0x5ED8D0, 0x9E78E8};
            case SUNSET: return new int[]{0xF0A36B, 0xE77E9B, 0xB28ADE};
            case SOFT:
            default: return new int[]{0x72D9D0, 0x78A8E8, 0xB39DDB};
        }
    }

    private static int blend(int first, int second, double amount) {
        int red = lerp((first >> 16) & 255, (second >> 16) & 255, amount);
        int green = lerp((first >> 8) & 255, (second >> 8) & 255, amount);
        int blue = lerp(first & 255, second & 255, amount);
        return (red << 16) | (green << 8) | blue;
    }

    private static int lerp(int first, int second, double amount) {
        return (int) Math.round(first + (second - first) * amount);
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
