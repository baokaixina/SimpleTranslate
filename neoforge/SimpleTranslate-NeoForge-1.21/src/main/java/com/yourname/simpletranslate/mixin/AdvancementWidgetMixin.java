package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owns an advancement hover in an isolated draw-time Component frame. */
@Mixin(AdvancementWidget.class)
public abstract class AdvancementWidgetMixin {
    @Shadow @Final private AdvancementNode advancementNode;

    // NeoForge 21.0 ships MixinExtras 0.3.5, which predates @WrapMethod; the
    // donor's single wrap splits into this HEAD/RETURN pair with the frame
    // state carried on unique fields.
    @Unique
    private boolean simple_translate$frameStarted;
    @Unique
    private boolean simple_translate$captureSuppressed;

    @Inject(method = "drawHover(Lnet/minecraft/client/gui/GuiGraphics;IIFII)V",
            at = @At("HEAD"), require = 1)
    private void simple_translate$beginAdvancementFrame(
            GuiGraphics graphics, int x, int y, float fade, int width, int height,
            CallbackInfo ci) {
        simple_translate$frameStarted = false;
        if (ModConfig.GLOBAL_ENABLED.get() && ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            String id = this.advancementNode != null && this.advancementNode.holder() != null
                    && this.advancementNode.holder().id() != null
                    ? this.advancementNode.holder().id().toString()
                    : Integer.toHexString(System.identityHashCode(this));
            simple_translate$frameStarted = GuiTranslationHelper.beginDetachedFrame(
                    "gui.advancement.widget\n" + id, "Advancement", true);
        }
        simple_translate$captureSuppressed = !simple_translate$frameStarted;
        if (simple_translate$captureSuppressed) {
            GuiTranslationHelper.beginCaptureSuppression();
        }
    }

    @Inject(method = "drawHover(Lnet/minecraft/client/gui/GuiGraphics;IIFII)V",
            at = @At("RETURN"), require = 1)
    private void simple_translate$endAdvancementFrame(
            GuiGraphics graphics, int x, int y, float fade, int width, int height,
            CallbackInfo ci) {
        if (simple_translate$frameStarted) {
            simple_translate$frameStarted = false;
            GuiTranslationHelper.endDetachedFrame(graphics);
        }
        if (simple_translate$captureSuppressed) {
            simple_translate$captureSuppressed = false;
            GuiTranslationHelper.endCaptureSuppression();
        }
    }
}
