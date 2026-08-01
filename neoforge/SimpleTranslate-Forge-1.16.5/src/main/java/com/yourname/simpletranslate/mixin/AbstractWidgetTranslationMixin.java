package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes button and label translations participate in widget measurement/wrapping. */
@Mixin(Widget.class)
public class AbstractWidgetTranslationMixin {
    @Inject(method = "getMessage()Lnet/minecraft/util/text/ITextComponent;", at = @At("RETURN"),
            cancellable = true, require = 1)
    private void simple_translate$translateWidgetMessage(CallbackInfoReturnable<ITextComponent> cir) {
        if (!GuiTranslationHelper.isActive() || (Object) this instanceof TextFieldWidget) {
            return;
        }
        cir.setReturnValue(GuiTranslationHelper.translateWidgetMessage(
                (Widget) (Object) this, cir.getReturnValue()));
    }
}
