package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.core.ComponentTranslationMemo;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.BossInfoClient;
import net.minecraft.client.gui.GuiBossOverlay;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;

/** Exact 1.12.2 target: GuiBossOverlay#renderBossHealth()V. */
@Mixin(GuiBossOverlay.class)
public abstract class GuiBossOverlayMixin {
    @Shadow @Final private Map<UUID, BossInfoClient> mapBossInfos;
    @Unique private final ThreadLocal<Map<BossInfoClient, ITextComponent>> simpletranslate$originalNames = new ThreadLocal<Map<BossInfoClient, ITextComponent>>();
    @Unique private static final Map<BossInfoClient, ComponentTranslationMemo> simpletranslate$memo =
            new WeakHashMap<BossInfoClient, ComponentTranslationMemo>();

    @Inject(method = "renderBossHealth()V", at = @At("HEAD"))
    private void simpletranslate$beginBossRender(CallbackInfo callback) {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        String surface = "bossbar.component.direct";
        if (engine == null || !engine.isConfigured() || !engine.isSurfaceEnabled(surface)
                || HoldOriginalState.isHolding(HoldOriginalFeature.BOSSBAR) || mapBossInfos.isEmpty()) return;
        Map<BossInfoClient, ITextComponent> originals = new IdentityHashMap<BossInfoClient, ITextComponent>();
        for (BossInfoClient boss : mapBossInfos.values()) {
            ITextComponent original = boss.getName();
            String source = ITextComponent.Serializer.componentToJson(original);
            long revision = SimpleTranslateForge1122.getRuntimeRevision();
            long now = System.currentTimeMillis();
            ITextComponent translated = null;
            boolean probe = true;
            synchronized (simpletranslate$memo) {
                ComponentTranslationMemo memo = simpletranslate$memo.get(boss);
                if (memo != null && memo.revision == revision && memo.source.equals(source)) {
                    translated = memo.translated;
                    probe = translated == null && now >= memo.nextProbeAt;
                }
            }
            if (probe) {
                translated = engine.getCachedComponent(original, surface);
                if (translated == null) engine.translateCachedOrEnqueue(original, surface);
                synchronized (simpletranslate$memo) {
                    simpletranslate$memo.put(boss, new ComponentTranslationMemo(revision, source,
                            translated, translated == null ? now + 1000L : Long.MAX_VALUE));
                }
            }
            if (translated == null) translated = original;
            if (translated != original) {
                originals.put(boss, original);
                boss.setName(translated);
            }
        }
        if (!originals.isEmpty()) simpletranslate$originalNames.set(originals);
    }

    @Inject(method = "renderBossHealth()V", at = @At("RETURN"))
    private void simpletranslate$endBossRender(CallbackInfo callback) {
        Map<BossInfoClient, ITextComponent> originals = simpletranslate$originalNames.get();
        simpletranslate$originalNames.remove();
        if (originals != null) for (Map.Entry<BossInfoClient, ITextComponent> entry : originals.entrySet()) {
            entry.getKey().setName(entry.getValue());
        }
    }
}
