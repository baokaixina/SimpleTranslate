package com.yourname.simpletranslate.mixin;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalAware;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.util.BlacklistRefreshAware;
import com.yourname.simpletranslate.util.ComponentSegmentHelper;
import com.yourname.simpletranslate.util.DirectSurfaceTranslator;
import com.yourname.simpletranslate.util.HudTranslationHistory;
import com.yourname.simpletranslate.util.TextSegmentInfo;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(Gui.class)
public abstract class TitleOverlayMixin implements HoldOriginalAware, BlacklistRefreshAware {
    @Unique
    private static final String TITLE_GROUP_SURFACE = "hud.title_group.component.direct";

    @Unique
    private static final String ACTIONBAR_SURFACE = "hud.actionbar.component.direct";

    @Unique
    private static final Pattern ACTIONBAR_DYNAMIC_VALUE_PATTERN =
            Pattern.compile("\\(?[+-]?\\d+(?:\\.\\d+)?(?:/[+-]?\\d+(?:\\.\\d+)?)*%?\\)?");

    @Unique
    private static final Pattern ACTIONBAR_PLACEHOLDER_PATTERN = Pattern.compile("@@(\\d+)@@");

    @Shadow
    @Nullable
    protected Component title;

    @Shadow
    @Nullable
    protected Component subtitle;

    @Shadow
    @Nullable
    protected Component overlayMessageString;

    @Unique
    @Nullable
    private Component simple_translate$originalTitle;

    @Unique
    @Nullable
    private Component simple_translate$originalSubtitle;

    @Unique
    @Nullable
    private Component simple_translate$originalOverlay;

    @Unique
    @Nullable
    private Component simple_translate$translatedTitle;

    @Unique
    @Nullable
    private Component simple_translate$translatedSubtitle;

    @Unique
    @Nullable
    private Component simple_translate$translatedOverlay;

    @Unique
    @Nullable
    private Component simple_translate$translatedOverlayTemplate;

    @Unique
    @Nullable
    private String simple_translate$titleImmediateSourceKey;

    @Unique
    @Nullable
    private String simple_translate$titleGroupKey;

    @Unique
    @Nullable
    private String simple_translate$titleCaptionSourceKey;

    @Unique
    @Nullable
    private String simple_translate$subtitleCaptionSourceKey;

    @Unique
    @Nullable
    private String simple_translate$titleHistoryKey;

    @Unique
    @Nullable
    private String simple_translate$subtitleHistoryKey;

    @Unique
    @Nullable
    private String simple_translate$overlayKey;

    @Unique
    @Nullable
    private String simple_translate$overlayHistoryKey;

    @Unique
    private long simple_translate$hudHistorySequence;

    @Unique
    private long simple_translate$seenRuntimeRevision = -1L;

    @Unique
    private boolean simple_translate$seenCaptionBatchMode;

    @Unique
    @Nullable
    private Set<String> simple_translate$pendingTitleHistoryKeys;

    @Unique
    @Nullable
    private Set<String> simple_translate$pendingActionbarHistoryKeys;

    @Inject(method = "setTitle", at = @At("TAIL"))
    private void simple_translate$onSetTitle(Component title, CallbackInfo ci) {
        simple_translate$syncRuntimeRevision();
        simple_translate$syncCaptionMode();
        this.simple_translate$originalTitle = title;
        this.simple_translate$translatedTitle = null;
        this.simple_translate$titleImmediateSourceKey = null;
        this.simple_translate$titleGroupKey = null;
        if (simple_translate$captionBatchMode()) {
            simple_translate$recordTitleCaption();
        } else {
            this.simple_translate$titleCaptionSourceKey = null;
            this.simple_translate$titleHistoryKey = null;
        }
    }

    @Inject(method = "setSubtitle", at = @At("TAIL"))
    private void simple_translate$onSetSubtitle(Component subtitle, CallbackInfo ci) {
        simple_translate$syncRuntimeRevision();
        simple_translate$syncCaptionMode();
        this.simple_translate$originalSubtitle = subtitle;
        this.simple_translate$translatedSubtitle = null;
        this.simple_translate$titleImmediateSourceKey = null;
        this.simple_translate$titleGroupKey = null;
        if (simple_translate$captionBatchMode()) {
            simple_translate$recordSubtitleCaption();
        } else {
            this.simple_translate$subtitleCaptionSourceKey = null;
            this.simple_translate$subtitleHistoryKey = null;
        }
    }

