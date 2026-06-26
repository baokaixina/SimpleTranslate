package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.core.ComponentTranslationResult;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
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
public class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void simple_translate$translateNameTag(T entity, S state, float partialTick, CallbackInfo ci) {
        Component displayName = state.nameTag;
        if (!ModConfig.CONTENT_ENTITY_NAME_ENABLED.get()) {
            return;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.ENTITY_NAME)) {
            return;
        }

        if (!simple_translate$isEntityInRange(state)) {
            return;
        }

        state.nameTag = simple_translate$translateWithStylePreservation(displayName);
    }

    @Unique
    private boolean simple_translate$isEntityInRange(EntityRenderState state) {
        if (state == null) {
            return false;
        }
        int radius = ModConfig.CONTENT_ENTITY_NAME_RADIUS.get();
        return state.distanceToCameraSq <= (double) radius * radius;
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

        ComponentTranslationResult direct =
                DirectSurfaceTranslator.translateComponent(component, "entity.name.direct", "entity-name");
        if (direct.handled) {
            return direct.component;
        }
        return component;
    }

}


