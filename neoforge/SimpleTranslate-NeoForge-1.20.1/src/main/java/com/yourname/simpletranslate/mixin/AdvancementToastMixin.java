package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.advancements.Advancement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Owns the complete toast text document in an isolated Component frame. */
@Mixin(AdvancementToast.class)
public class AdvancementToastMixin {
    @Shadow @Final private Advancement advancement;

    @WrapMethod(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/components/toasts/ToastComponent;J)Lnet/minecraft/client/gui/components/toasts/Toast$Visibility;",
            require = 1)
    private Toast.Visibility simple_translate$renderAdvancementToastFrame(
            GuiGraphics graphics, ToastComponent toast, long visibleTime, Operation<Toast.Visibility> original) {
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
            return original.call(graphics, toast, visibleTime);
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