    @Inject(method = "setOverlayMessage", at = @At("TAIL"))
    private void simple_translate$onSetOverlayMessage(Component component, boolean animateColor, CallbackInfo ci) {
        simple_translate$syncRuntimeRevision();
        simple_translate$syncCaptionMode();
        this.simple_translate$originalOverlay = component;
        ActionbarTemplate template = simple_translate$buildActionbarTemplate(component);
        String currentKey = simple_translate$overlayKey(template);
        if (!currentKey.equals(this.simple_translate$overlayKey)) {
            this.simple_translate$translatedOverlay = null;
            this.simple_translate$translatedOverlayTemplate = null;
            this.simple_translate$overlayKey = currentKey;
            this.simple_translate$overlayHistoryKey = null;
        }
        if (simple_translate$captionBatchMode()) {
            simple_translate$recordActionbarCaption(template, currentKey);
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void simple_translate$onRender(GuiGraphics graphics, float tickDelta, CallbackInfo ci) {
        simple_translate$syncRuntimeRevision();
        simple_translate$syncCaptionMode();
        if (simple_translate$captionBatchMode()) {
            simple_translate$refreshTitleGroupFromCaptionBatch();
            simple_translate$refreshOverlayFromCaptionBatch();
            HudTranslationHistory.tickTranslator(System.currentTimeMillis());
        } else {
            simple_translate$refreshTitleGroupImmediate();
            simple_translate$refreshOverlayImmediate();
        }
    }

    @Inject(method = "clear", at = @At("TAIL"))
    private void simple_translate$onClear(CallbackInfo ci) {
        this.simple_translate$originalTitle = null;
        this.simple_translate$originalSubtitle = null;
        this.simple_translate$originalOverlay = null;
        simple_translate$clearLocalTranslations();
        this.simple_translate$overlayKey = null;
        this.simple_translate$overlayHistoryKey = null;
        simple_translate$pendingTitleHistoryKeys().clear();
        simple_translate$pendingActionbarHistoryKeys().clear();
    }

    @Unique
    private void simple_translate$syncRuntimeRevision() {
        long revision = SimpleTranslateMod.getRuntimeRevision();
        if (this.simple_translate$seenRuntimeRevision == revision) {
            return;
        }
        this.simple_translate$seenRuntimeRevision = revision;
        this.simple_translate$seenCaptionBatchMode = simple_translate$captionBatchMode();
        simple_translate$clearLocalTranslations();
        this.simple_translate$overlayKey = simple_translate$overlayKey(this.simple_translate$originalOverlay);
        this.simple_translate$overlayHistoryKey = null;
        simple_translate$pendingTitleHistoryKeys().clear();
        simple_translate$pendingActionbarHistoryKeys().clear();
    }

    @Unique
    private void simple_translate$syncCaptionMode() {
        boolean batchMode = simple_translate$captionBatchMode();
        if (this.simple_translate$seenCaptionBatchMode == batchMode) {
            return;
        }
        this.simple_translate$seenCaptionBatchMode = batchMode;
        simple_translate$clearLocalTranslations();
        this.simple_translate$overlayKey = simple_translate$overlayKey(this.simple_translate$originalOverlay);
        this.simple_translate$overlayHistoryKey = null;
        simple_translate$pendingTitleHistoryKeys().clear();
        simple_translate$pendingActionbarHistoryKeys().clear();
    }

    @Unique
    private boolean simple_translate$captionBatchMode() {
        return ModConfig.HUD_TITLE_CONTEXT_ENABLED.get() || ModConfig.HUD_HISTORY_CHAT_ENABLED.get();
    }

    @Unique
    private void simple_translate$clearLocalTranslations() {
        this.simple_translate$translatedTitle = null;
        this.simple_translate$translatedSubtitle = null;
        this.simple_translate$translatedOverlay = null;
        this.simple_translate$translatedOverlayTemplate = null;
        this.simple_translate$titleImmediateSourceKey = null;
        this.simple_translate$titleGroupKey = null;
        this.simple_translate$titleCaptionSourceKey = null;
        this.simple_translate$subtitleCaptionSourceKey = null;
        this.simple_translate$titleHistoryKey = null;
        this.simple_translate$subtitleHistoryKey = null;
    }

    @Unique
    private Set<String> simple_translate$pendingTitleHistoryKeys() {
        if (this.simple_translate$pendingTitleHistoryKeys == null) {
            this.simple_translate$pendingTitleHistoryKeys = ConcurrentHashMap.newKeySet();
        }
        return this.simple_translate$pendingTitleHistoryKeys;
    }

    @Unique
    private Set<String> simple_translate$pendingActionbarHistoryKeys() {
        if (this.simple_translate$pendingActionbarHistoryKeys == null) {
            this.simple_translate$pendingActionbarHistoryKeys = ConcurrentHashMap.newKeySet();
        }
        return this.simple_translate$pendingActionbarHistoryKeys;
    }

    @Unique
    private String simple_translate$nextHudHistoryKey(String kind) {
        return SimpleTranslateMod.getRuntimeRevision() + "\u0000" + kind + "\u0000" + (++this.simple_translate$hudHistorySequence);
    }

    @Unique
    private void simple_translate$refreshTitleGroupFromCaptionBatch() {
        if (!ModConfig.HUD_TITLE_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.TITLE)) {
            this.title = this.simple_translate$originalTitle;
            this.subtitle = this.simple_translate$originalSubtitle;
            return;
        }

        simple_translate$recordTitleCaption();
        simple_translate$recordSubtitleCaption();

        this.title = simple_translate$currentCaptionTranslation(
                this.simple_translate$titleHistoryKey,
                this.simple_translate$originalTitle,
                true);
        this.subtitle = simple_translate$currentCaptionTranslation(
                this.simple_translate$subtitleHistoryKey,
                this.simple_translate$originalSubtitle,
                false);
    }

    @Unique
    @Nullable
    private Component simple_translate$currentCaptionTranslation(@Nullable String historyKey,
                                                                @Nullable Component original,
                                                                boolean titleSlot) {
        if (!simple_translate$shouldTranslateHudComponent(original, false) || historyKey == null) {
            return original;
        }
        Component translated = HudTranslationHistory.translatedComponent(historyKey);
        if (translated == null) {
            return original;
        }
        if (titleSlot) {
            this.simple_translate$translatedTitle = translated;
        } else {
            this.simple_translate$translatedSubtitle = translated;
        }
        return translated;
    }

    @Unique
    private void simple_translate$recordTitleCaption() {
        simple_translate$recordTextCaption(
                HudTranslationHistory.CaptionType.TITLE,
                this.simple_translate$originalTitle,
                "title",
                true);
    }

    @Unique
    private void simple_translate$recordSubtitleCaption() {
        simple_translate$recordTextCaption(
                HudTranslationHistory.CaptionType.SUBTITLE,
                this.simple_translate$originalSubtitle,
                "subtitle",
                false);
    }

    @Unique
    private void simple_translate$recordTextCaption(HudTranslationHistory.CaptionType type,
                                                    @Nullable Component original,
                                                    String kind,
                                                    boolean titleSlot) {
        if (!ModConfig.HUD_TITLE_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.TITLE)
                || !simple_translate$shouldTranslateHudComponent(original, false)) {
            if (titleSlot) {
                this.simple_translate$titleCaptionSourceKey = null;
                this.simple_translate$titleHistoryKey = null;
                this.simple_translate$translatedTitle = null;
            } else {
                this.simple_translate$subtitleCaptionSourceKey = null;
                this.simple_translate$subtitleHistoryKey = null;
                this.simple_translate$translatedSubtitle = null;
            }
            return;
        }
        String sourceKey = simple_translate$componentSourceKey(kind, original);
        String previousSourceKey = titleSlot
                ? this.simple_translate$titleCaptionSourceKey
                : this.simple_translate$subtitleCaptionSourceKey;
        String historyKey = titleSlot
                ? this.simple_translate$titleHistoryKey
                : this.simple_translate$subtitleHistoryKey;
        if (!sourceKey.equals(previousSourceKey) || historyKey == null) {
            historyKey = simple_translate$nextHudHistoryKey(kind);
            if (titleSlot) {
                this.simple_translate$titleCaptionSourceKey = sourceKey;
                this.simple_translate$titleHistoryKey = historyKey;
                this.simple_translate$translatedTitle = null;
            } else {
                this.simple_translate$subtitleCaptionSourceKey = sourceKey;
                this.simple_translate$subtitleHistoryKey = historyKey;
                this.simple_translate$translatedSubtitle = null;
            }
        }
        HudTranslationHistory.recordCaption(type, historyKey, sourceKey, original, original);
    }

