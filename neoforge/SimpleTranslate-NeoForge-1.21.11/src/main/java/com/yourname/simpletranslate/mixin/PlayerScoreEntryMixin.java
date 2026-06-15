package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.ScoreboardTranslationHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerScoreEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerScoreEntry.class)
public class PlayerScoreEntryMixin {

    @Inject(method = "ownerName", at = @At("RETURN"), cancellable = true, remap = false)
    private void simple_translate$translateOwnerName(CallbackInfoReturnable<Component> cir) {
        Component component = cir.getReturnValue();
        if (!ModConfig.HUD_SCOREBOARD_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) {
            return;
        }
        cir.setReturnValue(ScoreboardTranslationHelper.translateComponent(component));
    }
}
