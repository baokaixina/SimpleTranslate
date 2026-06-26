package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.MixinRuntimeProbe;
import com.yourname.simpletranslate.feature.sign.SignTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import com.yourname.simpletranslate.feature.sign.SignSelectionHighlighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register translated sign text at the inner text-rendering step so mods like
 * Enhanced Block Entities can replace the outer renderer without bypassing us.
 */
@Mixin(AbstractSignRenderer.class)
public class SignRendererMixin {

    @Inject(
            method = "submitSignText(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/level/block/entity/SignText;)V",
            at = @At("HEAD"),
            require = 0)
    private void simple_translate$onSubmitSignText(SignRenderState renderState, PoseStack poseStack,
            SubmitNodeCollector collector, SignText signText, CallbackInfo ci) {
        MixinRuntimeProbe.matched("SignRendererMixin#submitSignText");
        boolean front = signText == renderState.frontText || signText != renderState.backText;
        simple_translate$registerRenderedText(
                renderState.blockPos, signText, front, renderState.maxTextLineWidth);
    }

    @Inject(
            method = "submitSignText(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/level/block/entity/SignText;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/AbstractSignRenderer;getDarkColor(Lnet/minecraft/world/level/block/entity/SignText;)I",
                    shift = At.Shift.BEFORE),
            require = 0)
    private void simple_translate$scaleTranslatedText(SignRenderState renderState, PoseStack poseStack,
            SubmitNodeCollector collector, SignText signText, CallbackInfo ci) {
        if (!ModConfig.CONTENT_SIGN_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.SIGN)) {
            return;
        }
        SignTranslationHelper.SignTextIdentityData data = SignTranslationHelper.getSignTextData(signText);
        if (data == null || data.isTranslating || data.renderLines == null) {
            return;
        }
        float scale = data.renderScale;
        if (Float.isFinite(scale) && scale > 0.0F && scale < 1.0F) {
            float verticalCenter = -renderState.textLineHeight / 2.0F;
            poseStack.translate(0.0F, verticalCenter, 0.0F);
            poseStack.scale(scale, scale, scale);
            poseStack.translate(0.0F, -verticalCenter, 0.0F);
        }
    }

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"),
            require = 0)
    private void simple_translate$submitSelectionOutline(SignRenderState renderState, PoseStack poseStack,
            SubmitNodeCollector collector, net.minecraft.client.renderer.state.level.CameraRenderState cameraState,
            CallbackInfo ci) {
        SignSelectionHighlighter.submitSelectionOutline(renderState, poseStack, collector);
    }

    @Unique
    private void simple_translate$registerRenderedText(BlockPos pos, SignText signText, boolean front, int maxTextLineWidth) {
        if (!ModConfig.CONTENT_SIGN_ENABLED.get() || pos == null || signText == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        if (!(mc.level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            return;
        }

        boolean allowAutoRequest = ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.AUTO
                || simple_translate$isWithinAutoScanRange(mc, pos);
        SignTranslationHelper.TranslationResult result =
                SignTranslationHelper.getTranslatedLinesWithState(sign, front, mc.level, allowAutoRequest);
        SignTranslationHelper.registerSignText(
                signText, pos, front, result.lines, result.components,
                result.isTranslating, maxTextLineWidth);
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

