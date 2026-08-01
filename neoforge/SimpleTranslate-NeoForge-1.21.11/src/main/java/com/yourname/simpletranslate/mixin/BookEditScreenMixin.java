package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.book.BookFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.feature.book.BookBookmarkControl;
import com.yourname.simpletranslate.feature.book.BookTranslationHelper;
import com.yourname.simpletranslate.feature.book.BookTranslationHelper.PageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    private void updatePageContent() {
    }

    @Unique
    private BookFeature simple_translate$bookFeature = new BookFeature();

    @Unique
    private boolean simple_translate$suppressPageWrite;

    @Unique
    private boolean simple_translate$bookmarkMouseDown;

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
            this.updatePageContent();
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void simple_translate$onRenderTail(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        simple_translate$renderBookmark(graphics, mouseX, mouseY);
        simple_translate$handleBookmarkMouse(mouseX, mouseY);
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
            if (simple_translate$suppressPageWrite) {
                return;
            }
            listener.accept(value);
            simple_translate$invalidateTranslation();
        });
    }

    @Redirect(method = "updatePageContent",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/MultiLineEditBox;setValue(Ljava/lang/String;Z)V"))
    private void simple_translate$setDisplayPageText(MultiLineEditBox box, String text, boolean bypassLineLimit) {
        simple_translate$suppressPageWrite = true;
        try {
            box.setValue(simple_translate$getDisplayPageText(text), bypassLineLimit);
        } finally {
            simple_translate$suppressPageWrite = false;
        }
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
    private String simple_translate$getDisplayPageText(String original) {
        if (!ModConfig.GLOBAL_ENABLED.get()
                || !simple_translate$bookFeature.active()) {
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
        this.updatePageContent();
    }

    @Unique
    private void simple_translate$invalidateTranslation() {
        if (!simple_translate$bookFeature.active()) {
            return;
        }
        simple_translate$bookFeature.reset();
        this.updatePageContent();
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
        long window = Minecraft.getInstance().getWindow().handle();
        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (down && !simple_translate$bookmarkMouseDown
                && simple_translate$isBookmarkVisible()
                && simple_translate$isMouseOverBookmark(mouseX, mouseY)) {
            simple_translate$onTranslateBookmarkPressed();
        }
        simple_translate$bookmarkMouseDown = down;
    }
}
