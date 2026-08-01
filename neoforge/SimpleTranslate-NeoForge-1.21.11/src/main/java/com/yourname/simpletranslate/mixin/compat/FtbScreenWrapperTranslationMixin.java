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
 * <p>Evidence: ftb-library-neoforge-2111.1.1 bytecode (Minecraft 1.21.11,
 * verified with {@code javap -p -s}).
 * {@code dev.ftb.mods.ftblibrary.client.gui.widget.ScreenWrapper} extends
 * Screen and overrides keyPressed as
 * {@code public boolean keyPressed(KeyEvent)} (descriptor
 * {@code (Lnet/minecraft/client/input/KeyEvent;)Z}) and the frame draw as
 * {@code public void render(GuiGraphics, int, int, float)} (descriptor
 * {@code (Lnet/minecraft/client/gui/GuiGraphics;IIF)V}) without delegating
 * to the vanilla body; it does NOT override
 * {@code renderWithTooltipAndSubtitles}, the vanilla frame hook. Bracketing
 * render restores whole-frame K capture for FTB screens that render outside
 * the vanilla frame path, and the keyPressed bridge restores the K shortcut
 * that FTB's keyPressed override swallows. The NeoForge production runtime
 * is mojmap-named, so every remap=false method string uses these exact
 * mojmap names; intermediary names would not be found and require=1 would
 * crash the client.</p>
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.client.gui.widget.ScreenWrapper", remap = false)
public abstract class FtbScreenWrapperTranslationMixin {
    @Unique
    private boolean simple_translate$ownsTranslationFrame;

    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void simple_translate$translateGuiShortcut(
            KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (ModKeyBindings.handleTranslateGuiKey(event)) {
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
