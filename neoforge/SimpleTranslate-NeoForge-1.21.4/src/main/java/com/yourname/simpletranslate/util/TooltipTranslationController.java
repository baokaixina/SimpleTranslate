package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Single gate for every item-tooltip render entry (container screens and the
 * three GuiGraphics hooks). A shared render guard prevents the translated
 * re-render from being intercepted again by another tooltip mixin, so the same
 * tooltip can never be translated twice in one frame.
 */
public final class TooltipTranslationController {
    /** Render happens on the client thread only; a simple depth counter suffices. */
    private static int renderingTranslatedDepth = 0;

    private TooltipTranslationController() {
    }

    public enum RenderContext {
        ITEM,
        CHAT_OVERLAY,
        BOOK
    }

    public static boolean isRenderingTranslated() {
        return renderingTranslatedDepth > 0;
    }

    public static void beginRenderingTranslated() {
        renderingTranslatedDepth++;
    }

    public static void endRenderingTranslated() {
        renderingTranslatedDepth = Math.max(0, renderingTranslatedDepth - 1);
    }

    public static RenderContext resolveRenderContext() {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof ChatScreen) {
            return RenderContext.CHAT_OVERLAY;
        }
        if (screen instanceof BookViewScreen) {
            return RenderContext.BOOK;
        }
        return RenderContext.ITEM;
    }

    /** Common gate for materialized tooltip lists across all render entries. */
    public static boolean shouldTranslateRenderedTooltip(List<Component> components) {
        if (isRenderingTranslated()) {
            return false;
        }
        if (components == null || components.isEmpty()) {
            return false;
        }
        if (TooltipTranslationHelper.isMarkedTranslatedTooltip(components)) {
            return false;
        }
        if (!TooltipTranslationHelper.anyContainsEnglish(components)) {
            return false;
        }
        return isEnabledForContext(resolveRenderContext());
    }

    /** @deprecated use {@link #shouldTranslateRenderedTooltip(List)} */
    public static boolean shouldTranslateItemTooltip(List<Component> components) {
        if (isRenderingTranslated() || components == null || components.isEmpty()) {
            return false;
        }
        if (TooltipTranslationHelper.isMarkedTranslatedTooltip(components)) {
            return false;
        }
        if (!TooltipTranslationHelper.anyContainsEnglish(components)) {
            return false;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_ITEM)) {
            return false;
        }
        return ModConfig.TOOLTIP_ITEM_ENABLED.get();
    }

    public static boolean shouldTranslateChatHover(Component hoverText) {
        if (hoverText == null || isRenderingTranslated()) {
            return false;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER)) {
            return false;
        }
        if (!ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get()) {
            return false;
        }
        if (!(Minecraft.getInstance().screen instanceof ChatScreen)) {
            return false;
        }
        if (TooltipTranslationHelper.isMarkedTranslatedTooltip(hoverText)) {
            return false;
        }
        return TooltipTranslationHelper.containsEnglish(hoverText.getString());
    }

    public static List<Component> translateForRender(List<Component> components) {
        return translateForRender(components, RenderContext.ITEM);
    }

    public static List<Component> translateForRender(List<Component> components, RenderContext context) {
        return TooltipTranslationHelper.translateRenderedTooltip(components, context);
    }

    private static boolean isEnabledForContext(RenderContext context) {
        return switch (context) {
            case CHAT_OVERLAY -> !HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER)
                    && ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get();
            case BOOK -> !HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER)
                    && ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get();
            case ITEM -> !HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_ITEM)
                    && ModConfig.TOOLTIP_ITEM_ENABLED.get();
        };
    }
}
