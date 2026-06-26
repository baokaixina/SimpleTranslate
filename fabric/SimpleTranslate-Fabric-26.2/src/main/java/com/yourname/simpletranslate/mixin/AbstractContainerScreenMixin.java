package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationController;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationTriggerState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Container screens, including the player inventory, render item tooltips from
 * their hovered slot at the end of the screen render pass. Rendering the cached
 * translated tooltip here keeps the E-inventory path aligned with trading and
 * other container screens instead of relying on later GuiGraphicsExtractor hooks only.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Shadow
    @Final
    protected AbstractContainerMenu menu;

    @Shadow
    @Nullable
    protected Slot hoveredSlot;

    @Shadow
    protected abstract List<Component> getTooltipFromContainerItem(ItemStack stack);

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void simple_translate$armHoveredTooltipTranslation(
            KeyEvent event,
            CallbackInfoReturnable<Boolean> cir) {
        if (ModKeyBindings.matchesTranslateHoveredTooltipKey(event)
                && TooltipTranslationTriggerState.hasEnabledShortcutMode()) {
            TooltipTranslationTriggerState.armShortcutRequest();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void simple_translate$renderTranslatedItemTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                              CallbackInfo ci) {
        if (TooltipTranslationController.isRenderingTranslated()) {
            return;
        }
        if (!ModConfig.TOOLTIP_ITEM_ENABLED.get()) {
            return;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_ITEM)) {
            return;
        }
        if (!this.menu.getCarried().isEmpty() || this.hoveredSlot == null || !this.hoveredSlot.hasItem()) {
            return;
        }

        ItemStack stack = this.hoveredSlot.getItem();
        List<Component> original = this.getTooltipFromContainerItem(stack);
        if (original == null || original.isEmpty()) {
            return;
        }

        if (TooltipTranslationHelper.isMarkedTranslatedTooltip(original)) {
            TooltipTranslationController.beginRenderingTranslated();
            try {
                graphics.setTooltipForNextFrame(Minecraft.getInstance().font, original,
                        stack.getTooltipImage(), mouseX, mouseY);
            } finally {
                TooltipTranslationController.endRenderingTranslated();
            }
            ci.cancel();
            return;
        }
        if (TooltipTranslationHelper.anyContainsEnglish(original)) {
            List<Component> tooltip = TooltipTranslationController.translateForRender(original);
            if (tooltip != original) {
                TooltipTranslationController.beginRenderingTranslated();
                try {
                    graphics.setTooltipForNextFrame(Minecraft.getInstance().font, tooltip,
                            stack.getTooltipImage(), mouseX, mouseY);
                } finally {
                    TooltipTranslationController.endRenderingTranslated();
                }
                ci.cancel();
            }
        }
    }
}
