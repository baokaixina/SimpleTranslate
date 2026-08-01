package com.yourname.simpletranslate.mixin;

import net.minecraft.client.gui.GuiIngame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Field access for the 1.12.2 HUD strings. The fields are declared on
 * GuiIngame (SRG at runtime), while the render entry points that Forge
 * actually calls live on GuiIngameForge, so the injecting mixin targets the
 * subclass and reads/writes the superclass state through this accessor to
 * keep every SRG mapping on the declaring class.
 */
@Mixin(GuiIngame.class)
public interface GuiIngameAccessor {
    @Accessor("overlayMessage") String simpletranslate$getOverlayMessage();

    @Accessor("overlayMessage") void simpletranslate$setOverlayMessage(String value);

    @Accessor("displayedTitle") String simpletranslate$getDisplayedTitle();

    @Accessor("displayedTitle") void simpletranslate$setDisplayedTitle(String value);

    @Accessor("displayedSubTitle") String simpletranslate$getDisplayedSubTitle();

    @Accessor("displayedSubTitle") void simpletranslate$setDisplayedSubTitle(String value);
}
