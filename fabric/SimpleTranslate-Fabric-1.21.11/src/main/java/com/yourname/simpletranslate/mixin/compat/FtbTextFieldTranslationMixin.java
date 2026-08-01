package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps each FTB TextField as one semantic Component before FTB splits it into
 * visual lines. Cached translations are reflowed locally for this draw only.
 *
 * <p>Evidence: ftb-library-fabric-2111.1.1 bytecode (the FTB Library series
 * for Minecraft 1.21.11). TextField keeps its raw Component in
 * {@code rawText} (class_2561) and the wrapped lines in a private
 * {@code formattedText} array of FormattedCharSequence (class_5481) - note the
 * older ui.TextField series (1902.x-2101.x) stores FormattedText[]
 * (class_5348) instead, so this typed shadow is only valid for the
 * client.gui.widget package. {@code draw} takes an intermediary
 * {@code class_332} GuiGraphics and {@code client.gui.theme.Theme}; remap=false
 * method strings must stay in intermediary form.</p>
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.client.gui.widget.TextField", remap = false)
public abstract class FtbTextFieldTranslationMixin {
    @Shadow private Component rawText;
    @Shadow private FormattedCharSequence[] formattedText;
    @Shadow public int maxWidth;
    @Shadow public float scale;

    @Unique private FormattedCharSequence[] simple_translate$originalFormattedText;
    @Unique private boolean simple_translate$semanticDraw;

    @Inject(
            method = "draw(Lnet/minecraft/class_332;Ldev/ftb/mods/ftblibrary/client/gui/theme/Theme;IIII)V",
            at = @At("HEAD"), require = 1, remap = false)
    private void simple_translate$translateWholeTextField(CallbackInfo ci) {
        if (!GuiTranslationHelper.isActive() || rawText == null || formattedText == null) {
            return;
        }
        Component translated = GuiTranslationHelper.translateVisible(rawText);
        simple_translate$originalFormattedText = formattedText;
        if (translated != rawText) {
            float safeScale = scale <= 0.0F ? 1.0F : scale;
            int wrapWidth = Math.max(1, (int) (Math.max(1, maxWidth) / safeScale));
            formattedText = Minecraft.getInstance().font.split(translated, wrapWidth)
                    .toArray(FormattedCharSequence[]::new);
        }
        GuiTranslationHelper.beginSemanticWidgetDraw();
        simple_translate$semanticDraw = true;
    }

    @Inject(
            method = "draw(Lnet/minecraft/class_332;Ldev/ftb/mods/ftblibrary/client/gui/theme/Theme;IIII)V",
            at = @At("RETURN"), require = 1, remap = false)
    private void simple_translate$restoreWholeTextField(CallbackInfo ci) {
        if (!simple_translate$semanticDraw) {
            return;
        }
        simple_translate$semanticDraw = false;
        GuiTranslationHelper.endSemanticWidgetDraw();
        if (simple_translate$originalFormattedText != null) {
            formattedText = simple_translate$originalFormattedText;
            simple_translate$originalFormattedText = null;
        }
    }
}
