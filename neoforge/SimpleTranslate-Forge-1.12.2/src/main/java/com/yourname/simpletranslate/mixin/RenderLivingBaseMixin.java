package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.core.ComponentTranslationMemo;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.client.Minecraft;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Unique;
import java.util.Map;
import java.util.WeakHashMap;

/** Entity nameplate redirect, verified once in renderName(EntityLivingBase,DDD)V. */
@Mixin(RenderLivingBase.class)
public abstract class RenderLivingBaseMixin {
    @Unique private static final Map<EntityLivingBase, ComponentTranslationMemo> simpletranslate$memo =
            new WeakHashMap<EntityLivingBase, ComponentTranslationMemo>();
    @Redirect(
            method = "renderName(Lnet/minecraft/entity/EntityLivingBase;DDD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;getDisplayName()Lnet/minecraft/util/text/ITextComponent;")
    )
    private ITextComponent simpletranslate$translateEntityName(EntityLivingBase entity) {
        ITextComponent original = entity.getDisplayName();
        // Player/account identities are never translation input.
        if (entity instanceof EntityPlayer) return original;
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        Minecraft minecraft = Minecraft.getMinecraft();
        int radius = ModConfig.CONTENT_ENTITY_NAME_RADIUS.get();
        if (minecraft.player == null || entity.getDistanceSq(minecraft.player) > (double) radius * radius) return original;
        String surface = "entity.name.component.direct";
        if (engine == null || !engine.isConfigured() || !engine.isSurfaceEnabled(surface)
                || HoldOriginalState.isHolding(HoldOriginalFeature.ENTITY_NAME)) return original;
        String source = ITextComponent.Serializer.componentToJson(original);
        long revision = SimpleTranslateForge1122.getRuntimeRevision();
        long now = System.currentTimeMillis();
        synchronized (simpletranslate$memo) {
            ComponentTranslationMemo memo = simpletranslate$memo.get(entity);
            if (memo != null && memo.revision == revision && memo.source.equals(source)) {
                if (memo.translated != null) return memo.translated;
                if (now < memo.nextProbeAt) return original;
            }
        }
        ITextComponent translated = engine.getCachedComponent(original, surface);
        if (translated == null) engine.translateCachedOrEnqueue(original, surface);
        synchronized (simpletranslate$memo) {
            simpletranslate$memo.put(entity, new ComponentTranslationMemo(revision, source,
                    translated, translated == null ? now + 1000L : Long.MAX_VALUE));
        }
        return translated == null ? original : translated;
    }
}
