package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.core.ActiveFontManager;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the live {@link FontManager} so glyph fallback can resolve
 * {@code minecraft:default} without reflecting into Minecraft.
 *
 * <p>Minecraft 1.19.2 FontManager has no own {@code apply} method; the reload
 * listener is the anonymous FontManager$1, so the reload revision bump lives
 * in {@link FontManagerReloadMixin}. The manager instance is created once by
 * Minecraft and reused across reloads, so it is captured here at construction
 * and released on {@code close}.</p>
 */
@Mixin(FontManager.class)
public abstract class FontManagerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void simple_translate$captureActive(CallbackInfo ci) {
        ActiveFontManager.setActive((FontManager) (Object) this);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void simple_translate$clearActive(CallbackInfo ci) {
        ActiveFontManager.clearIfActive((FontManager) (Object) this);
    }
}
