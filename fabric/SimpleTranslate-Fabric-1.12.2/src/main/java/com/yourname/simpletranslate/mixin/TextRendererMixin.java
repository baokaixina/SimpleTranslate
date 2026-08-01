package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.legacy.LegacyFabricRuntime;
import net.minecraft.client.font.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Exact Legacy-Yarn 1.12.2 hook. The inspected mapping names TextRenderer's
 * draw overload as draw(String,float,float,int,boolean), descriptor
 * (Ljava/lang/String;FFIZ)I. The hook changes only the String argument and
 * leaves glyph layout, color, shadow, and return value to the target renderer.
 */
@Mixin(TextRenderer.class)
public abstract class TextRendererMixin {
    @ModifyVariable(method = "draw(Ljava/lang/String;FFIZ)I", at = @At("HEAD"), argsOnly = true)
    private String simple_translate$translateDrawText(String text) {
        return LegacyFabricRuntime.translate(text);
    }
}
