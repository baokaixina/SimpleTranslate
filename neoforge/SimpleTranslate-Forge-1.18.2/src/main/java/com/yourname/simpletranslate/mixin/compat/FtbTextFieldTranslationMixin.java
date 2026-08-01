package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Keeps each FTB TextField as one semantic Component before FTB splits it into
 * visual lines. The whole text is swapped through FTB's own
 * {@code setText(Component)} for this draw only, so FTB re-wraps the cached
 * translation with its native layout; the original text is restored after the
 * draw.
 *
 * <p>Evidence: {@code javap -p -s} on the exact
 * ftb-library-forge-1802.3.12-build.726.jar. Its
 * TextField keeps the raw text in the public field {@code component}
 * (descriptor {@code Lnet/minecraft/network/chat/Component;}; class names are
 * Mojmap at Forge runtime), {@code formattedText} is a private
 * {@code FormattedText[]} that {@code setText(Component)} rebuilds internally,
 * and {@code draw} is FTB's own method with descriptor
 * {@code (Lcom/mojang/blaze3d/vertex/PoseStack;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V}.
 * Only {@code component} and {@code setText} are used here; {@code setText} is
 * invoked reflectively because its FTB return type is not linkable from a
 * pseudo mixin.
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.ui.TextField", remap = false)
public abstract class FtbTextFieldTranslationMixin {
    @Unique
    private static final Logger simple_translate$LOGGER = LoggerFactory.getLogger("SimpleTranslate/FtbTextFieldTranslationMixin");
    @Unique
    private static Method simple_translate$setTextMethod;
    @Unique
    private static boolean simple_translate$setTextFailed;

    @Shadow
    public Component component;

    @Unique
    private Component simple_translate$savedText;
    @Unique
    private boolean simple_translate$semanticDraw;

    @Inject(
            method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V",
            at = @At("HEAD"), require = 1, remap = false)
    private void simple_translate$translateWholeTextField(CallbackInfo ci) {
        if (!GuiTranslationHelper.isActive() || component == null) {
            return;
        }
        Component translated = GuiTranslationHelper.translateVisible(component);
        if (translated != component) {
            simple_translate$savedText = component;
            simple_translate$setFtbText(translated);
        }
        GuiTranslationHelper.beginSemanticWidgetDraw();
        simple_translate$semanticDraw = true;
    }

    @Inject(
            method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V",
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
    private void simple_translate$setFtbText(Component text) {
        if (simple_translate$setTextFailed) {
            return;
        }
        try {
            Method method = simple_translate$setTextMethod;
            if (method == null) {
                method = getClass().getMethod("setText", Component.class);
                simple_translate$setTextMethod = method;
            }
            method.invoke(this, text);
        } catch (ReflectiveOperationException | RuntimeException e) {
            simple_translate$setTextFailed = true;
            simple_translate$LOGGER.warn("[SimpleTranslate] FTB TextField setText unavailable; FTB text translation disabled: {}", e.toString());
        }
    }
}
