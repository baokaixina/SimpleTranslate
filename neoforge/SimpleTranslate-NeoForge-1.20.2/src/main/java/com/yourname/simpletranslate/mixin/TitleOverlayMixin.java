package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yourname.simpletranslate.feature.hud.HudFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalAware;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.core.BlacklistRefreshAware;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
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
 *
 * <p>Minecraft 1.20.1 renders titles, subtitles and the actionbar inline inside
 * {@code Gui.render}, so the dedicated-surface capture suppression wraps the
 * exact draw/width calls and the four surface renders instead of whole
 * methods.</p>
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

    // Loader adaptation: Mixin 0.8.5 (NeoForge 20.2.x) has no Inject#order;
    // the donor orders these against third-party Gui.render injectors. No
    // other SimpleTranslate injector targets Gui.render on 1.20.2, so the
    // default order preserves in-mod behavior.
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
    @WrapOperation(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;render(Lnet/minecraft/client/gui/GuiGraphics;)V"),
            require = 1)
    private void simple_translate$renderBossOverlaySuppressed(
            BossHealthOverlay overlay, GuiGraphics graphics, Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(overlay, graphics);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

    @WrapOperation(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lnet/minecraft/client/gui/GuiGraphics;III)V"),
            require = 1)
    private void simple_translate$renderChatSuppressed(
            ChatComponent chat, GuiGraphics graphics, int tickCount, int mouseX, int mouseY,
            Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(chat, graphics, tickCount, mouseX, mouseY);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

    @WrapOperation(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V"),
            require = 1)
    private void simple_translate$renderSidebarSuppressed(
            Gui gui, GuiGraphics graphics, Objective objective, Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(gui, graphics, objective);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

    @WrapOperation(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;render(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V"),
            require = 1)
    private void simple_translate$renderTabListSuppressed(
            PlayerTabOverlay tabList, GuiGraphics graphics, int width, Scoreboard scoreboard,
            Objective objective, Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(tabList, graphics, width, scoreboard, objective);
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
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"),
            require = 3
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
     * Title, subtitle and actionbar text are drawn inline by Gui.render on
     * 1.20.1. A verified actionbar layout plan draws its fixed-anchor spans
     * instead of the vanilla string (the native backdrop keeps the source
     * width via the width wrap above); every other call stays vanilla and is
     * kept outside the whole-HUD K frame.
     */
    @WrapOperation(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"),
            require = 3
    )
    private int simple_translate$renderDedicatedHudText(
            GuiGraphics graphics, Font font, Component rendered, int x, int y, int color,
            Operation<Integer> original) {
        Component source = simple_translate$hud.layoutActionbarSource(rendered);
        if (source != null) {
            try {
                if (simple_translate$hud.renderLayoutActionbar(graphics, font, rendered, x, y,
                        graphics.guiWidth(), color)) {
                    return 0;
                }
            } catch (Throwable error) {
                // Rendering must be no-risk: a resource-pack font failure still
                // leaves the original actionbar visible.
                SafeTranslate.logLimited("title.layoutActionbarRender", error);
            }
            GuiTranslationHelper.beginCaptureSuppression();
            try {
                return original.call(graphics, font, source, x, y, color);
            } finally {
                GuiTranslationHelper.endCaptureSuppression();
            }
        }
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            return original.call(graphics, font, rendered, x, y, color);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
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
