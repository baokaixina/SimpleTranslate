package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.toasts.AdvancementToast;
import net.minecraft.client.gui.toasts.GuiToast;
import net.minecraft.client.gui.toasts.IToast;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact target: AdvancementToast#draw(GuiToast,J)LIToast$Visibility;. */
@Mixin(AdvancementToast.class)
public abstract class AdvancementToastMixin {
    @Shadow @Final private Advancement advancement;
    @Unique private ITextComponent simpletranslate$originalTitle;

    @Inject(method = "draw(Lnet/minecraft/client/gui/toasts/GuiToast;J)Lnet/minecraft/client/gui/toasts/IToast$Visibility;", at = @At("HEAD"))
    private void simpletranslate$beginToast(GuiToast guiToast, long time, CallbackInfoReturnable<IToast.Visibility> callback) {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        DisplayInfo display = advancement == null ? null : advancement.getDisplay();
        if (engine == null || !engine.isConfigured() || display == null) return;
        ITextComponent title = display.getTitle();
        ITextComponent translatedTitle = engine.translateCachedOrEnqueue(
                title, "advancement.toast.title.component.direct");
        if (translatedTitle != title) {
            simpletranslate$originalTitle = title;
            ((DisplayInfoAccessor) display).simpletranslate$setTitle(translatedTitle);
        }
    }

    @Inject(method = "draw(Lnet/minecraft/client/gui/toasts/GuiToast;J)Lnet/minecraft/client/gui/toasts/IToast$Visibility;", at = @At("RETURN"))
    private void simpletranslate$endToast(GuiToast guiToast, long time, CallbackInfoReturnable<IToast.Visibility> callback) {
        if (simpletranslate$originalTitle == null) return;
        DisplayInfo display = advancement == null ? null : advancement.getDisplay();
        if (display != null) {
            DisplayInfoAccessor accessor = (DisplayInfoAccessor) display;
            accessor.simpletranslate$setTitle(simpletranslate$originalTitle);
        }
        simpletranslate$originalTitle = null;
    }
}
