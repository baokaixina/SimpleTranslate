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

import java.util.Locale;
import java.util.Optional;

@Mixin(Display.TextDisplay.class)
public class TextDisplayMixin {
    private static final String WYNN_NPC_LABEL_SURFACE = "text_display.wynn.npc_label.v2";
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
                        original, simple_translate$translationSurface(original, plainText), "text-display");
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

    @Unique
    private static String simple_translate$translationSurface(Component component, String plainText) {
        return (simple_translate$isShortPlainLabel(plainText)
                || simple_translate$isDecoratedMerchantLabel(component, plainText))
                ? WYNN_NPC_LABEL_SURFACE
                : "text_display.component.direct";
    }

    @Unique
    private static boolean simple_translate$isShortPlainLabel(String plainText) {
        return plainText != null && plainText.length() <= 48
                && plainText.indexOf('\n') < 0 && plainText.indexOf('\r') < 0;
    }

    /**
     * Wynn service labels contain a local merchant icon and banner positioning
     * glyphs around a short natural-language title. Classify them by Component
     * font structure instead of the complete plain string, which also contains
     * newlines and many protected glyphs and therefore is never "short".
     */
    @Unique
    private static boolean simple_translate$isDecoratedMerchantLabel(
            Component component, String plainText) {
        if (component == null || plainText == null || !simple_translate$hasShortEnglishLine(plainText)) {
            return false;
        }
        boolean[] fonts = new boolean[2];
        try {
            component.visit((style, value) -> {
                String fontId = simple_translate$fontId(style);
                if ("minecraft:merchant".equals(fontId)) {
                    fonts[0] = true;
                } else if (fontId.startsWith("minecraft:banner/pill")) {
                    fonts[1] = true;
                }
                return Optional.empty();
            }, Style.EMPTY);
        } catch (Throwable ignored) {
            return false;
        }
        return fonts[0] && fonts[1];
    }

    @Unique
    private static boolean simple_translate$hasShortEnglishLine(String plainText) {
        for (String line : plainText.replace('\r', '\n').split("\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.codePointCount(0, trimmed.length()) <= 48
                    && TooltipTranslationHelper.containsEnglish(trimmed)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static String simple_translate$fontId(Style style) {
        if (style == null || style.getFont() == null) {
            return "";
        }
        // 1.21.8 Style fonts are plain ResourceLocations.
        return style.getFont().toString().toLowerCase(Locale.ROOT);
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
