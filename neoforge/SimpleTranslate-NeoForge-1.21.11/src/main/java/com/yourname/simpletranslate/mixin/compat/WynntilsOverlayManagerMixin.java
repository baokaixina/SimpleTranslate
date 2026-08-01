package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional bridge around Wynntils' single all-overlay render loop on
 * Minecraft 1.21.11. It gives normal overlay text an independently
 * configured, persistent Component document while retaining exact
 * draw-window suppression when Wynn overlay translation is disabled. The
 * callback omits the external RenderEvent because it is unused.
 *
 * <p><b>Exact per-version evidence (verified 2026-07-26):</b> the target
 * {@code private void renderOverlays(Lcom/wynntils/mc/event/RenderEvent;)V}
 * on {@code com.wynntils.core.consumers.overlays.OverlayManager} was
 * javap-verified byte-exact in every NeoForge Wynntils build published for
 * MC 1.21.11 at audit time: {@code wynntils-4.1.22-neoforge+MC-1.21.11.jar},
 * {@code wynntils-4.2.2-neoforge+MC-1.21.11.jar} and the current latest
 * {@code wynntils-4.2.3-neoforge+MC-1.21.11.jar}
 * ({@code .analysis/optional-2111/}). All of them pin {@code minecraft}
 * {@code [1.21.11]}, matching this target exactly.</p>
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
