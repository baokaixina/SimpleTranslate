package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.AdvancementTranslationHelper;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin to translate advancement toast text (title and description).
 */
@Mixin(AdvancementToast.class)
public class AdvancementToastMixin {

    @Shadow @Final private Advancement advancement;

    @Unique
    private int simple_translate$titleLineIndex;

    @Unique
    private String simple_translate$titleLinesCacheKey;

    @Unique
    private List<FormattedCharSequence> simple_translate$translatedTitleLines;

    @Inject(method = "render", at = @At("HEAD"))
    private void simple_translate$onRenderStart(PoseStack poseStack, ToastComponent toastComponent, long timeSinceLastVisible, CallbackInfoReturnable<Toast.Visibility> cir) {
        if (ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            DisplayInfo display = this.advancement.getDisplay();
            if (display != null) {
                simple_translate$titleLineIndex = 0;
                String title = display.getTitle().getString();
                String desc = display.getDescription().getString();
                SimpleTranslateMod.getLogger().debug("AdvancementToast rendering: title='{}', desc='{}'", title, desc);
                AdvancementTranslationHelper.ensureTranslation(
                        simple_translate$advancementKey(display.getTitle(), display.getDescription()),
                        display.getTitle(), display.getDescription());
            }
        }
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"),
            require = 1
    )
    private int simple_translate$redirectComponentDraw(Font font, PoseStack poseStack, Component component, float x, float y, int color) {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()) {
            return font.draw(poseStack, component, x, y, color);
        }
        return font.draw(poseStack, simple_translate$translateComponent(component), x, y, color);
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/util/FormattedCharSequence;FFI)I"),
            require = 1
    )
    private int simple_translate$redirectFormattedDraw(Font font, PoseStack poseStack, FormattedCharSequence text, float x, float y, int color) {
        FormattedCharSequence translated = simple_translate$getNextTranslatedTitleLine(font);
        if (translated != null) {
            return font.draw(poseStack, translated, x, y, color);
        }
        return font.draw(poseStack, text, x, y, color);
    }

    @Unique
    private FormattedCharSequence simple_translate$getNextTranslatedTitleLine(Font font) {
        if (!ModConfig.CONTENT_ADVANCEMENT_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)
                || this.advancement == null) {
            return null;
        }
        DisplayInfo display = this.advancement.getDisplay();
        if (display == null || display.getTitle() == null) {
            return null;
        }

        Component title = display.getTitle();
        Component translated = AdvancementTranslationHelper.getCachedTitleComponent(title);
        if (translated == null || translated.getString().equals(title.getString())) {
            return null;
        }

        String cacheKey = title.getString() + "|" + translated.getString();
        if (!cacheKey.equals(this.simple_translate$titleLinesCacheKey) || this.simple_translate$translatedTitleLines == null) {
            this.simple_translate$titleLinesCacheKey = cacheKey;
            this.simple_translate$translatedTitleLines = font.split(translated, 125);
        }
        if (this.simple_translate$translatedTitleLines == null
                || this.simple_translate$titleLineIndex >= this.simple_translate$translatedTitleLines.size()) {
            return null;
        }
        return this.simple_translate$translatedTitleLines.get(this.simple_translate$titleLineIndex++);
    }

    @Unique
    private Component simple_translate$translateComponent(Component component) {
        if (component == null) {
            return null;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.ADVANCEMENT)) {
            return component;
        }

        String text = component.getString();
        if (text.isEmpty() || !TooltipTranslationHelper.containsEnglish(text)) {
            return component;
        }

        return AdvancementTranslationHelper.translateComponent(component,
                "advancement.toast.component.direct", "advancement-toast");
    }

    @Unique
    private String simple_translate$advancementKey(Component title, Component description) {
        if (this.advancement != null && this.advancement.getId() != null) {
            return "advancement:" + this.advancement.getId();
        }
        String titleText = title == null ? "" : title.getString();
        String descriptionText = description == null ? "" : description.getString();
        return "advancement:document:" + titleText.hashCode() + ":" + descriptionText.hashCode();
    }
}
