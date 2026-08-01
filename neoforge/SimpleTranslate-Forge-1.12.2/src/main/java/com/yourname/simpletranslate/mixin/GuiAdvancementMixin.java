package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.advancements.GuiAdvancement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact 1.12.2 target: GuiAdvancement#drawHover(IIFII)V. The target owns a
 * final title String and final mutable description List<String>, so this
 * Mixin swaps their render-time values and restores both immediately.
 */
@Mixin(GuiAdvancement.class)
public abstract class GuiAdvancementMixin {
    @Shadow @Final @Mutable private String title;
    @Shadow @Final private List<String> description;
    @Unique private String simpletranslate$originalTitle;
    @Unique private List<String> simpletranslate$originalDescription;

    @Inject(method = "drawHover(IIFII)V", at = @At("HEAD"))
    private void simpletranslate$translateWidgetHover(CallbackInfo callback) {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (engine == null || !engine.isConfigured() || !engine.isSurfaceEnabled("advancement.widget")) return;
        simpletranslate$originalTitle = title;
        simpletranslate$originalDescription = new ArrayList<String>(description);
        title = engine.translateStringCachedOrEnqueue(title, "advancement.widget.title");
        for (int i = 0; i < description.size(); i++) {
            String line = description.get(i);
            if (line != null && !line.isEmpty()) {
                description.set(i, engine.translateStringCachedOrEnqueue(line, "advancement.widget.description"));
            }
        }
    }

    @Inject(method = "drawHover(IIFII)V", at = @At("RETURN"))
    private void simpletranslate$restoreWidgetHover(CallbackInfo callback) {
        if (simpletranslate$originalTitle == null || simpletranslate$originalDescription == null) return;
        title = simpletranslate$originalTitle;
        // GuiAdvancement#description is a fixed-size list (Arrays.asList result);
        // clear()/addAll() throw UnsupportedOperationException. Restore by index,
        // which mirrors the fixed-size set() swap used at HEAD.
        int count = Math.min(description.size(), simpletranslate$originalDescription.size());
        for (int i = 0; i < count; i++) {
            description.set(i, simpletranslate$originalDescription.get(i));
        }
        simpletranslate$originalTitle = null;
        simpletranslate$originalDescription = null;
    }
}
