package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.feature.hud.ScoreboardTranslationHelper;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.overlay.PlayerTabOverlayGui;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.ITextProperties;
import net.minecraft.util.IReorderingProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/** Keeps the native tab panel layout while translating only its header/footer. */
@Mixin(PlayerTabOverlayGui.class)
public class PlayerTabOverlayMixin {
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;split(Lnet/minecraft/util/text/ITextProperties;I)Ljava/util/List;"),
            require = 2
    )
    private List<IReorderingProcessor> simple_translate$translateHeaderFooter(FontRenderer font, ITextProperties text, int width) {
        if (text instanceof ITextComponent component) {
            return font.split(ScoreboardTranslationHelper.translateListComponent(component), width);
        }
        return font.split(text, width);
    }
}
