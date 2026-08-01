package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional bridge around Wynntils' single all-overlay render loop. It gives
 * normal overlay text an independently configured, persistent Component
 * document while retaining exact draw-window suppression when Wynn overlay
 * translation is disabled. The callback omits the external RenderEvent
 * because it is unused.
 *
 * <p><b>Per-version evidence status (verified 2026-07-26):</b> Wynntils
 * publishes no build for Minecraft 1.21.9 on any loader — Modrinth,
 * CurseForge, and the Wynntils/Wynntils GitHub releases all jump from
 * MC 1.21.4 straight to MC 1.21.11 in the 1.21 family, and the 1.21.11
 * builds pin {@code minecraft} to 1.21.11 so they cannot load here either.
 * This mixin is therefore permanently dormant on this target:
 * {@code SimpleTranslateMixinPlugin} applies it only when the
 * {@code wynntils} mod id is present, and no loadable artifact exists.
 * The API shape ({@code private void renderOverlays(
 * Lcom/wynntils/mc/event/RenderEvent;)V} on
 * {@code com.wynntils.core.consumers.overlays.OverlayManager}) is
 * javap-verified against the exact wynntils-4.2.2-neoforge+MC-1.21.11 and
 * 4.2.3 jars ({@code .analysis/optional-2111/}); that is neighbouring-version
 * evidence retained for donor parity only — exact 1.21.9 evidence cannot
 * exist until Wynntils ships a 1.21.9 build.</p>
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
