package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.DirectFormattedTranslationPipeline;
import com.yourname.simpletranslate.util.DirectSurfaceTranslator;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Translates entity name tags while preserving component styling.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void simple_translate$translateNameTag(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        if (state == null || state.nameTag == null) {
            return;
        }
        if (!ModConfig.CONTENT_ENTITY_NAME_ENABLED.get()) {
            return;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.ENTITY_NAME)) {
            return;
        }

        if (!simple_translate$isEntityInRange(state)) {
            return;
        }

        state.nameTag = simple_translate$translateWithStylePreservation(state.nameTag);
    }

    @Unique
    private boolean simple_translate$isEntityInRange(EntityRenderState state) {
        if (state == null) {
            return false;
        }

        int radius = ModConfig.CONTENT_ENTITY_NAME_RADIUS.get();
        return state.distanceToCameraSq <= (double) radius * (double) radius;
    }

    @Unique
    private Component simple_translate$translateWithStylePreservation(Component component) {
        if (component == null) {
            return null;
        }

        String plainText = component.getString();
        if (plainText.isEmpty()) {
            return component;
        }

        if (!TooltipTranslationHelper.containsEnglish(plainText)) {
            return component;
        }

        DirectFormattedTranslationPipeline.ComponentResult direct =
                DirectSurfaceTranslator.translateComponent(component, "entity.name.direct", "entity-name");
        if (direct.handled) {
            return direct.component;
        }
        return component;
    }

}


