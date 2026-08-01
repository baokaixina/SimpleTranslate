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
 * <p>Evidence: ftb-library-fabric-26.1.2.6 and 26.1.1.1 bytecode from
 * maven.ftb.dev (the complete FTB series for the Minecraft 26.1.x line),
 * verified with {@code javap -p -s}. Minecraft 26.x ships unobfuscated, so
 * the production runtime namespace is the Mojang-readable one (Fabric's
 * intermediary artifact for 26.x is a 0.0.0 identity stub); every remap=false
 * method string below intentionally uses those exact runtime names:
 * {@code draw(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ldev/ftb/mods/ftblibrary/client/gui/theme/Theme;IIII)V}.
 * The shadows match the jar field for field: {@code rawText} is a private
 * {@code net.minecraft.network.chat.Component}, {@code formattedText} is a
 * private {@code net.minecraft.util.FormattedCharSequence[]} (NOT the
 * FormattedText[] of the 2101.1.x series, so the reflow design stays),
 * {@code maxWidth} is a public int and {@code scale} a public float.</p>
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
            method = "draw(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ldev/ftb/mods/ftblibrary/client/gui/theme/Theme;IIII)V",
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
            method = "draw(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ldev/ftb/mods/ftblibrary/client/gui/theme/Theme;IIII)V",
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
