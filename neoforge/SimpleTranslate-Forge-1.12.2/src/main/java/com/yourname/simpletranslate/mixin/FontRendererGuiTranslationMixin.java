package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.gui.GuiTranslationController;
import net.minecraft.client.gui.FontRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact 1.12.2 targets: FontRenderer#renderString(String,FFIZ) and #getStringWidth(String). */
@Mixin(FontRenderer.class)
public abstract class FontRendererGuiTranslationMixin {
    @Inject(method = "renderString(Ljava/lang/String;FFIZ)I", at = @At("HEAD"), cancellable = true)
    private void simpletranslate$renderTranslatedGuiText(String text, float x, float y, int color,
                                                         boolean shadow,
                                                         CallbackInfoReturnable<Integer> cir) {
        Integer rendered = GuiTranslationController.renderVisibleText(
                (FontRenderer) (Object) this, text, x, y, color, shadow);
        if (rendered != null) cir.setReturnValue(rendered);
    }

    @ModifyVariable(method = "getStringWidth(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String simpletranslate$measureRenderedGuiText(String text) {
        return GuiTranslationController.transformVisibleText(text);
    }
}
