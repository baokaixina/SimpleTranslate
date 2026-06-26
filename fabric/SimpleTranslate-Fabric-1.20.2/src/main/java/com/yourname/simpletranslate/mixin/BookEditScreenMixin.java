package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.book.BookFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.feature.book.BookBookmarkControl;
import com.yourname.simpletranslate.feature.book.BookTranslationHelper;
import com.yourname.simpletranslate.feature.book.BookTranslationHelper.PageData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(BookEditScreen.class)
public abstract class BookEditScreenMixin extends Screen {

    @Shadow
    @Final
    private List<String> pages;

    @Shadow
    private int currentPage;

    @Shadow
    private boolean isSigning;

    @Shadow
    protected abstract String getCurrentPageText();

    @Shadow
    protected abstract void clearDisplayCache();

    @Unique
    private BookFeature simple_translate$bookFeature = new BookFeature();

    protected BookEditScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void simple_translate$onRenderHead(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            simple_translate$invalidateTranslation();
            return;
        }
        if (simple_translate$bookFeature.stateChanged()) {
            this.clearDisplayCache();
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void simple_translate$onRenderTail(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        simple_translate$renderBookmark(graphics, mouseX, mouseY);
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

    @Inject(method = "setCurrentPageText", at = @At("TAIL"))
    private void simple_translate$onPageTextChanged(String text, CallbackInfo ci) {
        simple_translate$invalidateTranslation();
    }

    @Inject(method = "appendPageToBook", at = @At("TAIL"))
    private void simple_translate$onAppendPage(CallbackInfo ci) {
        simple_translate$invalidateTranslation();
    }

    @Redirect(method = "rebuildDisplayCache",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/BookEditScreen;getCurrentPageText()Ljava/lang/String;"))
    private String simple_translate$redirectCurrentPageText(BookEditScreen self) {
        return simple_translate$getDisplayPageText();
    }

    @Redirect(method = "rebuildDisplayCache",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/font/TextFieldHelper;getCursorPos()I"))
    private int simple_translate$clampCursorPos(TextFieldHelper helper) {
        int cursorPos = helper.getCursorPos();
        if (!ModConfig.GLOBAL_ENABLED.get()
                || !simple_translate$bookFeature.active()
                || this.isSigning) {
            return cursorPos;
        }
        String displayText = simple_translate$getDisplayPageText();
        int max = displayText != null ? displayText.length() : 0;
        return Math.min(cursorPos, max);
    }

    @Redirect(method = "rebuildDisplayCache",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/font/TextFieldHelper;getSelectionPos()I"))
    private int simple_translate$clampSelectionPos(TextFieldHelper helper) {
        int selectionPos = helper.getSelectionPos();
        if (!ModConfig.GLOBAL_ENABLED.get()
                || !simple_translate$bookFeature.active()
                || this.isSigning) {
            return selectionPos;
        }
        String displayText = simple_translate$getDisplayPageText();
        int max = displayText != null ? displayText.length() : 0;
        return Math.min(selectionPos, max);
    }

    @Unique
    private void simple_translate$onTranslateBookmarkPressed() {
        if (simple_translate$bookFeature.active()) {
            simple_translate$invalidateTranslation();
        } else {
            simple_translate$startTranslation();
        }
    }

    @Unique
    private String simple_translate$getDisplayPageText() {
        String original = this.getCurrentPageText();
        if (!ModConfig.GLOBAL_ENABLED.get()
                || !simple_translate$bookFeature.active()
                || this.isSigning) {
            return original;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.BOOK)) {
            return original;
        }

        if (!simple_translate$bookFeature.pageNeedsTranslation(currentPage)) {
            return original;
        }

        List<Component> translatedPages = simple_translate$bookFeature.translatedPages();
        if (translatedPages != null && currentPage >= 0 && currentPage < translatedPages.size()) {
            return translatedPages.get(currentPage).getString();
        }

        if (simple_translate$bookFeature.translating()) {
            return BookTranslationHelper.getTranslatingText();
        }

        return original;
    }

    @Unique
    private void simple_translate$startTranslation() {
        if (!ModConfig.GLOBAL_ENABLED.get()
                || !ModConfig.CONTENT_BOOK_ENABLED.get()
                || this.pages == null
                || this.pages.isEmpty()) {
            return;
        }

        List<PageData> pageData = BookTranslationHelper.buildPageDataFromStrings(this.pages);
        if (!simple_translate$bookFeature.start(pageData)) {
            simple_translate$invalidateTranslation();
            return;
        }
        this.clearDisplayCache();
    }

    @Unique
    private void simple_translate$invalidateTranslation() {
        if (!simple_translate$bookFeature.active()) {
            return;
        }
        simple_translate$bookFeature.reset();
        this.clearDisplayCache();
    }

    @Unique
    private boolean simple_translate$isBookmarkVisible() {
        return ModConfig.GLOBAL_ENABLED.get() && ModConfig.CONTENT_BOOK_ENABLED.get() && !this.isSigning;
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
