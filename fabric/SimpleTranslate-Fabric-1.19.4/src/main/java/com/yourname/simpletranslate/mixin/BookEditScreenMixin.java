package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.BookBookmarkControl;
import com.yourname.simpletranslate.util.BookTranslationHelper;
import com.yourname.simpletranslate.util.BookTranslationHelper.PageData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.compat.GuiGraphics;
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

import java.util.ArrayList;
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
    private boolean simple_translate$translationActive = false;

    @Unique
    private String simple_translate$bookKey;

    @Unique
    private List<Boolean> simple_translate$pageNeedsTranslation = new ArrayList<>();

    @Unique
    private boolean simple_translate$lastTranslating;

    @Unique
    private boolean simple_translate$lastHasTranslation;

    protected BookEditScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void simple_translate$onRenderHead(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!simple_translate$translationActive || simple_translate$bookKey == null) {
            return;
        }

        boolean translating = BookTranslationHelper.isTranslating(simple_translate$bookKey);
        boolean hasTranslation = BookTranslationHelper.getTranslatedPages(simple_translate$bookKey) != null;

        if (translating != simple_translate$lastTranslating || hasTranslation != simple_translate$lastHasTranslation) {
            this.clearDisplayCache();
            simple_translate$lastTranslating = translating;
            simple_translate$lastHasTranslation = hasTranslation;
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
        if (!simple_translate$translationActive || simple_translate$bookKey == null || this.isSigning) {
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
        if (!simple_translate$translationActive || simple_translate$bookKey == null || this.isSigning) {
            return selectionPos;
        }
        String displayText = simple_translate$getDisplayPageText();
        int max = displayText != null ? displayText.length() : 0;
        return Math.min(selectionPos, max);
    }

    @Unique
    private void simple_translate$onTranslateBookmarkPressed() {
        if (simple_translate$translationActive) {
            simple_translate$invalidateTranslation();
        } else {
            simple_translate$startTranslation();
        }
    }

    @Unique
    private String simple_translate$getDisplayPageText() {
        String original = this.getCurrentPageText();
        if (!simple_translate$translationActive || simple_translate$bookKey == null || this.isSigning) {
            return original;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.BOOK)) {
            return original;
        }

        if (!simple_translate$pageNeedsTranslation(currentPage)) {
            return original;
        }

        List<Component> translatedPages = BookTranslationHelper.getTranslatedPages(simple_translate$bookKey);
        if (translatedPages != null && currentPage >= 0 && currentPage < translatedPages.size()) {
            return translatedPages.get(currentPage).getString();
        }

        if (BookTranslationHelper.isTranslating(simple_translate$bookKey)) {
            return BookTranslationHelper.getTranslatingText();
        }

        return original;
    }

    @Unique
    private void simple_translate$startTranslation() {
        if (!ModConfig.CONTENT_BOOK_ENABLED.get() || this.pages == null || this.pages.isEmpty()) {
            return;
        }

        List<PageData> pageData = BookTranslationHelper.buildPageDataFromStrings(this.pages);
        List<String> pageTexts = new ArrayList<>();
        List<Boolean> needs = new ArrayList<>();
        boolean hasEnglish = false;

        for (PageData page : pageData) {
            pageTexts.add(page.plainText);
            needs.add(page.needsTranslation);
            if (page.needsTranslation) {
                hasEnglish = true;
            }
        }

        if (!hasEnglish) {
            simple_translate$invalidateTranslation();
            return;
        }

        simple_translate$bookKey = BookTranslationHelper.buildBookKey(pageTexts);
        simple_translate$pageNeedsTranslation = needs;
        simple_translate$translationActive = true;
        simple_translate$lastTranslating = false;
        simple_translate$lastHasTranslation = false;

        BookTranslationHelper.requestTranslation(simple_translate$bookKey, pageData);
        this.clearDisplayCache();
    }

    @Unique
    private void simple_translate$invalidateTranslation() {
        if (!simple_translate$translationActive) {
            return;
        }

        simple_translate$translationActive = false;
        simple_translate$bookKey = null;
        simple_translate$pageNeedsTranslation.clear();
        simple_translate$lastTranslating = false;
        simple_translate$lastHasTranslation = false;
        this.clearDisplayCache();
    }

    @Unique
    private boolean simple_translate$pageNeedsTranslation(int pageIndex) {
        return pageIndex >= 0
                && pageIndex < simple_translate$pageNeedsTranslation.size()
                && simple_translate$pageNeedsTranslation.get(pageIndex);
    }

    @Unique
    private boolean simple_translate$isBookmarkVisible() {
        return ModConfig.CONTENT_BOOK_ENABLED.get() && !this.isSigning;
    }

    @Unique
    private void simple_translate$renderBookmark(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!simple_translate$isBookmarkVisible()) {
            return;
        }

        boolean translating = simple_translate$translationActive
                && simple_translate$bookKey != null
                && BookTranslationHelper.isTranslating(simple_translate$bookKey);

        BookBookmarkControl.render(graphics, this.font, this.width, mouseX, mouseY,
                simple_translate$translationActive, translating);
    }

    @Unique
    private boolean simple_translate$isMouseOverBookmark(double mouseX, double mouseY) {
        return BookBookmarkControl.isMouseOver(this.width, mouseX, mouseY);
    }
}

