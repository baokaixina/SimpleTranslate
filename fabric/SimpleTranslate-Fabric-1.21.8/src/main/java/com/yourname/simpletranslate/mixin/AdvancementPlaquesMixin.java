package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.AdvancementTranslationHelper;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
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
                    target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
                    remap = true
            ),
            require = 0,
            remap = false
    )
    private void simple_translate$drawTranslatedPlaqueText(Font font, Component text, float x, float y, int color,
                                                           boolean shadow, Matrix4f matrix, MultiBufferSource buffer,
                                                           Font.DisplayMode displayMode, int backgroundColor,
                                                           int packedLight) {
        Component translated = simple_translate$translatePlaqueText(text);
        font.drawInBatch(translated, x, y, color, shadow, matrix, buffer, displayMode, backgroundColor, packedLight);
    }

    @Unique
    private Component simple_translate$translatePlaqueText(Component component) {
        if (component == null || !ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()
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
