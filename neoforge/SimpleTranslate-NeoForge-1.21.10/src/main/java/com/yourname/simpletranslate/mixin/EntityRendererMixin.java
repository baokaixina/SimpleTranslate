package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.DirectFormattedTranslationPipeline;
import com.yourname.simpletranslate.util.DirectSurfaceTranslator;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Translates entity name tags while preserving component styling.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin<T extends Entity> {

    @Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
    private void simple_translate$translateNameTag(T entity, CallbackInfoReturnable<Component> cir) {
        Component displayName = cir.getReturnValue();
        if (!ModConfig.CONTENT_ENTITY_NAME_ENABLED.get()) {
            return;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.ENTITY_NAME)) {
            return;
        }

        if (!simple_translate$isEntityInRange(entity)) {
            return;
        }

        cir.setReturnValue(simple_translate$translateWithStylePreservation(displayName));
    }

    @Unique
    private boolean simple_translate$isEntityInRange(T entity) {
        if (entity == null) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }

        double distance = mc.player.distanceTo(entity);
        int radius = ModConfig.CONTENT_ENTITY_NAME_RADIUS.get();

        return distance <= radius;
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


