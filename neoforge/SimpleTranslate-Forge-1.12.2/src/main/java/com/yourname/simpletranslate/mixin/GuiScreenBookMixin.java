package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.book.BookTranslationBookmarkControl;
import com.yourname.simpletranslate.translation.TranslationEngine;
import com.yourname.simpletranslate.feature.book.BookTranslationSession;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.nbt.NBTTagList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.12.2 read-mode book translation. GuiScreenBook#pageGetCurrent() is only
 * invoked from the edit-mode paths (keyTypedInBook, pageInsertIntoCurrent —
 * verified from the exact 2860 mapped-jar bytecode), so the previous RETURN
 * injection there was dead code for signed books. drawScreen(IIF)V fetches
 * the visible page through exactly one
 * {@code NBTTagList#getStringTagAt(I)Ljava/lang/String;} call (insn 425 in
 * the 2860 mapped jar); redirect it and translate only view-mode pages.
 * Editing keeps the original NBT text.
 */
@Mixin(GuiScreenBook.class)
public abstract class GuiScreenBookMixin {
    @Shadow @Final private boolean bookIsUnsigned;
    @Shadow private boolean bookGettingSigned;
    @Shadow private NBTTagList bookPages;
    @Shadow private int currPage;

    /** The modern visible bookmark owns whether this book currently shows translations. */
    @Unique private boolean simpletranslate$translationBookmarkActive;
    @Unique private String simpletranslate$bookTranslationKey;
    @Unique private boolean simpletranslate$bookTranslationWasPending;
    @Unique private boolean simpletranslate$captureUnsignedEdit;
    @Unique private String simpletranslate$unsignedPageBeforeEdit;

    @Redirect(
            method = "drawScreen(IIF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/nbt/NBTTagList;getStringTagAt(I)Ljava/lang/String;"
            )
    )
    private String simpletranslate$translateReadOnlyPage(NBTTagList list, int index) {
        String original = list.getStringTagAt(index);
        if (!simpletranslate$translationBookmarkActive
                || HoldOriginalState.isHolding(HoldOriginalFeature.BOOK)) return original;
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (engine == null || !engine.isConfigured() || !engine.isSurfaceEnabled("book")
                || original == null || original.isEmpty()) return original;
        return bookIsUnsigned
                ? BookTranslationSession.translatedPlainPage(simpletranslate$bookTranslationKey, index, original)
                : BookTranslationSession.translatedPage(simpletranslate$bookTranslationKey, index, original);
    }

    /** Exact target drawScreen(IIF)V ends after vanilla book text and widgets. */
    @Inject(method = "drawScreen(IIF)V", at = @At("TAIL"))
    private void simpletranslate$renderTranslationBookmark(int mouseX, int mouseY, float partialTicks,
                                                           CallbackInfo callback) {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        if (bookGettingSigned || engine == null || !engine.isSurfaceEnabled("book")) return;
        GuiScreenBook screen = (GuiScreenBook) (Object) this;
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        if (font == null) return;
        if (simpletranslate$translationBookmarkActive && bookPages != null
                && !BookTranslationSession.isPending(simpletranslate$bookTranslationKey)
                && !BookTranslationSession.isReady(simpletranslate$bookTranslationKey)
                && !BookTranslationSession.isRejected(simpletranslate$bookTranslationKey)) {
            simpletranslate$bookTranslationKey = BookTranslationSession.request(bookPages);
        }
        if (BookTranslationSession.isRejected(simpletranslate$bookTranslationKey)) {
            simpletranslate$translationBookmarkActive = false;
        }
        boolean pending = simpletranslate$translationBookmarkActive
                && BookTranslationSession.isPending(simpletranslate$bookTranslationKey);
        if (simpletranslate$bookTranslationWasPending && !pending) {
            // GuiScreenBook caches the rendered page string. Invalidate it as
            // soon as the async request settles so the next frame shows the
            // translated page (or restores the original after a failure).
            ((GuiScreenBookAccessor) (Object) this).simpletranslate$setCachedPage(-1);
        }
        simpletranslate$bookTranslationWasPending = pending;
        BookTranslationBookmarkControl.render(font, screen.width, mouseX, mouseY,
                simpletranslate$translationBookmarkActive,
                pending);
    }

    /** Exact target mouseClicked(III)V is cancellable before vanilla text-click handling. */
    @Inject(method = "mouseClicked(III)V", at = @At("HEAD"), cancellable = true)
    private void simpletranslate$clickTranslationBookmark(int mouseX, int mouseY, int mouseButton,
                                                           CallbackInfo callback) {
        TranslationEngine engine = SimpleTranslateForge1122.getEngine();
        GuiScreenBook screen = (GuiScreenBook) (Object) this;
        if (mouseButton != 0 || bookGettingSigned || engine == null || !engine.isSurfaceEnabled("book")
                || !BookTranslationBookmarkControl.isMouseOver(screen.width, mouseX, mouseY)) return;
        simpletranslate$translationBookmarkActive = !simpletranslate$translationBookmarkActive;
        ((GuiScreenBookAccessor) (Object) this).simpletranslate$setCachedPage(-1);
        if (simpletranslate$translationBookmarkActive && bookPages != null) {
            simpletranslate$bookTranslationKey = BookTranslationSession.request(bookPages);
            if (BookTranslationSession.isRejected(simpletranslate$bookTranslationKey)) {
                simpletranslate$translationBookmarkActive = false;
            }
        }
        callback.cancel();
    }

    /** Snapshot the backing page; only a real mutation invalidates translated edit-mode display. */
    @Inject(method = "keyTypedInBook(CI)V", at = @At("HEAD"))
    private void simpletranslate$beforeUnsignedEdit(char typedChar, int keyCode, CallbackInfo callback) {
        simpletranslate$captureUnsignedEdit = bookIsUnsigned && simpletranslate$translationBookmarkActive
                && bookPages != null && currPage >= 0 && currPage < bookPages.tagCount();
        simpletranslate$unsignedPageBeforeEdit = simpletranslate$captureUnsignedEdit
                ? bookPages.getStringTagAt(currPage) : null;
    }

    @Inject(method = "keyTypedInBook(CI)V", at = @At("RETURN"))
    private void simpletranslate$afterUnsignedEdit(char typedChar, int keyCode, CallbackInfo callback) {
        if (!simpletranslate$captureUnsignedEdit) return;
        simpletranslate$captureUnsignedEdit = false;
        String current = bookPages != null && currPage >= 0 && currPage < bookPages.tagCount()
                ? bookPages.getStringTagAt(currPage) : null;
        if (simpletranslate$unsignedPageBeforeEdit == null
                ? current == null : simpletranslate$unsignedPageBeforeEdit.equals(current)) return;
        simpletranslate$unsignedPageBeforeEdit = null;
        simpletranslate$translationBookmarkActive = false;
        simpletranslate$bookTranslationKey = null;
        simpletranslate$bookTranslationWasPending = false;
        ((GuiScreenBookAccessor) (Object) this).simpletranslate$setCachedPage(-1);
    }
}
