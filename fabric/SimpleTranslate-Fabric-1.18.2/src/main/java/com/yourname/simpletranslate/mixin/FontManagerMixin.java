package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.core.ActiveFontManager;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the live {@link FontManager} after each reload so glyph fallback can
 * resolve {@code minecraft:default} without reflecting into Minecraft.
 *
 * <p>Minecraft 1.19.4 FontManager has no own {@code apply} method; the reload
 * listener is the anonymous FontManager$1, so reload capture lives in
 * {@link FontManagerReloadMixin}. Only {@code close} is hooked here.</p>
 */
@Mixin(FontManager.class)
public abstract class FontManagerMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void simple_translate$clearActive(CallbackInfo ci) {
        ActiveFontManager.clearIfActive((FontManager) (Object) this);
    }
}
