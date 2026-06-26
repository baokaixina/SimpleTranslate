package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.core.DrawStringHelper;
import com.yourname.simpletranslate.core.MixinRuntimeProbe;
import com.yourname.simpletranslate.feature.hud.ScoreboardTranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Gui.class)
public class ScoreboardMixin {

    @Redirect(
            method = "displayScoreboardSidebar",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/Objective;getDisplayName()Lnet/minecraft/network/chat/Component;"),
            require = 0
    )
    private Component simple_translate$redirectSidebarTitle(Objective objective) {
        MixinRuntimeProbe.matched("ScoreboardMixin#objectiveDisplayName");
        Component component = objective.getDisplayName();
        if (!ModConfig.HUD_SCOREBOARD_ENABLED.get()) {
            return component;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) {
            return component;
        }

        return ScoreboardTranslationHelper.translateComponent(component);
    }

    @Redirect(
            method = "displayScoreboardSidebar",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"
            ),
            require = 0
    )
    private void simple_translate$redirectSidebarComponentText(
            GuiGraphics guiGraphics,
            Font font,
            Component component,
            int x,
            int y,
            int color,
            boolean shadow
    ) {
        if (!ModConfig.HUD_SCOREBOARD_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) {
            guiGraphics.drawString(font, component, x, y, color, shadow);
            return;
        }
        DrawStringHelper.component(guiGraphics, font, component, x, y, color, shadow,
                ScoreboardTranslationHelper::translateComponent);
    }

    @Redirect(
            method = "displayScoreboardSidebar",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"
            ),
            require = 0
    )
    private void simple_translate$redirectSidebarStringText(
            GuiGraphics guiGraphics,
            Font font,
            String text,
            int x,
            int y,
            int color,
            boolean shadow
    ) {
        if (!ModConfig.HUD_SCOREBOARD_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) {
            guiGraphics.drawString(font, text, x, y, color, shadow);
            return;
        }
        DrawStringHelper.text(guiGraphics, font, text, x, y, color, shadow,
                ScoreboardTranslationHelper::translateString);
    }
}
