package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.ScoreboardTranslationHelper;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
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
            require = 1
    )
    private Component simple_translate$redirectSidebarTitle(Objective objective) {
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
                    target = "Lnet/minecraft/client/gui/Font;draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"
            ),
            require = 0
    )
    private int simple_translate$redirectSidebarComponentText(
            Font font,
            PoseStack poseStack,
            Component component,
            float x,
            float y,
            int color
    ) {
        if (!ModConfig.HUD_SCOREBOARD_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) {
            return font.draw(poseStack, component, x, y, color);
        }
        return font.draw(poseStack, ScoreboardTranslationHelper.translateComponent(component), x, y, color);
    }

    @Redirect(
            method = "displayScoreboardSidebar",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;draw(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/lang/String;FFI)I"
            ),
            require = 0
    )
    private int simple_translate$redirectSidebarStringText(
            Font font,
            PoseStack poseStack,
            String text,
            float x,
            float y,
            int color
    ) {
        if (!ModConfig.HUD_SCOREBOARD_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) {
            return font.draw(poseStack, text, x, y, color);
        }
        return font.draw(poseStack, ScoreboardTranslationHelper.translateString(text), x, y, color);
    }
}