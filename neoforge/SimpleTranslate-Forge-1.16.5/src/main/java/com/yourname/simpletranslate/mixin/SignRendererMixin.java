package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.MixinRuntimeProbe;
import com.yourname.simpletranslate.feature.sign.SignTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.tileentity.SignTileEntityRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.tileentity.SignTileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register translated sign text at the sign text-rendering step.
 *
 * <p>Minecraft 1.16.5 has no {@code renderSignText(...)}: the single sign face
 * is rendered inline by
 * {@code render(SignTileEntity, float, MatrixStack, IRenderTypeBuffer, int,
 * int)}. Per javap -c of forge-1.16.5-36.2.42_mapped_official_1.16.5.jar, the
 * text pose is translated (0.0, 0.33333334, 0.046666667) and scaled
 * (0.010416667) immediately before the single
 * {@code SignTileEntity.getColor()Lnet/minecraft/item/DyeColor;} invocation,
 * so that call is the version anchor for the scale hook. There is no SignText
 * side holder, so the block entity itself is the translation identity and the
 * face is always the front. The vanilla line-width constant is 90 and the line
 * height is 10 (both inline literals on 1.16.5).
 */
@Mixin(SignTileEntityRenderer.class)
public class SignRendererMixin {
    private static final int TEXT_LINE_HEIGHT = 10;

    @Inject(
            method = "render(Lnet/minecraft/tileentity/SignTileEntity;FLcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/renderer/IRenderTypeBuffer;II)V",
            at = @At("HEAD"),
            require = 1)
    private void simple_translate$onRenderSign(SignTileEntity sign, float partialTick, MatrixStack poseStack,
            IRenderTypeBuffer buffer, int packedLight, int packedOverlay, CallbackInfo ci) {
        MixinRuntimeProbe.matched("SignRendererMixin#render");
        simple_translate$registerRenderedText(sign);
    }

    @Inject(
            method = "render(Lnet/minecraft/tileentity/SignTileEntity;FLcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/renderer/IRenderTypeBuffer;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/tileentity/SignTileEntity;getColor()Lnet/minecraft/item/DyeColor;",
                    shift = At.Shift.BEFORE),
            require = 1)
    private void simple_translate$scaleTranslatedText(SignTileEntity sign, float partialTick, MatrixStack poseStack,
            IRenderTypeBuffer buffer, int packedLight, int packedOverlay, CallbackInfo ci) {
        if (!ModConfig.CONTENT_SIGN_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.SIGN)) {
            return;
        }
        SignTranslationHelper.SignTextIdentityData data = SignTranslationHelper.getSignTextData(sign);
        if (data == null || data.isTranslating || data.renderLines == null) {
            return;
        }
        float scale = data.renderScale;
        if (Float.isFinite(scale) && scale > 0.0F && scale < 1.0F) {
            float verticalCenter = -TEXT_LINE_HEIGHT / 2.0F;
            poseStack.translate(0.0F, verticalCenter, 0.0F);
            poseStack.scale(scale, scale, scale);
            poseStack.translate(0.0F, -verticalCenter, 0.0F);
        }
    }

    @Unique
    private void simple_translate$registerRenderedText(SignTileEntity sign) {
        if (!ModConfig.CONTENT_SIGN_ENABLED.get() || sign == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        BlockPos pos = sign.getBlockPos();
        boolean allowAutoRequest = ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.AUTO
                || simple_translate$isWithinAutoScanRange(mc, pos);
        SignTranslationHelper.TranslationResult result =
                SignTranslationHelper.getTranslatedLinesWithState(sign, true, mc.level, allowAutoRequest);
        SignTranslationHelper.registerSignText(
                sign, pos, true, result.lines, result.components,
                result.isTranslating, 90);
    }

    @Unique
    private boolean simple_translate$isWithinAutoScanRange(Minecraft mc, BlockPos pos) {
        if (mc == null || mc.player == null || pos == null) {
            return false;
        }
        int radius = Math.max(1, ModConfig.CONTENT_SIGN_RADIUS.get());
        double maxDistance = radius + 0.75D;
        return mc.player.distanceToSqr(
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D) <= maxDistance * maxDistance;
    }
}
