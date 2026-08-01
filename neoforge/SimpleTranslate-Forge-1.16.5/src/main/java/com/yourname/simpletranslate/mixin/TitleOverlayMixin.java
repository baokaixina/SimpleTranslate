package com.yourname.simpletranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.yourname.simpletranslate.core.render.GuiGraphics;
import com.yourname.simpletranslate.feature.hud.HudFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalAware;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.core.BlacklistRefreshAware;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.IngameGui;
import net.minecraft.client.gui.overlay.BossOverlayGui;
import net.minecraft.client.gui.NewChatGui;
import net.minecraft.client.gui.overlay.PlayerTabOverlayGui;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.ITextProperties;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Thin shell that delegates all title/subtitle/actionbar translation state and
 * logic to {@link HudFeature}. Only shadows the vanilla IngameGui fields and swaps
 * them from the feature's render results each frame.
 *
 * <p>Minecraft 1.19.4 renders titles, subtitles and the actionbar inline inside
 * {@code IngameGui.render(MatrixStack, float)} through {@code FontRenderer.drawShadow}, so the
 * dedicated-surface capture suppression wraps the exact draw/width calls and
 * the four surface renders instead of whole methods.</p>
 */
@Mixin(IngameGui.class)
public abstract class TitleOverlayMixin implements HoldOriginalAware, BlacklistRefreshAware {

    @Shadow
    @Nullable
    protected ITextComponent title;

    @Shadow
    @Nullable
    protected ITextComponent subtitle;

    @Shadow
    @Nullable
    protected ITextComponent overlayMessageString;

    @Unique
    private final HudFeature simple_translate$hud = new HudFeature();

    @Inject(method = "setTitles(Lnet/minecraft/util/text/ITextComponent;Lnet/minecraft/util/text/ITextComponent;III)V",
            at = @At("TAIL"), require = 1)
    private void simple_translate$onSetTitles(ITextComponent title, ITextComponent subtitle,
                                              int fadeIn, int stay, int fadeOut, CallbackInfo ci) {
        SafeTranslate.guard(() -> {
            simple_translate$hud.onSetTitle(title);
            simple_translate$hud.onSetSubtitle(subtitle);
        }, "title.onSetTitles");
    }

    @Inject(method = "setOverlayMessage(Lnet/minecraft/util/text/ITextComponent;Z)V", at = @At("TAIL"))
    private void simple_translate$onSetOverlayMessage(ITextComponent component, boolean animateColor, CallbackInfo ci) {
        SafeTranslate.guard(() -> simple_translate$hud.onSetOverlayMessage(component), "title.onSetOverlayMessage");
    }

    // Forge 1.16.5 ships Mixin 0.8.5, whose @Inject has no `order` member
    // (added in Mixin 0.8.7). The donor's order=900/1100 only ordered the HUD
    // frame begin/end against foreign default-order injectors on Gui.render;
    // no SimpleTranslate injector competes on this method, so the default
    // order keeps single-mod behavior identical.
    @Inject(
            method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At("HEAD"))
    private void simple_translate$onRender(MatrixStack poseStack, float partialTick, CallbackInfo ci) {
        SafeTranslate.guard(() -> {
            simple_translate$hud.onRender();
            this.title = simple_translate$hud.renderTitle();
            this.subtitle = simple_translate$hud.renderSubtitle();
            this.overlayMessageString = simple_translate$hud.renderOverlay();
        }, "title.onRender");
        SafeTranslate.guard(GuiTranslationHelper::beginHudFrame, "gui.hudFrame.begin");
    }

    @Inject(
            method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At("RETURN"))
    private void simple_translate$endHudFrame(MatrixStack poseStack, float partialTick, CallbackInfo ci) {
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
            method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/overlay/BossOverlayGui;render(Lcom/mojang/blaze3d/matrix/MatrixStack;)V"),
            require = 1)
    private void simple_translate$renderBossOverlaySuppressed(
            BossOverlayGui overlay, MatrixStack poseStack, Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(overlay, poseStack);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/NewChatGui;render(Lcom/mojang/blaze3d/matrix/MatrixStack;I)V"),
            require = 1)
    private void simple_translate$renderChatSuppressed(
            NewChatGui chat, MatrixStack poseStack, int tickCount,
            Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(chat, poseStack, tickCount);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/IngameGui;displayScoreboardSidebar(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/scoreboard/ScoreObjective;)V"),
            require = 1)
    private void simple_translate$renderSidebarSuppressed(
            IngameGui gui, MatrixStack poseStack, ScoreObjective objective, Operation<Void> original) {
        GuiTranslationHelper.beginCaptureSuppression();
        try {
            original.call(gui, poseStack, objective);
        } finally {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/overlay/PlayerTabOverlayGui;render(Lcom/mojang/blaze3d/matrix/MatrixStack;ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V"),
            require = 1)
    private void simple_translate$renderTabListSuppressed(
            PlayerTabOverlayGui tabList, MatrixStack poseStack, int width, Scoreboard scoreboard,
            ScoreObjective objective, Operation<Void> original) {
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
            method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;width(Lnet/minecraft/util/text/ITextProperties;)I"),
            require = 3
    )
    private int simple_translate$preserveLayoutActionbarWidth(
            FontRenderer font, ITextProperties rendered, Operation<Integer> original) {
        if (rendered instanceof ITextComponent component) {
            ITextComponent source = simple_translate$hud.layoutActionbarSource(component);
            if (source != null) {
                return original.call(font, source);
            }
        }
        return original.call(font, rendered);
    }

    /**
     * Title, subtitle and actionbar text are drawn inline by IngameGui.render.
     * On exact 1.16.5 (both the mapped 36.2.42 dev jar and the binpatched
     * production client, verified with javap -c 2026-07-27) render() contains
     * exactly two FontRenderer#drawShadow(MatrixStack,ITextComponent,FFI)I
     * calls (title + subtitle) and one #draw(MatrixStack,ITextComponent,FFI)I
     * call (the actionbar overlay draws without shadow on this version), so
     * both descriptors are wrapped and share one handler. A verified actionbar
     * layout plan draws its fixed-anchor spans instead of the vanilla string
     * (the native backdrop keeps the source width via the width wrap above);
     * every other call stays vanilla and is kept outside the whole-HUD K
     * frame.
     */
    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;drawShadow(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/util/text/ITextComponent;FFI)I"),
            require = 2
    )
    private int simple_translate$renderDedicatedHudTextShadowed(
            FontRenderer font, MatrixStack poseStack, ITextComponent rendered, float x, float y, int color,
            Operation<Integer> original) {
        return simple_translate$renderHudText(font, poseStack, rendered, x, y, color, original);
    }

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;draw(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/util/text/ITextComponent;FFI)I"),
            require = 1
    )
    private int simple_translate$renderDedicatedHudText(
            FontRenderer font, MatrixStack poseStack, ITextComponent rendered, float x, float y, int color,
            Operation<Integer> original) {
        return simple_translate$renderHudText(font, poseStack, rendered, x, y, color, original);
    }

    @Unique
    private int simple_translate$renderHudText(
            FontRenderer font, MatrixStack poseStack, ITextComponent rendered, float x, float y, int color,
            Operation<Integer> original) {
        ITextComponent source = simple_translate$hud.layoutActionbarSource(rendered);
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

    @Inject(method = "clearCache()V", at = @At("TAIL"), require = 1)
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
