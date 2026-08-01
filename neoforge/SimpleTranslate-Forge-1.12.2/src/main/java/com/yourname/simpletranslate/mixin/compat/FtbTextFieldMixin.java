package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.gui.GuiTranslationController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * FTBLib 5.4.7.2 evidence: TextField has public String[] text ([Ljava/lang/String;)
 * and draw(Theme,IIII)V reads that field for every rendered line.  The handler
 * omits private FTB argument types, a supported Mixin callback form, so this
 * pseudo Mixin remains optional without a compile-time FTBLib dependency.
 */
@Pseudo
@Mixin(targets = "com.feed_the_beast.ftblib.lib.gui.TextField", remap = false)
public abstract class FtbTextFieldMixin {
    @Shadow(remap = false) public String[] text;
    @Unique private String[] simpletranslate$originalText;

    @Inject(method = "draw(Lcom/feed_the_beast/ftblib/lib/gui/Theme;IIII)V", at = @At("HEAD"), remap = false)
    private void simpletranslate$translateTextField(CallbackInfo callback) {
        if (text == null) return;
        // Do not invoke clone() on the array directly: MixinBooter 9.4 tries
        // to remap the bytecode owner "[Ljava/lang/String;" and fails while
        // transforming this optional pseudo mixin. Arrays.copyOf emits a
        // normal JDK static invocation instead (verified against the exact
        // FTBLib 5.4.7.2 runtime jar).
        String[] translated = Arrays.copyOf(text, text.length);
        boolean changed = false;
        for (int i = 0; i < translated.length; i++) {
            String line = translated[i];
            if (line == null || line.isEmpty()) continue;
            String result = GuiTranslationController.transformVisibleText(line);
            if (!line.equals(result)) { translated[i] = result; changed = true; }
        }
        if (changed) {
            simpletranslate$originalText = text;
            text = translated;
        }
    }

    @Inject(method = "draw(Lcom/feed_the_beast/ftblib/lib/gui/Theme;IIII)V", at = @At("RETURN"), remap = false)
    private void simpletranslate$restoreTextField(CallbackInfo callback) {
        if (simpletranslate$originalText != null) {
            text = simpletranslate$originalText;
            simpletranslate$originalText = null;
        }
    }
}
