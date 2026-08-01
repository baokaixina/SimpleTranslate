package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import com.yourname.simpletranslate.core.ActiveFontManager;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * Recaptures the live {@link FontManager} after each font reload on 1.19.4.
 *
 * <p>Minecraft 1.19.4 performs font reloads inside the anonymous
 * {@code FontManager$1} reload listener; its synthetic outer reference keeps
 * the intermediary name {@code field_18216} (stable for this version).</p>
 */
@Mixin(targets = "net.minecraft.client.gui.font.FontManager$1")
public abstract class FontManagerReloadMixin {
    @Shadow
    @Final
    private FontManager field_18216;

    @Inject(
            method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("RETURN"))
    private void simple_translate$captureActive(Map<ResourceLocation, List<GlyphProvider>> providers,
                                                ResourceManager resourceManager,
                                                ProfilerFiller profiler,
                                                CallbackInfo ci) {
        ActiveFontManager.setActive(this.field_18216);
    }
}
