package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.ComponentTranslationMemo;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.WeakHashMap;

/** Covers custom names on minecarts, boats and other non-living entities. */
@Mixin(Render.class)
public abstract class RenderEntityNameMixin {
    @Unique private static final Map<Entity, ComponentTranslationMemo> simpletranslate$memo =
            new WeakHashMap<Entity, ComponentTranslationMemo>();

    @Redirect(
            method = "renderName(Lnet/minecraft/entity/Entity;DDD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getDisplayName()Lnet/minecraft/util/text/ITextComponent;"),
            require = 1
    )
    private ITextComponent simpletranslate$translateEntityName(Entity entity) {
        ITextComponent original = entity.getDisplayName();
        if (entity instanceof EntityPlayer || entity instanceof EntityLivingBase) return original;
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        Minecraft minecraft = Minecraft.getMinecraft();
        String surface = "entity.name.component.direct";
        int radius = ModConfig.CONTENT_ENTITY_NAME_RADIUS.get();
        if (minecraft.player == null || entity.getDistanceSq(minecraft.player) > (double) radius * radius
                || engine == null || !engine.isConfigured() || !engine.isSurfaceEnabled(surface)
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
