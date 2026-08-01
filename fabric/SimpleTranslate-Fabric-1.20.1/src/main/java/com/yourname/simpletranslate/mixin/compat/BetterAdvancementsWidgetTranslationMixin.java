package com.yourname.simpletranslate.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Owns Better Advancements' replacement hover widget as one advancement frame.
 *
 * <p>Exact runtime evidence: BetterAdvancements Fabric 1.20.1-0.4.2.10,
 * {@code BetterAdvancementWidget#drawHover(class_332,int,int,float,int,int)}.
 * The method draws its String title, FormattedCharSequence description, and
 * Component criteria through GuiGraphics, so the existing Component frame draw
 * hooks can translate every row without linking against the optional mod.</p>
 */
@Pseudo
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementWidget", remap = false)
public abstract class BetterAdvancementsWidgetTranslationMixin {
    @WrapMethod(
            method = "drawHover(Lnet/minecraft/class_332;IIFII)V",
            require = 1)
    private void simple_translate$drawBetterAdvancementFrame(
            GuiGraphics graphics, int scrollX, int scrollY, float fade, int left, int top,
            Operation<Void> original) {
        boolean frameStarted = false;
        if (ModConfig.GLOBAL_ENABLED.get() && ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            frameStarted = GuiTranslationHelper.beginDetachedFrame(
                    "gui.advancement.better_advancements\n"
                            + Integer.toHexString(System.identityHashCode(this)),
                    "Better Advancements hover", true);
        }
        boolean captureSuppressed = !frameStarted;
        if (captureSuppressed) {
            GuiTranslationHelper.beginCaptureSuppression();
        }
        try {
            original.call(graphics, scrollX, scrollY, fade, left, top);
        } finally {
            if (frameStarted) {
                GuiTranslationHelper.endDetachedFrame(graphics);
            }
            if (captureSuppressed) {
                GuiTranslationHelper.endCaptureSuppression();
            }
        }
    }
}
