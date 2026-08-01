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
 * <p>Evidence: ftb-library-neoforge-2004.2.5 (the 2004.x series built for
 * Minecraft 1.20.4 on NeoForge) bytecode, javap -p -s -c verified. NeoForge
 * 1.20.4 runs Mojang-mapped at runtime. TextField.formattedText is a private
 * {@code net.minecraft.network.chat.FormattedText[]}, NOT a
 * {@code FormattedCharSequence[]}; a typed shadow of that field with the wrong
 * array type fails to apply and crashes the client when FTB Quests opens. The
 * raw text field is a private plain {@code net.minecraft.network.chat.Component}
 * named {@code rawText} and {@code setText(Component)} is public, rebuilding
 * formattedText from rawText via Theme.listFormattedStringToWidth (javap -c
 * verified), so only those two are used here. {@code setText} is invoked
 * reflectively because its FTB return type is not linkable from a pseudo
 * mixin.</p>
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
    private Component rawText;

    @Unique
    private Component simple_translate$savedRawText;
    @Unique
    private boolean simple_translate$semanticDraw;

    @Inject(
            method = "draw(Lnet/minecraft/client/gui/GuiGraphics;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V",
            at = @At("HEAD"), require = 1, remap = false)
    private void simple_translate$translateWholeTextField(CallbackInfo ci) {
        if (!GuiTranslationHelper.isActive() || rawText == null) {
            return;
        }
        Component translated = GuiTranslationHelper.translateVisible(rawText);
        if (translated != rawText) {
            simple_translate$savedRawText = rawText;
            simple_translate$setFtbText(translated);
        }
        GuiTranslationHelper.beginSemanticWidgetDraw();
        simple_translate$semanticDraw = true;
    }

    @Inject(
            method = "draw(Lnet/minecraft/client/gui/GuiGraphics;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V",
            at = @At("RETURN"), require = 1, remap = false)
    private void simple_translate$restoreWholeTextField(CallbackInfo ci) {
        if (!simple_translate$semanticDraw) {
            return;
        }
        simple_translate$semanticDraw = false;
        GuiTranslationHelper.endSemanticWidgetDraw();
        if (simple_translate$savedRawText != null) {
            simple_translate$setFtbText(simple_translate$savedRawText);
            simple_translate$savedRawText = null;
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
