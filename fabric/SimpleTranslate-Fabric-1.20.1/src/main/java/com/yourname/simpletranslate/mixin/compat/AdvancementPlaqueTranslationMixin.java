package com.yourname.simpletranslate.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Bridges Advancement Plaques' direct Font renderer into the advancement frame.
 *
 * <p>Exact runtime evidence: AdvancementPlaques 1.20.1-fabric-1.6.7 has private
 * {@code drawPlaque(class_332,long):class_368$class_369}; its synthetic
 * {@code lambda$drawPlaque$0} invokes the exact 1.20.1
 * {@code Font#method_30882(Component,...)} descriptor three times (frame label
 * and the two mutually-exclusive title layouts). Intermediary selectors are
 * intentional because the optional Fabric jar itself is intermediary-named.</p>
 */
@Pseudo
@Mixin(targets = "com.anthonyhilyard.advancementplaques.ui.render.AdvancementPlaque", remap = false)
public abstract class AdvancementPlaqueTranslationMixin {
    @WrapMethod(
            method = "drawPlaque(Lnet/minecraft/class_332;J)Lnet/minecraft/class_368$class_369;",
            require = 1)
    private Toast.Visibility simple_translate$drawPlaqueFrame(
            GuiGraphics graphics, long displayTime, Operation<Toast.Visibility> original) {
        boolean frameStarted = false;
        if (ModConfig.GLOBAL_ENABLED.get() && ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            frameStarted = GuiTranslationHelper.beginDetachedFrame(
                    "gui.advancement.plaque\n"
                            + Integer.toHexString(System.identityHashCode(this)),
                    "Advancement plaque", true);
        }
        boolean captureSuppressed = !frameStarted;
        if (captureSuppressed) {
            GuiTranslationHelper.beginCaptureSuppression();
        }
        try {
            return original.call(graphics, displayTime);
        } finally {
            if (frameStarted) {
                GuiTranslationHelper.endDetachedFrame(graphics);
            }
            if (captureSuppressed) {
                GuiTranslationHelper.endCaptureSuppression();
            }
        }
    }

    @ModifyArg(
            method = "lambda$drawPlaque$0(JFFLnet/minecraft/class_185;Lnet/minecraft/class_332;Lnet/minecraft/class_310;Lnet/minecraft/class_4587;F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/class_327;method_30882(Lnet/minecraft/class_2561;FFIZLorg/joml/Matrix4f;Lnet/minecraft/class_4597;Lnet/minecraft/class_327$class_6415;II)I",
                    remap = false),
            index = 0,
            require = 3,
            remap = false)
    private Component simple_translate$translatePlaqueComponent(Component component) {
        return GuiTranslationHelper.translateVisible(component);
    }
}
