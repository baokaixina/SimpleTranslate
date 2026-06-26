package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.MixinRuntimeProbe;
import com.yourname.simpletranslate.feature.sign.SignTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SignRenderer.class)
public class SignRendererMixin {

    @Inject(
            method = "render(Lnet/minecraft/world/level/block/entity/SignBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"),
            require = 1)
    private void simple_translate$onRenderSign(SignBlockEntity sign, float partialTick, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, int packedOverlay, CallbackInfo ci) {
        MixinRuntimeProbe.matched("SignRendererMixin#render");
        simple_translate$registerRenderedText(sign);
    }

    @Inject(
            method = "renderSignText(Lnet/minecraft/world/level/block/entity/SignBlockEntity;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/SignRenderer;getDarkColor(Lnet/minecraft/world/level/block/entity/SignBlockEntity;)I",
                    shift = At.Shift.BEFORE),
            require = 1)
    private void simple_translate$scaleTranslatedText(SignBlockEntity sign, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, float scaleBase, CallbackInfo ci) {
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
            poseStack.scale(scale, scale, scale);
        }
    }

    @Unique
    private void simple_translate$registerRenderedText(SignBlockEntity sign) {
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
                result.isTranslating, sign.getMaxTextLineWidth());
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
