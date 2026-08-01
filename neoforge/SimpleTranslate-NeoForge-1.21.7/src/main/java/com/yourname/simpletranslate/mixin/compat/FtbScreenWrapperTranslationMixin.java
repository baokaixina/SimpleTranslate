package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
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
 * <p>FTB Library keeps widgets in {@code dev.ftb.mods.ftblibrary.ui} and ships
 * Mojmap-named overrides on NeoForge 1.21.x (the 21.x production runtime is
 * Mojmap-named, verified against the installed neoforge-21.7.25-beta-client.jar
 * and ftb-library-neoforge-2101.1.33 bytecode): {@code keyPressed} is
 * Screen#keyPressed, which FTB overrides without delegating to the vanilla
 * body, so the K shortcut must be delivered here. {@code render} is
 * Screen#render — FTB's real frame draw: its body calls its own
 * {@code renderBackground} (Screen#renderBackground, NOT renderWithTooltip as
 * an earlier revision of this comment claimed) and then draws the whole
 * BaseScreen including foreground and tooltips. Bracketing {@code render}
 * therefore captures the complete frame; bracketing {@code renderBackground}
 * would only cover the background pass.</p>
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.ui.ScreenWrapper", remap = false)
public abstract class FtbScreenWrapperTranslationMixin {
    @Unique
    private boolean simple_translate$ownsTranslationFrame;

    @Inject(method = "keyPressed(III)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void simple_translate$translateGuiShortcut(
            int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (ModKeyBindings.handleTranslateGuiKey(keyCode, scanCode)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
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

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
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
