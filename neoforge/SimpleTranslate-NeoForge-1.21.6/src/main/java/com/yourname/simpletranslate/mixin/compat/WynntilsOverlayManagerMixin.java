package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional bridge around Wynntils 4.2.2's single all-overlay render loop on
 * Minecraft 1.21.11 (the distributed jar filename currently says 4.1.22).
 * It gives normal overlay text an independently configured, persistent
 * Component document while retaining exact draw-window suppression when Wynn
 * overlay translation is disabled. The callback omits the external RenderEvent
 * because it is unused.
 */
@Pseudo
@Mixin(targets = "com.wynntils.core.consumers.overlays.OverlayManager", remap = false)
public abstract class WynntilsOverlayManagerMixin {
    @Inject(
            method = "renderOverlays(Lcom/wynntils/mc/event/RenderEvent;)V",
            at = @At("HEAD"), require = 1, remap = false)
    private void simple_translate$beginAllWynnOverlays(CallbackInfo ci) {
        GuiTranslationHelper.beginWynnOverlayFrame();
    }

    @Inject(
            method = "renderOverlays(Lcom/wynntils/mc/event/RenderEvent;)V",
            at = @At("RETURN"), require = 1, remap = false)
    private void simple_translate$endAllWynnOverlays(CallbackInfo ci) {
        GuiTranslationHelper.endWynnOverlayFrame();
    }
}
