package com.yourname.simpletranslate.mixin;

import net.minecraft.client.gui.fonts.FontResourceManager;
import net.minecraft.client.gui.fonts.Font;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Accesses the 1.20.1 FontResourceManager Font map. */
@Mixin(FontResourceManager.class)
public interface FontManagerAccessor {
    @Accessor("fontSets")
    Map<ResourceLocation, Font> simple_translate$getFontSets();
}
