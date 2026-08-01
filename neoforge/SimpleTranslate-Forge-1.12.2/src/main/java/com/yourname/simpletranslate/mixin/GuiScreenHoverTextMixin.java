package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.event.HoverEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Exact 1.12.2 target: GuiScreen#handleComponentHover(ITextComponent,II)V.
 * The target invokes HoverEvent#getValue exactly once per action branch.  The
 * redirect keeps SHOW_ITEM and SHOW_ENTITY NBT payloads untouched and routes
 * only SHOW_TEXT through its dedicated Component-JSON cache lane.
 */
@Mixin(GuiScreen.class)
public abstract class GuiScreenHoverTextMixin {
    @Redirect(
            method = "handleComponentHover(Lnet/minecraft/util/text/ITextComponent;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/text/event/HoverEvent;getValue()Lnet/minecraft/util/text/ITextComponent;"
            )
    )
    private ITextComponent simpletranslate$translateShowTextHover(HoverEvent event) {
        ITextComponent original = event.getValue();
        if (event.getAction() != HoverEvent.Action.SHOW_TEXT) return original;
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (engine == null || !engine.isConfigured()) return original;
        return ((Object) this) instanceof GuiScreenBook
                ? engine.translateBookHoverTextCachedOrEnqueue(original)
                : ((Object) this) instanceof GuiChat
                ? engine.translateHoverTextCachedOrEnqueue(original) : original;
    }
}
