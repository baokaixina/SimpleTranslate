package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.BookBookmarkControl;
import com.yourname.simpletranslate.util.BookTranslationHelper;
import com.yourname.simpletranslate.util.BookTranslationHelper.PageData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Mixin(BookEditScreen.class)
public abstract class BookEditScreenMixin extends Screen {

    @Shadow
    @Final
    private List<String> pages;

    @Shadow
    private int currentPage;

    @Shadow
    private MultiLineEditBox page;

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

    @Unique
    private boolean simple_translate$programmaticPageUpdate;

    protected BookEditScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void simple_translate$onRenderHead(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!simple_translate$translationActive || simple_translate$bookKey == null) {
            return;
        }

        boolean translating = BookTranslationHelper.isTranslating(simple_translate$bookKey);
        boolean hasTranslation = BookTranslationHelper.getTranslatedPages(simple_translate$bookKey) != null;

        if (translating != simple_translate$lastTranslating || hasTranslation != simple_translate$lastHasTranslation) {
            simple_translate$refreshDisplayedPageText();
            simple_translate$lastTranslating = translating;
            simple_translate$lastHasTranslation = hasTranslation;
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void simple_translate$onRenderTail(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        simple_translate$renderBookmark(graphics, mouseX, mouseY);
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (event.button() == 0
                && simple_translate$isBookmarkVisible()
                && simple_translate$isMouseOverBookmark(mouseX, mouseY)) {
            simple_translate$onTranslateBookmarkPressed();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Inject(method = "appendPageToBook", at = @At("TAIL"))
    private void simple_translate$onAppendPage(CallbackInfo ci) {
        simple_translate$invalidateTranslation();
    }

    @Redirect(method = "init",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/MultiLineEditBox;setValueListener(Ljava/util/function/Consumer;)V"))
    private void simple_translate$wrapPageValueListener(MultiLineEditBox box, Consumer<String> listener) {
        box.setValueListener(value -> {
            if (simple_translate$programmaticPageUpdate) {
                return;
            }
            if (simple_translate$translationActive && value != null && value.equals(simple_translate$getOriginalPageText())) {
                listener.accept(value);
                return;
            }
            simple_translate$clearTranslationState();
            listener.accept(value);
        });
    }

    @Inject(method = "updatePageContent", at = @At("TAIL"))
    private void simple_translate$onUpdatePageContent(CallbackInfo ci) {
        simple_translate$refreshDisplayedPageText();
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
        String original = simple_translate$getOriginalPageText();
        if (!simple_translate$translationActive || simple_translate$bookKey == null) {
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
        simple_translate$refreshDisplayedPageText();
    }

    @Unique
    private void simple_translate$invalidateTranslation() {
        if (!simple_translate$translationActive) {
            return;
        }

        simple_translate$clearTranslationState();
        simple_translate$refreshDisplayedPageText();
    }

    @Unique
    private void simple_translate$clearTranslationState() {
        simple_translate$translationActive = false;
        simple_translate$bookKey = null;
        simple_translate$pageNeedsTranslation.clear();
        simple_translate$lastTranslating = false;
        simple_translate$lastHasTranslation = false;
    }

    @Unique
    private boolean simple_translate$pageNeedsTranslation(int pageIndex) {
        return pageIndex >= 0
                && pageIndex < simple_translate$pageNeedsTranslation.size()
                && simple_translate$pageNeedsTranslation.get(pageIndex);
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

    @Unique
    private String simple_translate$getOriginalPageText() {
        if (this.pages == null || this.currentPage < 0 || this.currentPage >= this.pages.size()) {
            return "";
        }
        String original = this.pages.get(this.currentPage);
        return original != null ? original : "";
    }

    @Unique
    private void simple_translate$refreshDisplayedPageText() {
        if (this.page == null) {
            return;
        }
        String display = simple_translate$getDisplayPageText();
        if (display == null) {
            display = "";
        }
        if (display.equals(this.page.getValue())) {
            return;
        }
        simple_translate$programmaticPageUpdate = true;
        try {
            this.page.setValue(display, true);
        } finally {
            simple_translate$programmaticPageUpdate = false;
        }
    }
}
