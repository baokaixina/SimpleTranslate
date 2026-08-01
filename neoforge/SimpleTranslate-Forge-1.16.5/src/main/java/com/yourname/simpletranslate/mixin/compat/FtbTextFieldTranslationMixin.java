package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.util.text.ITextComponent;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Keeps each FTB TextField as one semantic ITextComponent before FTB splits it into
 * visual lines. The whole text is swapped through FTB's own
 * {@code setText(ITextComponent)} for this draw only, so FTB re-wraps the cached
 * translation with its native layout; the original text is restored after the
 * draw.
 *
 * <p>Evidence: {@code javap -p -s} on ftb-library-forge-1605.3.5-build.724.jar
 * (the newest 1.16.5-series FTB Library Forge build on maven.ftb.dev,
 * archived in .analysis/optional-1.16.5). Its TextField keeps the raw text in
 * the public field {@code component} (descriptor
 * {@code Lnet/minecraft/util/text/ITextComponent;}; 1.16.5 Forge runtime uses
 * SRG/MCP class names), {@code formattedText} is a private
 * {@code ITextProperties[]} that {@code setText(ITextComponent)} rebuilds
 * internally, and {@code draw} is FTB's own method with descriptor
 * {@code (Lcom/mojang/blaze3d/matrix/MatrixStack;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V}.
 * Only {@code component} and {@code setText} are used here; {@code setText} is
 * invoked reflectively because its FTB return type is not linkable from a
 * pseudo mixin.
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.ui.TextField", remap = false)
public abstract class FtbTextFieldTranslationMixin {
    @Unique
    private static final Logger simple_translate$LOGGER = LogManager.getLogger("SimpleTranslate/FtbTextFieldTranslationMixin");
    @Unique
    private static Method simple_translate$setTextMethod;
    @Unique
    private static boolean simple_translate$setTextFailed;

    @Shadow
    public ITextComponent component;

    @Unique
    private ITextComponent simple_translate$savedText;
    @Unique
    private boolean simple_translate$semanticDraw;

    @Inject(
            method = "draw(Lcom/mojang/blaze3d/matrix/MatrixStack;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V",
            at = @At("HEAD"), require = 1, remap = false)
    private void simple_translate$translateWholeTextField(CallbackInfo ci) {
        if (!GuiTranslationHelper.isActive() || component == null) {
            return;
        }
        ITextComponent translated = GuiTranslationHelper.translateVisible(component);
        if (translated != component) {
            simple_translate$savedText = component;
            simple_translate$setFtbText(translated);
        }
        GuiTranslationHelper.beginSemanticWidgetDraw();
        simple_translate$semanticDraw = true;
    }

    @Inject(
            method = "draw(Lcom/mojang/blaze3d/matrix/MatrixStack;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V",
            at = @At("RETURN"), require = 1, remap = false)
    private void simple_translate$restoreWholeTextField(CallbackInfo ci) {
        if (!simple_translate$semanticDraw) {
            return;
        }
        simple_translate$semanticDraw = false;
        GuiTranslationHelper.endSemanticWidgetDraw();
        if (simple_translate$savedText != null) {
            simple_translate$setFtbText(simple_translate$savedText);
            simple_translate$savedText = null;
        }
    }

    @Unique
    private void simple_translate$setFtbText(ITextComponent text) {
        if (simple_translate$setTextFailed) {
            return;
        }
        try {
            Method method = simple_translate$setTextMethod;
            if (method == null) {
                method = getClass().getMethod("setText", ITextComponent.class);
                simple_translate$setTextMethod = method;
            }
            method.invoke(this, text);
        } catch (ReflectiveOperationException | RuntimeException e) {
            simple_translate$setTextFailed = true;
            simple_translate$LOGGER.warn("[SimpleTranslate] FTB TextField setText unavailable; FTB text translation disabled: {}", e.toString());
        }
    }
}
