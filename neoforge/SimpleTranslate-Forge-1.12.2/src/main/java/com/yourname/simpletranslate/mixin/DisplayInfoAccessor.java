package com.yourname.simpletranslate.mixin;

import net.minecraft.advancements.DisplayInfo;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DisplayInfo.class)
public interface DisplayInfoAccessor {
    @Mutable @Accessor("title") void simpletranslate$setTitle(ITextComponent title);
    @Mutable @Accessor("description") void simpletranslate$setDescription(ITextComponent description);
}
