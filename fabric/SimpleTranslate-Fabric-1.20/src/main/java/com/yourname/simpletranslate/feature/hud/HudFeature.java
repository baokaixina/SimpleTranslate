package com.yourname.simpletranslate.feature.hud;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.feature.hud.HudTranslationHistory;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds all HUD title/subtitle/actionbar translation state and logic, keeping
 * {@link com.yourname.simpletranslate.mixin.TitleOverlayMixin} as a thin
 * delegating shell. The mixin reads {@link #renderTitle()},
 * {@link #renderSubtitle()}, and {@link #renderOverlay()} each frame and
 * assigns the results to its shadowed vanilla fields.
 */
public final class HudFeature {
    private static final String TITLE_GROUP_SURFACE = "hud.title_group.component.direct";
    private static final String ACTIONBAR_SURFACE = "hud.actionbar.component.direct";

    @Nullable private Component originalTitle;
    @Nullable private Component originalSubtitle;
    @Nullable private Component originalOverlay;
    @Nullable private Component translatedTitle;
    @Nullable private Component translatedSubtitle;
    @Nullable private Component translatedOverlay;
    @Nullable private Component translatedOverlayTemplate;
    @Nullable private String titleImmediateSourceKey;
    @Nullable private String titleGroupKey;
    @Nullable private String titleCaptionSourceKey;
    @Nullable private String subtitleCaptionSourceKey;
    @Nullable private String titleHistoryKey;
    @Nullable private String subtitleHistoryKey;
    @Nullable private String overlayKey;
    @Nullable private String overlayHistoryKey;
    private long hudHistorySequence;
    private long seenRuntimeRevision = -1L;
    private boolean seenCaptionBatchMode;
    @Nullable private Set<String> pendingTitleHistoryKeys;
    @Nullable private Set<String> pendingActionbarHistoryKeys;

    public void onSetTitle(Component title) {
        syncRuntimeRevision();
        syncCaptionMode();
        this.originalTitle = title;
        this.translatedTitle = null;
        this.titleImmediateSourceKey = null;
        this.titleGroupKey = null;
        if (captionBatchMode()) {
            recordTitleCaption();
        } else {
            this.titleCaptionSourceKey = null;
            this.titleHistoryKey = null;
        }
    }

    public void onSetSubtitle(Component subtitle) {
        syncRuntimeRevision();
        syncCaptionMode();
        this.originalSubtitle = subtitle;
        this.translatedSubtitle = null;
        this.titleImmediateSourceKey = null;
        this.titleGroupKey = null;
        if (captionBatchMode()) {
            recordSubtitleCaption();
        } else {
            this.subtitleCaptionSourceKey = null;
            this.subtitleHistoryKey = null;
        }
    }

    public void onSetOverlayMessage(Component component) {
        syncRuntimeRevision();
        syncCaptionMode();
        this.originalOverlay = component;
        HudTextSupport.ActionbarTemplate template = HudTextSupport.actionbarTemplate(component);
        String currentKey = overlayKey(template);
        if (!currentKey.equals(this.overlayKey)) {
            this.translatedOverlay = null;
            this.translatedOverlayTemplate = null;
            this.overlayKey = currentKey;
            this.overlayHistoryKey = null;
        }
        if (captionBatchMode()) {
            recordActionbarCaption(template, currentKey);
        }
    }

    public void onClear() {
        this.originalTitle = null;
        this.originalSubtitle = null;
        this.originalOverlay = null;
        clearLocalTranslations();
        this.overlayKey = null;
        this.overlayHistoryKey = null;
        pendingTitleHistoryKeys().clear();
        pendingActionbarHistoryKeys().clear();
    }

    public void onRender() {
        syncRuntimeRevision();
        syncCaptionMode();
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            this.title = this.originalTitle;
            this.subtitle = this.originalSubtitle;
            this.overlayMessageString = this.originalOverlay;
            return;
        }
        if (captionBatchMode()) {
            refreshTitleGroupFromCaptionBatch();
            refreshOverlayFromCaptionBatch();
            HudTranslationHistory.tickTranslator(System.currentTimeMillis());
        } else {
            refreshTitleGroupImmediate();
            refreshOverlayImmediate();
        }
    }

    @Nullable
    public Component renderTitle() {
        return title;
    }

    @Nullable
    public Component renderSubtitle() {
        return subtitle;
    }

    @Nullable
    public Component renderOverlay() {
        return overlayMessageString;
    }

    // The mixin assigns these directly from the render results.
    @Nullable private Component title;
    @Nullable private Component subtitle;
    @Nullable private Component overlayMessageString;

    public void onHoldOriginalChanged(HoldOriginalFeature feature, boolean holding) {
        try {
            switch (feature) {
                case TITLE -> {
                    if (captionBatchMode()) {
                        refreshTitleGroupFromCaptionBatch();
                    } else {
                        refreshTitleGroupImmediate();
                    }
                }
                case ACTIONBAR -> {
                    if (captionBatchMode()) {
                        refreshOverlayFromCaptionBatch();
                    } else {
                        refreshOverlayImmediate();
                    }
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            SimpleTranslateMod.getLogger().error("Title/ActionBar hold toggle failed", t);
        }
    }

    public boolean refreshBlacklistedTranslations() {
        boolean changed = false;
        if (this.translatedTitle != null
                && shouldHideTranslatedComponent(this.originalTitle, this.translatedTitle)) {
            this.translatedTitle = null;
            this.title = this.originalTitle;
            changed = true;
        }
        if (this.translatedSubtitle != null
                && shouldHideTranslatedComponent(this.originalSubtitle, this.translatedSubtitle)) {
            this.translatedSubtitle = null;
            this.subtitle = this.originalSubtitle;
            changed = true;
        }
        if (this.translatedOverlay != null
                && shouldHideTranslatedComponent(this.originalOverlay, this.translatedOverlay)) {
            this.translatedOverlay = null;
            this.translatedOverlayTemplate = null;
            this.overlayMessageString = this.originalOverlay;
            changed = true;
        }
        return changed;
    }

    private void syncRuntimeRevision() {
        long revision = SimpleTranslateMod.getRuntimeRevision();
        if (this.seenRuntimeRevision == revision) {
            return;
        }
        this.seenRuntimeRevision = revision;
        this.seenCaptionBatchMode = captionBatchMode();
        clearLocalTranslations();
        this.overlayKey = overlayKey(this.originalOverlay);
        this.overlayHistoryKey = null;
        pendingTitleHistoryKeys().clear();
        pendingActionbarHistoryKeys().clear();
    }

    private void syncCaptionMode() {
        boolean batchMode = captionBatchMode();
        if (this.seenCaptionBatchMode == batchMode) {
            return;
        }
        this.seenCaptionBatchMode = batchMode;
        clearLocalTranslations();
        this.overlayKey = overlayKey(this.originalOverlay);
        this.overlayHistoryKey = null;
        pendingTitleHistoryKeys().clear();
        pendingActionbarHistoryKeys().clear();
    }

    private boolean captionBatchMode() {
        return ModConfig.GLOBAL_ENABLED.get()
                && (ModConfig.HUD_TITLE_CONTEXT_ENABLED.get() || ModConfig.HUD_HISTORY_CHAT_ENABLED.get());
    }

    private void clearLocalTranslations() {
        this.translatedTitle = null;
        this.translatedSubtitle = null;
        this.translatedOverlay = null;
        this.translatedOverlayTemplate = null;
        this.titleImmediateSourceKey = null;
        this.titleGroupKey = null;
        this.titleCaptionSourceKey = null;
        this.subtitleCaptionSourceKey = null;
        this.titleHistoryKey = null;
        this.subtitleHistoryKey = null;
    }

    private Set<String> pendingTitleHistoryKeys() {
        if (this.pendingTitleHistoryKeys == null) {
            this.pendingTitleHistoryKeys = ConcurrentHashMap.newKeySet();
        }
        return this.pendingTitleHistoryKeys;
    }

    private Set<String> pendingActionbarHistoryKeys() {
        if (this.pendingActionbarHistoryKeys == null) {
            this.pendingActionbarHistoryKeys = ConcurrentHashMap.newKeySet();
        }
        return this.pendingActionbarHistoryKeys;
    }

    private String nextHudHistoryKey(String kind) {
        return SimpleTranslateMod.getRuntimeRevision() + "\u0000" + kind + "\u0000" + (++this.hudHistorySequence);
    }

    private void refreshTitleGroupFromCaptionBatch() {
        if (!ModConfig.HUD_TITLE_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.TITLE)) {
            this.title = this.originalTitle;
            this.subtitle = this.originalSubtitle;
            return;
        }
        recordTitleCaption();
        recordSubtitleCaption();
        this.title = currentCaptionTranslation(this.titleHistoryKey, this.originalTitle, true);
        this.subtitle = currentCaptionTranslation(this.subtitleHistoryKey, this.originalSubtitle, false);
    }

    @Nullable
    private Component currentCaptionTranslation(@Nullable String historyKey, @Nullable Component original,
                                                boolean titleSlot) {
        if (!shouldTranslateHudComponent(original, false) || historyKey == null) {
            return original;
        }
        Component translated = HudTranslationHistory.translatedComponent(historyKey);
        if (translated == null) {
            return original;
        }
        if (titleSlot) {
            this.translatedTitle = translated;
        } else {
            this.translatedSubtitle = translated;
        }
        return translated;
    }

    private void recordTitleCaption() {
        recordTextCaption(HudTranslationHistory.CaptionType.TITLE, this.originalTitle, "title", true);
    }

    private void recordSubtitleCaption() {
        recordTextCaption(HudTranslationHistory.CaptionType.SUBTITLE, this.originalSubtitle, "subtitle", false);
    }

    private void recordTextCaption(HudTranslationHistory.CaptionType type, @Nullable Component original,
                                   String kind, boolean titleSlot) {
        if (!ModConfig.HUD_TITLE_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.TITLE)
                || !shouldTranslateHudComponent(original, false)) {
            if (titleSlot) {
                this.titleCaptionSourceKey = null;
                this.titleHistoryKey = null;
                this.translatedTitle = null;
            } else {
                this.subtitleCaptionSourceKey = null;
                this.subtitleHistoryKey = null;
                this.translatedSubtitle = null;
            }
            return;
        }
        String sourceKey = componentSourceKey(kind, original);
        String previousSourceKey = titleSlot ? this.titleCaptionSourceKey : this.subtitleCaptionSourceKey;
        String historyKey = titleSlot ? this.titleHistoryKey : this.subtitleHistoryKey;
        if (!sourceKey.equals(previousSourceKey) || historyKey == null) {
            historyKey = nextHudHistoryKey(kind);
            if (titleSlot) {
                this.titleCaptionSourceKey = sourceKey;
                this.titleHistoryKey = historyKey;
                this.translatedTitle = null;
            } else {
                this.subtitleCaptionSourceKey = sourceKey;
                this.subtitleHistoryKey = historyKey;
                this.translatedSubtitle = null;
            }
        }
        HudTranslationHistory.recordCaption(type, historyKey, sourceKey, original, original);
    }

    private void refreshTitleGroupImmediate() {
        if (!ModConfig.HUD_TITLE_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.TITLE)) {
            this.title = this.originalTitle;
            this.subtitle = this.originalSubtitle;
            return;
        }
        boolean translateTitle = shouldTranslateHudComponent(this.originalTitle, false);
        boolean translateSubtitle = shouldTranslateHudComponent(this.originalSubtitle, false);
        String sourceKey = titleImmediateSourceKey(translateTitle, translateSubtitle);
        if (!sourceKey.equals(this.titleImmediateSourceKey)) {
            this.translatedTitle = null;
            this.translatedSubtitle = null;
            this.titleImmediateSourceKey = sourceKey;
            this.titleGroupKey = null;
        }
        if (!translateTitle && !translateSubtitle) {
            this.title = this.originalTitle;
            this.subtitle = this.originalSubtitle;
            return;
        }
        String groupKey = sourceKey;
        if (!groupKey.equals(this.titleGroupKey)) {
            this.titleGroupKey = groupKey;
        }
        if ((translateTitle && this.translatedTitle == null)
                || (translateSubtitle && this.translatedSubtitle == null)) {
            List<Component> originals = new ArrayList<>(2);
            List<Boolean> titleSlots = new ArrayList<>(2);
            if (translateTitle) {
                originals.add(this.originalTitle);
                titleSlots.add(Boolean.TRUE);
            }
            if (translateSubtitle) {
                originals.add(this.originalSubtitle);
                titleSlots.add(Boolean.FALSE);
            }
            requestTitleGroupAsync(originals, titleSlots, groupKey, translateTitle, translateSubtitle);
        }
        this.title = translateTitle && this.translatedTitle != null ? this.translatedTitle : this.originalTitle;
        this.subtitle = translateSubtitle && this.translatedSubtitle != null ? this.translatedSubtitle : this.originalSubtitle;
    }

    private void requestTitleGroupAsync(List<Component> originals, List<Boolean> titleSlots,
                                        String groupKey, boolean translateTitle, boolean translateSubtitle) {
        Set<String> pendingTitleKeys = pendingTitleHistoryKeys();
        if (!pendingTitleKeys.add(groupKey)) {
            return;
        }
        DirectSurfaceTranslator.translateComponentsAsync(
                        List.copyOf(originals), TITLE_GROUP_SURFACE, "title-subtitle", false, "")
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
                            if (!ModConfig.GLOBAL_ENABLED.get() || !groupKey.equals(this.titleGroupKey)) {
                                return;
                            }
                            if (translateTitle) {
                                this.translatedTitle = finalTranslatedTitle;
                            }
                            if (translateSubtitle) {
                                this.translatedSubtitle = finalTranslatedSubtitle;
                            }
                        });
                    }
                });
    }

    private void refreshOverlayFromCaptionBatch() {
        Component original = this.originalOverlay;
        if (original == null) {
            this.overlayMessageString = null;
            this.overlayKey = null;
            this.overlayHistoryKey = null;
            return;
        }
        HudTextSupport.ActionbarTemplate actionbarTemplate = HudTextSupport.actionbarTemplate(original);
        String currentKey = overlayKey(actionbarTemplate);
        if (!currentKey.equals(this.overlayKey)) {
            this.translatedOverlay = null;
            this.translatedOverlayTemplate = null;
            this.overlayKey = currentKey;
            this.overlayHistoryKey = null;
        }
        if (!ModConfig.HUD_ACTIONBAR_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.ACTIONBAR)) {
            this.overlayMessageString = original;
            return;
        }
        if (!shouldTranslateHudComponent(original, true)) {
            this.overlayMessageString = original;
            return;
        }
        recordActionbarCaption(actionbarTemplate, currentKey);
        if (this.overlayHistoryKey != null) {
            Component translatedTemplate = HudTranslationHistory.translatedRequestComponent(this.overlayHistoryKey);
            if (translatedTemplate != null) {
                Component restored = HudTextSupport.restoreActionbarVariables(translatedTemplate, actionbarTemplate);
                if (restored != null) {
                    this.translatedOverlayTemplate = translatedTemplate;
                    this.translatedOverlay = restored;
                    this.overlayMessageString = restored;
                    return;
                }
                this.translatedOverlayTemplate = translatedTemplate;
                this.translatedOverlay = translatedTemplate;
                this.overlayMessageString = translatedTemplate;
                return;
            }
        }
        this.overlayMessageString = original;
    }

    private void recordActionbarCaption(HudTextSupport.ActionbarTemplate actionbarTemplate, String currentKey) {
        if (!ModConfig.HUD_ACTIONBAR_ENABLED.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.ACTIONBAR)
                || !shouldTranslateHudComponent(this.originalOverlay, true)) {
            return;
        }
        if (this.overlayHistoryKey == null) {
            this.overlayHistoryKey = nextHudHistoryKey("actionbar");
        }
        String historyKey = this.overlayHistoryKey;
        HudTranslationHistory.recordCaption(
                HudTranslationHistory.CaptionType.ACTIONBAR,
                historyKey,
                currentKey,
                this.originalOverlay,
                actionbarTemplate.component(),
                translated -> HudTextSupport.restoreActionbarVariables(translated, actionbarTemplate));
    }

    private void refreshOverlayImmediate() {
        Component original = this.originalOverlay;
        if (original == null) {
            this.overlayMessageString = null;
            this.overlayKey = null;
            this.overlayHistoryKey = null;
            return;
        }
        HudTextSupport.ActionbarTemplate actionbarTemplate = HudTextSupport.actionbarTemplate(original);
        String currentKey = overlayKey(actionbarTemplate);
        if (!currentKey.equals(this.overlayKey)) {
            this.translatedOverlay = null;
            this.translatedOverlayTemplate = null;
            this.overlayKey = currentKey;
            this.overlayHistoryKey = null;
        }
        if (!ModConfig.HUD_ACTIONBAR_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.ACTIONBAR)) {
            this.overlayMessageString = original;
            return;
        }
        if (this.translatedOverlayTemplate != null) {
            Component restored = HudTextSupport.restoreActionbarVariables(this.translatedOverlayTemplate, actionbarTemplate);
            if (restored != null) {
                this.translatedOverlay = restored;
                this.overlayMessageString = restored;
                return;
            }
            this.translatedOverlay = this.translatedOverlayTemplate;
            this.overlayMessageString = this.translatedOverlayTemplate;
            return;
        }
        if (this.translatedOverlay != null && actionbarTemplate.variables().isEmpty()) {
            this.overlayMessageString = this.translatedOverlay;
            return;
        }
        if (!shouldTranslateHudComponent(original, true)) {
            this.overlayMessageString = original;
            return;
        }
        requestActionbarAsync(original, actionbarTemplate, currentKey);
        this.overlayMessageString = original;
    }

    private void requestActionbarAsync(Component original, HudTextSupport.ActionbarTemplate actionbarTemplate,
                                       String currentKey) {
        Set<String> pendingActionbarKeys = pendingActionbarHistoryKeys();
        if (!pendingActionbarKeys.add(currentKey)) {
            return;
        }
        DirectSurfaceTranslator.translateComponentsAsync(
                        List.of(actionbarTemplate.component()), ACTIONBAR_SURFACE, "actionbar", true, "")
                .whenComplete((direct, error) -> {
                    pendingActionbarKeys.remove(currentKey);
                    if (error != null || direct == null || !direct.handled || !direct.translated
                            || direct.components == null || direct.components.size() != 1) {
                        return;
                    }
                    Component translatedTemplate = direct.components.get(0);
                    Component restored = HudTextSupport.restoreActionbarVariables(translatedTemplate, actionbarTemplate);
                    Component finalOverlay = restored != null ? restored : translatedTemplate;
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft != null) {
                        minecraft.execute(() -> {
                            if (!ModConfig.GLOBAL_ENABLED.get() || !currentKey.equals(this.overlayKey)) {
                                return;
                            }
                            this.translatedOverlayTemplate = translatedTemplate;
                            this.translatedOverlay = finalOverlay;
                            this.overlayMessageString = finalOverlay;
                        });
                    }
                });
    }

    private boolean shouldTranslateHudComponent(@Nullable Component original, boolean skipTechnicalHudText) {
        if (original == null) {
            return false;
        }
        String text = original.getString();
        return text != null
                && !text.isBlank()
                && TooltipTranslationHelper.containsEnglish(text)
                && !TooltipTranslationHelper.isBlacklisted(text)
                && (!skipTechnicalHudText || !HudTextSupport.isTechnicalText(text));
    }

    private String titleImmediateSourceKey(boolean translateTitle, boolean translateSubtitle) {
        String titleText = this.originalTitle == null ? "" : this.originalTitle.getString();
        String subtitleText = this.originalSubtitle == null ? "" : this.originalSubtitle.getString();
        return SimpleTranslateMod.getRuntimeRevision() + "\u0000"
                + translateTitle + "\u0001" + titleText + "\u0001" + HudTextSupport.componentStyleSignature(this.originalTitle)
                + "\u0002" + translateSubtitle + "\u0001" + subtitleText + "\u0001" + HudTextSupport.componentStyleSignature(this.originalSubtitle);
    }

    private String componentSourceKey(String kind, @Nullable Component component) {
        String text = component == null ? "" : component.getString();
        return SimpleTranslateMod.getRuntimeRevision() + "\u0000"
                + kind + "\u0000"
                + HudTextSupport.cleanText(text) + "\u0001"
                + HudTextSupport.componentStyleSignature(component);
    }

    private String overlayKey(@Nullable Component original) {
        return overlayKey(HudTextSupport.actionbarTemplate(original));
    }

    private String overlayKey(HudTextSupport.ActionbarTemplate actionbarTemplate) {
        Component component = actionbarTemplate == null ? null : actionbarTemplate.component();
        return componentSourceKey("actionbar", component);
    }

    private boolean shouldHideTranslatedComponent(Component original, Component translated) {
        String originalText = original == null ? "" : original.getString();
        String translatedText = translated == null ? "" : translated.getString();
        return TooltipTranslationHelper.isBlacklisted(originalText)
                || TooltipTranslationHelper.containsBlacklistedText(translatedText);
    }
}
