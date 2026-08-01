package com.yourname.simpletranslate.core.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Minecraft 1.19.4 has no {@code net.minecraft.client.gui.GuiGraphics}: the
 * class was introduced in 1.20. This facade reproduces the small GuiGraphics
 * API subset the product uses on top of the 1.19.4 PoseStack-era static
 * helpers so shared screen/feature code stays source-identical to the donor
 * tree except for the import line and the render-boundary signatures.
 *
 * <p>Every instance wraps one PoseStack; identity only scopes product frames
 * (begin/end HudFrame, detached frames), never vanilla state.</p>
 */
public final class GuiGraphics {
    private final PoseStack poseStack;
    private final Minecraft minecraft;

    public GuiGraphics(PoseStack poseStack) {
        this.poseStack = poseStack;
        this.minecraft = Minecraft.getInstance();
    }

    public static GuiGraphics wrap(PoseStack poseStack) {
        return new GuiGraphics(poseStack);
    }

    public PoseStack pose() {
        return this.poseStack;
    }

    public int guiWidth() {
        return this.minecraft.getWindow().getGuiScaledWidth();
    }

    public int guiHeight() {
        return this.minecraft.getWindow().getGuiScaledHeight();
    }

    public void fill(int x0, int y0, int x1, int y1, int color) {
        GuiComponent.fill(this.poseStack, x0, y0, x1, y1, color);
    }

    public void enableScissor(int x0, int y0, int x1, int y1) {
        double scale = this.minecraft.getWindow().getGuiScale();
        RenderSystem.enableScissor(
                (int) (x0 * scale),
                (int) (this.minecraft.getWindow().getHeight() - y1 * scale),
                Math.max(0, (int) ((x1 - x0) * scale)),
                Math.max(0, (int) ((y1 - y0) * scale)));
    }

    public void disableScissor() {
        RenderSystem.disableScissor();
    }

    public int drawString(Font font, String text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            // 1.19.4's static drawString returns void; mirror 1.20's drawn-width return.
            GuiComponent.drawString(this.poseStack, font, text, x, y, color);
            return x + font.width(text);
        }
        return drawInBatchNow(font, FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY),
                x, y, color, false);
    }

    public int drawString(Font font, Component text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            GuiComponent.drawString(this.poseStack, font, text, x, y, color);
            return x + font.width(text);
        }
        return drawInBatchNow(font, text.getVisualOrderText(), x, y, color, false);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            GuiComponent.drawString(this.poseStack, font, text, x, y, color);
            return x + font.width(text);
        }
        return drawInBatchNow(font, text, x, y, color, false);
    }

    public void drawCenteredString(Font font, String text, int x, int y, int color) {
        GuiComponent.drawCenteredString(this.poseStack, font, text, x, y, color);
    }

    public void drawCenteredString(Font font, Component text, int x, int y, int color) {
        GuiComponent.drawCenteredString(this.poseStack, font, text, x, y, color);
    }

    public void drawCenteredString(Font font, FormattedCharSequence text, int x, int y, int color) {
        GuiComponent.drawCenteredString(this.poseStack, font, text, x, y, color);
    }

    private int drawInBatchNow(Font font, FormattedCharSequence text, int x, int y,
                               int color, boolean shadow) {
        MultiBufferSource.BufferSource bufferSource =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        int drawn = font.drawInBatch(text, x, y, color, shadow,
                this.poseStack.last().pose(), bufferSource, Font.DisplayMode.NORMAL, 0, 15728880);
        bufferSource.endBatch();
        return drawn;
    }
}
