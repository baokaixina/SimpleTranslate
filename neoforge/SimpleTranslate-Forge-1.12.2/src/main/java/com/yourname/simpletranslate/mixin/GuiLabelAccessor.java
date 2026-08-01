package com.yourname.simpletranslate.mixin;

import net.minecraft.client.gui.GuiLabel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Exact 1.12.2 field accessor for GuiLabel#labels: List<String>. */
@Mixin(GuiLabel.class)
public interface GuiLabelAccessor {
    @Accessor("labels") List<String> simpletranslate$getLines();
}
