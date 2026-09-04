package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.feature.book.ScholarBookBridge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional bridge for Scholar's replacement book screens.
 *
 * <p>Deliberately mixed into vanilla {@code Screen} rather than into a Scholar
 * class. Scholar's screens are ordinary {@code Screen}s, so this hook brackets
 * their render exactly the same way, while every Scholar-specific lookup stays
 * in {@link ScholarBookBridge} behind reflection. Nothing here needs Scholar on
 * the compile classpath, and no injector can fail against a Scholar update.</p>
 *
 * <p>Applied only when Scholar is installed; see {@code SimpleTranslateMixinPlugin}.</p>
 */
@Mixin(Screen.class)
public class ScholarBookScreenMixin {
    @Inject(method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("HEAD"), require = 1)
    private void simple_translate$scholarBookBeginRender(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ScholarBookBridge.beginRender((Screen) (Object) this);
    }

    @Inject(method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("RETURN"), require = 1)
    private void simple_translate$scholarBookEndRender(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ScholarBookBridge.endRender((Screen) (Object) this, graphics, mouseX, mouseY);
    }
}
