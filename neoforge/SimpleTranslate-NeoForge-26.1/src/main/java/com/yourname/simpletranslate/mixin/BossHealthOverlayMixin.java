package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.DynamicTextTemplate;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.core.ComponentTranslationResult;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.MixinRuntimeProbe;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BossHealthOverlay.class)
public class BossHealthOverlayMixin {
    @Unique
    private final java.util.Map<Component, Component> simple_translate$bossMemos =
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Component, Component> eldest) {
                    return size() > 8;
                }
            };
    @Unique
    private long simple_translate$bossMemoRevision = -1L;
    @Unique
    private long simple_translate$bossRetryAtNanos;

    @WrapOperation(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"),
            require = 1
    )
    private void simple_translate$drawBossName(
            GuiGraphicsExtractor graphics, Font font, Component component, int x, int y, int color,
            Operation<Void> original) {
        MixinRuntimeProbe.matched("BossHealthOverlayMixin#bossName");
        if (!ModConfig.HUD_BOSSBAR_ENABLED.get()) {
            original.call(graphics, font, component, x, y, color);
            return;
        }
        Component rendered = simple_translate$translate(component);
        if (rendered == null) rendered = component;
        original.call(graphics, font, rendered, x, y, color);
    }

    @Unique
    private Component simple_translate$translate(Component component) {
        if (component == null || HoldOriginalState.isHolding(HoldOriginalFeature.BOSSBAR)) {
            return component;
        }
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        long now = System.nanoTime();
        if (runtimeRevision == this.simple_translate$bossMemoRevision
                && now < this.simple_translate$bossRetryAtNanos) {
            Component memoResult = this.simple_translate$bossMemos.get(component);
            if (memoResult != null) {
                return memoResult;
            }
        }
        Component memoResult = SafeTranslate.guard(() -> {
            if (component == null || HoldOriginalState.isHolding(HoldOriginalFeature.BOSSBAR)) {
                return component;
            }
            String text = component.getString();
            if (text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
                return component;
            }
            DynamicTextTemplate template = DynamicTextTemplate.capture(component);
            Component request = template.hasValues() ? template.normalized() : component;
            ComponentTranslationResult result =
                    DirectSurfaceTranslator.translateComponent(
                            request, "bossbar.component.direct", "bossbar-name");
            if (!result.handled || !result.translated || result.component == null) {
                return component;
            }
            if (!template.hasValues()) {
                return result.component;
            }
            Component restored = template.restore(result.component);
            return restored == null ? component : restored;
        }, component, "bossbar.translateComponent");
        if (runtimeRevision != this.simple_translate$bossMemoRevision) {
            this.simple_translate$bossMemos.clear();
            this.simple_translate$bossMemoRevision = runtimeRevision;
        }
        this.simple_translate$bossMemos.put(component, memoResult);
        this.simple_translate$bossRetryAtNanos = memoResult != null && memoResult != component
                ? Long.MAX_VALUE : now + 1_000_000_000L;
        return memoResult;
    }
}
