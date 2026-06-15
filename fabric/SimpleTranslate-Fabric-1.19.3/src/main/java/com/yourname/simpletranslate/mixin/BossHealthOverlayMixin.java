package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.DirectFormattedTranslationPipeline;
import com.yourname.simpletranslate.util.DirectSurfaceTranslator;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BossHealthOverlay.class)
public class BossHealthOverlayMixin {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"),
            require = 1
    )
    private int simple_translate$redirectBossName(Font font, PoseStack poseStack, Component component, float x, float y, int color) {
        if (!ModConfig.HUD_BOSSBAR_ENABLED.get()) {
            return font.drawShadow(poseStack, component, x, y, color);
        }

        return font.drawShadow(poseStack, simple_translate$translateComponent(component), x, y, color);
    }

    @Unique
    private Component simple_translate$translateComponent(Component component) {
        if (component == null) {
            return null;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.BOSSBAR)) {
            return component;
        }

        String text = component.getString();
        if (text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
            return component;
        }

        DirectFormattedTranslationPipeline.ComponentResult direct =
                DirectSurfaceTranslator.translateComponent(component, "bossbar.component.direct", "bossbar-name");
        if (direct.handled) {
            return direct.component;
        }
        return component;
    }
}
