package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.chat.ChatTranslationController;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact targets: GuiNewChat optional-deletion output and direct line deletion. */
@Mixin(GuiNewChat.class)
public abstract class GuiNewChatMixin {
    @Inject(
            method = "printChatMessageWithOptionalDeletion(Lnet/minecraft/util/text/ITextComponent;I)V",
            at = @At("HEAD"),
            cancellable = true)
    private void simpletranslate$retainChatLine(ITextComponent message, int deletionId, CallbackInfo callback) {
        if (ChatTranslationController.isInternalPrint()) return;
        ChatTranslationController.invalidateExternalReplacement(deletionId);
        if (ChatTranslationController.shouldAttachButton(message)) {
            ChatTranslationController.ButtonPresentation presentation =
                    ChatTranslationController.attachButton(message, deletionId);
            ChatTranslationController.printInternal(
                    (GuiNewChat) (Object) this, presentation.message, presentation.id);
            callback.cancel();
            return;
        }
        if (!ChatTranslationController.shouldRetain(message)) return;
        int displayId = ChatTranslationController.retain(message, deletionId);
        ChatTranslationController.printInternal((GuiNewChat) (Object) this, message, displayId);
        callback.cancel();
    }

    @Inject(method = "deleteChatLine(I)V", at = @At("HEAD"))
    private void simpletranslate$invalidateDeletedChatLine(int deletionId, CallbackInfo callback) {
        if (!ChatTranslationController.isInternalPrint()) {
            ChatTranslationController.invalidateExternalReplacement(deletionId);
        }
    }

    /**
     * setChatLine also calls deleteChatLine while refreshChat rebuilds wrapped rows. Scope only
     * that exact internal invocation so resizing chat cannot discard live translation state.
     */
    @Redirect(
            method = "setChatLine(Lnet/minecraft/util/text/ITextComponent;IIZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiNewChat;deleteChatLine(I)V"))
    private void simpletranslate$deleteBeforeSetChatLine(GuiNewChat chat, int deletionId) {
        ChatTranslationController.deleteInternal(chat, deletionId);
    }

    @Inject(method = "clearChatMessages(Z)V", at = @At("HEAD"))
    private void simpletranslate$invalidateClearedChat(boolean clearSentMessages, CallbackInfo callback) {
        if (!ChatTranslationController.isInternalPrint()) {
            ChatTranslationController.clearRuntimeState();
        }
    }
}
