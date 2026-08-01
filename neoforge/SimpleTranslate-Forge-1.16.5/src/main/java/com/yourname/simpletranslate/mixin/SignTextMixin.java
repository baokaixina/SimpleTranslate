package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.feature.sign.SignTranslationHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.IReorderingProcessor;
import net.minecraft.tileentity.SignTileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.function.Function;

/**
 * Minecraft 1.19.4 has no SignText holder: the per-sign render lines live on
 * SignTileEntity#getRenderMessages itself, so the translated-line swap hooks
 * the block entity directly.
 */
@Mixin(SignTileEntity.class)
public abstract class SignTextMixin {

    @Inject(method = "getRenderMessage(ILjava/util/function/Function;)Lnet/minecraft/util/IReorderingProcessor;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void simple_translate$onGetRenderMessage(
            int line,
            Function<ITextComponent, IReorderingProcessor> transformer,
            CallbackInfoReturnable<IReorderingProcessor> cir
    ) {
        if (!ModConfig.CONTENT_SIGN_ENABLED.get()) {
            return;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.SIGN)) {
            return;
        }

        SignTranslationHelper.SignTextIdentityData data =
                SignTranslationHelper.getSignTextData((SignTileEntity) (Object) this);
        if (data == null || line < 0 || line >= 4
                || data.translatedComponents == null || data.translatedComponents.length != 4
                || data.renderLines == null || data.renderLines.length != 4 || data.isTranslating) {
            return;
        }
        cir.setReturnValue(data.renderLines[line]);
    }
}
