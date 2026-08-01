package com.yourname.simpletranslate.mixin;

import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invokes vanilla screen relayout after an atomic GUI translation arrives. */
@Mixin(Screen.class)
public interface ScreenAccessor {
    @Invoker("repositionElements")
    void simple_translate$repositionElements();
}
