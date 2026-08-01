package com.yourname.simpletranslate.core.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.IReorderingProcessor;

/**
 * Minecraft 1.19.2 has no {@code net.minecraft.client.gui.GuiGraphics}: the
 * class was introduced in 1.20. This facade reproduces the small GuiGraphics
 * API subset the product uses on top of the 1.19.x MatrixStack-era static
 * helpers so shared screen/feature code stays source-identical to the donor
 * tree except for the import line and the render-boundary signatures.
 *
 * <p>Every instance wraps one MatrixStack; identity only scopes product frames
 * (begin/end HudFrame, detached frames), never vanilla state.</p>
 */
public final class GuiGraphics {
    private final MatrixStack poseStack;
    private final Minecraft minecraft;

    public GuiGraphics(MatrixStack poseStack) {
        this.poseStack = poseStack;
        this.minecraft = Minecraft.getInstance();
    }

    public static GuiGraphics wrap(MatrixStack poseStack) {
        return new GuiGraphics(poseStack);
    }

    public MatrixStack pose() {
        return this.poseStack;
    }

    public int guiWidth() {
        return this.minecraft.getWindow().getGuiScaledWidth();
    }

    public int guiHeight() {
        return this.minecraft.getWindow().getGuiScaledHeight();
    }

    public void fill(int x0, int y0, int x1, int y1, int color) {
        AbstractGui.fill(this.poseStack, x0, y0, x1, y1, color);
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

    public int drawString(FontRenderer font, String text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(FontRenderer font, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            // 1.19.x's static drawString returns void; mirror 1.20's drawn-width return.
            AbstractGui.drawString(this.poseStack, font, text, x, y, color);
            return x + font.width(text);
        }
        return drawInBatchNow(font, IReorderingProcessor.forward(text, net.minecraft.util.text.Style.EMPTY),
                x, y, color, false);
    }

    public int drawString(FontRenderer font, ITextComponent text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(FontRenderer font, ITextComponent text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            AbstractGui.drawString(this.poseStack, font, text, x, y, color);
            return x + font.width(text);
        }
        return drawInBatchNow(font, text.getVisualOrderText(), x, y, color, false);
    }

    public int drawString(FontRenderer font, IReorderingProcessor text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(FontRenderer font, IReorderingProcessor text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            return font.drawShadow(this.poseStack, text, x, y, color);
        }
        return drawInBatchNow(font, text, x, y, color, false);
    }

    public void drawCenteredString(FontRenderer font, String text, int x, int y, int color) {
        AbstractGui.drawCenteredString(this.poseStack, font, text, x, y, color);
    }

    public void drawCenteredString(FontRenderer font, ITextComponent text, int x, int y, int color) {
        AbstractGui.drawCenteredString(this.poseStack, font, text, x, y, color);
    }

    public void drawCenteredString(FontRenderer font, IReorderingProcessor text, int x, int y, int color) {
        font.draw(this.poseStack, text, x - font.width(text) / 2.0F, y, color);
    }

    private int drawInBatchNow(FontRenderer font, IReorderingProcessor text, int x, int y,
                               int color, boolean shadow) {
        IRenderTypeBuffer.Impl bufferSource =
                IRenderTypeBuffer.immediate(Tessellator.getInstance().getBuilder());
        // 1.19.2 FontRenderer.drawInBatch has no DisplayMode overload: the trailing
        // boolean is the see-through flag (NORMAL == false, background 0).
        int drawn = font.drawInBatch(text, x, y, color, shadow,
                this.poseStack.last().pose(), bufferSource, false, 0, 15728880);
        bufferSource.endBatch();
        return drawn;
    }
}
