package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.DirectFormattedTranslationPipeline;
import com.yourname.simpletranslate.util.DirectSurfaceTranslator;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin to translate boss bar names
 *
 * 翻译Boss血条上方的名称
 * 使用 @Redirect 拦截 drawString 调用，替换为翻译后的文本
 */
@Mixin(BossHealthOverlay.class)
public class BossHealthOverlayMixin {

    /**
     * Redirect drawString(Font, Component, int, int, int) - 5 parameter version without shadow
     */
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"),
            require = 1
    )
    private void simple_translate$redirectDrawString5(GuiGraphics guiGraphics, Font font, Component component, int x, int y, int color) {
        if (!ModConfig.HUD_BOSSBAR_ENABLED.get()) {
            guiGraphics.drawString(font, component, x, y, color);
            return;
        }

        Component translated = simple_translate$translateComponent(component);
        guiGraphics.drawString(font, translated, x, y, color);
    }

    /**
     * Redirect drawString(Font, Component, int, int, int, boolean) - 6 parameter version with shadow
     */
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"),
            require = 0
    )
    private void simple_translate$redirectDrawString6(GuiGraphics guiGraphics, Font font, Component component, int x, int y, int color, boolean shadow) {
        if (!ModConfig.HUD_BOSSBAR_ENABLED.get()) {
            guiGraphics.drawString(font, component, x, y, color, shadow);
            return;
        }

        Component translated = simple_translate$translateComponent(component);
        guiGraphics.drawString(font, translated, x, y, color, shadow);
    }

    /**
     * Handle drawCenteredString with Component
     */
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"),
            require = 0
    )
    private void simple_translate$redirectDrawCenteredString(GuiGraphics guiGraphics, Font font, Component component, int x, int y, int color) {
        if (!ModConfig.HUD_BOSSBAR_ENABLED.get()) {
            guiGraphics.drawCenteredString(font, component, x, y, color);
            return;
        }

        Component translated = simple_translate$translateComponent(component);
        guiGraphics.drawCenteredString(font, translated, x, y, color);
    }

    /**
     * Handle drawCenteredString with String
     */
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"),
            require = 0
    )
    private void simple_translate$redirectDrawCenteredStringText(GuiGraphics guiGraphics, Font font, String text, int x, int y, int color) {
        if (!ModConfig.HUD_BOSSBAR_ENABLED.get()) {
            guiGraphics.drawCenteredString(font, text, x, y, color);
            return;
        }

        String translated = simple_translate$translateString(text);
        guiGraphics.drawCenteredString(font, translated, x, y, color);
    }

    /**
     * Handle drawString with String (5 params)
     */
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"),
            require = 0
    )
    private void simple_translate$redirectDrawStringText5(GuiGraphics guiGraphics, Font font, String text, int x, int y, int color) {
        if (!ModConfig.HUD_BOSSBAR_ENABLED.get()) {
            guiGraphics.drawString(font, text, x, y, color);
            return;
        }

        String translated = simple_translate$translateString(text);
        guiGraphics.drawString(font, translated, x, y, color);
    }

    /**
     * Handle drawString with String (6 params with shadow)
     */
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"),
            require = 0
    )
    private void simple_translate$redirectDrawStringText6(GuiGraphics guiGraphics, Font font, String text, int x, int y, int color, boolean shadow) {
        if (!ModConfig.HUD_BOSSBAR_ENABLED.get()) {
            guiGraphics.drawString(font, text, x, y, color, shadow);
            return;
        }

        String translated = simple_translate$translateString(text);
        guiGraphics.drawString(font, translated, x, y, color, shadow);
    }

    /**
     * Translate a Component, returning cached translation or triggering async translation
     */
    @Unique
    private Component simple_translate$translateComponent(Component component) {
        if (component == null) {
            return null;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.BOSSBAR)) {
            return component;
        }

        String text = component.getString();
        if (text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
            return component;
        }

        DirectFormattedTranslationPipeline.ComponentResult direct =
                DirectSurfaceTranslator.translateComponent(component, "bossbar.component.direct", "bossbar-name");
        if (direct.handled) {
            return direct.component;
        }
        return component;
    }

    /**
     * Translate a String
     */
    @Unique
    private String simple_translate$translateString(String text) {
        if (text == null || text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
            return text;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.BOSSBAR)) {
            return text;
        }

        DirectFormattedTranslationPipeline.ComponentResult direct =
                DirectSurfaceTranslator.translateComponent(Component.literal(text),
                        "bossbar.component.direct", "bossbar-name");
        return direct.handled && direct.component != null ? direct.component.getString() : text;
    }
}

