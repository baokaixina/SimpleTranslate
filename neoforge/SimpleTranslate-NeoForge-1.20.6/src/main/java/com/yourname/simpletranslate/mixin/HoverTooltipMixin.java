package com.yourname.simpletranslate.mixin;



import com.yourname.simpletranslate.config.ModConfig;

import com.yourname.simpletranslate.keybind.HoldOriginalFeature;

import com.yourname.simpletranslate.keybind.HoldOriginalState;

import com.yourname.simpletranslate.util.TooltipTranslationController;

import com.yourname.simpletranslate.util.TooltipTranslationHelper;

import net.minecraft.client.gui.Font;

import net.minecraft.client.gui.GuiGraphics;

import net.minecraft.client.gui.screens.inventory.BookViewScreen;

import net.minecraft.client.gui.screens.Screen;

import net.minecraft.network.chat.Component;

import net.minecraft.network.chat.HoverEvent;

import net.minecraft.network.chat.Style;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

import org.spongepowered.asm.mixin.Unique;

import org.spongepowered.asm.mixin.Mixin;

import org.spongepowered.asm.mixin.injection.At;

import org.spongepowered.asm.mixin.injection.Inject;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;



import java.util.List;

import java.util.Optional;



/**

 * Intercepts hover tooltips in chat/book screens and mod overlay tooltips that

 * render directly through {@link GuiGraphics} instead of {@link HoverEvent}.

 */

@Mixin(GuiGraphics.class)

public class HoverTooltipMixin {



    @Inject(method = "renderComponentHoverEffect", at = @At("HEAD"), cancellable = true)

    private void onRenderComponentHoverEffect(Font font, Style style, int mouseX, int mouseY, CallbackInfo ci) {

        if (style == null || HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER)) {

            return;

        }



        HoverEvent hoverEvent = style.getHoverEvent();

        if (hoverEvent == null || hoverEvent.getAction() != HoverEvent.Action.SHOW_TEXT) {

            return;

        }



        Component hoverText = hoverEvent.getValue(HoverEvent.Action.SHOW_TEXT);

        if (!TooltipTranslationController.shouldTranslateChatHover(hoverText)

                && !shouldTranslateBookHover()) {

            return;

        }



        List<Component> translatedLines = TooltipTranslationHelper.translateHoverComponentLines(hoverText);

        if (translatedLines.size() == 1 && translatedLines.get(0) == hoverText) {

            return;

        }



        GuiGraphics guiGraphics = (GuiGraphics) (Object) this;

        TooltipTranslationController.beginRenderingTranslated();

        try {

            guiGraphics.renderTooltip(font, translatedLines, Optional.empty(), mouseX, mouseY);

        } finally {

            TooltipTranslationController.endRenderingTranslated();

        }

        ci.cancel();

    }



    @Inject(

            method = "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V",

            at = @At("HEAD"),

            cancellable = true

    )

    private void simple_translate$onRenderTooltipList(Font font, List<Component> components,

                                                      Optional<TooltipComponent> image, int mouseX, int mouseY,

                                                      CallbackInfo ci) {

        if (!TooltipTranslationController.shouldTranslateRenderedTooltip(components)) {

            return;

        }



        TooltipTranslationController.RenderContext context = TooltipTranslationController.resolveRenderContext();

        List<Component> translated = TooltipTranslationController.translateForRender(components, context);

        if (translated == components) {

            return;

        }



        GuiGraphics guiGraphics = (GuiGraphics) (Object) this;

        TooltipTranslationController.beginRenderingTranslated();

        try {

            guiGraphics.renderTooltip(font, translated, image, mouseX, mouseY);

        } finally {

            TooltipTranslationController.endRenderingTranslated();

        }

        ci.cancel();

    }



    @Inject(method = "renderComponentTooltip", at = @At("HEAD"), cancellable = true)

    private void simple_translate$onRenderComponentTooltip(Font font, List<Component> components, int mouseX, int mouseY,

                                                           CallbackInfo ci) {

        if (!TooltipTranslationController.shouldTranslateRenderedTooltip(components)) {

            return;

        }



        TooltipTranslationController.RenderContext context = TooltipTranslationController.resolveRenderContext();

        List<Component> translated = TooltipTranslationController.translateForRender(components, context);

        if (translated == components) {

            return;

        }



        GuiGraphics guiGraphics = (GuiGraphics) (Object) this;

        TooltipTranslationController.beginRenderingTranslated();

        try {

            guiGraphics.renderComponentTooltip(font, translated, mouseX, mouseY);

        } finally {

            TooltipTranslationController.endRenderingTranslated();

        }

        ci.cancel();

    }



    @Inject(

            method = "renderTooltip(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;II)V",

            at = @At("HEAD"),

            cancellable = true

    )

    private void simple_translate$onRenderTooltipComponent(Font font, Component component, int mouseX, int mouseY,

                                                           CallbackInfo ci) {

        if (component == null || TooltipTranslationController.isRenderingTranslated()) {

            return;

        }

        if (TooltipTranslationHelper.isMarkedTranslatedTooltip(component)) {

            return;

        }

        if (!TooltipTranslationController.shouldTranslateRenderedTooltip(List.of(component))) {

            return;

        }



        TooltipTranslationController.RenderContext context = TooltipTranslationController.resolveRenderContext();

        List<Component> translated = TooltipTranslationController.translateForRender(List.of(component), context);

        if (translated.isEmpty() || (translated.size() == 1 && translated.get(0) == component)) {

            return;

        }



        GuiGraphics guiGraphics = (GuiGraphics) (Object) this;

        TooltipTranslationController.beginRenderingTranslated();

        try {

            if (translated.size() == 1) {

                guiGraphics.renderTooltip(font, translated.get(0), mouseX, mouseY);

            } else {

                guiGraphics.renderTooltip(font, translated, Optional.empty(), mouseX, mouseY);

            }

        } finally {

            TooltipTranslationController.endRenderingTranslated();

        }

        ci.cancel();

    }



    @Unique

    private static boolean shouldTranslateBookHover() {

        Screen screen = net.minecraft.client.Minecraft.getInstance().screen;

        return screen instanceof BookViewScreen

                && ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get()

                && !HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER);

    }

}

