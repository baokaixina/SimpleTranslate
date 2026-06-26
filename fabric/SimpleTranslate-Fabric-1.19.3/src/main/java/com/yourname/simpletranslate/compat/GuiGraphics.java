package com.yourname.simpletranslate.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Minimal 1.19.2 compatibility wrapper for code written against GuiGraphics.
 */
public class GuiGraphics {
    private final PoseStack poseStack;

    public GuiGraphics(PoseStack poseStack) {
        this.poseStack = poseStack;
    }

    public PoseStack pose() {
        return this.poseStack;
    }

    public int guiWidth() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? 0 : minecraft.getWindow().getGuiScaledWidth();
    }

    public int guiHeight() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? 0 : minecraft.getWindow().getGuiScaledHeight();
    }

    public void fill(int minX, int minY, int maxX, int maxY, int color) {
        net.minecraft.client.gui.GuiComponent.fill(this.poseStack, minX, minY, maxX, maxY, color);
    }

    public void enableScissor(int x, int y, int width, int height) {
        net.minecraft.client.gui.GuiComponent.enableScissor(x, y, width, height);
    }

    public void disableScissor() {
        net.minecraft.client.gui.GuiComponent.disableScissor();
    }

    public int drawString(Font font, String text, int x, int y, int color) {
        net.minecraft.client.gui.GuiComponent.drawString(this.poseStack, font, text, x, y, color);
        return font.width(text);
    }

    public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        return shadow ? font.drawShadow(this.poseStack, text, x, y, color) : font.draw(this.poseStack, text, x, y, color);
    }

    public int drawString(Font font, Component component, int x, int y, int color) {
        net.minecraft.client.gui.GuiComponent.drawString(this.poseStack, font, component, x, y, color);
        return font.width(component);
    }

    public int drawString(Font font, Component component, int x, int y, int color, boolean shadow) {
        return shadow ? font.drawShadow(this.poseStack, component, x, y, color) : font.draw(this.poseStack, component, x, y, color);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color) {
        net.minecraft.client.gui.GuiComponent.drawString(this.poseStack, font, text, x, y, color);
        return font.width(text);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        return shadow ? font.drawShadow(this.poseStack, text, x, y, color) : font.draw(this.poseStack, text, x, y, color);
    }

    public void drawCenteredString(Font font, String text, int x, int y, int color) {
        net.minecraft.client.gui.GuiComponent.drawCenteredString(this.poseStack, font, text, x, y, color);
    }

    public void drawCenteredString(Font font, Component component, int x, int y, int color) {
        net.minecraft.client.gui.GuiComponent.drawCenteredString(this.poseStack, font, component, x, y, color);
    }

    public void drawCenteredString(Font font, FormattedCharSequence text, int x, int y, int color) {
        net.minecraft.client.gui.GuiComponent.drawCenteredString(this.poseStack, font, text, x, y, color);
    }

    public void blit(int x, int y, int uOffset, int vOffset, int width, int height) {
        net.minecraft.client.gui.GuiComponent.blit(this.poseStack, x, y, uOffset, vOffset, width, height, 256, 256);
    }

    public void blit(int x, int y, int blitOffset, float uOffset, float vOffset, int width, int height, int textureWidth, int textureHeight) {
        net.minecraft.client.gui.GuiComponent.blit(this.poseStack, x, y, blitOffset, uOffset, vOffset, width, height, textureWidth, textureHeight);
    }

    public void blit(ResourceLocation location, int x, int y, int width, int height, float uOffset, float vOffset,
            int uWidth, int vHeight, int textureWidth, int textureHeight) {
        RenderSystem.setShaderTexture(0, location);
        net.minecraft.client.gui.GuiComponent.blit(this.poseStack, x, y, width, height, uOffset, vOffset,
                uWidth, vHeight, textureWidth, textureHeight);
    }

    public void renderTooltip(Font font, ItemStack stack, int mouseX, int mouseY) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen != null) {
            screen.renderTooltip(this.poseStack, stack.getTooltipLines(Minecraft.getInstance().player, Minecraft.getInstance().options.advancedItemTooltips ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED : net.minecraft.world.item.TooltipFlag.Default.NORMAL), stack.getTooltipImage(), mouseX, mouseY);
        }
    }

    public void renderTooltip(Font font, Component component, int mouseX, int mouseY) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen != null) {
            screen.renderTooltip(this.poseStack, component, mouseX, mouseY);
        }
    }

    public void renderTooltip(Font font, List<Component> components, Optional<TooltipComponent> image, int mouseX, int mouseY) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen != null) {
            screen.renderTooltip(this.poseStack, components, image, mouseX, mouseY);
        }
    }

    public void renderComponentTooltip(Font font, List<Component> components, int mouseX, int mouseY) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen != null) {
            screen.renderComponentTooltip(this.poseStack, components, mouseX, mouseY);
        }
    }
}

