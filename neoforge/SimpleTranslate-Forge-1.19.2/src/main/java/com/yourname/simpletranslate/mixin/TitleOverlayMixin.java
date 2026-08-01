package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.core.render.GuiGraphics;
import com.yourname.simpletranslate.feature.hud.HudFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalAware;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.core.BlacklistRefreshAware;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Thin shell that delegates all title/subtitle/actionbar translation state and
 * logic to {@link HudFeature}. Only shadows the vanilla Gui fields and swaps
 * them from the feature's render results each frame.
 *
 * <p><b>Forge 1.19.2 runtime path (verified against
 * {@code forge-1.19.2-43.5.2_mapped_official} bytecode, 2026-07-26):</b>
 * {@code Minecraft} instantiates {@code net.minecraftforge.client.gui.overlay.ForgeGui},
 * whose {@code render(PoseStack,F)} override posts {@code RenderGuiEvent.Pre},
 * runs the {@code GuiOverlayManager} overlay list, and posts
 * {@code RenderGuiEvent.Post} without ever calling the vanilla
 * {@code Gui.render}. Injections and operation wraps placed on
 * {@code Gui.render} therefore never fire at runtime on Forge (the donor's
 * Fabric-era hooks were dead code here). The per-frame swap, the whole-HUD K
 * frame window, and the dedicated-surface capture suppression are instead
 * driven by the Forge events that the live {@code ForgeGui.render} does post:
 * {@code RenderGuiEvent.Pre/Post} for the frame window and field swap, and
 * {@code RenderGuiOverlayEvent.Pre/Post} for keeping the boss bar
 * ({@code BOSS_EVENT_PROGRESS}), chat ({@code CHAT_PANEL}), scoreboard sidebar
 * ({@code SCOREBOARD}), tab list ({@code PLAYER_LIST}), titles
 * ({@code TITLE_TEXT}) and actionbar ({@code RECORD_OVERLAY}) outside the K
 * frame — the same set the donor wrapped inline in {@code Gui.render}.</p>
 *
 * <p>Actionbar layout preservation: the donor's width/draw wraps forced a
 * layout-critical actionbar back to its source Component
 * ({@code HudFeature.layoutActionbarSource}; {@code renderLayoutActionbar} is a
 * {@code false} stub below 1.21.4). The live Forge draw
 * ({@code ForgeGui.renderRecordOverlay}) reads {@code overlayMessageString}
 * for both the width and the draw (getfield verified), so swapping the field
 * to the source Component in that case is behavior-identical.</p>
 *
 * <p>The setter/clear hooks stay on vanilla {@code Gui}: packet handlers call
 * {@code gui.setTitle/setSubtitle/setOverlayMessage/clear}, which ForgeGui does
 * not override, so those injections remain live.</p>
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

    /**
     * Dedicated-owner overlay windows kept outside the whole-HUD K frame.
     * Ids taken from the exact 43.5.2 {@code VanillaGuiOverlay} constants.
     */
    @Unique
    private static final Set<ResourceLocation> SIMPLE_TRANSLATE$SUPPRESSED_OVERLAYS = Set.of(
            VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id(),
            VanillaGuiOverlay.CHAT_PANEL.id(),
            VanillaGuiOverlay.SCOREBOARD.id(),
            VanillaGuiOverlay.PLAYER_LIST.id(),
            VanillaGuiOverlay.TITLE_TEXT.id(),
            VanillaGuiOverlay.RECORD_OVERLAY.id());

    @Inject(method = "<init>", at = @At("TAIL"))
    private void simple_translate$registerForgeHudHooks(CallbackInfo ci) {
        // Explicit-class addListener overload (eventbus 6.0.0) avoids generic
        // sniffing on lambdas that were relocated into Gui by Mixin.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false,
                RenderGuiEvent.Pre.class, this::simple_translate$onHudRenderPre);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false,
                RenderGuiEvent.Post.class, this::simple_translate$onHudRenderPost);
        // Pre at LOWEST / Post at HIGHEST nest the suppression window tightly
        // around the overlay render; a Pre canceled by another mod never
        // reaches this listener (receiveCanceled=false), so begin/end stay
        // balanced, and beginHudFrame resets the depth each frame regardless.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false,
                RenderGuiOverlayEvent.Pre.class, this::simple_translate$onOverlayPre);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false,
                RenderGuiOverlayEvent.Post.class, this::simple_translate$onOverlayPost);
    }

    @Unique
    private boolean simple_translate$isActiveGui() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.gui == (Gui) (Object) this;
    }

    @Unique
    private void simple_translate$onHudRenderPre(RenderGuiEvent.Pre event) {
        if (!simple_translate$isActiveGui()) {
            return;
        }
        SafeTranslate.guard(() -> {
            simple_translate$hud.onRender();
            this.title = simple_translate$hud.renderTitle();
            this.subtitle = simple_translate$hud.renderSubtitle();
            Component overlay = simple_translate$hud.renderOverlay();
            Component layoutSource = simple_translate$hud.layoutActionbarSource(overlay);
            this.overlayMessageString = layoutSource != null ? layoutSource : overlay;
        }, "title.onRender");
        SafeTranslate.guard(GuiTranslationHelper::beginHudFrame, "gui.hudFrame.begin");
    }

    @Unique
    private void simple_translate$onHudRenderPost(RenderGuiEvent.Post event) {
        if (!simple_translate$isActiveGui()) {
            return;
        }
        SafeTranslate.guard(() -> GuiTranslationHelper.endHudFrame(GuiGraphics.wrap(event.getPoseStack())),
                "gui.hudFrame.end");
    }

    @Unique
    private void simple_translate$onOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (!simple_translate$isActiveGui()) {
            return;
        }
        if (SIMPLE_TRANSLATE$SUPPRESSED_OVERLAYS.contains(event.getOverlay().id())) {
            GuiTranslationHelper.beginCaptureSuppression();
        }
    }

    @Unique
    private void simple_translate$onOverlayPost(RenderGuiOverlayEvent.Post event) {
        if (!simple_translate$isActiveGui()) {
            return;
        }
        if (SIMPLE_TRANSLATE$SUPPRESSED_OVERLAYS.contains(event.getOverlay().id())) {
            GuiTranslationHelper.endCaptureSuppression();
        }
    }

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
