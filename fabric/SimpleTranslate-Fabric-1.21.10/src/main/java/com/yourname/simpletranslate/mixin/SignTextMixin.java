package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.SignTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Function;

@Mixin(SignText.class)
public abstract class SignTextMixin {

    @Shadow
    @Final
    private Component[] messages;

    @Inject(method = "getRenderMessages", at = @At("HEAD"), cancellable = true)
    private void simple_translate$onGetRenderMessages(
            boolean filtered,
            Function<Component, FormattedCharSequence> transformer,
            CallbackInfoReturnable<FormattedCharSequence[]> cir
    ) {
        if (!ModConfig.CONTENT_SIGN_ENABLED.get()) {
            return;
        }
        if (HoldOriginalState.isHolding(HoldOriginalFeature.SIGN)) {
            return;
        }

        SignTranslationHelper.SignTextIdentityData data =
                SignTranslationHelper.getSignTextDataByIdentity(System.identityHashCode(this));
        if (data == null || data.translatedComponents == null || data.translatedComponents.length != 4
                || data.isTranslating) {
            return;
        }

        Component[] logicalLines = simple_translate$buildLogicalLines(data);
        FormattedCharSequence[] result = new FormattedCharSequence[4];
        Font font = Minecraft.getInstance().font;
        int maxWidth = data.maxTextLineWidth > 0 ? data.maxTextLineWidth : 90;
        int renderedLines = 0;

        for (int lineIndex = 0; lineIndex < logicalLines.length && renderedLines < result.length; lineIndex++) {
            Component logicalLine = logicalLines[lineIndex];
            if (simple_translate$isEmpty(logicalLine)) {
                result[renderedLines++] = FormattedCharSequence.EMPTY;
                continue;
            }

            List<FormattedCharSequence> wrappedLines = simple_translate$splitLine(logicalLine, maxWidth, font, transformer);
            if (wrappedLines.isEmpty()) {
                result[renderedLines++] = FormattedCharSequence.EMPTY;
                continue;
            }

            int remainingVisibleLines = simple_translate$countRemainingVisibleLines(logicalLines, lineIndex + 1);
            int maxRowsForThisLine = Math.max(1, result.length - renderedLines - remainingVisibleLines);
            int rowsToCopy = Math.min(maxRowsForThisLine, wrappedLines.size());
            for (int i = 0; i < rowsToCopy && renderedLines < result.length; i++) {
                result[renderedLines++] = wrappedLines.get(i);
            }
        }

        while (renderedLines < result.length) {
            result[renderedLines++] = FormattedCharSequence.EMPTY;
        }

        cir.setReturnValue(result);
    }

    private Component[] simple_translate$buildLogicalLines(SignTranslationHelper.SignTextIdentityData data) {
        Component[] logicalLines = new Component[4];
        for (int i = 0; i < logicalLines.length; i++) {
            Component translated = data.translatedComponents[i];
            logicalLines[i] = translated == null ? simple_translate$getOriginalLine(i) : translated;
        }
        return logicalLines;
    }

    private Component simple_translate$getOriginalLine(int lineIndex) {
        if (this.messages == null || lineIndex < 0 || lineIndex >= this.messages.length || this.messages[lineIndex] == null) {
            return Component.empty();
        }
        return this.messages[lineIndex];
    }

    private List<FormattedCharSequence> simple_translate$splitLine(
            Component line,
            int maxWidth,
            Font font,
            Function<Component, FormattedCharSequence> transformer
    ) {
        if (font == null || maxWidth <= 0) {
            return List.of(transformer.apply(line));
        }

        if (font.width(line) <= maxWidth) {
            return List.of(transformer.apply(line));
        }

        List<FormattedCharSequence> wrapped = font.split(line, maxWidth);
        if (wrapped == null || wrapped.isEmpty()) {
            return List.of(transformer.apply(line));
        }
        return wrapped;
    }

    private int simple_translate$countRemainingVisibleLines(Component[] logicalLines, int startIndex) {
        int count = 0;
        for (int i = startIndex; i < logicalLines.length; i++) {
            if (!simple_translate$isEmpty(logicalLines[i])) {
                count++;
            }
        }
        return count;
    }

    private boolean simple_translate$isEmpty(Component component) {
        return component == null || component.getString().isEmpty();
    }
}
