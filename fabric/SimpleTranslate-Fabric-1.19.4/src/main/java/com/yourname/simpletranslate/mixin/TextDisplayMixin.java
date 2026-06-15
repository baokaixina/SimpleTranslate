package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.DirectFormattedTranslationPipeline;
import com.yourname.simpletranslate.util.DirectSurfaceTranslator;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Display.TextDisplay.class)
public abstract class TextDisplayMixin {

    @Shadow
    public abstract Component getText();

    @Shadow
    public abstract int getLineWidth();

    @Inject(method = "cacheDisplay", at = @At("HEAD"), cancellable = true)
    private void simple_translate$cacheDisplay(Display.TextDisplay.LineSplitter splitter,
            CallbackInfoReturnable<Display.TextDisplay.CachedInfo> cir) {
        if (!ModConfig.CONTENT_TEXT_DISPLAY_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.TEXT_DISPLAY)
                || !simple_translate$isInRange()) {
            return;
        }

        Component original = getText();
        if (original == null || !TooltipTranslationHelper.containsEnglish(original.getString())) {
            return;
        }

        DirectFormattedTranslationPipeline.ComponentResult direct =
                DirectSurfaceTranslator.translateComponent(
                        original, "text_display.component.direct", "text-display");
        if (!direct.handled || !direct.translated) {
            return;
        }

        Component renderComponent = direct.component;
        if (renderComponent == null || renderComponent == original) {
            return;
        }

        cir.setReturnValue(splitter.split(renderComponent, getLineWidth()));
    }

    private boolean simple_translate$isInRange() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        return mc.player.distanceTo((Display.TextDisplay) (Object) this)
                <= ModConfig.CONTENT_TEXT_DISPLAY_RADIUS.get();
    }
}
