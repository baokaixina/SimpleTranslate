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
 * <p>Evidence: ftb-library-fabric-2101.1.33 bytecode (maven.ftb.dev, the only
 * series installable on Minecraft 1.21.9; verified with {@code javap -p -s}).
 * Its {@code dev.ftb.mods.ftblibrary.ui.TextField.formattedText} is a private
 * {@code FormattedText[]} (intermediary class_5348), NOT a
 * {@code FormattedCharSequence[]} (class_5481); a typed shadow of that field
 * fails to apply and crashes the client when FTB Quests opens. The raw text
 * field is a plain Component (class_2561) and {@code setText(Component)} is
 * public in every FTB Library series, so only those two are used here.
 * {@code setText} is invoked reflectively because its FTB return type is not
 * linkable from a pseudo mixin. The injected draw descriptor
 * {@code draw(Lnet/minecraft/class_332;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V}
 * uses the intermediary names of the production runtime namespace.</p>
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
            method = "draw(Lnet/minecraft/class_332;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V",
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
            method = "draw(Lnet/minecraft/class_332;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V",
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
