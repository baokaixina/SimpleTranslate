package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.translation.TranslationEngine;
import com.yourname.simpletranslate.feature.hud.HudTranslationController;
import net.minecraftforge.client.GuiIngameForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD title/subtitle/actionbar for Forge 1.12.2. GuiIngameForge overrides
 * GuiIngame#renderGameOverlay(F)V without calling super, so an injection into
 * the vanilla method never runs on a production Forge client (verified in a
 * live world session 2026-07-27: title/actionbar stayed untranslated while
 * scoreboard/tab-list hooks fired). Inject into the Forge-declared,
 * never-obfuscated GuiIngameForge#renderTitle(IIF)V and
 * #renderRecordOverlay(IIF)V instead (remap = false); both descriptors
 * verified with javap -p against forge-1.12.2-14.23.5.2860 (build target)
 * and forge-1.12.2-14.23.5.2795 (runtime client). Field state goes through
 * GuiIngameAccessor on the declaring class.
 */
@Mixin(GuiIngameForge.class)
public abstract class GuiIngameHudMixin {
    @Unique private String simpletranslate$originalOverlayMessage;
    @Unique private String simpletranslate$originalDisplayedTitle;
    @Unique private String simpletranslate$originalDisplayedSubTitle;
    @Unique private boolean simpletranslate$restoreOverlayMessage;
    @Unique private boolean simpletranslate$restoreDisplayedTitles;

    @Inject(method = "renderTitle(IIF)V", at = @At("HEAD"), remap = false)
    private void simpletranslate$translateTitle(int width, int height, float partialTicks, CallbackInfo callback) {
        simpletranslate$restoreDisplayedTitles = false;
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (engine == null || !engine.isConfigured()) return;
        GuiIngameAccessor access = (GuiIngameAccessor) (Object) this;
        simpletranslate$originalDisplayedTitle = access.simpletranslate$getDisplayedTitle();
        simpletranslate$originalDisplayedSubTitle = access.simpletranslate$getDisplayedSubTitle();
        simpletranslate$restoreDisplayedTitles = true;
        access.simpletranslate$setDisplayedTitle(
                translate(simpletranslate$originalDisplayedTitle, HudTranslationController.Type.TITLE));
        access.simpletranslate$setDisplayedSubTitle(
                translate(simpletranslate$originalDisplayedSubTitle, HudTranslationController.Type.SUBTITLE));
    }

    @Inject(method = "renderTitle(IIF)V", at = @At("RETURN"), remap = false)
    private void simpletranslate$restoreTitle(int width, int height, float partialTicks, CallbackInfo callback) {
        if (!simpletranslate$restoreDisplayedTitles) return;
        simpletranslate$restoreDisplayedTitles = false;
        GuiIngameAccessor access = (GuiIngameAccessor) (Object) this;
        access.simpletranslate$setDisplayedTitle(simpletranslate$originalDisplayedTitle);
        access.simpletranslate$setDisplayedSubTitle(simpletranslate$originalDisplayedSubTitle);
        simpletranslate$originalDisplayedTitle = null;
        simpletranslate$originalDisplayedSubTitle = null;
    }

    @Inject(method = "renderRecordOverlay(IIF)V", at = @At("HEAD"), remap = false)
    private void simpletranslate$translateActionbar(int width, int height, float partialTicks, CallbackInfo callback) {
        simpletranslate$restoreOverlayMessage = false;
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (engine == null || !engine.isConfigured()) return;
        GuiIngameAccessor access = (GuiIngameAccessor) (Object) this;
        simpletranslate$originalOverlayMessage = access.simpletranslate$getOverlayMessage();
        simpletranslate$restoreOverlayMessage = true;
        access.simpletranslate$setOverlayMessage(
                translate(simpletranslate$originalOverlayMessage, HudTranslationController.Type.ACTIONBAR));
    }

    @Inject(method = "renderRecordOverlay(IIF)V", at = @At("RETURN"), remap = false)
    private void simpletranslate$restoreActionbar(int width, int height, float partialTicks, CallbackInfo callback) {
        if (!simpletranslate$restoreOverlayMessage) return;
        simpletranslate$restoreOverlayMessage = false;
        ((GuiIngameAccessor) (Object) this).simpletranslate$setOverlayMessage(simpletranslate$originalOverlayMessage);
        simpletranslate$originalOverlayMessage = null;
    }

    @Unique
    private String translate(String current, HudTranslationController.Type type) {
        if (current == null || current.isEmpty()) return current;
        return HudTranslationController.translate(current, type);
    }
}