    @Unique
    private void simple_translate$refreshTitleGroupImmediate() {
        if (!ModConfig.HUD_TITLE_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.TITLE)) {
            this.title = this.simple_translate$originalTitle;
            this.subtitle = this.simple_translate$originalSubtitle;
            return;
        }

        boolean translateTitle = simple_translate$shouldTranslateHudComponent(this.simple_translate$originalTitle, false);
        boolean translateSubtitle = simple_translate$shouldTranslateHudComponent(this.simple_translate$originalSubtitle, false);
        String sourceKey = simple_translate$titleImmediateSourceKey(translateTitle, translateSubtitle);
        if (!sourceKey.equals(this.simple_translate$titleImmediateSourceKey)) {
            this.simple_translate$translatedTitle = null;
            this.simple_translate$translatedSubtitle = null;
            this.simple_translate$titleImmediateSourceKey = sourceKey;
            this.simple_translate$titleGroupKey = null;
        }

        if (!translateTitle && !translateSubtitle) {
            this.title = this.simple_translate$originalTitle;
            this.subtitle = this.simple_translate$originalSubtitle;
            return;
        }

        String groupKey = sourceKey;
        if (!groupKey.equals(this.simple_translate$titleGroupKey)) {
            this.simple_translate$titleGroupKey = groupKey;
        }
        if ((translateTitle && this.simple_translate$translatedTitle == null)
                || (translateSubtitle && this.simple_translate$translatedSubtitle == null)) {
            List<Component> originals = new ArrayList<>(2);
            List<Boolean> titleSlots = new ArrayList<>(2);
            if (translateTitle) {
                originals.add(this.simple_translate$originalTitle);
                titleSlots.add(Boolean.TRUE);
            }
            if (translateSubtitle) {
                originals.add(this.simple_translate$originalSubtitle);
                titleSlots.add(Boolean.FALSE);
            }
            simple_translate$requestTitleGroupAsync(originals, titleSlots, groupKey, translateTitle, translateSubtitle);
        }

