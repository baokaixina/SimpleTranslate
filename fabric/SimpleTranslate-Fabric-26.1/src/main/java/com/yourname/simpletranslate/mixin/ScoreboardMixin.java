package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.core.MixinRuntimeProbe;
import com.yourname.simpletranslate.feature.hud.ScoreboardTranslationHelper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
            method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void simple_translate$beginSidebarFrame(
            GuiGraphicsExtractor graphics, Objective objective, CallbackInfo ci) {
        ScoreboardTranslationHelper.beginFrame();
    }

    @Inject(
            method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void simple_translate$endSidebarFrame(
            GuiGraphicsExtractor graphics, Objective objective, CallbackInfo ci) {
        ScoreboardTranslationHelper.endFrame();
    }

    @WrapOperation(
            method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/Objective;getDisplayName()Lnet/minecraft/network/chat/Component;"),
            require = 1
    )
    private Component simple_translate$wrapSidebarTitle(Objective objective, Operation<Component> original) {
        MixinRuntimeProbe.matched("ScoreboardMixin#objectiveDisplayName");
        Component component = original.call(objective);
        // Width/background calculations must always see the untouched source.
        return component;
    }

    @WrapOperation(
            method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"
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

    @WrapOperation(
            method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"
            ),
            require = 1
    )
    private void simple_translate$redirectSidebarComponentText(
            GuiGraphicsExtractor GuiGraphicsExtractor,
            Font font,
            Component component,
            int x,
            int y,
            int color,
            boolean shadow,
            Operation<Void> original
    ) {
        if (!ModConfig.HUD_SCOREBOARD_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.SCOREBOARD)) {
            original.call(GuiGraphicsExtractor, font, component, x, y, color, shadow);
            return;
        }
        Component translated = ScoreboardTranslationHelper.translateFrameComponent(component);
        Component rendered = translated == null ? component : translated;
        original.call(GuiGraphicsExtractor, font, rendered, x, y, color, shadow);
    }

}
