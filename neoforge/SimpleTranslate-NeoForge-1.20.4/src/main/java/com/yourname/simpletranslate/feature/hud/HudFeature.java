package com.yourname.simpletranslate.feature.hud;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.core.ComponentJsonCompat;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import com.yourname.simpletranslate.feature.hud.HudTranslationHistory;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    /** Translation template with runtime values replaced by stable local markers. */
    @Nullable private HudTextSupport.ActionbarTemplate overlayTemplate;
    @Nullable private String titleImmediateSourceKey;
    @Nullable private String titleGroupKey;
    @Nullable private String titleCaptionSourceKey;
    @Nullable private String subtitleCaptionSourceKey;
    @Nullable private String titleHistoryKey;
    @Nullable private String subtitleHistoryKey;
    /** Request/history key: raw Component JSON for the variable-masked template. */
    @Nullable private String overlayKey;
    /** Layout key: raw Component JSON for the current, unmasked overlay. */
    @Nullable private String overlayLayoutKey;
    @Nullable private String overlayHistoryKey;
    private boolean overlayLayoutCritical;
    /** False only when a live dynamic marker cannot be restored safely. */
    private boolean overlayVariablesRestored = true;
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
        refreshActionbarMetadata();
        if (captionBatchMode()
                && this.overlayTemplate != null && this.overlayKey != null) {
            recordActionbarCaption(this.overlayTemplate, this.overlayKey);
        }
    }

    public void onClear() {
        this.originalTitle = null;
        this.originalSubtitle = null;
        this.originalOverlay = null;
        clearLocalTranslations();
        this.overlayKey = null;
        this.overlayLayoutKey = null;
        this.overlayTemplate = null;
        this.overlayLayoutCritical = false;
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
        return this.title;
    }

    @Nullable
    public Component renderSubtitle() {
        return this.subtitle;
    }

    @Nullable
    public Component renderOverlay() {
        return this.overlayMessageString;
    }

    private static boolean sameComponent(Component first, Component second) {
        return first == second || (first != null && first.equals(second));
    }

    /**
     * Called from the two actionbar-only mixin wrappers. Identity matching is
     * deliberate: unrelated HUD components that happen to compare equal must
     * never enter the layout renderer.
     */
    @Nullable
    public Component layoutActionbarSource(@Nullable Component rendered) {
        return this.overlayLayoutCritical
                && rendered != null
                && rendered == this.translatedOverlay
                ? this.originalOverlay
                : null;
    }

    /**
     * No layout plan renderer is available on this target. A false result
     * tells the mixin to render the original component through vanilla
     * instead.
     */
    public boolean renderLayoutActionbar(GuiGraphics graphics, Font font,
                                         @Nullable Component rendered,
                                         int x, int y, int width, int color) {
        return false;
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
        refreshActionbarMetadata();
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
        refreshActionbarMetadata();
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
        this.overlayVariablesRestored = true;
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
            clearActionbarMetadata();
            this.overlayHistoryKey = null;
            return;
        }
        HudTextSupport.ActionbarTemplate actionbarTemplate = currentOverlayTemplate();
        String currentKey = this.overlayKey;
        if (actionbarTemplate == null || currentKey == null) {
            this.overlayMessageString = original;
            return;
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
                if (!translatedTemplate.equals(this.translatedOverlayTemplate)) {
                    this.translatedOverlayTemplate = translatedTemplate;
                    applyCurrentActionbarTranslation();
                } else if (this.translatedOverlay == null) {
                    applyCurrentActionbarTranslation();
                }
                this.overlayMessageString = this.translatedOverlay == null ? original : this.translatedOverlay;
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
            clearActionbarMetadata();
            this.overlayHistoryKey = null;
            return;
        }
        HudTextSupport.ActionbarTemplate actionbarTemplate = currentOverlayTemplate();
        String currentKey = this.overlayKey;
        if (actionbarTemplate == null || currentKey == null) {
            this.overlayMessageString = original;
            return;
        }
        if (!ModConfig.HUD_ACTIONBAR_ENABLED.get() || HoldOriginalState.isHolding(HoldOriginalFeature.ACTIONBAR)) {
            this.overlayMessageString = original;
            return;
        }
        if (this.translatedOverlayTemplate != null) {
            if (this.translatedOverlay == null) {
                applyCurrentActionbarTranslation();
            }
            this.overlayMessageString = this.translatedOverlay == null ? original : this.translatedOverlay;
            return;
        }
        if (this.translatedOverlay != null) {
            this.overlayMessageString = this.translatedOverlay;
            return;
        }
        if (!shouldTranslateHudComponent(original, true)) {
            this.overlayMessageString = original;
            return;
        }
        requestActionbarAsync(actionbarTemplate, currentKey);
        this.overlayMessageString = original;
    }

    private void requestActionbarAsync(HudTextSupport.ActionbarTemplate actionbarTemplate, String currentKey) {
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
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft != null) {
                        minecraft.execute(() -> {
                            if (!ModConfig.GLOBAL_ENABLED.get() || !currentKey.equals(this.overlayKey)) {
                                return;
                            }
                            this.translatedOverlayTemplate = translatedTemplate;
                            // Never use the request-time variables here. The
                            // actionbar may have ticked while the request was
                            // in flight, so reattach the current template.
                            applyCurrentActionbarTranslation();
                        });
                    }
                });
    }

    /**
     * Updates the two deliberately separate actionbar identities. The template
     * identity masks live numbers so a ticking HUD does not repeatedly request
     * the same translation; the layout identity retains every original glyph,
     * font, whitespace run, and live value so the rendered component is never
     * reused across a different coordinate stream.
     */
    private void refreshActionbarMetadata() {
        Component original = this.originalOverlay;
        if (original == null) {
            clearActionbarMetadata();
            return;
        }

        HudTextSupport.ActionbarTemplate template = HudTextSupport.actionbarTemplate(original);
        String requestKey = actionbarTemplateKey(template);
        String layoutKey = actionbarLayoutKey(original);
        boolean templateChanged = !Objects.equals(requestKey, this.overlayKey);
        boolean layoutChanged = !Objects.equals(layoutKey, this.overlayLayoutKey);
        boolean layoutCritical = isLayoutCriticalActionbar(original);
        boolean layoutModeChanged = layoutCritical != this.overlayLayoutCritical;

        this.overlayTemplate = template;
        this.overlayKey = requestKey;
        this.overlayLayoutKey = layoutKey;
        this.overlayLayoutCritical = layoutCritical;

        if (templateChanged) {
            this.translatedOverlay = null;
            this.translatedOverlayTemplate = null;
            this.overlayVariablesRestored = true;
            this.overlayHistoryKey = null;
            return;
        }

        // Dynamic values, fonts, PUA anchors, and exact spaces all travel
        // through the layout key. Existing translations stay reusable, but
        // their rendered component must be rebuilt from this frame's source
        // template.
        if (layoutChanged || layoutModeChanged) {
            if (this.translatedOverlayTemplate != null) {
                applyCurrentActionbarTranslation();
            }
        }
    }

    @Nullable
    private HudTextSupport.ActionbarTemplate currentOverlayTemplate() {
        if (this.originalOverlay == null) {
            return null;
        }
        if (this.overlayTemplate == null || this.overlayKey == null) {
            refreshActionbarMetadata();
        }
        return this.overlayTemplate;
    }

    private void applyCurrentActionbarTranslation() {
        HudTextSupport.ActionbarTemplate template = currentOverlayTemplate();
        Component translatedTemplate = this.translatedOverlayTemplate;
        if (template == null || translatedTemplate == null) {
            this.translatedOverlay = null;
            this.overlayVariablesRestored = false;
            return;
        }

        Component restored = HudTextSupport.restoreActionbarVariables(translatedTemplate, template);
        this.overlayVariablesRestored = template.variables().isEmpty() || restored != null;
        this.translatedOverlay = restored != null ? restored : translatedTemplate;
        this.overlayMessageString = this.translatedOverlay;
    }

    private void clearActionbarMetadata() {
        clearGenericActionbarMetadata();
    }

    private void clearGenericActionbarMetadata() {
        this.overlayTemplate = null;
        this.overlayKey = null;
        this.overlayLayoutKey = null;
        this.overlayLayoutCritical = false;
        this.overlayVariablesRestored = true;
        this.translatedOverlay = null;
        this.translatedOverlayTemplate = null;
    }

    private boolean isLayoutCriticalActionbar(Component component) {
        try {
            return JsonPassthroughPipeline.isLayoutCriticalHudTree(
                    JsonParser.parseString(ComponentJsonCompat.toJson(component)));
        } catch (Throwable ignored) {
            // A serialization failure must leave a normal actionbar on the
            // normal path; it must not turn into a partially custom render.
            return false;
        }
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

    private String actionbarTemplateKey(HudTextSupport.ActionbarTemplate actionbarTemplate) {
        Component component = actionbarTemplate == null ? Component.empty() : actionbarTemplate.component();
        return componentJsonKey("actionbar.template", component);
    }

    private String actionbarLayoutKey(Component component) {
        return componentJsonKey("actionbar.layout", component);
    }

    private String componentJsonKey(String kind, @Nullable Component component) {
        Component safeComponent = component == null ? Component.empty() : component;
        try {
            return SimpleTranslateMod.getRuntimeRevision() + "\u0000" + kind + "\u0000"
                    + ComponentJsonCompat.toJson(safeComponent);
        } catch (Throwable ignored) {
            // Keep the legacy signature only as a serialization-failure key.
            // Normal actionbar identities always use unnormalized JSON.
            return componentSourceKey(kind + ".fallback", safeComponent);
        }
    }

    private boolean shouldHideTranslatedComponent(Component original, Component translated) {
        String originalText = original == null ? "" : original.getString();
        String translatedText = translated == null ? "" : translated.getString();
        return TooltipTranslationHelper.isBlacklisted(originalText)
                || TooltipTranslationHelper.containsBlacklistedText(translatedText);
    }
}
