package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.core.ComponentTranslationResult;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.TextContextMemory;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.Display;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(Display.TextDisplay.class)
public class TextDisplayMixin {
    private static final long CACHE_RECHECK_MILLIS = 1_000L;

    @Shadow
    @Nullable
    private Display.TextDisplay.TextRenderState textRenderState;
    @Unique private Component simple_translate$lastSource;
    @Unique private Component simple_translate$lastTranslation;
    @Unique private long simple_translate$nextCacheCheckAtNanos;
    @Unique private long simple_translate$runtimeRevision = -1L;
    @Unique private long simple_translate$textContextRevision = -1L;

    @Inject(method = "cacheDisplay", at = @At("HEAD"), cancellable = true)
    private void simple_translate$cacheDisplay(Display.TextDisplay.LineSplitter splitter,
            CallbackInfoReturnable<Display.TextDisplay.CachedInfo> cir) {
        if (!ModConfig.GLOBAL_ENABLED.get()
                || !ModConfig.CONTENT_TEXT_DISPLAY_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.TEXT_DISPLAY)
                || !simple_translate$isInRange()
                || this.textRenderState == null) {
            return;
        }

        Component original = this.textRenderState.text();
        if (original == null) {
            return;
        }

        String plainText = original.getString();
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        long textContextRevision = TextContextMemory.revision();
        if (simple_translate$runtimeRevision != runtimeRevision
                || simple_translate$textContextRevision != textContextRevision) {
            simple_translate$runtimeRevision = runtimeRevision;
            simple_translate$textContextRevision = textContextRevision;
            simple_translate$lastSource = null;
            simple_translate$lastTranslation = null;
            simple_translate$nextCacheCheckAtNanos = 0L;
        }
        long nowNanos = System.nanoTime();
        if (original.equals(simple_translate$lastSource)) {
            if (simple_translate$lastTranslation != null) {
                cir.setReturnValue(splitter.split(simple_translate$lastTranslation,
                        this.textRenderState.lineWidth()));
                return;
            }
            if (nowNanos < simple_translate$nextCacheCheckAtNanos) {
                return;
            }
        } else {
            simple_translate$lastSource = original;
            simple_translate$lastTranslation = null;
            simple_translate$nextCacheCheckAtNanos = 0L;
        }
        if (!TooltipTranslationHelper.containsEnglish(original.getString())) {
            simple_translate$nextCacheCheckAtNanos = Long.MAX_VALUE;
            return;
        }

        ComponentTranslationResult direct =
                DirectSurfaceTranslator.translateComponent(
                        original, "text_display.component.direct", "text-display");
        if (!direct.handled || !direct.translated) {
            simple_translate$nextCacheCheckAtNanos = nowNanos + CACHE_RECHECK_MILLIS * 1_000_000L;
            return;
        }

        Component renderComponent = direct.component;
        if (renderComponent == null || renderComponent == original) {
            simple_translate$nextCacheCheckAtNanos = nowNanos + CACHE_RECHECK_MILLIS * 1_000_000L;
            return;
        }

        simple_translate$lastTranslation = renderComponent;
        simple_translate$nextCacheCheckAtNanos = Long.MAX_VALUE;

        Display.TextDisplay.CachedInfo cachedInfo =
                splitter.split(renderComponent, this.textRenderState.lineWidth());
        cir.setReturnValue(cachedInfo);
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
