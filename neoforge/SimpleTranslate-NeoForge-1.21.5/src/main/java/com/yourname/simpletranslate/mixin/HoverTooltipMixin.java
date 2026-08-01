package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationController;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationGlowRenderer;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Consumer;

/**
 * Intercepts hover tooltips in chat/book screens and mod overlay tooltips that
 * render directly through {@link GuiGraphics} instead of {@link HoverEvent}.
 *
 * <p>Minecraft 1.21.5 has no deferred setTooltipForNextFrame pipeline: every
 * overload funnels synchronously into the private
 * {@code renderTooltipInternal(Font, List, int, int, ClientTooltipPositioner,
 * ResourceLocation)}. The item-frame capture, commit and render hooks
 * therefore hang on that exact overload set.</p>
 */
@Mixin(GuiGraphics.class)
public class HoverTooltipMixin {
    @Unique private String simple_translate$candidateItemFrameKey;
    @Unique private boolean simple_translate$candidateItemFrameRequest;
    @Unique private boolean simple_translate$candidateItemSubmission;
    @Unique private boolean simple_translate$candidatePrepared;
    @Unique private List<Component> simple_translate$candidateItemRows;
    @Unique private String simple_translate$pendingItemFrameKey;
    @Unique private boolean simple_translate$pendingItemFrameRequest;
    @Unique private boolean simple_translate$pendingItemSubmission;
    @Unique private List<Component> simple_translate$pendingItemRows;

    @Inject(method = "renderComponentHoverEffect", at = @At("HEAD"), cancellable = true)
    private void onRenderComponentHoverEffect(Font font, Style style, int mouseX, int mouseY, CallbackInfo ci) {
        if (style == null || HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER)) {
            return;
        }
        HoverEvent hoverEvent = style.getHoverEvent();
        if (!(hoverEvent instanceof HoverEvent.ShowText showText)) {
            return;
        }
        Component hoverText = showText.value();
        if (!TooltipTranslationController.shouldTranslateChatHover(hoverText) && !shouldTranslateBookHover()) {
            return;
        }
        TooltipTranslationController.RenderContext context = TooltipTranslationController.resolveRenderContext();
        boolean requestAllowed = TooltipTranslationController.allowRequest(context, List.of(hoverText));
        List<Component> translatedLines = TooltipTranslationHelper.translateHoverComponentLines(hoverText, requestAllowed);

