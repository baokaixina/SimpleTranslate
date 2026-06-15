package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.util.SignTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.Vec3;
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
            method = "extractRenderState(Lnet/minecraft/world/level/block/entity/SignBlockEntity;Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
            at = @At("TAIL"),
            require = 1)
    private void simple_translate$onExtractRenderState(SignBlockEntity sign, SignRenderState state, float partialTick,
            Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, CallbackInfo ci) {
        if (sign == null || state == null) {
            return;
        }
        simple_translate$registerRenderedText(sign, state.frontText, true, state.maxTextLineWidth);
        simple_translate$registerRenderedText(sign, state.backText, false, state.maxTextLineWidth);
    }

    @Unique
    private void simple_translate$registerRenderedText(SignBlockEntity sign, SignText signText, boolean front, int maxTextLineWidth) {
        BlockPos pos = sign == null ? null : sign.getBlockPos();
        if (!ModConfig.CONTENT_SIGN_ENABLED.get() || pos == null || signText == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
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

