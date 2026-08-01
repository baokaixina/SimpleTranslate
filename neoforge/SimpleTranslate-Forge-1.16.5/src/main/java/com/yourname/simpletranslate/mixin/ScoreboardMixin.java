package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.core.MixinRuntimeProbe;
import com.yourname.simpletranslate.feature.hud.ScoreboardTranslationHelper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.IngameGui;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.ITextProperties;
import net.minecraft.scoreboard.ScoreObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IngameGui.class)
public class ScoreboardMixin {

    @Inject(
            method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/scoreboard/ScoreObjective;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void simple_translate$beginSidebarFrame(
            MatrixStack poseStack, ScoreObjective objective, CallbackInfo ci) {
        ScoreboardTranslationHelper.beginFrame();
    }

    @Inject(
            method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/scoreboard/ScoreObjective;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void simple_translate$endSidebarFrame(
            MatrixStack poseStack, ScoreObjective objective, CallbackInfo ci) {
        ScoreboardTranslationHelper.endFrame();
    }

    @WrapOperation(
            method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/scoreboard/ScoreObjective;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/scoreboard/ScoreObjective;getDisplayName()Lnet/minecraft/util/text/ITextComponent;"),
            require = 1
    )
    private ITextComponent simple_translate$wrapSidebarTitle(ScoreObjective objective, Operation<ITextComponent> original) {
        MixinRuntimeProbe.matched("ScoreboardMixin#objectiveDisplayName");
        ITextComponent component = original.call(objective);
        // Width/background calculations must always see the untouched source.
        return component;
    }

    @WrapOperation(
            method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/scoreboard/ScoreObjective;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/FontRenderer;width(Lnet/minecraft/util/text/ITextProperties;)I"
            ),
            require = 1
    )
    private int simple_translate$measureTranslatedSidebarText(
            FontRenderer font, ITextProperties text, Operation<Integer> original) {
        if (text instanceof ITextComponent component
                && ModConfig.HUD_SCOREBOARD_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) {
            ITextComponent translated = ScoreboardTranslationHelper.translateKnownComponent(component);
            return original.call(font, translated == null ? component : translated);
        }
        return original.call(font, text);
    }

    /**
     * Minecraft 1.19.4 draws the sidebar rows inline through
     * {@code FontRenderer.draw(MatrixStack, ITextComponent, float, float, int)} (two sites:
     * title and row names).
     */
    @WrapOperation(
            method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/scoreboard/ScoreObjective;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/FontRenderer;draw(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/util/text/ITextComponent;FFI)I"
            ),
            require = 1
    )
    private int simple_translate$redirectSidebarComponentText(
            FontRenderer font,
            MatrixStack poseStack,
            ITextComponent component,
            float x,
            float y,
            int color,
            Operation<Integer> original
    ) {
        if (!ModConfig.HUD_SCOREBOARD_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) {
            return original.call(font, poseStack, component, x, y, color);
        }
        ITextComponent translated = ScoreboardTranslationHelper.translateFrameComponent(component);
        ITextComponent rendered = translated == null ? component : translated;
        return original.call(font, poseStack, rendered, x, y, color);
    }

}
