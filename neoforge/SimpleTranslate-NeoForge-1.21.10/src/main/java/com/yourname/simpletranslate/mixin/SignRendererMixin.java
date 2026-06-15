package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.util.SignTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
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
            method = "renderSignText(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/SignText;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IIIZ)V",
            at = @At("HEAD"),
            require = 1)
    private void simple_translate$onRenderSignText(BlockPos pos, SignText signText, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, int lineHeight, int maxTextLineWidth, boolean front, CallbackInfo ci) {
        simple_translate$registerRenderedText(pos, signText, front, maxTextLineWidth);
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
        SignTranslationHelper.registerSignTextByIdentity(
                System.identityHashCode(signText), pos, front, result.lines, result.components,
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

