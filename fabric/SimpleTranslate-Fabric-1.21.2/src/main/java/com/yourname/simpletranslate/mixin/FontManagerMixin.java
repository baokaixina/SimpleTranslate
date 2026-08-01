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
 * <p>
 * Handler takes only {@link CallbackInfo}: {@code apply}'s first argument is the
 * private {@code FontManager$Preparation} type, which cannot be named (or widened
 * to {@code Object}) in the inject descriptor without {@code InvalidInjectionException}.
 */
@Mixin(FontManager.class)
public abstract class FontManagerMixin {
    @Inject(method = "apply", at = @At("RETURN"))
    private void simple_translate$captureActive(CallbackInfo ci) {
        ActiveFontManager.setActive((FontManager) (Object) this);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void simple_translate$clearActive(CallbackInfo ci) {
        ActiveFontManager.clearIfActive((FontManager) (Object) this);
    }
}
