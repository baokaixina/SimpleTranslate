package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.core.render.GuiGraphics;
import com.yourname.simpletranslate.feature.hud.HudFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalAware;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.core.BlacklistRefreshAware;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
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
 * <p>Minecraft 1.19.4 renders titles, subtitles and the actionbar inline inside
 * {@code Gui.render(PoseStack, float)} through {@code Font.drawShadow}, so the
 * dedicated-surface capture suppression wraps the exact draw/width calls and
 * the four surface renders instead of whole methods.</p>
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

    // Forge 1.19.2 ships Mixin 0.8.5, whose @Inject has no `order` member
    // (added in Mixin 0.8.6). The donor's order=900/1100 only ordered the HUD
    // frame begin/end against foreign default-order injectors on Gui.render;
    // no SimpleTranslate injector competes on this method, so the default
    // order keeps single-mod behavior identical.
    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At("HEAD"))
    private void simple_translate$onRender(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        SafeTranslate.guard(() -> {
            simple_translate$hud.onRender();
            this.title = simple_translate$hud.renderTitle();
            this.subtitle = simple_translate$hud.renderSubtitle();
            this.overlayMessageString = simple_translate$hud.renderOverlay();
        }, "title.onRender");
        SafeTranslate.guard(GuiTranslationHelper::beginHudFrame, "gui.hudFrame.begin");
    }

    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At("RETURN"))
    private void simple_translate$endHudFrame(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        SafeTranslate.guard(() -> GuiTranslationHelper.endHudFrame(GuiGraphics.wrap(poseStack)),
                "gui.hudFrame.end");
    }

    /**
     * Chat, boss bars, scoreboard, tab list, titles and actionbars all have
     * dedicated translation owners. Keep the whole-HUD K frame outside these
     * render windows even when an individual owner is disabled; otherwise K
     * can translate the same source a second time.
     */
    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            // MixinExtras 0.4.1 does not remap nested @At targets on Forge
            // 40.2.21. These are the exact SRG calls in Gui#m_93030_.
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;m_93704_(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
                    remap = false
            ),
            require = 1)
    private void simple_translate$renderBossOverlaySuppressed(
            BossHealthOverlay overlay, PoseStack poseStack, Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(overlay, poseStack);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/ChatComponent;m_93780_(Lcom/mojang/blaze3d/vertex/PoseStack;I)V",
                    remap = false
            ),
            require = 1)
    private void simple_translate$renderChatSuppressed(
            ChatComponent chat, PoseStack poseStack, int tickCount,
            Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(chat, poseStack, tickCount);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Gui;m_93036_(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/scores/Objective;)V",
                    remap = false
            ),
            require = 1)
    private void simple_translate$renderSidebarSuppressed(
            Gui gui, PoseStack poseStack, Objective objective, Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(gui, poseStack, objective);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;m_94544_(Lcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V",
                    remap = false
            ),
            require = 1)
    private void simple_translate$renderTabListSuppressed(
            PlayerTabOverlay tabList, PoseStack poseStack, int width, Scoreboard scoreboard,
            Objective objective, Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(tabList, poseStack, width, scoreboard, objective);
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
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;m_92852_(Lnet/minecraft/network/chat/FormattedText;)I",
                    remap = false
            ),
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
     * Title, subtitle and actionbar text are drawn inline by Gui.render. On
     * exact 1.18.2 (javap -c against the 40.2.21 mapped dev jar and the
     * binpatched production Gui, 2026-07-27) render() contains exactly two
     * Font#drawShadow(PoseStack,Component,FFI)I calls (title + subtitle) and
     * one Font#draw(PoseStack,Component,FFI)I call (the actionbar draws
     * without shadow on this version), so both descriptors are wrapped and
     * share one body. The donor-copied single drawShadow wrap with
     * require = 3 could never match. A verified actionbar layout plan draws
     * its fixed-anchor spans instead of the vanilla string (the native
     * backdrop keeps the source width via the width wrap above); every other
     * call stays vanilla and is kept outside the whole-HUD K frame.
     */
    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;m_92763_(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
                    remap = false
            ),
            require = 2
    )
    private int simple_translate$renderDedicatedHudTextShadowed(
            Font font, PoseStack poseStack, Component rendered, float x, float y, int color,
            Operation<Integer> original) {
        return simple_translate$renderHudText(font, poseStack, rendered, x, y, color, original);
    }

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;m_92889_(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
                    remap = false
            ),
            require = 1
    )
    private int simple_translate$renderDedicatedHudText(
            Font font, PoseStack poseStack, Component rendered, float x, float y, int color,
            Operation<Integer> original) {
        return simple_translate$renderHudText(font, poseStack, rendered, x, y, color, original);
    }

    @Unique
    private int simple_translate$renderHudText(
            Font font, PoseStack poseStack, Component rendered, float x, float y, int color,
            Operation<Integer> original) {
        Component source = simple_translate$hud.layoutActionbarSource(rendered);
        if (source != null) {
            GuiGraphics graphics = GuiGraphics.wrap(poseStack);
            try {
                if (simple_translate$hud.renderLayoutActionbar(graphics, font, rendered,
                        (int) x, (int) y, graphics.guiWidth(), color)) {
                    return 0;
                }
            } catch (Throwable error) {
                // Rendering must be no-risk: a resource-pack font failure still
                // leaves the original actionbar visible.
                SafeTranslate.logLimited("title.layoutActionbarRender", error);
            }
            GuiTranslationHelper.beginCaptureSuppression();
            try {
                return original.call(font, poseStack, source, x, y, color);
            } finally {
                GuiTranslationHelper.endCaptureSuppression();
            }
        }
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            return original.call(font, poseStack, rendered, x, y, color);
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
