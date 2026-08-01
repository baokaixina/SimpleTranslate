package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.book.BookFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import com.yourname.simpletranslate.feature.book.BookBookmarkControl;
import com.yourname.simpletranslate.feature.book.BookTranslationHelper;
import com.yourname.simpletranslate.feature.book.BookTranslationHelper.PageData;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationTriggerState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.lwjgl.glfw.GLFW;
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

    @Unique
    private BookViewScreen.BookAccess simple_translate$originalBookAccess;

    @Unique
    private boolean simple_translate$bookmarkMouseDown;

    protected BookViewScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void simple_translate$armHoveredTooltipTranslation(
            int keyCode, int scanCode, int modifiers,
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
    private void simple_translate$onRenderHead(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (simple_translate$bookFeature.stateChanged()) {
            this.cachedPage = -1;
        }
        simple_translate$swapTranslatedBookAccess();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void simple_translate$onRenderTail(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        simple_translate$renderBookmark(graphics, mouseX, mouseY);
        simple_translate$handleBookmarkMouse(mouseX, mouseY);
        simple_translate$restoreBookAccessAfterRender();
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/BookViewScreen$BookAccess;getPage(I)Lnet/minecraft/network/chat/FormattedText;"),
            require = 0)
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
            return BookTranslationHelper.buildTranslatingComponent(simple_translate$pageComponent(original));
        }

        return original;
    }

    @Unique
    private void simple_translate$swapTranslatedBookAccess() {
        if (simple_translate$originalBookAccess != null
                || this.bookAccess == null
                || !ModConfig.GLOBAL_ENABLED.get()
                || !ModConfig.CONTENT_BOOK_ENABLED.get()
                || !simple_translate$bookFeature.active()
                || HoldOriginalState.isHolding(HoldOriginalFeature.BOOK)) {
            return;
        }
        List<FormattedText> pages = new ArrayList<>();
        List<Component> translatedPages = simple_translate$bookFeature.translatedPages();
        int pageCount = this.bookAccess.getPageCount();
        for (int i = 0; i < pageCount; i++) {
            FormattedText original = this.bookAccess.getPage(i);
            if (translatedPages != null && i >= 0 && i < translatedPages.size()) {
                pages.add(translatedPages.get(i));
            } else if (simple_translate$bookFeature.translating()
                    && simple_translate$bookFeature.pageNeedsTranslation(i)) {
                pages.add(BookTranslationHelper.buildTranslatingComponent(simple_translate$pageComponent(original)));
            } else {
                pages.add(original);
            }
        }
        simple_translate$originalBookAccess = this.bookAccess;
        // 1.20 BookAccess is an interface; supply the swapped pages through an
        // anonymous implementation (1.21.x has a concrete record instead).
        this.bookAccess = new BookViewScreen.BookAccess() {
            @Override
            public int getPageCount() {
                return pages.size();
            }

            @Override
            public FormattedText getPageRaw(int index) {
                return pages.get(index);
            }
        };
        this.cachedPage = -1;
    }

    @Unique
    private static Component simple_translate$pageComponent(FormattedText page) {
        return page instanceof Component component ? component
                : Component.literal(page == null ? "" : page.getString());
    }

    @Unique
    private void simple_translate$restoreBookAccessAfterRender() {
        if (simple_translate$originalBookAccess == null) {
            return;
        }
        this.bookAccess = simple_translate$originalBookAccess;
        simple_translate$originalBookAccess = null;
        this.cachedPage = -1;
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

    @Unique
    private void simple_translate$handleBookmarkMouse(double mouseX, double mouseY) {
        long window = Minecraft.getInstance().getWindow().getWindow();
        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (down && !simple_translate$bookmarkMouseDown
                && simple_translate$isBookmarkVisible()
                && simple_translate$isMouseOverBookmark(mouseX, mouseY)) {
            simple_translate$onTranslateBookmarkPressed();
        }
        simple_translate$bookmarkMouseDown = down;
    }
}
