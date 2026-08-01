package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.feature.chat.ChatComponentAccess;
import com.yourname.simpletranslate.feature.chat.ChatTranslationController;
import com.yourname.simpletranslate.keybind.HoldOriginalAware;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.core.BlacklistRefreshAware;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.feature.chat.ChatButtonClickHandler;
import com.yourname.simpletranslate.feature.chat.HudHistoryChatBridge;
import com.yourname.simpletranslate.feature.hud.HudTranslationHistory;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Thin chat mixin: only vanilla injection points plus delegation into
 * {@link ChatTranslationController}. All translation logic lives in the
 * {@code chat} package.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin
        implements ChatButtonClickHandler, HudHistoryChatBridge, HoldOriginalAware, BlacklistRefreshAware,
        ChatComponentAccess {

    @Shadow
    @Final
    private List<GuiMessage> allMessages;

    @Shadow
    public abstract void rescaleChat();

    @Unique
    private ChatTranslationController simple_translate$controller;

    @Unique
    private ChatTranslationController simple_translate$controller() {
        if (simple_translate$controller == null) {
            simple_translate$controller = new ChatTranslationController(this);
        }
        return simple_translate$controller;
    }

    @Override
    public List<GuiMessage> simpleTranslateAllMessages() {
        return allMessages;
    }

    @Override
    public void simpleTranslateRescale() {
        this.rescaleChat();
    }

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("TAIL"))
    private void onAddMessage(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        SafeTranslate.guard(() -> simple_translate$controller().onAddMessage(message), "chat.onAddMessage");
    }

    @Override
    public boolean simple_translate$handleButtonClickEvent(String clickValue) {
        return simple_translate$controller().handleButtonClickEvent(clickValue);
    }

    @Override
    public boolean simple_translate$showVisibleOriginalMessages() {
        return simple_translate$controller().showVisibleOriginalMessages();
    }

    @Override
    public void simple_translate$upsertHudHistoryCaption(HudTranslationHistory.Entry entry) {
        simple_translate$controller().upsertHudHistoryCaption(entry);
    }

    @Override
    public void simple_translate$onHoldOriginalChanged(HoldOriginalFeature feature, boolean holding) {
        simple_translate$controller().onHoldOriginalChanged(feature, holding);
    }

    @Override
    public boolean simple_translate$refreshBlacklistedTranslations() {
        return simple_translate$controller().refreshBlacklistedTranslations();
    }
}
