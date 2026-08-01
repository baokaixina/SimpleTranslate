package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.feature.hud.ScoreboardTranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/** Keeps the native tab panel layout while translating only its header/footer. */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"),
            require = 2
    )
    private List<FormattedCharSequence> simple_translate$translateHeaderFooter(Font font, FormattedText text, int width) {
        if (text instanceof Component component) {
            return font.split(ScoreboardTranslationHelper.translateListComponent(component), width);
        }
        return font.split(text, width);
    }
}
