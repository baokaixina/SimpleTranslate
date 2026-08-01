package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.translation.TranslationEngine;
import com.yourname.simpletranslate.feature.sign.SignContextSelectionManager;
import net.minecraft.client.renderer.tileentity.TileEntitySignRenderer;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Exact 1.12.2 render target: TileEntitySignRenderer#render(TileEntitySign,
 * DDDFIF)V. The world TileEntity remains untouched after the render call.
 */
@Mixin(TileEntitySignRenderer.class)
public abstract class TileEntitySignRendererMixin {
    @Unique private final ThreadLocal<ITextComponent[]> simpletranslate$originalLines = new ThreadLocal<ITextComponent[]>();

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntitySign;DDDFIF)V", at = @At("HEAD"))
    private void simpletranslate$beginTranslatedSignRender(
            TileEntitySign sign, double x, double y, double z, float partialTicks,
            int destroyStage, float alpha, CallbackInfo callback
    ) {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (engine == null || sign == null || sign.signText == null) return;
        // Avoid array.clone(): MixinBooter's 1.12 FG3 remapper attempts to
        // remap the array owner as a class method during transformation.
        ITextComponent[] original = new ITextComponent[sign.signText.length];
        System.arraycopy(sign.signText, 0, original, 0, original.length);
        boolean changed = false;
        ITextComponent[] translatedLines = SignContextSelectionManager.translatedForRender(sign);
        if (translatedLines != null && translatedLines.length == sign.signText.length) {
            System.arraycopy(translatedLines, 0, sign.signText, 0, translatedLines.length);
            changed = true;
        }
        if (changed) simpletranslate$originalLines.set(original);
    }

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntitySign;DDDFIF)V", at = @At("RETURN"))
    private void simpletranslate$restoreOriginalSignLines(
            TileEntitySign sign, double x, double y, double z, float partialTicks,
            int destroyStage, float alpha, CallbackInfo callback
    ) {
        ITextComponent[] original = simpletranslate$originalLines.get();
        simpletranslate$originalLines.remove();
        if (original != null && sign != null && sign.signText != null) {
            System.arraycopy(original, 0, sign.signText, 0, Math.min(original.length, sign.signText.length));
        }
    }
}
