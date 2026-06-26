package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.DynamicTextTemplate;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.core.ComponentTranslationResult;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.DrawStringHelper;
import com.yourname.simpletranslate.core.MixinRuntimeProbe;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"),
            require = 0
    )
    private int simple_translate$drawBossName(
            GuiGraphics graphics, Font font, Component component, int x, int y, int color) {
        MixinRuntimeProbe.matched("BossHealthOverlayMixin#bossName");
        if (!ModConfig.HUD_BOSSBAR_ENABLED.get()) {
            return graphics.drawString(font, component, x, y, color);
        }
        return DrawStringHelper.component(
                graphics, font, component, x, y, color, false, this::simple_translate$translate);
    }

    @Unique
    private Component simple_translate$translate(Component component) {
        return SafeTranslate.guard(() -> {
            if (component == null || HoldOriginalState.isHolding(HoldOriginalFeature.BOSSBAR)) {
                return component;
            }
            String text = component.getString();
            if (text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
                return component;
            }
            DynamicTextTemplate template = DynamicTextTemplate.capture(component);
            Component request = template.hasValues() ? template.normalized() : component;
            ComponentTranslationResult result =
                    DirectSurfaceTranslator.translateComponent(
                            request, "bossbar.component.direct", "bossbar-name");
            if (!result.handled || !result.translated || result.component == null) {
                return component;
            }
            if (!template.hasValues()) {
                return result.component;
            }
            Component restored = template.restore(result.component);
            return restored == null ? component : restored;
        }, component, "bossbar.translateComponent");
    }
}
