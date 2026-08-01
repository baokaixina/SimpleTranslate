package com.yourname.simpletranslate.mixin.compat;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional bridge around Wynntils 3.4.5's all-overlay render loop on
 * Minecraft 1.21.4. It gives normal overlay text an independently configured,
 * persistent Component document while retaining exact draw-window suppression
 * when Wynn overlay translation is disabled.
 *
 * <p>On Wynntils 3.4.5 (the newest series supporting Minecraft 1.21.4) the
 * public {@code onRenderPre}/{@code onRenderPost} handlers both delegate to the
 * private {@code renderOverlays(RenderEvent, RenderState)} loop, so bracketing
 * that two-argument method owns each exact overlay render invocation
 * (verified against wynntils-3.4.5-fabric+MC-1.21.4.jar bytecode). The
 * callback omits both external arguments because they are unused.</p>
 */
@Pseudo
@Mixin(targets = "com.wynntils.core.consumers.overlays.OverlayManager", remap = false)
public abstract class WynntilsOverlayManagerMixin {
    @Inject(
            method = "renderOverlays(Lcom/wynntils/mc/event/RenderEvent;Lcom/wynntils/core/consumers/overlays/RenderState;)V",
            at = @At("HEAD"), require = 1, remap = false)
    private void simple_translate$beginAllWynnOverlays(CallbackInfo ci) {
        GuiTranslationHelper.beginWynnOverlayFrame();
    }

    @Inject(
            method = "renderOverlays(Lcom/wynntils/mc/event/RenderEvent;Lcom/wynntils/core/consumers/overlays/RenderState;)V",
            at = @At("RETURN"), require = 1, remap = false)
    private void simple_translate$endAllWynnOverlays(CallbackInfo ci) {
        GuiTranslationHelper.endWynnOverlayFrame();
    }
}
