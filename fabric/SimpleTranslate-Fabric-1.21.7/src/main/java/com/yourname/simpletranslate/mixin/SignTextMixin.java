package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.feature.sign.SignTranslationHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.function.Function;

@Mixin(SignText.class)
public abstract class SignTextMixin {

    @Inject(method = "getRenderMessages", at = @At("HEAD"), cancellable = true)
    private void simple_translate$onGetRenderMessages(
            boolean filtered,
            Function<Component, FormattedCharSequence> transformer,
            CallbackInfoReturnable<FormattedCharSequence[]> cir
    ) {
        if (!ModConfig.CONTENT_SIGN_ENABLED.get()) {
            return;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.SIGN)) {
            return;
        }

        SignTranslationHelper.SignTextIdentityData data =
                SignTranslationHelper.getSignTextData((SignText) (Object) this);
        if (data == null || data.translatedComponents == null || data.translatedComponents.length != 4
                || data.renderLines == null || data.renderLines.length != 4 || data.isTranslating) {
            return;
        }
        cir.setReturnValue(Arrays.copyOf(data.renderLines, data.renderLines.length));
    }
}
