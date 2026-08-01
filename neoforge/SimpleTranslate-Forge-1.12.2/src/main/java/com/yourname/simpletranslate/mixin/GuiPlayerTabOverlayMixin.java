package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Preserves player identities and translates only the tab-list header/footer,
 * matching the baseline. Exact target: renderPlayerlist(I,Scoreboard,ScoreObjective)V.
 */
@Mixin(GuiPlayerTabOverlay.class)
public abstract class GuiPlayerTabOverlayMixin {
    @Shadow private ITextComponent header;
    @Shadow private ITextComponent footer;
    @Unique private ITextComponent simpletranslate$originalHeader;
    @Unique private ITextComponent simpletranslate$originalFooter;

    @Inject(method = "renderPlayerlist(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V", at = @At("HEAD"))
    private void simpletranslate$beginTabRender(int width, Scoreboard scoreboard, ScoreObjective objective,
                                                CallbackInfo callback) {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (engine == null || !engine.isConfigured()) return;
        if (header != null) {
            ITextComponent translated = engine.translateCachedOrEnqueue(
                    header, "scoreboard.component.list.v1.header");
            if (translated != header) { simpletranslate$originalHeader = header; header = translated; }
        }
        if (footer != null) {
            ITextComponent translated = engine.translateCachedOrEnqueue(
                    footer, "scoreboard.component.list.v1.footer");
            if (translated != footer) { simpletranslate$originalFooter = footer; footer = translated; }
        }
    }

    @Inject(method = "renderPlayerlist(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V", at = @At("RETURN"))
    private void simpletranslate$endTabRender(int width, Scoreboard scoreboard, ScoreObjective objective,
                                              CallbackInfo callback) {
        if (simpletranslate$originalHeader != null) { header = simpletranslate$originalHeader; simpletranslate$originalHeader = null; }
        if (simpletranslate$originalFooter != null) { footer = simpletranslate$originalFooter; simpletranslate$originalFooter = null; }
    }
}
