package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.feature.advancement.AdvancementTranslationHelper;
import com.yourname.simpletranslate.core.ComponentRenderSafety;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import com.mojang.math.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.anthonyhilyard.advancementplaques.ui.render.AdvancementPlaque", remap = false)
public class AdvancementPlaquesMixin {

    @Redirect(
            method = "lambda$drawPlaque$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
                    remap = true
            ),
            require = 0,
            remap = false
    )
    private int simple_translate$drawTranslatedPlaqueText(Font font, Component text, float x, float y, int color,
                                                          boolean shadow, Matrix4f matrix, MultiBufferSource buffer,
                                                          Font.DisplayMode displayMode, int backgroundColor,
                                                          int packedLight) {
        Component safeText = ComponentRenderSafety.sanitize(text);
        Component translated = simple_translate$translatePlaqueText(safeText);
        return font.drawInBatch(ComponentRenderSafety.sanitize(translated, safeText.getString()),
                x, y, color, shadow, matrix, buffer,
                displayMode == Font.DisplayMode.SEE_THROUGH, backgroundColor, packedLight);
    }

    @Unique
    private Component simple_translate$translatePlaqueText(Component component) {
        component = ComponentRenderSafety.sanitize(component);
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            return component;
        }

        String text = component.getString();
        if (text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
            return component;
        }

        return AdvancementTranslationHelper.translateComponent(component,
                "advancement.plaque.component.direct", "advancement-plaque");
    }
}
