package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.DirectFormattedTranslationPipeline;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin to translate item tooltips
 *
 * 重要：使用批量翻译保持上下文，不要逐行翻译
 */
@Mixin(ItemStack.class)
public class ItemStackMixin {

    @Inject(method = "getTooltipLines", at = @At("RETURN"), cancellable = true)
    private void onGetTooltipLines(Item.TooltipContext context, Player player, TooltipFlag flag,
                                   CallbackInfoReturnable<List<Component>> cir) {
        if (!ModConfig.TOOLTIP_ITEM_ENABLED.get()) {
            return;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_ITEM)) {
            return;
        }

        List<Component> original = cir.getReturnValue();
        if (original == null || original.isEmpty()) {
            return;
        }

        // Check if any line contains English
        if (!TooltipTranslationHelper.anyContainsEnglish(original)) {
            return;
        }

        /*
         * ItemStack#getTooltipLines is used by many screens for layout, search,
         * recipe previews, and background item lists. Starting network work here
         * can flood the direct lane with unrelated items before the player hovers
         * the one tooltip they actually want. The render mixins queue missing
         * translations; this mixin only returns already-cached results.
        */
        DirectFormattedTranslationPipeline.ComponentListResult cached =
                TooltipTranslationHelper.getCachedComponentsBatch(original);
        List<Component> translated = cached.translated ? cached.components : original;

        // Only update if we got translated results
        if (translated != original) {
            TooltipTranslationHelper.markTranslatedTooltip(translated);
            cir.setReturnValue(translated);
        }
    }
}