        if (translatedLines.size() == 1 && translatedLines.get(0) == hoverText) {
            if (requestAllowed || TooltipTranslationHelper.isHoverTranslationPending(hoverText)) {
                GuiGraphics guiGraphics = (GuiGraphics) (Object) this;
                TooltipTranslationController.beginRenderingTranslated();
                try {
                    TooltipTranslationController.armPendingGlowForHover(hoverText, requestAllowed);
                    guiGraphics.renderComponentTooltip(font,
                            TooltipTranslationHelper.splitHoverComponentLinesForRender(hoverText),
                            mouseX, mouseY);
                } finally {
                    TooltipTranslationController.endRenderingTranslated();
                }
                ci.cancel();
            }
            return;
        }
        renderTranslatedTooltip(guiGraphics -> guiGraphics.renderComponentTooltip(font, translatedLines, mouseX, mouseY), ci);
    }

    @Inject(
            method = "renderTooltipInternal(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/ResourceLocation;)V",
            at = @At("TAIL"),
            require = 0)
    private void simple_translate$renderPendingTranslationGlow(Font font,
                                                               List<ClientTooltipComponent> components,
                                                               int mouseX, int mouseY,
                                                               ClientTooltipPositioner positioner,
                                                               ResourceLocation texture,
                                                               CallbackInfo ci) {
        if (!TooltipTranslationController.consumePendingGlow()) {
            return;
        }
        TooltipTranslationGlowRenderer.render((GuiGraphics) (Object) this, font, components,
                mouseX, mouseY, positioner);
    }

    /** Records the final post-decoration Component document without changing it. */
    @Inject(
            method = "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/ResourceLocation;)V",
            at = @At("HEAD"), require = 1)
    private void simple_translate$captureFinalComponentRows(
            Font font, List<Component> components, java.util.Optional<?> image,
            int mouseX, int mouseY, ResourceLocation texture, CallbackInfo ci) {
        simple_translate$captureItemFrame(components);
    }

    /**
     * This target exposes an ItemStack-bearing component overload for
     * decorated tooltips. Keep the item scope while it forwards to the
     * component renderer so the dedicated item-tooltip pipeline owns it.
     */
    @Inject(
            method = "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;Lnet/minecraft/world/item/ItemStack;II)V",
            at = @At("HEAD"), require = 1)
    private void simple_translate$beginDecoratedItemStackSubmission(
            Font font, List<Component> components, java.util.Optional<?> image, ItemStack stack,
            int mouseX, int mouseY, CallbackInfo ci) {
        TooltipTranslationController.beginItemTooltipSubmission();
    }

    @Inject(
            method = "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;Lnet/minecraft/world/item/ItemStack;II)V",
            at = @At("RETURN"), require = 1)
    private void simple_translate$endDecoratedItemStackSubmission(
            Font font, List<Component> components, java.util.Optional<?> image, ItemStack stack,
            int mouseX, int mouseY, CallbackInfo ci) {
        TooltipTranslationController.endItemTooltipSubmission();
    }

    /** The ItemStack overload proves that its delegated Component list is an item tooltip. */
    @Inject(
            method = "renderTooltip(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V",
            at = @At("HEAD"), require = 1)
    private void simple_translate$beginItemStackSubmission(
            Font font, ItemStack stack, int mouseX, int mouseY, CallbackInfo ci) {
        TooltipTranslationController.beginItemTooltipSubmission();
    }

    @Inject(
            method = "renderTooltip(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V",
            at = @At("RETURN"), require = 1)
    private void simple_translate$endItemStackSubmission(
            Font font, ItemStack stack, int mouseX, int mouseY, CallbackInfo ci) {
        TooltipTranslationController.endItemTooltipSubmission();
    }

    /** Records final shaped rows used by custom mod tooltip submitters. */
    @Inject(
            method = "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/resources/ResourceLocation;)V",
            at = @At("HEAD"), require = 1)
    private void simple_translate$captureFinalVisualRows(
            Font font, List<FormattedCharSequence> rows, int mouseX, int mouseY,
            ResourceLocation texture, CallbackInfo ci) {
        if (TooltipTranslationController.isRenderingTranslated() || rows == null || rows.isEmpty()) {
            simple_translate$clearCandidateItemFrame();
            return;
        }
        List<Component> source = rows.stream()
                .map(GuiTranslationHelper::componentFromFormattedSequence)
                .toList();
        simple_translate$captureItemFrame(source);
    }

    @Unique
    private void simple_translate$captureItemFrame(List<Component> components) {
        simple_translate$clearCandidateItemFrame();
        simple_translate$candidatePrepared = true;
        if (!TooltipTranslationController.isItemTooltipSubmission()) {
            return;
        }
        simple_translate$candidateItemSubmission = true;
        TooltipTranslationController.RenderContext context =
                TooltipTranslationController.resolveRenderContext();
        if (context != TooltipTranslationController.RenderContext.ITEM
                || !TooltipTranslationController.shouldCaptureItemFrame(components)) {
            return;
        }
        simple_translate$candidateItemFrameKey =
                GuiTranslationHelper.detachedFrameKey("gui.item_tooltip", components);
        // This Component list is only a provisional submission. Vanilla and
        // third-party decorators can replace it before the internal renderer
        // accepts the frame, so feeding it into the hover dwell state would
        // alternate signatures with the final visual rows and prevent
        // automatic requests.
        simple_translate$candidateItemFrameRequest = false;
        simple_translate$candidateItemRows = List.copyOf(components);
    }

    /**
     * 1.21.5 accepts a tooltip frame as soon as renderTooltipInternal runs
     * with a non-empty component list; there is no deferred first-wins rule.
     */
    @Inject(
            method = "renderTooltipInternal(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/ResourceLocation;)V",
            at = @At("HEAD"), require = 1)
    private void simple_translate$commitAcceptedTooltipClassification(
            Font font, List<ClientTooltipComponent> components, int mouseX, int mouseY,
            ClientTooltipPositioner positioner, ResourceLocation texture, CallbackInfo ci) {
        boolean accepted = components != null && !components.isEmpty();
        if (accepted) {
            simple_translate$clearPendingItemFrame();
            if (simple_translate$candidatePrepared) {
                simple_translate$pendingItemSubmission = simple_translate$candidateItemSubmission;
                simple_translate$pendingItemFrameKey = simple_translate$candidateItemFrameKey;
                simple_translate$pendingItemFrameRequest = simple_translate$candidateItemFrameRequest;
                simple_translate$pendingItemRows = simple_translate$candidateItemRows;
            }
        }
        simple_translate$clearCandidateItemFrame();
    }

    @WrapMethod(
            method = "renderTooltipInternal(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/ResourceLocation;)V",
            require = 1)
    private void simple_translate$renderItemTooltipFrame(
            Font font, List<ClientTooltipComponent> components, int mouseX, int mouseY,
            ClientTooltipPositioner positioner, ResourceLocation texture, Operation<Void> original) {
        boolean itemSubmission = simple_translate$pendingItemSubmission;
        String frameKey = simple_translate$pendingItemFrameKey;
        boolean request = simple_translate$pendingItemFrameRequest;
        List<Component> submittedRows = simple_translate$pendingItemRows;
        List<Component> finalTextRows = simple_translate$finalTextRows(components);
        if (itemSubmission && TooltipTranslationController.shouldCaptureItemFrame(finalTextRows)) {
            // Probe and key the cache from the exact visual rows the renderer
            // accepted, not the earlier Component submission: resource-pack
            // resolution, Alt/Shift decorators and third-party tooltip hooks
            // may have changed structure before the frame was accepted.
            if (finalTextRows == simple_translate$lastKeyRows) {
                frameKey = simple_translate$lastKeyResult;
            } else {
                frameKey = GuiTranslationHelper.detachedFrameKey(
                        "gui.item_tooltip", finalTextRows);
                simple_translate$lastKeyRows = finalTextRows;
                simple_translate$lastKeyResult = frameKey;
            }
            request = request || TooltipTranslationController.allowRequest(
                    TooltipTranslationController.RenderContext.ITEM, finalTextRows);
            submittedRows = finalTextRows;
        }
        boolean itemFrameStarted = itemSubmission && frameKey != null
                && GuiTranslationHelper.beginItemTooltipFrame(
                frameKey, "Item tooltip", request, submittedRows);
        boolean itemFrameSuppressed = itemSubmission && !itemFrameStarted;
        if (itemFrameSuppressed) {
            // GUI AUTO/K must never bypass the dedicated item-tooltip setting.
            GuiTranslationHelper.beginCaptureSuppression();
        }
        boolean pending = GuiTranslationHelper.isFrameTranslationPending(frameKey);
        boolean hasSnapshot = GuiTranslationHelper.hasFrameSnapshot(frameKey);
        // Cache hits (memory or synchronous disk hydrate) must never arm the
        // pending glow — only a true miss that is requesting or already in flight.
        TooltipTranslationController.armPendingGlowIf(
                itemSubmission && itemFrameStarted && !hasSnapshot && (request || pending));
        simple_translate$clearPendingItemFrame();
        try {
            original.call(font, components, mouseX, mouseY, positioner, texture);
        } finally {
            if (itemFrameStarted) {
                GuiTranslationHelper.endDetachedFrame((GuiGraphics) (Object) this);
            }
            if (itemFrameSuppressed) {
                GuiTranslationHelper.endCaptureSuppression();
            }
        }
    }

    @Unique
    private static List<ClientTooltipComponent> simple_translate$lastRowComponents;
    @Unique
    private static List<Component> simple_translate$lastRowResult;
    @Unique
    private static List<Component> simple_translate$lastKeyRows;
    @Unique
    private static String simple_translate$lastKeyResult;

    @Unique
    private static List<Component> simple_translate$finalTextRows(
            List<ClientTooltipComponent> components) {
        if (components == simple_translate$lastRowComponents) {
            return simple_translate$lastRowResult;
        }
        if (components == null || components.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<Component> rows = new java.util.ArrayList<>();
        for (ClientTooltipComponent component : components) {
            if (component instanceof ClientTextTooltipAccessor accessor) {
                FormattedCharSequence text = accessor.simple_translate$getText();
                if (text != null) {
                    rows.add(GuiTranslationHelper.componentFromFormattedSequence(text));
                }
            }
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Component> result = List.copyOf(rows);
        simple_translate$lastRowComponents = components;
        simple_translate$lastRowResult = result;
        return result;
    }

    @Unique
    private void simple_translate$clearPendingItemFrame() {
        simple_translate$pendingItemFrameKey = null;
        simple_translate$pendingItemFrameRequest = false;
        simple_translate$pendingItemSubmission = false;
        simple_translate$pendingItemRows = null;
    }

    @Unique
    private void simple_translate$clearCandidateItemFrame() {
        simple_translate$candidateItemFrameKey = null;
        simple_translate$candidateItemFrameRequest = false;
        simple_translate$candidateItemSubmission = false;
        simple_translate$candidatePrepared = false;
        simple_translate$candidateItemRows = null;
    }

    /** Shared re-render path: wraps the callback in begin/end guards and cancels. */
    private void renderTranslatedTooltip(java.util.function.Consumer<GuiGraphics> renderer, CallbackInfo ci) {
        GuiGraphics guiGraphics = (GuiGraphics) (Object) this;
        renderWithGuard(guiGraphics, renderer, ci);
    }

    private void renderWithGuard(GuiGraphics guiGraphics, Consumer<GuiGraphics> renderer, CallbackInfo ci) {
        TooltipTranslationController.beginRenderingTranslated();
        try {
            renderer.accept(guiGraphics);
        } finally {
            TooltipTranslationController.endRenderingTranslated();
        }
        ci.cancel();
    }

    @Unique
    private static boolean shouldTranslateBookHover() {
        Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
        return ModConfig.GLOBAL_ENABLED.get()
                && screen instanceof BookViewScreen
                && ModConfig.TOOLTIP_BOOK_HOVER_ENABLED.get()
                && !HoldOriginalState.isHolding(HoldOriginalFeature.TOOLTIP_HOVER);
    }
}
