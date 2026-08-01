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
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.overlay.BossOverlayGui;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BossOverlayGui.class)
public class BossHealthOverlayMixin {
    @Unique
    private final java.util.Map<ITextComponent, ITextComponent> simple_translate$bossMemos =
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<ITextComponent, ITextComponent> eldest) {
                    return size() > 8;
                }
            };
    @Unique
    private long simple_translate$bossMemoRevision = -1L;
    @Unique
    private long simple_translate$bossRetryAtNanos;

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;drawShadow(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/util/text/ITextComponent;FFI)I"),
            require = 1
    )
    private int simple_translate$drawBossName(
            FontRenderer font, MatrixStack poseStack, ITextComponent component, float x, float y, int color,
            Operation<Integer> original) {
        MixinRuntimeProbe.matched("BossHealthOverlayMixin#bossName");
        if (!ModConfig.HUD_BOSSBAR_ENABLED.get()) {
            return original.call(font, poseStack, component, x, y, color);
        }
        ITextComponent rendered = simple_translate$translate(component);
        if (rendered == null) rendered = component;
        return original.call(font, poseStack, rendered, x, y, color);
    }

    @Unique
    private ITextComponent simple_translate$translate(ITextComponent component) {
        if (component == null || HoldOriginalState.isHolding(HoldOriginalFeature.BOSSBAR)) {
            return component;
        }
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        long now = System.nanoTime();
        if (runtimeRevision == this.simple_translate$bossMemoRevision
                && now < this.simple_translate$bossRetryAtNanos) {
            ITextComponent memoResult = this.simple_translate$bossMemos.get(component);
            if (memoResult != null) {
                return memoResult;
            }
        }
        ITextComponent memoResult = SafeTranslate.guard(() -> {
            if (component == null || HoldOriginalState.isHolding(HoldOriginalFeature.BOSSBAR)) {
                return component;
            }
            String text = component.getString();
            if (text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
                return component;
            }
            DynamicTextTemplate template = DynamicTextTemplate.capture(component);
            ITextComponent request = template.hasValues() ? template.normalized() : component;
            ComponentTranslationResult result =
                    DirectSurfaceTranslator.translateComponent(
                            request, "bossbar.component.direct", "bossbar-name");
            if (!result.handled || !result.translated || result.component == null) {
                return component;
            }
            if (!template.hasValues()) {
                return result.component;
            }
            ITextComponent restored = template.restore(result.component);
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
