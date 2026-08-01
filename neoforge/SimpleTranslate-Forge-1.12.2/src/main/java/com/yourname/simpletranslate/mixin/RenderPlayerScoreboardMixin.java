package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.scoreboard.ScoreObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Translates only the below-name objective label; player identity and numeric score remain native. */
@Mixin(RenderPlayer.class)
public abstract class RenderPlayerScoreboardMixin {
    @Redirect(
            method = "renderEntityName(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDLjava/lang/String;D)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/scoreboard/ScoreObjective;getDisplayName()Ljava/lang/String;"),
            require = 1
    )
    private String simpletranslate$translateBelowNameObjective(ScoreObjective objective) {
        String original = objective.getDisplayName();
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        return engine == null || !engine.isConfigured() ? original
                : engine.translateStringCachedOrEnqueue(
                        original, "scoreboard.component.below_name.v1");
    }
}
