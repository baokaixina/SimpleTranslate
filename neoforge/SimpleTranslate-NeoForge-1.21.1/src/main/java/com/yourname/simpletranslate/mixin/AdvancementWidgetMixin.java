package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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

/** Owns an advancement hover in an isolated draw-time Component frame. */
@Mixin(AdvancementWidget.class)
public abstract class AdvancementWidgetMixin {
    @Shadow @Final private AdvancementNode advancementNode;

    @WrapMethod(method = "drawHover(Lnet/minecraft/client/gui/GuiGraphics;IIFII)V", require = 1)
    private void simple_translate$drawAdvancementFrame(
            GuiGraphics graphics, int x, int y, float fade, int width, int height,
            Operation<Void> original) {
        boolean frameStarted = false;
        if (ModConfig.GLOBAL_ENABLED.get() && ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            String id = this.advancementNode != null && this.advancementNode.holder() != null
                    && this.advancementNode.holder().id() != null
                    ? this.advancementNode.holder().id().toString()
                    : Integer.toHexString(System.identityHashCode(this));
            frameStarted = GuiTranslationHelper.beginDetachedFrame(
                    "gui.advancement.widget\n" + id, "Advancement", true);
        }
        boolean captureSuppressed = !frameStarted;
        if (captureSuppressed) {
            GuiTranslationHelper.beginCaptureSuppression();
        }
        try {
            original.call(graphics, x, y, fade, width, height);
        } finally {
            if (frameStarted) {
                GuiTranslationHelper.endDetachedFrame(graphics);
            }
            if (captureSuppressed) {
                GuiTranslationHelper.endCaptureSuppression();
            }
        }
    }
}
