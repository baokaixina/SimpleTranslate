package com.yourname.simpletranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.book.BookFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import com.yourname.simpletranslate.feature.book.BookBookmarkControl;
import com.yourname.simpletranslate.feature.book.BookTranslationHelper;
import com.yourname.simpletranslate.feature.book.BookTranslationHelper.PageData;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationTriggerState;
import com.yourname.simpletranslate.compat.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(BookViewScreen.class)
public abstract class BookViewScreenMixin extends Screen {

    @Shadow
    private BookViewScreen.BookAccess bookAccess;

    @Shadow
    private int cachedPage;

    @Unique
    private BookFeature simple_translate$bookFeature = new BookFeature();

    protected BookViewScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void simple_translate$armHoveredTooltipTranslation(int keyCode, int scanCode, int modifiers,
                                                               CallbackInfoReturnable<Boolean> cir) {
        if (ModKeyBindings.matchesTranslateHoveredTooltipKey(keyCode, scanCode)
                && TooltipTranslationTriggerState.hasEnabledShortcutMode()) {
            TooltipTranslationTriggerState.armShortcutRequest();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setBookAccess", at = @At("TAIL"))
    private void simple_translate$onSetBookAccess(BookViewScreen.BookAccess access, CallbackInfo ci) {
        simple_translate$bookFeature.reset();
        this.cachedPage = -1;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void simple_translate$onRenderHead(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (simple_translate$bookFeature.stateChanged()) {
            this.cachedPage = -1;
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void simple_translate$onRenderTail(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        simple_translate$renderBookmark(new GuiGraphics(poseStack), mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void simple_translate$onMouseClicked(double mouseX, double mouseY, int button,
            CallbackInfoReturnable<Boolean> cir) {
        if (button != 0 || !simple_translate$isBookmarkVisible()) {
            return;
        }

        if (simple_translate$isMouseOverBookmark(mouseX, mouseY)) {
            simple_translate$onTranslateBookmarkPressed();
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/BookViewScreen$BookAccess;getPage(I)Lnet/minecraft/network/chat/FormattedText;"))
    private FormattedText simple_translate$redirectPageText(BookViewScreen.BookAccess access, int pageIndex) {
        FormattedText original = access.getPage(pageIndex);
        if (!ModConfig.GLOBAL_ENABLED.get()
                || !ModConfig.CONTENT_BOOK_ENABLED.get()
                || !simple_translate$bookFeature.active()) {
            return original;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.BOOK)) {
            return original;
        }

        List<Component> translatedPages = simple_translate$bookFeature.translatedPages();
        if (translatedPages != null && pageIndex >= 0 && pageIndex < translatedPages.size()) {
            return translatedPages.get(pageIndex);
        }

        if (simple_translate$bookFeature.translating()
                && simple_translate$bookFeature.pageNeedsTranslation(pageIndex)) {
            Component originalComponent = (original instanceof Component) ? (Component) original : null;
            return BookTranslationHelper.buildTranslatingComponent(originalComponent);
        }

        return original;
    }

    @Unique
    private void simple_translate$onTranslateBookmarkPressed() {
        if (simple_translate$bookFeature.active()) {
            simple_translate$restoreOriginal();
        } else {
            simple_translate$startTranslation();
        }
    }

    @Unique
    private void simple_translate$startTranslation() {
        if (!ModConfig.GLOBAL_ENABLED.get() || !ModConfig.CONTENT_BOOK_ENABLED.get() || this.bookAccess == null) {
            return;
        }

        int pageCount = this.bookAccess.getPageCount();
        if (pageCount <= 0) {
            return;
        }

        List<FormattedText> pages = new ArrayList<>();
        for (int i = 0; i < pageCount; i++) {
            pages.add(this.bookAccess.getPageRaw(i));
        }

        List<PageData> pageData = BookTranslationHelper.buildPageDataFromFormatted(pages);
        if (!simple_translate$bookFeature.start(pageData)) {
            simple_translate$restoreOriginal();
            return;
        }
        this.cachedPage = -1;
    }

    @Unique
    private void simple_translate$restoreOriginal() {
        simple_translate$bookFeature.reset();
        this.cachedPage = -1;
    }

    @Unique
    private boolean simple_translate$isBookmarkVisible() {
        return ModConfig.GLOBAL_ENABLED.get() && ModConfig.CONTENT_BOOK_ENABLED.get();
    }

    @Unique
    private void simple_translate$renderBookmark(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!simple_translate$isBookmarkVisible()) {
            return;
        }

        BookBookmarkControl.render(graphics, this.font, this.width, mouseX, mouseY,
                simple_translate$bookFeature.active(), simple_translate$bookFeature.translating());
    }

    @Unique
    private boolean simple_translate$isMouseOverBookmark(double mouseX, double mouseY) {
        return BookBookmarkControl.isMouseOver(this.width, mouseX, mouseY);
    }
}

