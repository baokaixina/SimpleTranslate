package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.ComponentJsonCompat;
import com.yourname.simpletranslate.core.ComponentTranslationResult;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Translates entity name tags while preserving component styling.
 *
 * <p>Minecraft 1.21.1 has no EntityRenderState extraction step; the translated
 * Component is substituted directly on the renderNameTag argument.</p>
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin<T extends Entity> {
    @Unique
    private static final long simple_translate$PENDING_RETRY_MS = 1_000L;
    @Unique
    private static final Map<Entity, EntityNameMemo> simple_translate$ENTITY_NAME_MEMOS =
            Collections.synchronizedMap(new WeakHashMap<>());
    @Unique
    private T simple_translate$currentEntity;

    @ModifyVariable(method = "renderNameTag", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private T simple_translate$captureEntity(T entity) {
        this.simple_translate$currentEntity = entity;
        return entity;
    }

    @ModifyVariable(method = "renderNameTag", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component simple_translate$translateNameTag(Component displayName) {
        if (!ModConfig.GLOBAL_ENABLED.get() || !ModConfig.CONTENT_ENTITY_NAME_ENABLED.get()) {
            return displayName;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.ENTITY_NAME)) {
            return displayName;
        }
        // Player/account names are identity data, not server prose. Never submit them.
        if (displayName == null || !simple_translate$isEntityInRange()) {
            return displayName;
        }

        return simple_translate$translateWithMemo(simple_translate$currentEntity, displayName);
    }

    @Unique
    private boolean simple_translate$isEntityInRange() {
        Entity entity = simple_translate$currentEntity;
        if (entity == null || entity instanceof Player) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        int radius = ModConfig.CONTENT_ENTITY_NAME_RADIUS.get();
        return mc.player.distanceToSqr(entity) <= (double) radius * radius;
    }

    @Unique
    private Component simple_translate$translateWithMemo(Entity entity, Component component) {
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        long now = System.nanoTime();
        EntityNameMemo memo;
        synchronized (simple_translate$ENTITY_NAME_MEMOS) {
            memo = simple_translate$ENTITY_NAME_MEMOS.get(entity);
        }
        if (memo != null && memo.runtimeRevision() == runtimeRevision
                && memo.source() == component
                && (memo.retryAfterNanos() == Long.MAX_VALUE
                || now < memo.retryAfterNanos())) {
            return memo.result();
        }
        String plainText = component.getString();
        if (plainText.isEmpty() || !TooltipTranslationHelper.containsEnglish(plainText)) {
            return component;
        }
        if (memo != null && memo.runtimeRevision() == runtimeRevision
                && plainText.equals(memo.sourcePlainText())
                && now < memo.retryAfterNanos()) {
            return memo.result();
        }
        int structuralHash = component.hashCode();

        String sourceKey;
        try {
            sourceKey = ComponentJsonCompat.toJson(component);
        } catch (RuntimeException ignored) {
            sourceKey = component.getString();
        }
        if (memo != null && memo.runtimeRevision() == runtimeRevision
                && sourceKey.equals(memo.sourceKey()) && now < memo.retryAfterNanos()) {
            return memo.result();
        }

        ComponentTranslationResult direct = DirectSurfaceTranslator.translateComponent(
                component, "entity.name.direct", "entity-name");
        Component result = direct.handled && direct.component != null ? direct.component : component;
        long retryAfter = direct.translated
                ? Long.MAX_VALUE : now + simple_translate$PENDING_RETRY_MS * 1_000_000L;
        EntityNameMemo updated = new EntityNameMemo(component, structuralHash, sourceKey,
                plainText, runtimeRevision, result, retryAfter);
        synchronized (simple_translate$ENTITY_NAME_MEMOS) {
            simple_translate$ENTITY_NAME_MEMOS.put(entity, updated);
        }
        return result;
    }

    @Unique
    private record EntityNameMemo(Component source, int structuralHash, String sourceKey,
                                  String sourcePlainText, long runtimeRevision, Component result,
                                  long retryAfterNanos) {
    }
}
