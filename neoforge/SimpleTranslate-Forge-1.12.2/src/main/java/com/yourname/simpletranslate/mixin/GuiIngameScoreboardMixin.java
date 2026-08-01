package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Team;
import com.yourname.simpletranslate.feature.hud.ScoreboardTranslationController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact 1.12.2 target: GuiIngame#renderScoreboard(ScoreObjective,ScaledResolution)V. */
@Mixin(GuiIngame.class)
public abstract class GuiIngameScoreboardMixin {
    @Unique private final ThreadLocal<ScoreObjective> simpletranslate$originalObjective = new ThreadLocal<ScoreObjective>();
    @Unique private final ThreadLocal<String> simpletranslate$originalTitle = new ThreadLocal<String>();

    @Inject(method = "renderScoreboard(Lnet/minecraft/scoreboard/ScoreObjective;Lnet/minecraft/client/gui/ScaledResolution;)V", at = @At("HEAD"))
    private void simpletranslate$beginScoreboard(ScoreObjective objective, ScaledResolution resolution, CallbackInfo callback) {
        ScoreboardTranslationController.beginFrame();
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (engine == null || !engine.isConfigured() || objective == null) return;
        String original = objective.getDisplayName();
        String translated = engine.translateStringCachedOrEnqueue(
                original, "scoreboard.objective.title.string.direct");
        if (!original.equals(translated)) {
            simpletranslate$originalObjective.set(objective);
            simpletranslate$originalTitle.set(original);
            objective.setDisplayName(translated);
        }
    }

    @Inject(method = "renderScoreboard(Lnet/minecraft/scoreboard/ScoreObjective;Lnet/minecraft/client/gui/ScaledResolution;)V", at = @At("RETURN"))
    private void simpletranslate$endScoreboard(ScoreObjective objective, ScaledResolution resolution, CallbackInfo callback) {
        ScoreObjective originalObjective = simpletranslate$originalObjective.get();
        String originalTitle = simpletranslate$originalTitle.get();
        simpletranslate$originalObjective.remove();
        simpletranslate$originalTitle.remove();
        if (originalObjective != null && originalTitle != null) originalObjective.setDisplayName(originalTitle);
        ScoreboardTranslationController.endFrame();
    }

    @Redirect(
            method = "renderScoreboard(Lnet/minecraft/scoreboard/ScoreObjective;Lnet/minecraft/client/gui/ScaledResolution;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/scoreboard/ScorePlayerTeam;formatPlayerName(Lnet/minecraft/scoreboard/Team;Ljava/lang/String;)Ljava/lang/String;", ordinal = 0),
            require = 1
    )
    private String simpletranslate$measureScoreboardRow(Team team, String rawName) {
        return ScoreboardTranslationController.measure(team, rawName);
    }

    @Redirect(
            method = "renderScoreboard(Lnet/minecraft/scoreboard/ScoreObjective;Lnet/minecraft/client/gui/ScaledResolution;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/scoreboard/ScorePlayerTeam;formatPlayerName(Lnet/minecraft/scoreboard/Team;Ljava/lang/String;)Ljava/lang/String;", ordinal = 1),
            require = 1
    )
    private String simpletranslate$drawScoreboardRow(Team team, String rawName) {
        return ScoreboardTranslationController.draw(team, rawName);
    }
}
