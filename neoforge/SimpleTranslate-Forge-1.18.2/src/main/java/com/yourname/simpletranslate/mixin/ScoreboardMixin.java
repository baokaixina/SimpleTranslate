package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.core.MixinRuntimeProbe;
import com.yourname.simpletranslate.feature.hud.ScoreboardTranslationHelper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class ScoreboardMixin {

    @Inject(
            method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/scores/Objective;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void simple_translate$beginSidebarFrame(
            PoseStack poseStack, Objective objective, CallbackInfo ci) {
        ScoreboardTranslationHelper.beginFrame();
    }

    @Inject(
            method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/scores/Objective;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void simple_translate$endSidebarFrame(
            PoseStack poseStack, Objective objective, CallbackInfo ci) {
        ScoreboardTranslationHelper.endFrame();
    }

    @WrapOperation(
            method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/scores/Objective;)V",
            // Forge 40.2.21 runs SRG-named game classes. MixinExtras 0.4.1 does
            // not remap this nested @At target through the generated refmap, so
            // keep the exact runtime owner/name/descriptor explicit.
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/scores/Objective;m_83322_()Lnet/minecraft/network/chat/Component;",
                    remap = false
            ),
            require = 1
    )
    private Component simple_translate$wrapSidebarTitle(Objective objective, Operation<Component> original) {
        MixinRuntimeProbe.matched("ScoreboardMixin#objectiveDisplayName");
        Component component = original.call(objective);
        // Width/background calculations must always see the untouched source.
        return component;
    }

    @WrapOperation(
            method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;m_92852_(Lnet/minecraft/network/chat/FormattedText;)I",
                    remap = false
            ),
            require = 1
    )
    private int simple_translate$measureTranslatedSidebarText(
            Font font, FormattedText text, Operation<Integer> original) {
        if (text instanceof Component component
                && ModConfig.HUD_SCOREBOARD_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) {
            Component translated = ScoreboardTranslationHelper.translateKnownComponent(component);
            return original.call(font, translated == null ? component : translated);
        }
        return original.call(font, text);
    }

    /**
     * Minecraft 1.19.4 draws the sidebar rows inline through
     * {@code Font.draw(PoseStack, Component, float, float, int)} (two sites:
     * title and row names).
     */
    @WrapOperation(
            method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;m_92889_(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
                    remap = false
            ),
            require = 1
    )
    private int simple_translate$redirectSidebarComponentText(
            Font font,
            PoseStack poseStack,
            Component component,
            float x,
            float y,
            int color,
            Operation<Integer> original
    ) {
        if (!ModConfig.HUD_SCOREBOARD_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) {
            return original.call(font, poseStack, component, x, y, color);
        }
        Component translated = ScoreboardTranslationHelper.translateFrameComponent(component);
        Component rendered = translated == null ? component : translated;
        return original.call(font, poseStack, rendered, x, y, color);
    }

}
