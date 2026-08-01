package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import com.yourname.simpletranslate.core.ActiveFontManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * Bumps the font resource revision after each font reload.
 *
 * <p>Minecraft 1.19.2 performs font reloads inside the anonymous
 * {@code FontManager$1} reload listener (verified against
 * forge-1.19.2-43.5.2_mapped_official_1.19.2.jar: {@code apply(Map,
 * ResourceManager, ProfilerFiller)} exists only on the anonymous listener).
 * The donor shadows its synthetic outer field under the intermediary name
 * {@code field_18216}; neither that name nor the Mojmap {@code this$0} can be
 * shadowed on ForgeGradle 0.7.38 / Mixin 0.8.5 — the annotation processor
 * crashes while printing the unresolved {@code this$0} target. The FontManager
 * instance itself is stable across reloads and is captured once by
 * {@link FontManagerMixin}'s constructor injector, so this hook only bumps
 * the revision used by layout caches.</p>
 */
@Mixin(targets = "net.minecraft.client.gui.font.FontManager$1")
public abstract class FontManagerReloadMixin {
    @Inject(
            method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("RETURN"))
    private void simple_translate$onFontsReloaded(Map<ResourceLocation, List<GlyphProvider>> providers,
                                                  ResourceManager resourceManager,
                                                  ProfilerFiller profiler,
                                                  CallbackInfo ci) {
        ActiveFontManager.notifyReloaded();
    }
}
