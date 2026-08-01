package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional terminal bridge for FTB Library's Screen method overrides.
 *
 * <p>Evidence: ftb-library-fabric-2111.1.1 bytecode (Minecraft 1.21.11).
 * ScreenWrapper extends Screen (class_437) and overrides keyPressed as
 * {@code method_25404(Lnet/minecraft/class_11908;)Z} (class_11908 = KeyEvent)
 * and the frame draw as {@code method_25394(Lnet/minecraft/class_332;IIF)V}
 * (Screen#render) without delegating to the vanilla body; it does NOT
 * override {@code method_47413} (Screen#renderWithTooltipAndSubtitles), the
 * vanilla frame hook. Bracketing method_25394 restores whole-frame K capture
 * for FTB screens that render outside the vanilla frame path, and the
 * method_25404 bridge restores the K shortcut that FTB's keyPressed override
 * swallows. remap=false method strings must stay in intermediary form.</p>
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.client.gui.widget.ScreenWrapper", remap = false)
public abstract class FtbScreenWrapperTranslationMixin {
    @Unique
    private boolean simple_translate$ownsTranslationFrame;

    @Inject(method = "method_25404(Lnet/minecraft/class_11908;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void simple_translate$translateGuiShortcut(
            KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (ModKeyBindings.handleTranslateGuiKey(event)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "method_25394(Lnet/minecraft/class_332;IIF)V",
            at = @At("HEAD"), require = 1, remap = false)
    private void simple_translate$beginFtbFrame(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        boolean alreadyActive = GuiTranslationHelper.isActive();
        if (!alreadyActive) {
            GuiTranslationHelper.beginFrame((Screen) (Object) this);
        }
        simple_translate$ownsTranslationFrame = !alreadyActive && GuiTranslationHelper.isActive();
    }

    @Inject(method = "method_25394(Lnet/minecraft/class_332;IIF)V",
            at = @At("RETURN"), require = 1, remap = false)
    private void simple_translate$endFtbFrame(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        if (simple_translate$ownsTranslationFrame) {
            simple_translate$ownsTranslationFrame = false;
            GuiTranslationHelper.endFrame(graphics);
        }
    }
}
