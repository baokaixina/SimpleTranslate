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

    // NeoForge 20.6 bundles sponge-mixin 0.8.5, which has no injector `order`
    // attribute (0.8.7+); the donor's order 900/1100 brackets Wynntils GUI_POST
    // overlays, a >=1.21.4-only compat, so default order is sufficient here.
    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            at = @At("HEAD"))
    private void simple_translate$onRender(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        SafeTranslate.guard(() -> {
            simple_translate$hud.onRender();
            this.title = simple_translate$hud.renderTitle();
            this.subtitle = simple_translate$hud.renderSubtitle();
            this.overlayMessageString = simple_translate$hud.renderOverlay();
        }, "title.onRender");
        SafeTranslate.guard(GuiTranslationHelper::beginHudFrame, "gui.hudFrame.begin");
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            at = @At("RETURN"))
    private void simple_translate$endHudFrame(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        SafeTranslate.guard(() -> GuiTranslationHelper.endHudFrame(graphics), "gui.hudFrame.end");
    }

    /**
     * Chat, boss bars, scoreboard, tab list, titles and actionbars all have
     * dedicated translation owners. Keep the whole-HUD K frame outside these
     * render windows even when an individual owner is disabled; otherwise K
     * can translate the same source a second time.
     */
    @WrapMethod(
            method = {
                    "renderChat(Lnet/minecraft/client/gui/GuiGraphics;F)V",
                    "renderScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;F)V",
                    "renderTabList(Lnet/minecraft/client/gui/GuiGraphics;F)V",
                    "renderOverlayMessage(Lnet/minecraft/client/gui/GuiGraphics;F)V",
                    "renderTitle(Lnet/minecraft/client/gui/GuiGraphics;F)V"
            }, require = 5)
    private void simple_translate$renderDedicatedHudSurface(
            GuiGraphics graphics, float partialTick, Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(graphics, partialTick);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

    /**
     * Vanilla centers the actionbar from this width. A verified layout plan
     * must retain the untouched source width so its PUA coordinate stream
     * starts at the vanilla x.
     */
    @WrapOperation(
            method = "renderOverlayMessage(Lnet/minecraft/client/gui/GuiGraphics;F)V",
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
     * Draw a verified layout plan when one exists. Normal actionbars continue
     * through vanilla unchanged, and every unsafe plan falls back to the
     * untouched source component. Minecraft 1.20.6 draws the actionbar through
     * {@code GuiGraphics.drawString} plus a separate Gui-owned backdrop, so the
     * plan renderer receives the GUI width instead of a backdrop width.
     */
    @WrapOperation(
            method = "renderOverlayMessage(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"),
            require = 1
    )
    private int simple_translate$renderLayoutActionbar(
            GuiGraphics graphics, Font font, Component rendered,
            int x, int y, int color, Operation<Integer> original) {
        Component source = simple_translate$hud.layoutActionbarSource(rendered);
        if (source == null) {
            return original.call(graphics, font, rendered, x, y, color);
        }
        try {
            if (simple_translate$hud.renderLayoutActionbar(graphics, font, rendered, x, y,
                    graphics.guiWidth(), color)) {
                return 0;
            }
        } catch (Throwable error) {
            // Rendering must be no-risk: a resource-pack font or deferred GUI
            // extractor failure still leaves the original actionbar visible.
            SafeTranslate.logLimited("title.layoutActionbarRender", error);
        }
        return original.call(graphics, font, source, x, y, color);
    }

    @Inject(method = "clear()V", at = @At("TAIL"))
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
