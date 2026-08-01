package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Exact 1.12.2 target: GuiScreen#getItemToolTip(ItemStack)Ljava/util/List;.
 * Item tooltips are deliberately isolated from visible chat/HUD translation.
 */
@Mixin(GuiScreen.class)
public abstract class GuiScreenTooltipMixin {
    @Inject(
            method = "getItemToolTip(Lnet/minecraft/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void simpletranslate$translateItemTooltip(
            ItemStack stack,
            CallbackInfoReturnable<List<String>> callback
    ) {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        List<String> original = callback.getReturnValue();
        if (engine == null || !engine.isConfigured() || original == null || original.isEmpty()) return;
        callback.setReturnValue(engine.translateTooltipLines(original));
    }
}