        this.title = translateTitle && this.simple_translate$translatedTitle != null
                ? this.simple_translate$translatedTitle
                : this.simple_translate$originalTitle;
        this.subtitle = translateSubtitle && this.simple_translate$translatedSubtitle != null
                ? this.simple_translate$translatedSubtitle
                : this.simple_translate$originalSubtitle;
    }

    @Unique
    private void simple_translate$requestTitleGroupAsync(List<Component> originals,
                                                         List<Boolean> titleSlots,
                                                         String groupKey,
                                                         boolean translateTitle,
                                                         boolean translateSubtitle) {
        Set<String> pendingTitleKeys = simple_translate$pendingTitleHistoryKeys();
        if (!pendingTitleKeys.add(groupKey)) {
            return;
        }
        DirectSurfaceTranslator.translateComponentsAsync(
                        List.copyOf(originals),
                        TITLE_GROUP_SURFACE,
                        "title-subtitle",
                        false,
                        "")
                .whenComplete((direct, error) -> {
                    pendingTitleKeys.remove(groupKey);
                    if (error != null || direct == null || !direct.handled || !direct.translated
                            || direct.components == null || direct.components.size() != originals.size()) {
                        return;
                    }
                    Component translatedTitle = null;
                    Component translatedSubtitle = null;
                    for (int i = 0; i < direct.components.size(); i++) {
                        if (titleSlots.get(i)) {
                            translatedTitle = direct.components.get(i);
                        } else {
                            translatedSubtitle = direct.components.get(i);
                        }
                    }
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft != null) {
                        Component finalTranslatedTitle = translatedTitle;
                        Component finalTranslatedSubtitle = translatedSubtitle;
                        minecraft.execute(() -> {
                            if (!groupKey.equals(this.simple_translate$titleGroupKey)) {
                                return;
                            }
                            if (translateTitle) {
                                this.simple_translate$translatedTitle = finalTranslatedTitle;
                            }
                            if (translateSubtitle) {
                                this.simple_translate$translatedSubtitle = finalTranslatedSubtitle;
                            }
                        });
                    }
                });
    }

    @Unique
    private void simple_translate$refreshOverlayFromCaptionBatch() {
        Component original = this.simple_translate$originalOverlay;
        if (original == null) {
            this.overlayMessageString = null;
            this.simple_translate$overlayKey = null;
            this.simple_translate$overlayHistoryKey = null;
            return;
        }
        ActionbarTemplate actionbarTemplate = simple_translate$buildActionbarTemplate(original);
        String currentKey = simple_translate$overlayKey(actionbarTemplate);
        if (!currentKey.equals(this.simple_translate$overlayKey)) {
            this.simple_translate$translatedOverlay = null;
            this.simple_translate$translatedOverlayTemplate = null;
            this.simple_translate$overlayKey = currentKey;
            this.simple_translate$overlayHistoryKey = null;
        }
        if (!ModConfig.HUD_ACTIONBAR_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.ACTIONBAR)) {
            this.overlayMessageString = original;
            return;
        }
        if (!simple_translate$shouldTranslateHudComponent(original, true)) {
            this.overlayMessageString = original;
            return;
        }

        simple_translate$recordActionbarCaption(actionbarTemplate, currentKey);
        if (this.simple_translate$overlayHistoryKey != null) {
            Component translatedTemplate = HudTranslationHistory.translatedRequestComponent(this.simple_translate$overlayHistoryKey);
            if (translatedTemplate != null) {
                Component restored = simple_translate$restoreActionbarVariables(
                        translatedTemplate,
                        actionbarTemplate.variables());
                if (restored != null) {
                    this.simple_translate$translatedOverlayTemplate = translatedTemplate;
                    this.simple_translate$translatedOverlay = restored;
                    this.overlayMessageString = restored;
                    return;
                }
            }
        }
        this.overlayMessageString = original;
    }

    @Unique
    private void simple_translate$recordActionbarCaption(ActionbarTemplate actionbarTemplate, String currentKey) {
        if (!ModConfig.HUD_ACTIONBAR_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.ACTIONBAR)
                || !simple_translate$shouldTranslateHudComponent(this.simple_translate$originalOverlay, true)) {
            return;
        }
        if (this.simple_translate$overlayHistoryKey == null) {
            this.simple_translate$overlayHistoryKey = simple_translate$nextHudHistoryKey("actionbar");
        }
        String historyKey = this.simple_translate$overlayHistoryKey;
        HudTranslationHistory.recordCaption(
                HudTranslationHistory.CaptionType.ACTIONBAR,
                historyKey,
                currentKey,
                this.simple_translate$originalOverlay,
                actionbarTemplate.component(),
                translated -> simple_translate$restoreActionbarVariables(translated, actionbarTemplate.variables()));
    }

    @Unique
    private void simple_translate$refreshOverlayImmediate() {
        Component original = this.simple_translate$originalOverlay;
        if (original == null) {
            this.overlayMessageString = null;
            this.simple_translate$overlayKey = null;
            this.simple_translate$overlayHistoryKey = null;
            return;
        }
        ActionbarTemplate actionbarTemplate = simple_translate$buildActionbarTemplate(original);
        String currentKey = simple_translate$overlayKey(actionbarTemplate);
        if (!currentKey.equals(this.simple_translate$overlayKey)) {
            this.simple_translate$translatedOverlay = null;
            this.simple_translate$translatedOverlayTemplate = null;
            this.simple_translate$overlayKey = currentKey;
            this.simple_translate$overlayHistoryKey = null;
        }
        if (!ModConfig.HUD_ACTIONBAR_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.ACTIONBAR)) {
            this.overlayMessageString = original;
            return;
        }
        if (this.simple_translate$translatedOverlayTemplate != null) {
            Component restored = simple_translate$restoreActionbarVariables(
                    this.simple_translate$translatedOverlayTemplate,
                    actionbarTemplate.variables());
            if (restored != null) {
                this.simple_translate$translatedOverlay = restored;
                this.overlayMessageString = restored;
                return;
            }
            this.simple_translate$translatedOverlay = null;
            this.simple_translate$translatedOverlayTemplate = null;
        }
        if (this.simple_translate$translatedOverlay != null && actionbarTemplate.variables().isEmpty()) {
            this.overlayMessageString = this.simple_translate$translatedOverlay;
            return;
        }
        if (!simple_translate$shouldTranslateHudComponent(original, true)) {
            this.overlayMessageString = original;
            return;
        }
        simple_translate$requestActionbarAsync(original, actionbarTemplate, currentKey);
        this.overlayMessageString = original;
    }

    @Unique
    private void simple_translate$requestActionbarAsync(Component original,
                                                       ActionbarTemplate actionbarTemplate,
                                                       String currentKey) {
        Set<String> pendingActionbarKeys = simple_translate$pendingActionbarHistoryKeys();
        if (!pendingActionbarKeys.add(currentKey)) {
            return;
        }
        DirectSurfaceTranslator.translateComponentsAsync(
                        List.of(actionbarTemplate.component()),
                        ACTIONBAR_SURFACE,
                        "actionbar",
                        true,
                        "")
                .whenComplete((direct, error) -> {
                    pendingActionbarKeys.remove(currentKey);
                    if (error != null || direct == null || !direct.handled || !direct.translated
                            || direct.components == null || direct.components.size() != 1) {
                        return;
                    }
                    Component translatedTemplate = direct.components.get(0);
                    Component restored = simple_translate$restoreActionbarVariables(
                            translatedTemplate,
                            actionbarTemplate.variables());
                    if (restored == null) {
                        return;
                    }
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft != null) {
                        minecraft.execute(() -> {
                            if (!currentKey.equals(this.simple_translate$overlayKey)) {
                                return;
                            }
                            this.simple_translate$translatedOverlayTemplate = translatedTemplate;
                            this.simple_translate$translatedOverlay = restored;
                            this.overlayMessageString = restored;
                        });
                    }
                });
    }

    @Unique
    private boolean simple_translate$shouldTranslateHudComponent(@Nullable Component original,
            boolean skipTechnicalHudText) {
        if (original == null) {
            return false;
        }
        String text = original.getString();
        return text != null
                && !text.isBlank()
                && TooltipTranslationHelper.containsEnglish(text)
                && !TooltipTranslationHelper.isBlacklisted(text)
                && (!skipTechnicalHudText || !simple_translate$isTechnicalHudText(text));
    }

    @Unique
    private String simple_translate$titleImmediateSourceKey(boolean translateTitle, boolean translateSubtitle) {
        String titleText = this.simple_translate$originalTitle == null ? "" : this.simple_translate$originalTitle.getString();
        String subtitleText = this.simple_translate$originalSubtitle == null ? "" : this.simple_translate$originalSubtitle.getString();
        return SimpleTranslateMod.getRuntimeRevision() + "\u0000"
                + translateTitle + "\u0001" + titleText + "\u0001" + simple_translate$componentStyleSignature(this.simple_translate$originalTitle)
                + "\u0002" + translateSubtitle + "\u0001" + subtitleText + "\u0001" + simple_translate$componentStyleSignature(this.simple_translate$originalSubtitle);
    }

    @Unique
    private String simple_translate$componentSourceKey(String kind, @Nullable Component component) {
        String text = component == null ? "" : component.getString();
        return SimpleTranslateMod.getRuntimeRevision() + "\u0000"
                + kind + "\u0000"
                + simple_translate$cleanText(text) + "\u0001"
                + simple_translate$componentStyleSignature(component);
    }

    @Unique
    private String simple_translate$overlayKey(@Nullable Component original) {
        return simple_translate$overlayKey(simple_translate$buildActionbarTemplate(original));
    }

    @Unique
    private String simple_translate$overlayKey(ActionbarTemplate actionbarTemplate) {
        Component component = actionbarTemplate == null ? null : actionbarTemplate.component();
        return simple_translate$componentSourceKey("actionbar", component);
    }

    @Unique
    private String simple_translate$componentStyleSignature(@Nullable Component component) {
        if (component == null) {
            return "";
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
        if (segments.isEmpty()) {
            return simple_translate$styleSignature(component.getStyle());
        }
        StringBuilder signature = new StringBuilder();
        for (TextSegmentInfo segment : segments) {
            if (segment == null) {
                continue;
            }
            signature.append(simple_translate$cleanText(segment.text)).append('@')
                    .append(simple_translate$styleSignature(segment.style == null ? Style.EMPTY : segment.style))
                    .append('\u0002');
        }
        return signature.toString();
    }

    @Unique
    private String simple_translate$styleSignature(Style style) {
        Style effective = style == null ? Style.EMPTY : style;
        String color = effective.getColor() == null ? "" : Integer.toString(effective.getColor().getValue());
        return "c=" + color
                + ";b=" + effective.isBold()
                + ";i=" + effective.isItalic()
                + ";u=" + effective.isUnderlined()
                + ";s=" + effective.isStrikethrough()
                + ";o=" + effective.isObfuscated();
    }

    @Unique
    private String simple_translate$cleanText(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ");
    }

    @Unique
    private ActionbarTemplate simple_translate$buildActionbarTemplate(@Nullable Component original) {
        if (original == null) {
            return new ActionbarTemplate(Component.empty(), List.of());
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(original, segments, Style.EMPTY, false);
        MutableComponent normalized = Component.empty();
        List<ActionbarVariable> variables = new ArrayList<>();
        if (segments.isEmpty()) {
            simple_translate$appendActionbarTemplateText(normalized, original.getString(), Style.EMPTY, variables);
        } else {
            for (TextSegmentInfo segment : segments) {
                if (segment == null || segment.text == null || segment.text.isEmpty()) {
                    continue;
                }
                simple_translate$appendActionbarTemplateText(
                        normalized,
                        segment.text,
                        segment.style == null ? Style.EMPTY : segment.style,
                        variables);
            }
        }
        return new ActionbarTemplate(normalized, List.copyOf(variables));
    }

    @Unique
    private void simple_translate$appendActionbarTemplateText(MutableComponent target, String text, Style style,
            List<ActionbarVariable> variables) {
        if (target == null || text == null || text.isEmpty()) {
            return;
        }
        Style effectiveStyle = style == null ? Style.EMPTY : style;
        Matcher matcher = ACTIONBAR_DYNAMIC_VALUE_PATTERN.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                target.append(Component.literal(text.substring(cursor, matcher.start())).withStyle(effectiveStyle));
            }
            String value = matcher.group();
            String placeholder = simple_translate$actionbarPlaceholder(variables.size());
            variables.add(new ActionbarVariable(placeholder, value, effectiveStyle));
            target.append(Component.literal(placeholder).withStyle(effectiveStyle));
            cursor = matcher.end();
        }
        if (cursor < text.length()) {
            target.append(Component.literal(text.substring(cursor)).withStyle(effectiveStyle));
        }
    }

    @Unique
    private String simple_translate$actionbarPlaceholder(int index) {
        return "@@" + index + "@@";
    }

    @Unique
    @Nullable
    private Component simple_translate$restoreActionbarVariables(Component translatedTemplate,
            List<ActionbarVariable> variables) {
        if (translatedTemplate == null) {
            return null;
        }
        if (variables == null || variables.isEmpty()) {
            return translatedTemplate;
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(translatedTemplate, segments, Style.EMPTY, false);
        MutableComponent restored = Component.empty();
        int restoredVariables = 0;
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null || segment.text.isEmpty()) {
                continue;
            }
            Style style = segment.style == null ? Style.EMPTY : segment.style;
            Matcher matcher = ACTIONBAR_PLACEHOLDER_PATTERN.matcher(segment.text);
            int cursor = 0;
            while (matcher.find()) {
                if (matcher.start() > cursor) {
                    restored.append(Component.literal(segment.text.substring(cursor, matcher.start())).withStyle(style));
                }
                ActionbarVariable variable = simple_translate$actionbarVariable(variables, matcher.group(1));
                if (variable == null) {
                    restored.append(Component.literal(matcher.group()).withStyle(style));
                } else {
                    restored.append(Component.literal(variable.value()).withStyle(variable.style()));
                    restoredVariables++;
                }
                cursor = matcher.end();
            }
            if (cursor < segment.text.length()) {
                restored.append(Component.literal(segment.text.substring(cursor)).withStyle(style));
            }
        }
        return restoredVariables == variables.size() ? restored : null;
    }

    @Unique
    @Nullable
    private ActionbarVariable simple_translate$actionbarVariable(List<ActionbarVariable> variables, String indexText) {
        try {
            int index = Integer.parseInt(indexText);
            if (index < 0 || index >= variables.size()) {
                return null;
            }
            return variables.get(index);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Unique
    private boolean simple_translate$isTechnicalHudText(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String withoutSymbols = text.replaceAll("[\\p{Punct}\\s\\u00A7§※✥✦✧❤♥☼☽☾◆◇■□▶▷◀◁|/\\\\]+", "");
        if (withoutSymbols.isEmpty()) {
            return true;
        }
        String words = withoutSymbols.replaceAll("[0-9]+", "");
        if (words.matches("(?i)^(N|S|E|W|NE|NW|SE|SW|HP|MP|XP|ATK|DEF|SPD|DPS|DOT|HPS|LV|LVL)+$")) {
            return true;
        }
        String[] tokens = text.replaceAll("[^A-Za-z0-9/]+", " ").trim().split("\\s+");
        int meaningfulWords = 0;
        int technicalWords = 0;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (token.matches("[0-9/]+")) {
                continue;
            }
            meaningfulWords++;
            if (token.matches("(?i)N|S|E|W|NE|NW|SE|SW|HP|MP|XP|ATK|DEF|SPD|DPS|DOT|HPS|LV|LVL")) {
                technicalWords++;
            }
        }
        return meaningfulWords > 0 && technicalWords >= meaningfulWords && meaningfulWords <= 2;
    }

    @Override
    public void simple_translate$onHoldOriginalChanged(HoldOriginalFeature feature, boolean holding) {
        try {
            switch (feature) {
                case TITLE -> {
                    if (simple_translate$captionBatchMode()) {
                        simple_translate$refreshTitleGroupFromCaptionBatch();
                    } else {
                        simple_translate$refreshTitleGroupImmediate();
                    }
                }
                case ACTIONBAR -> {
                    if (simple_translate$captionBatchMode()) {
                        simple_translate$refreshOverlayFromCaptionBatch();
                    } else {
                        simple_translate$refreshOverlayImmediate();
                    }
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            SimpleTranslateMod.getLogger().error("Title/ActionBar hold toggle failed", t);
        }
    }

    @Override
    public boolean simple_translate$refreshBlacklistedTranslations() {
        boolean changed = false;

        if (this.simple_translate$translatedTitle != null
                && simple_translate$shouldHideTranslatedComponent(
                this.simple_translate$originalTitle, this.simple_translate$translatedTitle)) {
            this.simple_translate$translatedTitle = null;
            this.title = this.simple_translate$originalTitle;
            changed = true;
        }

        if (this.simple_translate$translatedSubtitle != null
                && simple_translate$shouldHideTranslatedComponent(
                this.simple_translate$originalSubtitle, this.simple_translate$translatedSubtitle)) {
            this.simple_translate$translatedSubtitle = null;
            this.subtitle = this.simple_translate$originalSubtitle;
            changed = true;
        }

        if (this.simple_translate$translatedOverlay != null
                && simple_translate$shouldHideTranslatedComponent(
                this.simple_translate$originalOverlay, this.simple_translate$translatedOverlay)) {
            this.simple_translate$translatedOverlay = null;
            this.simple_translate$translatedOverlayTemplate = null;
            this.overlayMessageString = this.simple_translate$originalOverlay;
            changed = true;
        }

        return changed;
    }

    @Unique
    private boolean simple_translate$shouldHideTranslatedComponent(Component original, Component translated) {
        String originalText = original == null ? "" : original.getString();
        String translatedText = translated == null ? "" : translated.getString();
        return TooltipTranslationHelper.isBlacklisted(originalText)
                || TooltipTranslationHelper.containsBlacklistedText(translatedText);
    }

    private record ActionbarTemplate(Component component, List<ActionbarVariable> variables) {
    }

    private record ActionbarVariable(String placeholder, String value, Style style) {
    }
}
