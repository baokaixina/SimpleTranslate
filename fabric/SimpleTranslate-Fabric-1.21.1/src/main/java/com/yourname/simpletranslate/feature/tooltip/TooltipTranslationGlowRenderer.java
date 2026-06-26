package com.yourname.simpletranslate.feature.tooltip;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.joml.Vector2ic;

import java.util.List;

/** Draws the same moving halo for live previews and pending tooltips. */
public final class TooltipTranslationGlowRenderer {
    private static final double HIGHLIGHT_RADIUS = 0.18D;
    private static final double BASE_INTENSITY = 0.20D;

    private TooltipTranslationGlowRenderer() {
    }

    public static void render(GuiGraphics graphics, Font font,
                              List<ClientTooltipComponent> components,
                              int mouseX, int mouseY,
                              ClientTooltipPositioner positioner) {
        if (graphics == null || font == null || components == null || components.isEmpty()
                || positioner == null || !ModConfig.TOOLTIP_GLOW_ENABLED.get()) {
            return;
        }

        int width = 0;
        int height = components.size() == 1 ? -2 : 0;
        for (ClientTooltipComponent component : components) {
            width = Math.max(width, component.getWidth(font));
            height += component.getHeight();
        }
        if (width <= 0 || height <= 0) {
            return;
        }

        Vector2ic position = positioner.positionTooltip(
                graphics.guiWidth(), graphics.guiHeight(), mouseX, mouseY, width, height);
        renderTooltipBounds(graphics, position.x() - 3, position.y() - 3,
                position.x() + width + 3, position.y() + height + 3, 500.0F);
    }

    public static void renderPreview(GuiGraphics graphics, int left, int top, int right, int bottom) {
        if (ModConfig.TOOLTIP_GLOW_ENABLED.get()) {
            renderTooltipBounds(graphics, left, top, right, bottom, 5.0F);
        }
    }

