package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes button and label translations participate in widget measurement/wrapping. */
@Mixin(AbstractWidget.class)
public class AbstractWidgetTranslationMixin {
    @Inject(method = "getMessage()Lnet/minecraft/network/chat/Component;", at = @At("RETURN"),
            cancellable = true, require = 1)
    private void simple_translate$translateWidgetMessage(CallbackInfoReturnable<Component> cir) {
        if (!GuiTranslationHelper.isActive() || (Object) this instanceof EditBox) {
            return;
        }
        cir.setReturnValue(GuiTranslationHelper.translateWidgetMessage(
                (AbstractWidget) (Object) this, cir.getReturnValue()));
    }
}
