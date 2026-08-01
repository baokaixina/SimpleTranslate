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
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
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
            // This mixin has only a MixinExtras injector, so Mixin 0.8.5 does
            // not emit a refmap owner entry for its method selector. Use the
            // exact Forge 40.2.21 SRG selector instead.
            method = "m_93704_(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(
                    value = "INVOKE",
                    // Exact Forge 40.2.21 runtime call in BossHealthOverlay#m_93704_.
                    target = "Lnet/minecraft/client/gui/Font;m_92763_(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
                    remap = false
            ),
            remap = false,
            require = 1
    )
    private int simple_translate$drawBossName(
            Font font, PoseStack poseStack, Component component, float x, float y, int color,
            Operation<Integer> original) {
        MixinRuntimeProbe.matched("BossHealthOverlayMixin#bossName");
        if (!ModConfig.HUD_BOSSBAR_ENABLED.get()) {
            return original.call(font, poseStack, component, x, y, color);
        }
        Component rendered = simple_translate$translate(component);
        if (rendered == null) rendered = component;
        return original.call(font, poseStack, rendered, x, y, color);
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
