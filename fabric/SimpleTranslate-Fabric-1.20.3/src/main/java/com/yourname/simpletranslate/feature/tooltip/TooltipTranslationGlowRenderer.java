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
            // 1.20.1 ClientTooltipComponent#getHeight takes no font argument.
            height += component.getHeight();
        }
        if (width <= 0 || height <= 0) {
            return;
        }

        Vector2ic position = positioner.positionTooltip(
                graphics.guiWidth(), graphics.guiHeight(), mouseX, mouseY, width, height);
        renderTooltipBounds(graphics, position.x() - 3, position.y() - 3,
                position.x() + width + 3, position.y() + height + 3, true);
    }

    public static void renderPreview(GuiGraphics graphics, int left, int top, int right, int bottom) {
        if (ModConfig.TOOLTIP_GLOW_ENABLED.get()) {
            renderTooltipBounds(graphics, left, top, right, bottom, true);
        }
    }

    /**
     * Reuses the configured pending-translation palette for an already drawn
     * text region. Unlike tooltip chrome, every spread layer points inward so
     * the surrounding frame and neighbouring positioning glyphs are untouched.
     */
    public static void renderPendingTextRegion(GuiGraphics graphics,
                                               int left, int top, int right, int bottom) {
        // Enablement is deliberately owned by the calling surface and must
        // not silently inherit the unrelated tooltip.glow.enabled value.
        if (graphics != null && right > left && bottom > top) {
            renderTooltipBounds(graphics, left, top, right, bottom, false);
        }
    }

    private static void renderTooltipBounds(GuiGraphics graphics, int left, int top,
                                            int right, int bottom, boolean spreadOutward) {
        int width = Math.max(1, right - left);
        int height = Math.max(1, bottom - top);
        int perimeter = Math.max(1, 2 * (width + height));
        int cycleMillis = Math.max(1, ModConfig.TOOLTIP_GLOW_CYCLE_MS.get());
        double movingPhase = (System.currentTimeMillis() % cycleMillis) / (double) cycleMillis;
        int lineWidth = ModConfig.TOOLTIP_GLOW_LINE_WIDTH.get();
        int horizontalSpreadLimit = spreadOutward
                ? Integer.MAX_VALUE : Math.max(0, height / 2 - lineWidth);
        int verticalSpreadLimit = spreadOutward
                ? Integer.MAX_VALUE : Math.max(0, width / 2 - lineWidth);

        graphics.pose().pushPose();
        drawHorizontal(graphics, left, right, top, false, 0, perimeter, movingPhase,
                spreadOutward, horizontalSpreadLimit);
        drawVertical(graphics, right, top, bottom, false, width, perimeter, movingPhase,
                spreadOutward, verticalSpreadLimit);
        drawHorizontal(graphics, left, right, bottom, true, width + height, perimeter, movingPhase,
                spreadOutward, horizontalSpreadLimit);
        drawVertical(graphics, left, top, bottom, true, width + height + width, perimeter, movingPhase,
                spreadOutward, verticalSpreadLimit);
        graphics.pose().popPose();
    }

    private static void drawHorizontal(GuiGraphics graphics, int left, int right, int y,
                                       boolean reverse, int pathOffset, int perimeter,
                                       double movingPhase, boolean spreadOutward, int spreadLimit) {
        int length = Math.max(1, right - left);
        int segments = Math.min(96, Math.max(16, length));
        for (int segment = 0; segment < segments; segment++) {
            int start = length * segment / segments;
            int end = Math.max(start + 1, length * (segment + 1) / segments);
            int x0 = reverse ? right - end : left + start;
            int x1 = reverse ? right - start : left + end;
            double pathPhase = (pathOffset + (start + end) * 0.5D) / perimeter;
            drawHorizontalSegment(graphics, x0, x1, y, reverse, pathPhase, movingPhase,
                    spreadOutward, spreadLimit);
        }
    }

    private static void drawVertical(GuiGraphics graphics, int x, int top, int bottom,
                                     boolean reverse, int pathOffset, int perimeter,
                                     double movingPhase, boolean spreadOutward, int spreadLimit) {
        int length = Math.max(1, bottom - top);
        int segments = Math.min(96, Math.max(16, length));
        for (int segment = 0; segment < segments; segment++) {
            int start = length * segment / segments;
            int end = Math.max(start + 1, length * (segment + 1) / segments);
            int y0 = reverse ? bottom - end : top + start;
            int y1 = reverse ? bottom - start : top + end;
            double pathPhase = (pathOffset + (start + end) * 0.5D) / perimeter;
            drawVerticalSegment(graphics, x, y0, y1, reverse, pathPhase, movingPhase,
                    spreadOutward, spreadLimit);
        }
    }

    private static void drawHorizontalSegment(GuiGraphics graphics, int x0, int x1, int y,
                                              boolean bottomEdge, double pathPhase,
                                              double movingPhase, boolean spreadOutward, int spreadLimit) {
        int lineWidth = ModConfig.TOOLTIP_GLOW_LINE_WIDTH.get();
        int spread = Math.min(ModConfig.TOOLTIP_GLOW_SPREAD.get(), spreadLimit);
        int alpha = animatedAlpha(pathPhase, movingPhase);
        graphics.fill(x0, bottomEdge ? y - lineWidth : y,
                x1, bottomEdge ? y : y + lineWidth,
                animatedColor(pathPhase, movingPhase, alpha));
        for (int layer = 1; layer <= spread; layer++) {
            int layerAlpha = glowAlpha(alpha, layer, spread);
            int glowY = spreadOutward
                    ? (bottomEdge ? y + layer - 1 : y - layer)
                    : (bottomEdge ? y - lineWidth - layer + 1 : y + lineWidth + layer - 1);
            graphics.fill(x0, glowY, x1, glowY + 1,
                    animatedColor(pathPhase, movingPhase, layerAlpha));
        }
    }

    private static void drawVerticalSegment(GuiGraphics graphics, int x, int y0, int y1,
                                            boolean leftEdge, double pathPhase,
                                            double movingPhase, boolean spreadOutward, int spreadLimit) {
        int lineWidth = ModConfig.TOOLTIP_GLOW_LINE_WIDTH.get();
        int spread = Math.min(ModConfig.TOOLTIP_GLOW_SPREAD.get(), spreadLimit);
        int alpha = animatedAlpha(pathPhase, movingPhase);
        graphics.fill(leftEdge ? x : x - lineWidth, y0,
                leftEdge ? x + lineWidth : x, y1,
                animatedColor(pathPhase, movingPhase, alpha));
        for (int layer = 1; layer <= spread; layer++) {
            int layerAlpha = glowAlpha(alpha, layer, spread);
            int glowX = spreadOutward
                    ? (leftEdge ? x - layer : x + layer - 1)
                    : (leftEdge ? x + lineWidth + layer - 1 : x - lineWidth - layer + 1);
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
