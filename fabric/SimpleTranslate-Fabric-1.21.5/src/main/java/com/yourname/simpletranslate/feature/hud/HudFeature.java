package com.yourname.simpletranslate.feature.hud;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.core.ComponentJsonCompat;
import com.yourname.simpletranslate.core.ComponentListTranslationResult;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import com.yourname.simpletranslate.feature.hud.HudTranslationHistory;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.feature.wynn.WynnActionbarGlyphOverlayPlan;
import com.yourname.simpletranslate.feature.wynn.WynnDialogueProjection;
import com.yourname.simpletranslate.feature.wynn.WynnDialoguePendingEffect;
import com.yourname.simpletranslate.feature.wynn.WynnDialogueRenderPlan;
import com.yourname.simpletranslate.feature.wynn.WynncraftProfile;
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
    private static final String WYNN_ACTIONBAR_SURFACE = "hud.actionbar.wynn.glyph_overlay.v4";
    private static final String WYNN_ACTIONBAR_ROLE = "wynn-actionbar-glyph-overlay";
    private static final String WYNN_ACTIONBAR_CONTEXT = "Wynncraft selector actionbar natural-language phrases. "
            + "Each Component entry is one complete safe phrase. Translate every entry in order "
            + "and return exactly the same Component array. Do not add formatting codes, icons, keybinds, "
            + "private-use glyphs, controls, arrows, or spacing; those stay local to the Wynn renderer.";
    private static final String WYNN_DIALOGUE_CONTENT_SURFACE =
            "hud.actionbar.wynn.dialogue.content.paragraph.v5";
    private static final String WYNN_DIALOGUE_OPTIONS_SURFACE =
            "hud.actionbar.wynn.dialogue.options.semantic.v1";
    private static final String WYNN_DIALOGUE_CONTENT_CONTEXT =
            "wynn_dialogue_cache_format=paragraph.v5\n"
                    + "Wynncraft dialogue semantic content: NPC name, one complete BODY paragraph, "
                    + "and a control prompt. The BODY paragraph is ordinary spoken prose; physical source "
                    + "rows, colours, icons, keycaps, private-use glyphs, and format controls have already "
                    + "been removed and are restored by the client. Translate the complete BODY paragraph as "
                    + "natural target-language prose in its one Component slot. Do not split it into visual "
                    + "source fragments or invent formatting codes, icons, keybinds, private-use glyphs, "
                    + "controls, arrows, or spacing.";
    private static final String WYNN_DIALOGUE_OPTIONS_CONTEXT =
            "Wynncraft dialogue choices already delivered by the server, including temporarily hidden choices. "
                    + "Translate every entry and return exactly the same ordered Component array.";
    private static final long WYNN_DIALOGUE_FAILURE_RETRY_MS = 6_000L;
    private static final long WYNN_ACTIONBAR_FAILURE_RETRY_MS = 6_000L;

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
    /** Render-plan key: raw Component JSON for the current, unmasked overlay. */
    @Nullable private String overlayLayoutKey;
    @Nullable private String overlayHistoryKey;
    private boolean overlayLayoutCritical;
    /** False only when a live dynamic marker cannot be restored safely. */
    private boolean overlayVariablesRestored = true;
    @Nullable private ActionbarLayoutRenderer.Plan overlayLayoutPlan;
    /** Active Wynn selector projection. Its source component is never replaced. */
    @Nullable private WynnActionbarGlyphOverlayPlan.Projection wynnOverlayProjection;
    /** Semantic cache key: intentionally stable across layout-only/dynamic source changes. */
    @Nullable private String wynnOverlaySemanticKey;
    /** Raw current selector stream identity; rebuilds the plan without another request. */
    @Nullable private String wynnOverlayLayoutKey;
    @Nullable private List<Component> wynnTranslatedSlots;
    @Nullable private String wynnActionbarFailedSemanticKey;
    private long wynnActionbarRetryAfterNanos;
    /** Identity-only marker routed through the two actionbar mixin wrappers. */
    @Nullable private Component wynnTranslatedOverlay;
    @Nullable private WynnActionbarGlyphOverlayPlan.Plan wynnActionbarPlan;
    @Nullable private WynnDialogueProjection wynnDialogueProjection;
    /** A known dialogue font must never fall through to generic PUA translation. */
    private boolean wynnDialogueStructureObserved;
    private Component lastWynnDialogueProbeSource;
    private Component lastWynnActionbarProbeSource;
    @Nullable private String wynnDialogueContentFingerprint;
    @Nullable private String wynnDialogueOptionsFingerprint;
    @Nullable private String wynnDialogueSessionKey;
    @Nullable private List<Component> wynnDialogueTranslatedContent;
    @Nullable private List<Component> wynnDialogueTranslatedOptions;
    @Nullable private Component wynnDialogueTranslatedOverlay;
    @Nullable private WynnDialogueRenderPlan wynnDialogueRenderPlan;
    /** A cache miss is expensive to establish (Component JSON + legacy lanes). Probe each frame once only. */
    @Nullable private String wynnDialogueContentCacheMissFingerprint;
    @Nullable private String wynnDialogueOptionsCacheMissFingerprint;
    /** Prevents the render loop from resubmitting an already completed/failed request every frame. */
    @Nullable private String wynnDialogueContentRequestedFingerprint;
    @Nullable private String wynnDialogueOptionsRequestedFingerprint;
    private long wynnDialogueContentRetryAfterNanos;
    private long wynnDialogueOptionsRetryAfterNanos;
    private final WynnDialoguePendingEffect.Tracker wynnDialoguePendingEffect =
            new WynnDialoguePendingEffect.Tracker();
    private long wynnDialogueSemanticChangedAtNanos;
    private long wynnDialogueSessionRevision;
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
        if (captionBatchMode() && !isWynnActionbarRecognized()
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
        clearWynnActionbarMetadata();
        clearWynnDialogueMetadata();
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
        if (this.wynnDialogueRenderPlan != null && rendered != null
                && rendered == this.wynnDialogueTranslatedOverlay) {
            return this.wynnDialogueRenderPlan.sourceActionbar();
        }
        if (this.wynnActionbarPlan != null && rendered != null && rendered == this.wynnTranslatedOverlay) {
            return this.wynnActionbarPlan.sourceActionbar();
        }
        return this.overlayLayoutCritical
                && rendered != null
                && rendered == this.translatedOverlay
                ? this.originalOverlay
                : null;
    }

    /**
     * Attempts either the Wynn glyph-overlay plan or the generic fixed-anchor
     * plan. A false result tells the mixin to render the original component
     * through vanilla instead.
     */
    public boolean renderLayoutActionbar(GuiGraphics graphics, Font font,
                                         @Nullable Component rendered,
                                         int x, int y, int width, int color) {
        if (rendered != null && rendered == this.wynnDialogueTranslatedOverlay) {
            WynnDialogueRenderPlan plan = this.wynnDialogueRenderPlan;
            return plan != null && plan.render(graphics, font, x, y, width, color);
        }
        if (rendered != null && rendered == this.wynnTranslatedOverlay) {
            WynnActionbarGlyphOverlayPlan.Plan plan = this.wynnActionbarPlan;
            return plan != null && plan.render(graphics, font, x, y, width, color);
        }
        if (layoutActionbarSource(rendered) == null) {
            return false;
        }
        ActionbarLayoutRenderer.Plan plan = this.overlayLayoutPlan;
        return plan != null && plan.render(graphics, font, x, y, width, color);
    }

    /** Draws pending feedback before vanilla redraws the untouched dialogue text. */
    public boolean renderWynnDialoguePendingEffect(GuiGraphics graphics, Font font,
                                                    @Nullable Component rendered,
                                                    int x, int y, int width) {
        WynnDialogueProjection projection = this.wynnDialogueProjection;
        String identity = wynnDialoguePendingIdentity(projection);
        if (projection == null || rendered == null || rendered != this.originalOverlay
                || rendered != projection.sourceActionbar()
                || !this.wynnDialoguePendingEffect.isActive(identity, System.nanoTime())) {
            return false;
        }
        return WynnDialoguePendingEffect.render(graphics, font, projection, x, y, width);
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
            this.overlayLayoutPlan = null;
            this.overlayMessageString = this.originalOverlay;
            changed = true;
        }
        if (this.wynnTranslatedOverlay != null && shouldHideWynnActionbarTranslation()) {
            clearWynnActionbarTranslation();
            this.overlayMessageString = this.originalOverlay;
            changed = true;
        }
        if (this.wynnDialogueTranslatedOverlay != null && shouldHideWynnDialogueTranslation()) {
            clearWynnDialogueTranslation();
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
        this.overlayLayoutPlan = null;
        clearWynnActionbarTranslation();
        clearWynnDialogueTranslation();
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
        // Selector actionbars carry their own semantic cache and anchored
        // renderer. They intentionally bypass the generic history template,
        // whose variable splitting cannot preserve Wynn PUA coordinates.
        if (refreshWynnDialogue() || refreshWynnActionbar()) {
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
        if (isWynnActionbarRecognized()) {
            return;
        }
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
        if (refreshWynnDialogue() || refreshWynnActionbar()) {
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

    /** Dedicated typewriter-stable Wynn dialogue path, ahead of selector/generic actionbars. */
    private boolean refreshWynnDialogue() {
        Component original = this.originalOverlay;
        if (original != null && this.wynnDialogueProjection == null) {
            refreshWynnDialogueMetadata(original);
        }
        WynnDialogueProjection projection = this.wynnDialogueProjection;
        if (original == null) {
            return false;
        }
        // Wynncraft master switch: keep every Wynn surface as untouched source.
        if (!ModConfig.HUD_WYNN_OVERLAY_ENABLED.get()) {
            clearWynnDialogueTranslation();
            this.overlayMessageString = original;
            return true;
        }
        if (projection == null) {
            if (this.wynnDialogueStructureObserved) {
                this.overlayMessageString = original;
                return true;
            }
            return false;
        }

        // A dedicated semantic surface must obey the same source blacklist as
        // ordinary HUD text. Since one Wynn frame is split into several model
        // slots, blacklisting any displayed phrase rejects the complete frame;
        // translating the other slots would otherwise create a half-English
        // dialogue that the generic path never produces.
        if (shouldHideWynnDialogueSource(projection)) {
            clearWynnDialogueTranslation();
            this.overlayMessageString = original;
            return true;
        }

        if (!ModConfig.HUD_ACTIONBAR_ENABLED.get()
                || ModConfig.LAYOUT_CRITICAL_HUD_KEEP_ORIGINAL.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.ACTIONBAR)) {
            this.wynnDialoguePendingEffect.clear();
            this.overlayMessageString = original;
            return true;
        }

        List<Component> content = projection.contentComponents();
        List<Component> options = projection.optionComponents();
        boolean contentWaiting = false;
        boolean optionsWaiting = false;
        if (content.isEmpty()) {
            this.wynnDialogueTranslatedContent = List.of();
            clearWynnDialogueContentPendingState();
        } else if (this.wynnDialogueTranslatedContent == null) {
            String fingerprint = projection.contentFingerprint();
            String contentContext = wynnDialogueContentContext(projection, fingerprint);
            if (!fingerprint.equals(this.wynnDialogueContentCacheMissFingerprint)) {
                ComponentListTranslationResult cached = DirectSurfaceTranslator.getCachedComponents(
                        content, WYNN_DIALOGUE_CONTENT_SURFACE, "wynn-dialogue-content", true,
                        contentContext);
                if (cached.handled && cached.translated && cached.components != null
                        && cached.components.size() == content.size()) {
                    this.wynnDialogueTranslatedContent = List.copyOf(cached.components);
                    clearWynnDialogueContentPendingState();
                } else if (cached.handled) {
                    this.wynnDialogueContentCacheMissFingerprint = fingerprint;
                }
            }
            if (this.wynnDialogueTranslatedContent == null
                    && fingerprint.equals(this.wynnDialogueContentCacheMissFingerprint)) {
                contentWaiting = true;
                if (dialogueContentStable(projection)
                        && System.nanoTime() >= this.wynnDialogueContentRetryAfterNanos
                        && !fingerprint.equals(this.wynnDialogueContentRequestedFingerprint)) {
                    requestWynnDialogueContent(projection, contentContext);
                }
            }
        }

        boolean visibleOptions = projection.optionVisibility()
                == WynnDialogueProjection.OptionVisibility.VISIBLE;
        if (options.isEmpty()) {
            this.wynnDialogueTranslatedOptions = List.of();
            clearWynnDialogueOptionsPendingState();
        } else if (!visibleOptions) {
            // Wynn preloads choice text before its shader makes the choices
            // visible, and some transition frames cannot classify that phase.
            // Hidden/unknown options must never block an already translated
            // name, BODY, or control prompt. Keep them local until a later
            // VISIBLE frame explicitly requests their translation.
            this.wynnDialogueTranslatedOptions = List.copyOf(options);
            clearWynnDialogueOptionsPendingState();
        } else if (this.wynnDialogueTranslatedOptions == null) {
            String fingerprint = projection.optionsFingerprint();
            String optionsContext = wynnDialogueOptionsContext(projection, fingerprint);
            if (!fingerprint.equals(this.wynnDialogueOptionsCacheMissFingerprint)) {
                ComponentListTranslationResult cached = DirectSurfaceTranslator.getCachedComponents(
                        options, WYNN_DIALOGUE_OPTIONS_SURFACE, "wynn-dialogue-options", true,
                        optionsContext);
                if (cached.handled && cached.translated && cached.components != null
                        && cached.components.size() == options.size()) {
                    this.wynnDialogueTranslatedOptions = List.copyOf(cached.components);
                    clearWynnDialogueOptionsPendingState();
                } else if (cached.handled) {
                    this.wynnDialogueOptionsCacheMissFingerprint = fingerprint;
                }
            }
            if (this.wynnDialogueTranslatedOptions == null
                    && fingerprint.equals(this.wynnDialogueOptionsCacheMissFingerprint)) {
                optionsWaiting = true;
                if (System.nanoTime() >= this.wynnDialogueOptionsRetryAfterNanos
                        && !fingerprint.equals(this.wynnDialogueOptionsRequestedFingerprint)) {
                    requestWynnDialogueOptions(projection, optionsContext);
                }
            }
        }

        String pendingIdentity = wynnDialoguePendingIdentity(projection);
        this.wynnDialoguePendingEffect.observe(pendingIdentity,
                contentWaiting || optionsWaiting, System.nanoTime());

        if (this.wynnDialogueTranslatedContent != null
                && this.wynnDialogueTranslatedOptions != null) {
            if (this.wynnDialogueRenderPlan == null || this.wynnDialogueTranslatedOverlay == null) {
                bindWynnDialoguePlan(projection);
            }
            this.overlayMessageString = this.wynnDialogueTranslatedOverlay == null
                    ? original : this.wynnDialogueTranslatedOverlay;
        } else {
            this.overlayMessageString = original;
        }
        return true;
    }

    private boolean dialogueContentStable(WynnDialogueProjection projection) {
        long required = (projection.terminalBodyPunctuation() ? 150L : 800L) * 1_000_000L;
        return System.nanoTime() - this.wynnDialogueSemanticChangedAtNanos >= required;
    }

    private void requestWynnDialogueContent(WynnDialogueProjection projection, String stableContext) {
        List<Component> semantic = List.copyOf(projection.contentComponents());
        if (semantic.isEmpty()) return;
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        long sessionRevision = this.wynnDialogueSessionRevision;
        String fingerprint = projection.contentFingerprint();
        if (fingerprint.equals(this.wynnDialogueContentRequestedFingerprint)) return;
        String pendingKey = "wynn-dialogue-content\u0000" + runtimeRevision + '\u0000' + fingerprint;
        Set<String> pending = pendingActionbarHistoryKeys();
        if (!pending.add(pendingKey)) return;
        this.wynnDialogueContentRequestedFingerprint = fingerprint;
        DirectSurfaceTranslator.translateComponentsAsync(
                        semantic, WYNN_DIALOGUE_CONTENT_SURFACE, "wynn-dialogue-content", true,
                        stableContext)
                .whenComplete((result, error) -> {
                    pending.remove(pendingKey);
                    if (error != null || result == null || !result.handled || !result.translated
                            || result.components == null || result.components.size() != semantic.size()) {
                        failWynnDialogueRequest(runtimeRevision, sessionRevision, fingerprint, true);
                        return;
                    }
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft == null) return;
                    List<Component> translated = List.copyOf(result.components);
                    minecraft.execute(() -> {
                        WynnDialogueProjection current = this.wynnDialogueProjection;
                        if (!SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                                || sessionRevision != this.wynnDialogueSessionRevision
                                 || current == null || !fingerprint.equals(current.contentFingerprint())
                                || shouldHideWynnDialogueSource(current)) {
                            return;
                        }
                        if (containsBlacklistedTranslation(translated)) {
                            failWynnDialogueRequest(
                                    runtimeRevision, sessionRevision, fingerprint, true);
                            return;
                        }
                        this.wynnDialogueTranslatedContent = translated;
                        clearWynnDialogueContentPendingState();
                        stopWynnDialoguePendingIfComplete(current);
                        bindWynnDialoguePlan(current);
                    });
                });
    }

    private void requestWynnDialogueOptions(WynnDialogueProjection projection, String stableContext) {
        List<Component> semantic = List.copyOf(projection.optionComponents());
        if (semantic.isEmpty()) return;
        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        long sessionRevision = this.wynnDialogueSessionRevision;
        String fingerprint = projection.optionsFingerprint();
        if (fingerprint.equals(this.wynnDialogueOptionsRequestedFingerprint)) return;
        String pendingKey = "wynn-dialogue-options\u0000" + runtimeRevision + '\u0000' + fingerprint;
        Set<String> pending = pendingActionbarHistoryKeys();
        if (!pending.add(pendingKey)) return;
        this.wynnDialogueOptionsRequestedFingerprint = fingerprint;
        DirectSurfaceTranslator.translateComponentsAsync(
                        semantic, WYNN_DIALOGUE_OPTIONS_SURFACE, "wynn-dialogue-options", true,
                        stableContext)
                .whenComplete((result, error) -> {
                    pending.remove(pendingKey);
                    if (error != null || result == null || !result.handled || !result.translated
                            || result.components == null || result.components.size() != semantic.size()) {
                        failWynnDialogueRequest(runtimeRevision, sessionRevision, fingerprint, false);
                        return;
                    }
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft == null) return;
                    List<Component> translated = List.copyOf(result.components);
                    minecraft.execute(() -> {
                        WynnDialogueProjection current = this.wynnDialogueProjection;
                        if (!SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                                || sessionRevision != this.wynnDialogueSessionRevision
                                 || current == null || !fingerprint.equals(current.optionsFingerprint())
                                || shouldHideWynnDialogueSource(current)) {
                            return;
                        }
                        if (containsBlacklistedTranslation(translated)) {
                            failWynnDialogueRequest(
                                    runtimeRevision, sessionRevision, fingerprint, false);
                            return;
                        }
                        this.wynnDialogueTranslatedOptions = translated;
                        clearWynnDialogueOptionsPendingState();
                        stopWynnDialoguePendingIfComplete(current);
                        bindWynnDialoguePlan(current);
                    });
                });
    }

    private void failWynnDialogueRequest(long runtimeRevision, long sessionRevision,
                                         String fingerprint, boolean contentLane) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        minecraft.execute(() -> {
            WynnDialogueProjection current = this.wynnDialogueProjection;
            if (!SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                    || sessionRevision != this.wynnDialogueSessionRevision || current == null) {
                return;
            }
            String currentFingerprint = contentLane
                    ? current.contentFingerprint() : current.optionsFingerprint();
            if (fingerprint.equals(currentFingerprint)) {
                long retryAt = System.nanoTime() + WYNN_DIALOGUE_FAILURE_RETRY_MS * 1_000_000L;
                if (contentLane) {
                    if (fingerprint.equals(this.wynnDialogueContentRequestedFingerprint)) {
                        this.wynnDialogueContentRequestedFingerprint = null;
                    }
                    this.wynnDialogueContentRetryAfterNanos = retryAt;
                } else {
                    if (fingerprint.equals(this.wynnDialogueOptionsRequestedFingerprint)) {
                        this.wynnDialogueOptionsRequestedFingerprint = null;
                    }
                    this.wynnDialogueOptionsRetryAfterNanos = retryAt;
                }
                this.wynnDialoguePendingEffect.fail(wynnDialoguePendingIdentity(current));
            }
        });
    }

    private void stopWynnDialoguePendingIfComplete(WynnDialogueProjection projection) {
        if (this.wynnDialogueTranslatedContent != null && this.wynnDialogueTranslatedOptions != null) {
            this.wynnDialoguePendingEffect.stop(wynnDialoguePendingIdentity(projection));
        }
    }


    private boolean bindWynnDialoguePlan(WynnDialogueProjection projection) {
        this.wynnDialogueRenderPlan = null;
        this.wynnDialogueTranslatedOverlay = null;
        if (projection == null || this.wynnDialogueTranslatedContent == null
                || this.wynnDialogueTranslatedOptions == null) {
            return false;
        }
        if (shouldHideWynnDialogueSource(projection)
                || containsBlacklistedTranslation(this.wynnDialogueTranslatedContent)
                || containsBlacklistedTranslation(this.wynnDialogueTranslatedOptions)) {
            return false;
        }
        WynnDialogueRenderPlan plan = projection.bindTranslations(
                this.wynnDialogueTranslatedContent, this.wynnDialogueTranslatedOptions);
        if (plan == null) return false;
        this.wynnDialogueRenderPlan = plan;
        this.wynnDialogueTranslatedOverlay = Component.literal("");
        return true;
    }

    private void refreshWynnDialogueMetadata(Component original) {
        if (original == this.lastWynnDialogueProbeSource) {
            return;
        }
        this.lastWynnDialogueProbeSource = original;
        boolean structureObserved = WynnDialogueProjection.hasKnownDialogueTextStructure(original);
        this.wynnDialogueStructureObserved = structureObserved;
        WynnDialogueProjection projection = WynnDialogueProjection.project(original);
        if (projection == null) {
            clearWynnDialogueMetadata();
            // A typewriter frame may already expose Wynn's dialogue fonts
            // before it contains enough semantic text for a safe projection.
            // Keep that structural observation after clearing stale projection
            // and translation state so the frame cannot fall through to the
            // generic actionbar translator.
            this.wynnDialogueStructureObserved = structureObserved;
            return;
        }
        WynnDialogueProjection previous = this.wynnDialogueProjection;
        boolean sessionChanged = previous == null
                || !Objects.equals(this.wynnDialogueSessionKey, projection.sessionKey())
                || (!Objects.equals(this.wynnDialogueContentFingerprint, projection.contentFingerprint())
                && !previous.isSemanticPrefixOf(projection));
        boolean contentChanged = !Objects.equals(
                this.wynnDialogueContentFingerprint, projection.contentFingerprint());
        boolean optionsChanged = !Objects.equals(
                this.wynnDialogueOptionsFingerprint, projection.optionsFingerprint());
        boolean optionsBecameVisible = previous != null
                && previous.optionVisibility() != WynnDialogueProjection.OptionVisibility.VISIBLE
                && projection.optionVisibility() == WynnDialogueProjection.OptionVisibility.VISIBLE;
        boolean layoutChanged = previous == null || !previous.hasSameLayout(projection);

        if (sessionChanged) {
            this.wynnDialogueSessionRevision++;
            clearWynnDialogueTranslation();
        } else {
            if (contentChanged) {
                this.wynnDialogueTranslatedContent = null;
                this.wynnDialogueRenderPlan = null;
                this.wynnDialogueTranslatedOverlay = null;
                clearWynnDialogueContentPendingState();
            }
            if (optionsChanged || optionsBecameVisible) {
                this.wynnDialogueTranslatedOptions = null;
                this.wynnDialogueRenderPlan = null;
                this.wynnDialogueTranslatedOverlay = null;
                clearWynnDialogueOptionsPendingState();
            }
        }
        if (contentChanged) {
            this.wynnDialogueSemanticChangedAtNanos = System.nanoTime();
        }
        this.wynnDialogueProjection = projection;
        this.wynnDialogueContentFingerprint = projection.contentFingerprint();
        this.wynnDialogueOptionsFingerprint = projection.optionsFingerprint();
        this.wynnDialogueSessionKey = projection.sessionKey();
        this.overlayHistoryKey = null;
        if (layoutChanged && this.wynnDialogueTranslatedContent != null
                && this.wynnDialogueTranslatedOptions != null) {
            bindWynnDialoguePlan(projection);
        }
    }

    private void clearWynnDialogueTranslation() {
        this.wynnDialogueTranslatedContent = null;
        this.wynnDialogueTranslatedOptions = null;
        this.wynnDialogueTranslatedOverlay = null;
        this.wynnDialogueRenderPlan = null;
        clearWynnDialogueContentPendingState();
        clearWynnDialogueOptionsPendingState();
        this.wynnDialoguePendingEffect.clear();
    }

    private void clearWynnDialogueContentPendingState() {
        this.wynnDialogueContentCacheMissFingerprint = null;
        this.wynnDialogueContentRequestedFingerprint = null;
        this.wynnDialogueContentRetryAfterNanos = 0L;
    }

    private void clearWynnDialogueOptionsPendingState() {
        this.wynnDialogueOptionsCacheMissFingerprint = null;
        this.wynnDialogueOptionsRequestedFingerprint = null;
        this.wynnDialogueOptionsRetryAfterNanos = 0L;
    }

    private void clearWynnDialogueMetadata() {
        this.wynnDialogueProjection = null;
        this.lastWynnDialogueProbeSource = null;
        this.wynnDialogueStructureObserved = false;
        this.wynnDialogueContentFingerprint = null;
        this.wynnDialogueOptionsFingerprint = null;
        this.wynnDialogueSessionKey = null;
        this.wynnDialogueSemanticChangedAtNanos = 0L;
        clearWynnDialogueTranslation();
    }

    /**
     * Dedicated Wynn selector path shared by immediate and caption/history
     * rendering. The caption mode still gets the same cache and async response;
     * only complete natural-language phrases are requested. The original
     * visual glyph stream stays client-local for direct masked rendering.
     */
    private boolean refreshWynnActionbar() {
        Component original = this.originalOverlay;
        if (original != null && this.wynnOverlayProjection == null) {
            refreshWynnActionbarMetadata(original);
        }
        WynnActionbarGlyphOverlayPlan.Projection projection = this.wynnOverlayProjection;
        if (original == null || projection == null) {
            return false;
        }
        // Wynncraft master switch: keep every Wynn surface as untouched source.
        if (!ModConfig.HUD_WYNN_OVERLAY_ENABLED.get()) {
            clearWynnActionbarTranslation();
            this.overlayMessageString = original;
            return true;
        }

        if (shouldHideWynnActionbarSource(projection)) {
            clearWynnActionbarTranslation();
            this.overlayMessageString = original;
            return true;
        }

        if (!projection.valid() || !projection.hasSlots()
                || !ModConfig.HUD_ACTIONBAR_ENABLED.get()
                || ModConfig.LAYOUT_CRITICAL_HUD_KEEP_ORIGINAL.get()
                || HoldOriginalState.isHolding(HoldOriginalFeature.ACTIONBAR)) {
            this.overlayMessageString = original;
            return true;
        }

        if (this.wynnTranslatedSlots != null) {
            if (this.wynnActionbarPlan == null || this.wynnTranslatedOverlay == null) {
                applyWynnActionbarTranslation(projection, this.wynnTranslatedSlots);
            }
            this.overlayMessageString = this.wynnTranslatedOverlay == null
                    ? original : this.wynnTranslatedOverlay;
            return true;
        }

        List<Component> semantic = projection.semanticComponents();
        if (semantic.isEmpty()) {
            this.overlayMessageString = original;
            return true;
        }

        String semanticKey = projection.cacheKey();
        String stableContext = wynnActionbarStableContext(projection, semanticKey);
        if (!semanticKey.equals(this.wynnActionbarCacheMissSemanticKey)) {
            ComponentListTranslationResult cached = DirectSurfaceTranslator.getCachedComponents(
                    semantic, WYNN_ACTIONBAR_SURFACE, WYNN_ACTIONBAR_ROLE, true, stableContext);
            if (cached.handled && cached.translated && cached.components != null
                    && cached.components.size() == semantic.size()) {
                applyWynnActionbarTranslation(projection, cached.components);
                this.overlayMessageString = this.wynnTranslatedOverlay == null
                        ? original : this.wynnTranslatedOverlay;
                return true;
            }
            if (cached.handled) {
                this.wynnActionbarCacheMissSemanticKey = semanticKey;
            }
        }

        if (semanticKey.equals(this.wynnActionbarCacheMissSemanticKey)
                && (this.wynnActionbarFailedSemanticKey == null
                || !this.wynnActionbarFailedSemanticKey.equals(semanticKey)
                || System.nanoTime() >= this.wynnActionbarRetryAfterNanos)) {
            requestWynnActionbarAsync(projection, stableContext);
        }
        this.overlayMessageString = original;
        return true;
    }

    private void requestWynnActionbarAsync(WynnActionbarGlyphOverlayPlan.Projection projection,
                                           String stableContext) {
        if (projection == null || !projection.valid() || !projection.hasSlots()) {
            return;
        }
        long requestRevision = SimpleTranslateMod.getRuntimeRevision();
        String semanticKey = projection.cacheKey();
        String pendingKey = "wynn-actionbar\u0000" + requestRevision + "\u0000" + semanticKey;
        Set<String> pendingActionbarKeys = pendingActionbarHistoryKeys();
        if (!pendingActionbarKeys.add(pendingKey)) {
            return;
        }
        List<Component> semantic = List.copyOf(projection.semanticComponents());
        DirectSurfaceTranslator.translateComponentsAsync(
                        semantic, WYNN_ACTIONBAR_SURFACE, WYNN_ACTIONBAR_ROLE, true, stableContext)
                .whenComplete((direct, error) -> {
                    pendingActionbarKeys.remove(pendingKey);
                    if (error != null || direct == null || !direct.handled || !direct.translated
                            || direct.components == null || direct.components.size() != semantic.size()) {
                        if (semanticKey.equals(this.wynnOverlaySemanticKey)) {
                            this.wynnActionbarFailedSemanticKey = semanticKey;
                            this.wynnActionbarRetryAfterNanos = System.nanoTime()
                                    + WYNN_ACTIONBAR_FAILURE_RETRY_MS * 1_000_000L;
                        }
                        return;
                    }
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft != null) {
                        List<Component> translatedSlots = List.copyOf(direct.components);
                        minecraft.execute(() -> {
                            WynnActionbarGlyphOverlayPlan.Projection current = this.wynnOverlayProjection;
                            if (!ModConfig.GLOBAL_ENABLED.get() || current == null
                                    || requestRevision != SimpleTranslateMod.getRuntimeRevision()
                                    || !semanticKey.equals(this.wynnOverlaySemanticKey)
                                    || !semanticKey.equals(current.cacheKey())
                                    || shouldHideWynnActionbarSource(current)
                                    || containsBlacklistedTranslation(translatedSlots)) {
                                return;
                            }
                            applyWynnActionbarTranslation(current, translatedSlots);
                            this.wynnActionbarFailedSemanticKey = null;
                            this.wynnActionbarRetryAfterNanos = 0L;
                        });
                    }
                });
    }

    /**
     * Shows the model how semantic slots connect around locally retained Wynn
     * glyphs and values without allowing ticking numbers to churn cache keys.
     */
    private String cachedDialogueContentContext;
    private String cachedDialogueContentFingerprint;
    private String cachedDialogueOptionsContext;
    private String cachedDialogueOptionsFingerprint;
    private String cachedPendingSessionKey;
    private String cachedPendingContentFingerprint;
    private String cachedPendingOptionsFingerprint;
    private String cachedPendingIdentity;
    private Component lastHideDialogueComponent;
    private long lastHideDialogueRevision = -1L;
    private boolean lastHideDialogueResult;
    private Component lastHideActionbarComponent;
    private long lastHideActionbarRevision = -1L;
    private boolean lastHideActionbarResult;

    private String wynnDialogueContentContext(WynnDialogueProjection projection, String fingerprint) {
        if (!java.util.Objects.equals(fingerprint, this.cachedDialogueContentFingerprint)) {
            this.cachedDialogueContentFingerprint = fingerprint;
            this.cachedDialogueContentContext = wynnContextWithSourceShape(
                    WYNN_DIALOGUE_CONTENT_CONTEXT, projection.sourceActionbar());
        }
        return this.cachedDialogueContentContext;
    }

    private String wynnDialogueOptionsContext(WynnDialogueProjection projection, String fingerprint) {
        if (!java.util.Objects.equals(fingerprint, this.cachedDialogueOptionsFingerprint)) {
            this.cachedDialogueOptionsFingerprint = fingerprint;
            this.cachedDialogueOptionsContext = wynnContextWithSourceShape(
                    WYNN_DIALOGUE_OPTIONS_CONTEXT, projection.sourceActionbar());
        }
        return this.cachedDialogueOptionsContext;
    }

    private String wynnActionbarStableContext(
            WynnActionbarGlyphOverlayPlan.Projection projection, String semanticKey) {
        if (!java.util.Objects.equals(semanticKey, this.cachedActionbarContextKey)) {
            this.cachedActionbarContextKey = semanticKey;
            this.cachedActionbarContext = wynnContextWithSourceShape(
                    WYNN_ACTIONBAR_CONTEXT, projection.sourceActionbar());
        }
        return this.cachedActionbarContext;
    }

    private String cachedActionbarContextKey;
    private String cachedActionbarContext;

    private String wynnDialoguePendingIdentity(@Nullable WynnDialogueProjection projection) {
        if (projection == null) {
            return null;
        }
        if (!java.util.Objects.equals(projection.sessionKey(), this.cachedPendingSessionKey)
                || !java.util.Objects.equals(projection.contentFingerprint(),
                this.cachedPendingContentFingerprint)
                || !java.util.Objects.equals(projection.optionsFingerprint(),
                this.cachedPendingOptionsFingerprint)) {
            this.cachedPendingSessionKey = projection.sessionKey();
            this.cachedPendingContentFingerprint = projection.contentFingerprint();
            this.cachedPendingOptionsFingerprint = projection.optionsFingerprint();
            this.cachedPendingIdentity = projection.sessionKey() + '\u0000'
                    + projection.contentFingerprint() + '\u0000' + projection.optionsFingerprint();
        }
        return this.cachedPendingIdentity;
    }

    private static String wynnContextWithSourceShape(String baseContext, Component sourceActionbar) {
        String sourceShape = JsonPassthroughPipeline.semanticPromptSourceShape(
                sourceActionbar == null ? List.of() : List.of(sourceActionbar));
        if (sourceShape.isBlank()) {
            return baseContext;
        }
        return baseContext
                + "\nStable readable source shape (dynamic numbers are <number>):\n"
                + sourceShape;
    }

    /**
     * Installs a marker only after the renderer has verified every semantic
     * slot and the complete raw source stream. A failed plan means the wrapper
     * receives the original actionbar and vanilla renders it normally.
     */
    private boolean applyWynnActionbarTranslation(WynnActionbarGlyphOverlayPlan.Projection projection,
                                                   List<Component> translatedSlots) {
        clearWynnActionbarTranslation();
        if (projection == null || !projection.valid() || !projection.isActionbar()
                || translatedSlots == null || translatedSlots.size() != projection.semanticComponents().size()
                || shouldHideWynnActionbarSource(projection)
                || containsBlacklistedTranslation(translatedSlots)) {
            return false;
        }
        WynnActionbarGlyphOverlayPlan.Plan plan = projection.bindTranslations(translatedSlots);
        if (plan == null) {
            return false;
        }
        this.wynnTranslatedSlots = List.copyOf(translatedSlots);
        this.wynnActionbarPlan = plan;
        // This is an identity token only. TitleOverlayMixin recognizes this
        // exact instance and lets the plan draw the original glyph stream plus
        // Chinese overlays; it must never be handed to vanilla on its own.
        this.wynnTranslatedOverlay = Component.literal("");
        return true;
    }

    private boolean isWynnActionbarRecognized() {
        return this.wynnDialogueStructureObserved || this.wynnOverlayProjection != null;
    }

    private void refreshWynnActionbarMetadata(Component original) {
        if (original == this.lastWynnActionbarProbeSource) {
            return;
        }
        this.lastWynnActionbarProbeSource = original;
        WynnActionbarGlyphOverlayPlan.Projection projection =
                WynnActionbarGlyphOverlayPlan.projectStructure(original);
        if (projection == null) {
            clearWynnActionbarMetadata();
            return;
        }

        String semanticKey = projection.cacheKey();
        String layoutKey = componentJsonKey("wynn.actionbar.layout", original);
        boolean semanticChanged = !Objects.equals(semanticKey, this.wynnOverlaySemanticKey);
        boolean layoutChanged = !Objects.equals(layoutKey, this.wynnOverlayLayoutKey);

        this.wynnOverlayProjection = projection;
        this.wynnOverlaySemanticKey = semanticKey;
        this.wynnOverlayLayoutKey = layoutKey;
        this.overlayHistoryKey = null;

        if (semanticChanged) {
            clearWynnActionbarTranslation();
            return;
        }
        if (layoutChanged && this.wynnTranslatedSlots != null) {
            applyWynnActionbarTranslation(projection, this.wynnTranslatedSlots);
        }
    }

    private void clearWynnActionbarTranslation() {
        this.wynnTranslatedSlots = null;
        this.wynnTranslatedOverlay = null;
        this.wynnActionbarPlan = null;
    }

    private String wynnActionbarCacheMissSemanticKey;

    private void clearWynnActionbarMetadata() {
        this.wynnOverlayProjection = null;
        this.wynnActionbarCacheMissSemanticKey = null;
        this.lastWynnActionbarProbeSource = null;
        this.wynnOverlaySemanticKey = null;
        this.wynnOverlayLayoutKey = null;
        this.wynnActionbarFailedSemanticKey = null;
        this.wynnActionbarRetryAfterNanos = 0L;
        clearWynnActionbarTranslation();
    }

    /**
     * Updates the two deliberately separate actionbar identities. The template
     * identity masks live numbers so a ticking HUD does not repeatedly request
     * the same translation; the layout identity retains every original glyph,
     * font, whitespace run, and live value so a render plan is never reused
     * across a different coordinate stream.
     */
    private void refreshActionbarMetadata() {
        Component original = this.originalOverlay;
        if (original == null) {
            clearActionbarMetadata();
            return;
        }

        refreshWynnDialogueMetadata(original);
        if (this.wynnDialogueStructureObserved) {
            clearWynnActionbarMetadata();
            clearGenericActionbarMetadata();
            return;
        }

        refreshWynnActionbarMetadata(original);
        if (this.wynnOverlayProjection != null) {
            clearGenericActionbarMetadata();
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
            this.overlayLayoutPlan = null;
            this.overlayHistoryKey = null;
            return;
        }

        // Dynamic values, fonts, PUA anchors, and exact spaces all travel
        // through the layout key. Existing translations stay reusable, but
        // their rendered component and plan must be rebuilt from this frame's
        // source template.
        if (layoutChanged || layoutModeChanged) {
            if (this.translatedOverlayTemplate != null) {
                applyCurrentActionbarTranslation();
            } else {
                this.overlayLayoutPlan = null;
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
            this.overlayLayoutPlan = null;
            return;
        }

        Component restored = HudTextSupport.restoreActionbarVariables(translatedTemplate, template);
        this.overlayVariablesRestored = template.variables().isEmpty() || restored != null;
        this.translatedOverlay = restored != null ? restored : translatedTemplate;
        this.overlayMessageString = this.translatedOverlay;
        refreshActionbarLayoutPlan();
    }

    private void refreshActionbarLayoutPlan() {
        this.overlayLayoutPlan = null;
        if (!this.overlayLayoutCritical || !this.overlayVariablesRestored
                || this.originalOverlay == null || this.overlayTemplate == null
                || this.translatedOverlayTemplate == null) {
            return;
        }
        this.overlayLayoutPlan = ActionbarLayoutRenderer.compile(
                this.originalOverlay, this.overlayTemplate, this.translatedOverlayTemplate);
    }

    private void clearActionbarMetadata() {
        clearGenericActionbarMetadata();
        clearWynnActionbarMetadata();
        clearWynnDialogueMetadata();
    }

    /** Clears only the generic actionbar lane; dedicated Wynn state survives. */
    private void clearGenericActionbarMetadata() {
        this.overlayTemplate = null;
        this.overlayKey = null;
        this.overlayLayoutKey = null;
        this.overlayLayoutCritical = false;
        this.overlayVariablesRestored = true;
        this.overlayLayoutPlan = null;
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

    /**
     * The direct Wynn path intentionally uses an empty identity component, so
     * normal marker-text blacklist inspection would miss the Chinese overlays.
     * Inspect the accepted semantic translations instead while preserving the
     * normal original-text blacklist behavior.
     */
    private boolean shouldHideWynnActionbarTranslation() {
        if (shouldHideWynnActionbarSource(this.wynnOverlayProjection)) {
            return true;
        }
        return containsBlacklistedTranslation(this.wynnTranslatedSlots);
    }

    private boolean shouldHideWynnDialogueTranslation() {
        if (shouldHideWynnDialogueSource(this.wynnDialogueProjection)) {
            return true;
        }
        return containsBlacklistedTranslation(this.wynnDialogueTranslatedContent)
                || containsBlacklistedTranslation(this.wynnDialogueTranslatedOptions);
    }

    private boolean shouldHideWynnActionbarSource(
            @Nullable WynnActionbarGlyphOverlayPlan.Projection projection) {
        long revision = SimpleTranslateMod.getBlacklistRevision();
        if (this.originalOverlay == this.lastHideActionbarComponent
                && revision == this.lastHideActionbarRevision) {
            return this.lastHideActionbarResult;
        }
        this.lastHideActionbarComponent = this.originalOverlay;
        this.lastHideActionbarRevision = revision;
        this.lastHideActionbarResult = shouldHideWynnActionbarSourceUncached(projection);
        return this.lastHideActionbarResult;
    }

    private boolean shouldHideWynnActionbarSourceUncached(
            @Nullable WynnActionbarGlyphOverlayPlan.Projection projection) {
        String originalText = this.originalOverlay == null ? "" : this.originalOverlay.getString();
        return TooltipTranslationHelper.isBlacklisted(originalText)
                || (projection != null && containsBlacklistedSource(projection.semanticComponents()));
    }

    private boolean shouldHideWynnDialogueSource(@Nullable WynnDialogueProjection projection) {
        long revision = SimpleTranslateMod.getBlacklistRevision();
        if (this.originalOverlay == this.lastHideDialogueComponent
                && revision == this.lastHideDialogueRevision) {
            return this.lastHideDialogueResult;
        }
        this.lastHideDialogueComponent = this.originalOverlay;
        this.lastHideDialogueRevision = revision;
        this.lastHideDialogueResult = shouldHideWynnDialogueSourceUncached(projection);
        return this.lastHideDialogueResult;
    }

    private boolean shouldHideWynnDialogueSourceUncached(@Nullable WynnDialogueProjection projection) {
        String originalText = this.originalOverlay == null ? "" : this.originalOverlay.getString();
        return TooltipTranslationHelper.isBlacklisted(originalText)
                || (projection != null
                && (containsBlacklistedSource(projection.contentComponents())
                || containsBlacklistedSource(projection.optionComponents())));
    }

    private static boolean containsBlacklistedSource(@Nullable List<Component> source) {
        if (source == null) {
            return false;
        }
        for (Component component : source) {
            if (component != null && TooltipTranslationHelper.isBlacklisted(component.getString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBlacklistedTranslation(@Nullable List<Component> translated) {
        if (translated == null) {
            return false;
        }
        for (Component component : translated) {
            if (component != null
                    && TooltipTranslationHelper.containsBlacklistedText(component.getString())) {
                return true;
            }
        }
        return false;
    }
}
