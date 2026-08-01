package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * CycleButton re-derives its tooltip from the builder's tooltip supplier on
 * every value change, and the default supplier returns null — erasing any
 * tooltip assigned after construction (every mod mode-switch button). This
 * mixin only skips the null case: a supplier that legitimately produces a
 * tooltip still overwrites, while an externally assigned tooltip is never
 * wiped by the default null supplier.
 */
@Mixin(CycleButton.class)
public abstract class CycleButtonTooltipMixin {
    @WrapOperation(method = "updateTooltip", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/CycleButton;setTooltip(Lnet/minecraft/client/gui/components/Tooltip;)V"))
    private void simple_translate$keepExternalTooltip(CycleButton self, Tooltip tooltip,
                                                      Operation<Void> original) {
        if (tooltip == null) {
            return;
        }
        original.call(self, tooltip);
    }
}
