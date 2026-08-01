package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * <p>Evidence: ftb-library-fabric-26.1.2.6 and 26.1.1.1 bytecode from
 * maven.ftb.dev, verified with {@code javap -p -s}. Minecraft 26.x ships
 * unobfuscated, so the production runtime namespace is the Mojang-readable
 * one and every remap=false method string must use those exact names on
 * {@code dev.ftb.mods.ftblibrary.client.gui.widget.ScreenWrapper}:
 * {@code keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z} and
 * {@code extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V}.
 * Switching these strings to intermediary {@code class_xxxx}/{@code method_xxxx}
 * names would break the injection, because 26.x has no intermediary names.</p>
 * <p>No FTB Library build exists for Minecraft 26.2 (the newest FTB
 * series is 26.1.2.6 for the 26.1.x line), so this compat is dormant on
 * 26.2 by design; it is kept in the verified 26.1.x form so a future
 * 26.2 FTB build with the same contract applies immediately.</p>
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

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("HEAD"), require = 1, remap = false)
    private void simple_translate$beginFtbFrame(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        boolean alreadyActive = GuiTranslationHelper.isActive();
        if (!alreadyActive) {
            GuiTranslationHelper.beginFrame((Screen) (Object) this);
        }
        simple_translate$ownsTranslationFrame = !alreadyActive && GuiTranslationHelper.isActive();
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("RETURN"), require = 1, remap = false)
    private void simple_translate$endFtbFrame(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        if (simple_translate$ownsTranslationFrame) {
            simple_translate$ownsTranslationFrame = false;
            GuiTranslationHelper.endFrame(graphics);
        }
    }
}
