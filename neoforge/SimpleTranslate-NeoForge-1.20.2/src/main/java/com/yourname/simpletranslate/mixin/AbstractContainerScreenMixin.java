package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationTriggerState;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationController;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps only the hovered-tooltip shortcut at the container boundary.
 *
 * Item text is deliberately not replaced here: cancelling renderTooltip would
 * skip downstream tooltip decorators from other mods. The final GuiGraphics
 * submission is translated after every decorator ran.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Inject(
            method = "renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V",
            at = @At("HEAD"), require = 1)
    private void simple_translate$beginItemTooltipSubmission(CallbackInfo ci) {
        TooltipTranslationController.beginItemTooltipSubmission();
    }

    @Inject(
            method = "renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V",
            at = @At("RETURN"), require = 1)
    private void simple_translate$endItemTooltipSubmission(CallbackInfo ci) {
        TooltipTranslationController.endItemTooltipSubmission();
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void simple_translate$armHoveredTooltipTranslation(
            int keyCode, int scanCode, int modifiers,
            CallbackInfoReturnable<Boolean> cir) {
        if (TooltipTranslationTriggerState.hasEnabledShortcutMode(
                TooltipTranslationController.RenderContext.ITEM)
                && ModKeyBindings.matchesTranslateHoveredTooltipKey(keyCode, scanCode)) {
            TooltipTranslationTriggerState.armShortcutRequest();
            cir.setReturnValue(true);
        }
    }
}
