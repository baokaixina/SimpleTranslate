package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owns the complete toast text document in an isolated Component frame. */
@Mixin(AdvancementToast.class)
public class AdvancementToastMixin {
    @Shadow @Final private AdvancementHolder advancement;

    // NeoForge 21.0 ships MixinExtras 0.3.5, which predates @WrapMethod; the
    // donor's single wrap splits into this HEAD/RETURN pair with the frame
    // state carried on unique fields.
    @Unique
    private boolean simple_translate$frameStarted;
    @Unique
    private boolean simple_translate$captureSuppressed;

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/components/toasts/ToastComponent;J)Lnet/minecraft/client/gui/components/toasts/Toast$Visibility;",
            at = @At("HEAD"), require = 1)
    private void simple_translate$beginAdvancementToastFrame(
            GuiGraphics graphics, ToastComponent toast, long visibleTime, CallbackInfo ci) {
        simple_translate$frameStarted = false;
        if (ModConfig.GLOBAL_ENABLED.get() && ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            String id = this.advancement != null && this.advancement.id() != null
                    ? this.advancement.id().toString()
                    : Integer.toHexString(System.identityHashCode(this));
            simple_translate$frameStarted = GuiTranslationHelper.beginDetachedFrame(
                    "gui.advancement.toast\n" + id, "Advancement toast", true);
        }
        simple_translate$captureSuppressed = !simple_translate$frameStarted;
        if (simple_translate$captureSuppressed) {
            GuiTranslationHelper.beginCaptureSuppression();
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/components/toasts/ToastComponent;J)Lnet/minecraft/client/gui/components/toasts/Toast$Visibility;",
            at = @At("RETURN"), require = 1)
    private void simple_translate$endAdvancementToastFrame(
            GuiGraphics graphics, ToastComponent toast, long visibleTime, CallbackInfo ci) {
        if (simple_translate$frameStarted) {
            simple_translate$frameStarted = false;
            GuiTranslationHelper.endDetachedFrame(graphics);
        }
        if (simple_translate$captureSuppressed) {
            simple_translate$captureSuppressed = false;
            GuiTranslationHelper.endCaptureSuppression();
        }
    }
}