    private static void renderTooltipBounds(GuiGraphics graphics, int left, int top,
                                            int right, int bottom, float z) {
        int width = Math.max(1, right - left);
        int height = Math.max(1, bottom - top);
        int perimeter = Math.max(1, 2 * (width + height));
        int cycleMillis = Math.max(1, ModConfig.TOOLTIP_GLOW_CYCLE_MS.get());
        double movingPhase = (System.currentTimeMillis() % cycleMillis) / (double) cycleMillis;

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, z);
        drawHorizontal(graphics, left, right, top, false, 0, perimeter, movingPhase);
        drawVertical(graphics, right, top, bottom, false, width, perimeter, movingPhase);
        drawHorizontal(graphics, left, right, bottom, true, width + height, perimeter, movingPhase);
        drawVertical(graphics, left, top, bottom, true, width + height + width, perimeter, movingPhase);
        graphics.pose().popPose();
    }

    private static void drawHorizontal(GuiGraphics graphics, int left, int right, int y,
                                       boolean reverse, int pathOffset, int perimeter,
                                       double movingPhase) {
        int length = Math.max(1, right - left);
        int segments = Math.min(96, Math.max(16, length));
        for (int segment = 0; segment < segments; segment++) {
            int start = length * segment / segments;
            int end = Math.max(start + 1, length * (segment + 1) / segments);
            int x0 = reverse ? right - end : left + start;
            int x1 = reverse ? right - start : left + end;
            double pathPhase = (pathOffset + (start + end) * 0.5D) / perimeter;
            drawHorizontalSegment(graphics, x0, x1, y, reverse, pathPhase, movingPhase);
        }
    }

    private static void drawVertical(GuiGraphics graphics, int x, int top, int bottom,
                                     boolean reverse, int pathOffset, int perimeter,
                                     double movingPhase) {
        int length = Math.max(1, bottom - top);
        int segments = Math.min(96, Math.max(16, length));
        for (int segment = 0; segment < segments; segment++) {
            int start = length * segment / segments;
            int end = Math.max(start + 1, length * (segment + 1) / segments);
            int y0 = reverse ? bottom - end : top + start;
            int y1 = reverse ? bottom - start : top + end;
            double pathPhase = (pathOffset + (start + end) * 0.5D) / perimeter;
            drawVerticalSegment(graphics, x, y0, y1, reverse, pathPhase, movingPhase);
        }
    }

    private static void drawHorizontalSegment(GuiGraphics graphics, int x0, int x1, int y,
                                              boolean bottomEdge, double pathPhase,
                                              double movingPhase) {
        int lineWidth = ModConfig.TOOLTIP_GLOW_LINE_WIDTH.get();
        int spread = ModConfig.TOOLTIP_GLOW_SPREAD.get();
        int alpha = animatedAlpha(pathPhase, movingPhase);
        graphics.fill(x0, bottomEdge ? y - lineWidth : y,
                x1, bottomEdge ? y : y + lineWidth,
                animatedColor(pathPhase, movingPhase, alpha));
        for (int layer = 1; layer <= spread; layer++) {
            int layerAlpha = glowAlpha(alpha, layer, spread);
            int glowY = bottomEdge ? y + layer - 1 : y - layer;
            graphics.fill(x0, glowY, x1, glowY + 1,
                    animatedColor(pathPhase, movingPhase, layerAlpha));
        }
    }

    private static void drawVerticalSegment(GuiGraphics graphics, int x, int y0, int y1,
                                            boolean leftEdge, double pathPhase,
                                            double movingPhase) {
        int lineWidth = ModConfig.TOOLTIP_GLOW_LINE_WIDTH.get();
        int spread = ModConfig.TOOLTIP_GLOW_SPREAD.get();
        int alpha = animatedAlpha(pathPhase, movingPhase);
        graphics.fill(leftEdge ? x : x - lineWidth, y0,
                leftEdge ? x + lineWidth : x, y1,
                animatedColor(pathPhase, movingPhase, alpha));
        for (int layer = 1; layer <= spread; layer++) {
            int layerAlpha = glowAlpha(alpha, layer, spread);
            int glowX = leftEdge ? x - layer : x + layer - 1;
            graphics.fill(glowX, y0, glowX + 1, y1,
                    animatedColor(pathPhase, movingPhase, layerAlpha));
        }
    }

    private static int animatedAlpha(double pathPhase, double movingPhase) {
        double distance = Math.abs(pathPhase - movingPhase);
        distance = Math.min(distance, 1.0D - distance);
        double highlight = Math.max(0.0D, 1.0D - distance / HIGHLIGHT_RADIUS);
        highlight = highlight * highlight * (3.0D - 2.0D * highlight);
        double intensity = BASE_INTENSITY + (1.0D - BASE_INTENSITY) * highlight;
        return clampAlpha((int) Math.round(ModConfig.TOOLTIP_GLOW_OPACITY.get() * intensity));
    }

    private static int glowAlpha(int lineAlpha, int layer, int spread) {
        if (spread <= 0) {
            return 0;
        }
        double falloff = 1.0D - layer / (double) (spread + 1);
        return clampAlpha((int) Math.round(lineAlpha * 0.42D * falloff * falloff));
    }

    private static int animatedColor(double pathPhase, double movingPhase, int alpha) {
        int[] palette = palette();
        double wrapped = pathPhase + movingPhase * 0.35D;
        wrapped -= Math.floor(wrapped);
        double scaled = wrapped * palette.length;
        int index = Math.min(palette.length - 1, (int) scaled);
        int next = (index + 1) % palette.length;
        int rgb = blend(palette[index], palette[next], scaled - index);
        return (clampAlpha(alpha) << 24) | rgb;
    }

    private static int[] palette() {
        return switch (ModConfig.TOOLTIP_GLOW_THEME.get()) {
            case SOFT -> new int[]{0x72D9D0, 0x78A8E8, 0xB39DDB};
            case OCEAN -> new int[]{0x45D6D0, 0x4A9FE8, 0x456FE8};
            case AURORA -> new int[]{0x57E389, 0x5ED8D0, 0x9E78E8};
            case SUNSET -> new int[]{0xF0A36B, 0xE77E9B, 0xB28ADE};
        };
    }

    private static int blend(int first, int second, double amount) {
        int red = lerp((first >> 16) & 0xFF, (second >> 16) & 0xFF, amount);
        int green = lerp((first >> 8) & 0xFF, (second >> 8) & 0xFF, amount);
        int blue = lerp(first & 0xFF, second & 0xFF, amount);
        return (red << 16) | (green << 8) | blue;
    }

    private static int lerp(int first, int second, double amount) {
        return (int) Math.round(first + (second - first) * amount);
    }

    private static int clampAlpha(int alpha) {
        return Math.max(0, Math.min(255, alpha));
    }
}
