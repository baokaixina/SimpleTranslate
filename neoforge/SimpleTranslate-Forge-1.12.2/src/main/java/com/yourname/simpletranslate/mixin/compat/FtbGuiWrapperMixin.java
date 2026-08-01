package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.compat.FtbGuiCompat;
import com.yourname.simpletranslate.gui.GuiTranslationController;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Optional exact FTBLib-5.4.7.2 target. The production jar's GuiWrapper
 * renders through func_73863_a(IIF)V, so remap stays disabled. No FTB type is
 * referenced in the handler signature, keeping this pseudo Mixin safe when
 * FTB Library/Quests is absent.
 */
@Pseudo
@Mixin(targets = "com.feed_the_beast.ftblib.lib.gui.GuiWrapper", remap = false)
public abstract class FtbGuiWrapperMixin {
    @Unique private final Map<Object, String> simpletranslate$changed = new IdentityHashMap<Object, String>();

    @Inject(method = "func_73863_a(IIF)V", at = @At("HEAD"), remap = false)
    private void simpletranslate$translateFtbWidgets(int mouseX, int mouseY, float partialTicks, CallbackInfo callback) {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        GuiScreen screen = (GuiScreen) (Object) this;
        if (engine != null && engine.isConfigured() && engine.isSurfaceEnabled("ftb.gui")
                && GuiTranslationController.isEnabled(screen)) {
            FtbGuiCompat.translateVisibleWidgets(this, engine, simpletranslate$changed);
        }
    }

    @Inject(method = "func_73863_a(IIF)V", at = @At("RETURN"), remap = false)
    private void simpletranslate$restoreFtbWidgets(int mouseX, int mouseY, float partialTicks, CallbackInfo callback) {
        for (Map.Entry<Object, String> entry : simpletranslate$changed.entrySet()) {
            FtbGuiCompat.restore(entry.getKey(), entry.getValue());
        }
        simpletranslate$changed.clear();
    }
}
