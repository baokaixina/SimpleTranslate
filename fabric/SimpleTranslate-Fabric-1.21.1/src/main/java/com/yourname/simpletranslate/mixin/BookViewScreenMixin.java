package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.BookBookmarkControl;
import com.yourname.simpletranslate.util.BookTranslationHelper;
import com.yourname.simpletranslate.util.BookTranslationHelper.PageData;
import net.minecraft.client.gui.GuiGraphics;
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
    private boolean simple_translate$translationActive = false;

    @Unique
    private String simple_translate$bookKey;

    @Unique
    private List<Boolean> simple_translate$pageNeedsTranslation = new ArrayList<>();

    @Unique
    private boolean simple_translate$lastTranslating;

    @Unique
    private boolean simple_translate$lastHasTranslation;

    protected BookViewScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "setBookAccess", at = @At("TAIL"))
    private void simple_translate$onSetBookAccess(BookViewScreen.BookAccess access, CallbackInfo ci) {
        simple_translate$translationActive = false;
        simple_translate$bookKey = null;
        simple_translate$pageNeedsTranslation.clear();
        simple_translate$lastTranslating = false;
        simple_translate$lastHasTranslation = false;
        this.cachedPage = -1;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void simple_translate$onRenderHead(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!simple_translate$translationActive || simple_translate$bookKey == null) {
            return;
        }

        boolean translating = BookTranslationHelper.isTranslating(simple_translate$bookKey);
        boolean hasTranslation = BookTranslationHelper.getTranslatedPages(simple_translate$bookKey) != null;

        if (translating != simple_translate$lastTranslating || hasTranslation != simple_translate$lastHasTranslation) {
            this.cachedPage = -1;
            simple_translate$lastTranslating = translating;
            simple_translate$lastHasTranslation = hasTranslation;
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

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/BookViewScreen$BookAccess;getPage(I)Lnet/minecraft/network/chat/FormattedText;"))
    private FormattedText simple_translate$redirectPageText(BookViewScreen.BookAccess access, int pageIndex) {
        FormattedText original = access.getPage(pageIndex);
        if (!simple_translate$translationActive || !ModConfig.CONTENT_BOOK_ENABLED.get() || simple_translate$bookKey == null) {
            return original;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.BOOK)) {
            return original;
        }

        List<Component> translatedPages = BookTranslationHelper.getTranslatedPages(simple_translate$bookKey);
        if (translatedPages != null && pageIndex >= 0 && pageIndex < translatedPages.size()) {
            return translatedPages.get(pageIndex);
        }

        if (BookTranslationHelper.isTranslating(simple_translate$bookKey)
                && simple_translate$pageNeedsTranslation(pageIndex)) {
            Component originalComponent = (original instanceof Component) ? (Component) original : null;
            return BookTranslationHelper.buildTranslatingComponent(originalComponent);
        }

        return original;
    }

    @Unique
    private void simple_translate$onTranslateBookmarkPressed() {
        if (simple_translate$translationActive) {
            simple_translate$restoreOriginal();
        } else {
            simple_translate$startTranslation();
        }
    }

    @Unique
    private void simple_translate$startTranslation() {
        if (!ModConfig.CONTENT_BOOK_ENABLED.get() || this.bookAccess == null) {
            return;
        }

        int pageCount = this.bookAccess.getPageCount();
        if (pageCount <= 0) {
            return;
        }

        List<FormattedText> pages = new ArrayList<>();
        for (int i = 0; i < pageCount; i++) {
            pages.add(this.bookAccess.pages().get(i));
        }

        List<PageData> pageData = BookTranslationHelper.buildPageDataFromFormatted(pages);
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
            simple_translate$restoreOriginal();
            return;
        }

        simple_translate$bookKey = BookTranslationHelper.buildBookKey(pageTexts);
        simple_translate$pageNeedsTranslation = needs;
        simple_translate$translationActive = true;
        simple_translate$lastTranslating = false;
        simple_translate$lastHasTranslation = false;

        BookTranslationHelper.requestTranslation(simple_translate$bookKey, pageData);
        this.cachedPage = -1;
    }

    @Unique
    private boolean simple_translate$pageNeedsTranslation(int pageIndex) {
        return pageIndex >= 0
                && pageIndex < simple_translate$pageNeedsTranslation.size()
                && simple_translate$pageNeedsTranslation.get(pageIndex);
    }

    @Unique
    private void simple_translate$restoreOriginal() {
        simple_translate$translationActive = false;
        simple_translate$bookKey = null;
        simple_translate$pageNeedsTranslation.clear();
        simple_translate$lastTranslating = false;
        simple_translate$lastHasTranslation = false;
        this.cachedPage = -1;
    }

    @Unique
    private boolean simple_translate$isBookmarkVisible() {
        return ModConfig.CONTENT_BOOK_ENABLED.get();
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
