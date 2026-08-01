package com.yourname.simpletranslate.mixin;

import net.minecraft.client.gui.GuiScreenBook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact 1.12.2 GuiScreenBook fields: currPage:I and cachedPage:I. */
@Mixin(GuiScreenBook.class)
public interface GuiScreenBookAccessor {
    @Accessor("currPage") int simpletranslate$getCurrentPage();
    @Accessor("currPage") void simpletranslate$setCurrentPage(int page);
    @Accessor("cachedPage") void simpletranslate$setCachedPage(int page);
}
