package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.yourname.simpletranslate.feature.hud.HudFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalAware;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.core.BlacklistRefreshAware;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Thin shell that delegates all title/subtitle/actionbar translation state and
 * logic to {@link HudFeature}. Only shadows the vanilla Gui fields and swaps
 * them from the feature's render results each frame.
 */
@Mixin(Gui.class)
public abstract class TitleOverlayMixin implements HoldOriginalAware, BlacklistRefreshAware {

    @Shadow
    @Nullable
    protected Component title;

    @Shadow
    @Nullable
    protected Component subtitle;

    @Shadow
    @Nullable
    protected Component overlayMessageString;

    @Unique
    private final HudFeature simple_translate$hud = new HudFeature();

    @Inject(method = "setTitle(Lnet/minecraft/network/chat/Component;)V", at = @At("TAIL"))
    private void simple_translate$onSetTitle(Component title, CallbackInfo ci) {
        SafeTranslate.guard(() -> simple_translate$hud.onSetTitle(title), "title.onSetTitle");
    }

    @Inject(method = "setSubtitle(Lnet/minecraft/network/chat/Component;)V", at = @At("TAIL"))
    private void simple_translate$onSetSubtitle(Component subtitle, CallbackInfo ci) {
        SafeTranslate.guard(() -> simple_translate$hud.onSetSubtitle(subtitle), "title.onSetSubtitle");
    }

    @Inject(method = "setOverlayMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("TAIL"))
    private void simple_translate$onSetOverlayMessage(Component component, boolean animateColor, CallbackInfo ci) {
        SafeTranslate.guard(() -> simple_translate$hud.onSetOverlayMessage(component), "title.onSetOverlayMessage");
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("HEAD"), order = 900)
    private void simple_translate$onRender(GuiGraphics graphics, DeltaTracker tickCounter, CallbackInfo ci) {
        SafeTranslate.guard(() -> {
            simple_translate$hud.onRender();
            this.title = simple_translate$hud.renderTitle();
            this.subtitle = simple_translate$hud.renderSubtitle();
            this.overlayMessageString = simple_translate$hud.renderOverlay();
        }, "title.onRender");
        SafeTranslate.guard(GuiTranslationHelper::beginHudFrame, "gui.hudFrame.begin");
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            // Wynntils draws GUI_POST overlays from its default-order RETURN
            // callback. Close the ordinary K/HUD frame afterwards; the exact
            // Wynntils manager window owns or suppresses its independent frame.
            at = @At("RETURN"), order = 1100)
    private void simple_translate$endHudFrame(GuiGraphics graphics, DeltaTracker tickCounter, CallbackInfo ci) {
        SafeTranslate.guard(() -> GuiTranslationHelper.endHudFrame(graphics), "gui.hudFrame.end");
    }

    /**
     * Chat, boss bars, scoreboard, tab list, titles and actionbars all have
     * dedicated translation owners. Keep the whole-HUD K frame outside these
     * render windows even when an individual owner is disabled; otherwise K
     * can translate the same source or destroy Wynn's event-scoped glyph mask.
     */
    @WrapMethod(
            method = {
                    "renderBossOverlay(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
                    "renderChat(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
                    "renderScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
                    "renderTabList(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
                    "renderOverlayMessage(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
                    "renderTitle(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"
            }, require = 6)
    private void simple_translate$renderDedicatedHudSurface(
            GuiGraphics graphics, DeltaTracker tickCounter, Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(graphics, tickCounter);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

    /**
     * Vanilla centers the actionbar from this width. Both the generic fixed
     * layout plan and the Wynn glyph-overlay plan must retain the untouched
     * source width so their PUA coordinate stream starts at the vanilla x.
     */
    @WrapOperation(
            method = "renderOverlayMessage(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"),
            require = 1
    )
    private int simple_translate$preserveLayoutActionbarWidth(
            Font font, FormattedText rendered, Operation<Integer> original) {
        if (rendered instanceof Component component) {
            Component source = simple_translate$hud.layoutActionbarSource(component);
            if (source != null) {
                return original.call(font, source);
            }
        }
        return original.call(font, rendered);
    }

    /**
     * Draw a verified layout plan. The Wynn path lets vanilla consume one
     * masked copy of its complete original glyph stream and then overlays
     * Chinese; the generic path may draw fixed-anchor spans. Normal actionbars
     * continue through vanilla unchanged, and every unsafe plan falls back to
     * the untouched source component.
     */
    @WrapOperation(
            method = "renderOverlayMessage(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"),
            require = 1
    )
    private void simple_translate$renderLayoutActionbar(
            GuiGraphics graphics, Font font, Component rendered,
            int x, int y, int width, int color, Operation<Void> original) {
        Component source = simple_translate$hud.layoutActionbarSource(rendered);
        if (source == null) {
            try {
                // Queue the pending effect before vanilla's text call so the
                // untouched Wynn glyphs stay crisp above the inward-only glow.
                simple_translate$hud.renderWynnDialoguePendingEffect(
                        graphics, font, rendered, x, y, width);
            } catch (Throwable error) {
                SafeTranslate.logLimited("title.wynnDialoguePendingEffect", error);
            }
            original.call(graphics, font, rendered, x, y, width, color);
            return;
        }
        try {
            if (simple_translate$hud.renderLayoutActionbar(graphics, font, rendered, x, y, width, color)) {
                return;
            }
        } catch (Throwable error) {
            // Rendering must be no-risk: a resource-pack font or deferred GUI
            // extractor failure still leaves the original actionbar visible.
            SafeTranslate.logLimited("title.layoutActionbarRender", error);
        }
        original.call(graphics, font, source, x, y, width, color);
    }

    @Inject(method = "clearTitles()V", at = @At("TAIL"))
    private void simple_translate$onClear(CallbackInfo ci) {
        simple_translate$hud.onClear();
    }

    @Override
    public void simple_translate$onHoldOriginalChanged(HoldOriginalFeature feature, boolean holding) {
        simple_translate$hud.onHoldOriginalChanged(feature, holding);
    }

    @Override
    public boolean simple_translate$refreshBlacklistedTranslations() {
        return simple_translate$hud.refreshBlacklistedTranslations();
    }
}
