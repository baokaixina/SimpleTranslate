package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationGlowRenderer;
import com.yourname.simpletranslate.gui.GuiTranslationController;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 1.12.2 tooltip glow and GUI-frame ownership. Exact Forge bytecode shows the
 * vanilla List overload delegates to the FontRenderer overload, so glow is
 * drawn only at the latter to avoid a duplicate pass. Item and component
 * hover entry points establish a dedicated scope; ordinary mod/button
 * tooltips remain part of whole-frame GUI translation.
 */
@Mixin(GuiScreen.class)
public abstract class GuiScreenTooltipGlowMixin {
    @Inject(method = "renderToolTip(Lnet/minecraft/item/ItemStack;II)V", at = @At("HEAD"))
    private void simpletranslate$beginItemTooltip(ItemStack stack, int x, int y, CallbackInfo callback) {
        GuiTranslationController.beginDedicatedTooltip();
    }

    @Inject(method = "renderToolTip(Lnet/minecraft/item/ItemStack;II)V", at = @At("RETURN"))
    private void simpletranslate$endItemTooltip(ItemStack stack, int x, int y, CallbackInfo callback) {
        GuiTranslationController.endDedicatedTooltip();
    }

    @Inject(method = "handleComponentHover(Lnet/minecraft/util/text/ITextComponent;II)V", at = @At("HEAD"))
    private void simpletranslate$beginComponentTooltip(ITextComponent component, int x, int y, CallbackInfo callback) {
        if ((Object) this instanceof GuiChat || (Object) this instanceof GuiScreenBook) {
            GuiTranslationController.beginDedicatedTooltip();
        }
    }

    @Inject(method = "handleComponentHover(Lnet/minecraft/util/text/ITextComponent;II)V", at = @At("RETURN"))
    private void simpletranslate$endComponentTooltip(ITextComponent component, int x, int y, CallbackInfo callback) {
        if ((Object) this instanceof GuiChat || (Object) this instanceof GuiScreenBook) {
            GuiTranslationController.endDedicatedTooltip();
        }
    }

    @Inject(
            method = "drawHoveringText(Ljava/util/List;IILnet/minecraft/client/gui/FontRenderer;)V",
            at = @At("HEAD"),
            remap = false
    )
    private void simpletranslate$drawItemTooltipGlow(List<String> lines, int x, int y, FontRenderer font, CallbackInfo callback) {
        TooltipTranslationGlowRenderer.renderPending((GuiScreen) (Object) this, font, lines, x, y);
    }
}
