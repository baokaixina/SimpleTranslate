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
 * <p>The only FTB Library installable on Minecraft 1.21.10 is the 2101.1.x
 * series (ftb-library-neoforge-2101.1.33 from maven.ftb.dev; no 2102-2110
 * series exists). It keeps widgets in {@code dev.ftb.mods.ftblibrary.ui},
 * verified against the 2101.1.33 jar bytecode with {@code javap -p -s}:
 * {@code public boolean keyPressed(int, int, int)} (descriptor {@code (III)Z})
 * and {@code public void render(GuiGraphics, int, int, float)} (descriptor
 * {@code (Lnet/minecraft/client/gui/GuiGraphics;IIF)V}) both exist on
 * {@code dev.ftb.mods.ftblibrary.ui.ScreenWrapper}. The NeoForge production
 * runtime is mojmap-named, so every remap=false method string uses these
 * exact mojmap names; intermediary names would not be found and require=1
 * would crash the client. The raw (keyCode, scanCode, modifiers) triple is
 * repacked into the 1.21.9 {@link KeyEvent} record for the shared shortcut
 * handler.</p>
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
        if (ModKeyBindings.handleTranslateGuiKey(new KeyEvent(keyCode, scanCode, modifiers))) {
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
