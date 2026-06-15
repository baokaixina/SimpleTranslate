package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.util.SignTranslationHelper;
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

/**
 * Register translated sign text at the renderer step. Minecraft 1.19.2 signs
 * expose their rendered messages from the block entity, so the sign block
 * entity identity is used as the render lookup key for this target.
 */
@Mixin(SignRenderer.class)
public class SignRendererMixin {

    @Inject(
            method = "render(Lnet/minecraft/world/level/block/entity/SignBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"),
            require = 1)
    private void simple_translate$onRenderSignText(SignBlockEntity sign, float partialTick, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, int packedOverlay, CallbackInfo ci) {
        simple_translate$registerRenderedText(sign);
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

        boolean front = true;
        BlockPos pos = sign.getBlockPos();
        boolean allowAutoRequest = ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.AUTO
                || simple_translate$isWithinAutoScanRange(mc, pos);
        SignTranslationHelper.TranslationResult result =
                SignTranslationHelper.getTranslatedLinesWithState(sign, front, mc.level, allowAutoRequest);
        SignTranslationHelper.registerSignTextByIdentity(
                System.identityHashCode(sign), pos, front, result.lines, result.components,
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
