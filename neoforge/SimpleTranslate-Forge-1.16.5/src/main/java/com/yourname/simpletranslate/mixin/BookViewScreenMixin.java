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
import com.mojang.blaze3d.matrix.MatrixStack;
import com.yourname.simpletranslate.core.render.GuiGraphics;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ReadBookScreen;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.ITextProperties;
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

@Mixin(ReadBookScreen.class)
public abstract class BookViewScreenMixin extends Screen {

    @Shadow
    private ReadBookScreen.IBookInfo bookAccess;

    @Shadow
    private int cachedPage;

    @Unique
    private BookFeature simple_translate$bookFeature = new BookFeature();

    @Unique
    private ReadBookScreen.IBookInfo simple_translate$originalBookAccess;

    @Unique
    private boolean simple_translate$bookmarkMouseDown;

    protected BookViewScreenMixin(ITextComponent title) {
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
    private void simple_translate$onSetBookAccess(ReadBookScreen.IBookInfo access, CallbackInfo ci) {
        simple_translate$bookFeature.reset();
        this.cachedPage = -1;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void simple_translate$onRenderHead(MatrixStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (simple_translate$bookFeature.stateChanged()) {
            this.cachedPage = -1;
        }
        simple_translate$swapTranslatedBookAccess();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void simple_translate$onRenderTail(MatrixStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        simple_translate$renderBookmark(GuiGraphics.wrap(poseStack), mouseX, mouseY);
        simple_translate$handleBookmarkMouse(mouseX, mouseY);
        simple_translate$restoreBookAccessAfterRender();
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/ReadBookScreen$IBookInfo;getPage(I)Lnet/minecraft/util/text/ITextProperties;"),
            require = 0)
    private ITextProperties simple_translate$redirectPageText(ReadBookScreen.IBookInfo access, int pageIndex) {
        ITextProperties original = access.getPage(pageIndex);
        if (!ModConfig.GLOBAL_ENABLED.get()
                || !ModConfig.CONTENT_BOOK_ENABLED.get()
                || !simple_translate$bookFeature.active()) {
            return original;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.BOOK)) {
            return original;
        }

        List<ITextComponent> translatedPages = simple_translate$bookFeature.translatedPages();
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
        List<ITextProperties> pages = new ArrayList<>();
        List<ITextComponent> translatedPages = simple_translate$bookFeature.translatedPages();
        int pageCount = this.bookAccess.getPageCount();
        for (int i = 0; i < pageCount; i++) {
            ITextProperties original = this.bookAccess.getPage(i);
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
        // 1.20.1 BookAccess is an interface; supply the swapped pages through an
        // anonymous implementation (1.21.x has a concrete record instead).
        this.bookAccess = new ReadBookScreen.IBookInfo() {
            @Override
            public int getPageCount() {
                return pages.size();
            }

            @Override
            public ITextProperties getPageRaw(int index) {
                return pages.get(index);
            }
        };
        this.cachedPage = -1;
    }

    @Unique
    private static ITextComponent simple_translate$pageComponent(ITextProperties page) {
        return page instanceof ITextComponent component ? component
                : com.yourname.simpletranslate.core.LegacyComponentFactory.literal(page == null ? "" : page.getString());
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

        List<ITextProperties> pages = new ArrayList<>();
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
