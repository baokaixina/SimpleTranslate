package com.yourname.simpletranslate.mixin;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiLabel;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Exact 1.12.2 field accessors for GuiScreen buttonList/labelList. */
@Mixin(GuiScreen.class)
public interface GuiScreenAccessor {
    @Accessor("buttonList") List<GuiButton> simpletranslate$getButtons();
    @Accessor("labelList") List<GuiLabel> simpletranslate$getLabels();
}
