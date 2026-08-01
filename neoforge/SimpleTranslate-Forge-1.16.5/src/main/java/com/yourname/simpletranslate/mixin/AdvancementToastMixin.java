package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.yourname.simpletranslate.core.render.GuiGraphics;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.advancements.Advancement;
import net.minecraft.client.gui.toasts.AdvancementToast;
import net.minecraft.client.gui.toasts.IToast;
import net.minecraft.client.gui.toasts.ToastGui;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Owns the complete toast text document in an isolated Component frame. */
@Mixin(AdvancementToast.class)
public class AdvancementToastMixin {
    @Shadow @Final private Advancement advancement;

    @WrapMethod(
            method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/client/gui/toasts/ToastGui;J)Lnet/minecraft/client/gui/toasts/IToast$Visibility;",
            require = 1)
    private IToast.Visibility simple_translate$renderAdvancementToastFrame(
            MatrixStack poseStack, ToastGui toast, long visibleTime, Operation<IToast.Visibility> original) {
        boolean frameStarted = false;
        if (ModConfig.GLOBAL_ENABLED.get() && ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            String id = this.advancement != null && this.advancement.getId() != null
                    ? this.advancement.getId().toString()
                    : Integer.toHexString(System.identityHashCode(this));
            frameStarted = GuiTranslationHelper.beginDetachedFrame(
                    "gui.advancement.toast\n" + id, "Advancement toast", true);
        }
        boolean captureSuppressed = !frameStarted;
        if (captureSuppressed) {
            GuiTranslationHelper.beginCaptureSuppression();
        }
        try {
            return original.call(poseStack, toast, visibleTime);
        } finally {
            if (frameStarted) {
                GuiTranslationHelper.endDetachedFrame(GuiGraphics.wrap(poseStack));
            }
            if (captureSuppressed) {
                GuiTranslationHelper.endCaptureSuppression();
            }
        }
    }
}
