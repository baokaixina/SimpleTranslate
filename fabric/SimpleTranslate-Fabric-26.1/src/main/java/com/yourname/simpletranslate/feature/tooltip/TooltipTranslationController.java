package com.yourname.simpletranslate.feature.tooltip;



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

 * three GuiGraphicsExtractor hooks). A shared render guard prevents the translated

 * re-render from being intercepted again by another tooltip mixin, so the same

 * tooltip can never be translated twice in one frame.

 */

public final class TooltipTranslationController {

    /** Render happens on the client thread only; a simple depth counter suffices. */

    private static int renderingTranslatedDepth = 0;
    private static long pendingGlowArmedUntilNanos;



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
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return false;
        }

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
    @Deprecated
    public static boolean shouldTranslateItemTooltip(List<Component> components) {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return false;
        }

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
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return false;
        }

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
        boolean requestAllowed = TooltipTranslationTriggerState.allowRequest(context, components);
        return translateForRender(components, context, requestAllowed);
    }

    public static List<Component> translateForRender(List<Component> components, RenderContext context,
                                                     boolean requestAllowed) {
        List<Component> translated = TooltipTranslationHelper.translateRenderedTooltip(
                components, context, requestAllowed);
        if (translated == components && shouldRenderPendingOriginal(components, context, requestAllowed)) {
            armPendingGlow();
        }
        return translated;

    }

    public static boolean shouldRenderPendingOriginal(List<Component> components, RenderContext context,
                                                      boolean requestAllowed) {
        return requestAllowed || TooltipTranslationHelper.isTranslationPending(components, context);
    }

    public static void armPendingGlowForHover(Component component, boolean requestAllowed) {
        if (requestAllowed || TooltipTranslationHelper.isHoverTranslationPending(component)) {
            armPendingGlow();
        }
    }

    private static void armPendingGlow() {
        if (!ModConfig.TOOLTIP_GLOW_ENABLED.get()) {
            pendingGlowArmedUntilNanos = 0L;
            return;
        }
        pendingGlowArmedUntilNanos = System.nanoTime() + 100_000_000L;
    }

    public static boolean consumePendingGlow() {
        long deadline = pendingGlowArmedUntilNanos;
        pendingGlowArmedUntilNanos = 0L;
        return deadline != 0L && System.nanoTime() <= deadline;
    }

    public static boolean allowRequest(RenderContext context, List<Component> components) {
        return TooltipTranslationTriggerState.allowRequest(context, components);
    }



    private static boolean isEnabledForContext(RenderContext context) {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            return false;
        }

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

