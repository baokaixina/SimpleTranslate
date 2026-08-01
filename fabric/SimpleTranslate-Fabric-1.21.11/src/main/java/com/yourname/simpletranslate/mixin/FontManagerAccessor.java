package com.yourname.simpletranslate.mixin;

import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(FontManager.class)
public interface FontManagerAccessor {
    @Invoker("getFontSetRaw")
    FontSet simple_translate$getFontSetRaw(Identifier id);
}
