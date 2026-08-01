# SimpleTranslate NeoForge 1.21.11 Mixin evidence (2026-07-27)

Exact build target: Minecraft 1.21.11, NeoForge `21.11.42` (ModDevGradle,
Mojang official mappings at compile and runtime, Java 21). Evidence jar:
`build/moddev/artifacts/neoforge-21.11.42-merged.jar` (the exact
NeoForge-patched recompile this project builds against). All vanilla rows
below were verified with `javap -p -s` (and `javap -c` for every
`@At(INVOKE)` call-site) against that jar; the machine-generated audit
(130 OK checks) is `<workspace>\.analysis\nf-121c\verify_12111.txt`,
produced by `.analysis\nf-121c\verify_mixins.py` on 2026-07-26 23:27 and
spot-re-run manually this session. Runtime proof: dedicated client
`<designated-test-client>\neoforge\1.21.11` (`1.21.11-NeoForge`), Quick Play
singleplayer world entry PASS 2026-07-27 05:37
(`CodexTester[local:...] logged in with entity id 38`), zero mixin
injection errors under `defaultRequire=1`, no crash reports, deployed jar
SHA256-identical to `build/libs` at that time.

Mixin config: 30 client entries (27 vanilla incl. 4 accessors + 3 optional
compat), byte-identical to the canonical Fabric 1.21.11 baseline donor.
`ScreenComponentClickMixin` is correctly absent: 1.21.11 drops
`Screen#handleComponentClicked(Style)Z`; the click hook lives on
`ChatScreen#handleComponentClicked(LStyle;Z)Z` and is handled by
`ChatScreenMixin` (descriptor verified below).

## Vanilla mixins (exact 21.11.42 descriptors)

1.21.11 renames `ResourceLocation` to `net.minecraft.resources.Identifier`
and moves book text visiting to `ActiveTextCollector`; every affected row
below reflects the verified 21.11.42 shape.

| Mixin | Exact target evidence | Runtime |
| --- | --- | --- |
| AbstractContainerScreenMixin | `renderTooltip(LGuiGraphics;II)V`; `keyPressed` unambiguous -> `(Lnet/minecraft/client/input/KeyEvent;)Z` | applied |
| AbstractWidgetTranslationMixin | `AbstractWidget#getMessage()LComponent;` | applied |
| AdvancementToastMixin | `AdvancementToast#render(LGuiGraphics;LFont;J)V`; `@Shadow advancement:LAdvancementHolder;` | lazy (javap-verified) |
| AdvancementWidgetMixin | `AdvancementWidget#drawHover(LGuiGraphics;IIFII)V`; `@Shadow advancementNode:LAdvancementNode;` | lazy (javap-verified) |
| BookEditScreenMixin | `render(LGuiGraphics;IIF)V`, `appendPageToBook()V`, `init()V`, `updatePageContent()V`; redirects `MultiLineEditBox#setValueListener(LConsumer;)V` / `setValue(LString;Z)V` at call-sites; shadows `pages:List`, `currentPage:I`, `updatePageContent()V` | applied |
| BookViewScreenMixin | `keyPressed(LKeyEvent;)Z`, `setBookAccess(LBookAccess;)V`, `render(LGuiGraphics;IIF)V`, `visitText(LActiveTextCollector;Z)V` (new 1.21.11 surface); redirect `BookViewScreen$BookAccess#getPage(I)LComponent;`; shadows `bookAccess`, `cachedPage:I` | applied |
| BossHealthOverlayMixin | `render(LGuiGraphics;)V`; INVOKE `GuiGraphics#drawString(LFont;LComponent;III)V` at call-site | applied |
| ChatComponentMixin | `addMessage(LComponent;LMessageSignature;LGuiMessageTag;)V`; shadows `allMessages:List`, `rescaleChat()V` (manual javap; see adjudication) | applied |
| ChatScreenMixin | `keyPressed(LKeyEvent;)Z`; `handleComponentClicked` unambiguous -> `(Lnet/minecraft/network/chat/Style;Z)Z` (replaces the removed Screen-level hook); `@Shadow input:LEditBox;` | applied |
| CycleButtonTooltipMixin | `updateTooltip()V`; call-site `invokevirtual setTooltip:(LTooltip;)V` owner `CycleButton` at pc 14 | applied |
| EntityRendererMixin | `extractRenderState` unambiguous -> `(LEntity;LEntityRenderState;F)V` | applied |
| FontManagerMixin / FontManagerAccessor | `apply(LFontManager$Preparation;LProfilerFiller;)V`, `close()V` | applied |
| FontPreparedTextBuilderMixin | `Font$PreparedTextBuilder#accept(ILStyle;LBakedGlyph;)Z`; INVOKE `BakedGlyph#createGlyph(FFIILStyle;FF)LTextRenderable$Styled;` (1.21.11 returns the nested `Styled` type) at call-site | applied |
| FontSetMixin / FontSetAccessor | `computeGlyphInfo(I)LFontSet$SelectedGlyphs;` | applied |
| GuiGraphicsTranslationMixin | all six draw overloads EXACT: `drawString(LFont;LComponent;IIIZ)V`, `(LFont;LString;IIIZ)V`, `(LFont;LFormattedCharSequence;IIIZ)V`, `(LFont;LFormattedCharSequence;III)V`, `drawCenteredString(LFont;LComponent;III)V`, `drawWordWrap(LFont;LFormattedText;IIII)V` | applied |
| HoverTooltipMixin | `renderComponentHoverEffect(LFont;LStyle;II)V`; `renderTooltip(LFont;LList;IILClientTooltipPositioner;LIdentifier;)V`; all five `setTooltipForNextFrame` overloads + `setTooltipForNextFrameInternal(...LIdentifier;Z)V` EXACT (Identifier, not ResourceLocation); `@Shadow deferredTooltip:LRunnable;` | applied |
| PlayerTabOverlayMixin | `render(LGuiGraphics;ILScoreboard;LObjective;)V`; INVOKE `Font#split(LFormattedText;I)LList;` at call-site | applied |
| ScoreboardMixin | `Gui#displayScoreboardSidebar(LGuiGraphics;LObjective;)V`; INVOKEs `Objective#getDisplayName()`, `Font#width(LFormattedText;)I`, `GuiGraphics#drawString(LFont;LComponent;IIIZ)V` at call-sites | applied |
| ScreenAccessor | `Screen` present | applied |
| ScreenGuiTranslationMixin | `Screen#renderWithTooltipAndSubtitles(LGuiGraphics;IIF)V`; INVOKE `GuiGraphics#renderDeferredElements()V` at call-site | applied |
| SignRendererMixin | `AbstractSignRenderer#submitSignText(LSignRenderState;LPoseStack;LSubmitNodeCollector;Z)V`, `#submit(LSignRenderState;LPoseStack;LSubmitNodeCollector;LCameraRenderState;)V`; INVOKE `getDarkColor(LSignText;)I` at call-site | applied |
| SignTextMixin | `SignText#getRenderMessages(ZLFunction;)[LFormattedCharSequence;` | applied |
| TextDisplayMixin | `Display$TextDisplay#cacheDisplay(LLineSplitter;)LCachedInfo;` | applied |
| TitleOverlayMixin | `setTitle/setSubtitle(LComponent;)V`, `setOverlayMessage(LComponent;Z)V`, `render(LGuiGraphics;LDeltaTracker;)V`, `renderBossOverlay/renderChat/renderScoreboardSidebar/renderTabList/renderOverlayMessage/renderTitle(LGuiGraphics;LDeltaTracker;)V`, `clearTitles()V`; INVOKEs `Font#width(LFormattedText;)I`, `GuiGraphics#drawStringWithBackdrop(LFont;LComponent;IIII)V` at call-sites | applied |
| ClientTextTooltipAccessor | `ClientTextTooltip` present | lazy (javap-verified) |

"applied" = the class was transformed in the 2026-07-27 05:37 world-entry run
(24 distinct `Mixing ... from simple_translate.mixins.json` lines in
`debug.log`) with zero injection errors under `defaultRequire=1`.

## Optional third-party compat (exact per-version artifact status)

Unlike 1.21.9/1.21.10, ALL THREE optional integrations have exact loadable
artifacts on MC 1.21.11; every descriptor below is javap-verified against the
exact NeoForge artifact for this Minecraft version.

| Integration | Exact-artifact status on MC 1.21.11 |
| --- | --- |
| Wynntils (`compat.WynntilsOverlayManagerMixin`, pseudo, plugin-gated on `wynntils`) | **EXACT + live.** `private void renderOverlays(Lcom/wynntils/mc/event/RenderEvent;)V` on `com.wynntils.core.consumers.overlays.OverlayManager` javap-verified byte-identical in ALL NeoForge builds published for MC 1.21.11 at audit time: `wynntils-4.1.22-neoforge+MC-1.21.11.jar`, `wynntils-4.2.2-...`, and the newest `wynntils-4.2.3-neoforge+MC-1.21.11.jar` (`.analysis/optional-2111/`, extracted classes in `x4_1_22`/`x4_2_2`/`x4_2_3`). 4.2.3 pins `minecraft [1.21.11]`, `neoforge [21.11.23-beta,)` (its own requirement; does not move this mod's floor). Not runtime-exercised (test client has no Wynntils). |
| FTB Library (`compat.FtbScreenWrapperTranslationMixin`, `compat.FtbTextFieldTranslationMixin`, pseudo, `remap=false`, plugin-gated on `ftblibrary`) | **EXACT vs `ftb-library-neoforge-2111.1.1.jar`** (newest 2111-series build on the official FTB maven: series is {2111.1.0, 2111.1.1}; declares `minecraft [1.21.11,)`, `neoforge [21.11.0-beta,22.0.0)`). The 2111 series RENAMED the API vs 2101.1.33: classes moved to `dev.ftb.mods.ftblibrary.client.gui.widget.*`; `ScreenWrapper#keyPressed` is now `(Lnet/minecraft/client/input/KeyEvent;)Z` (was `(III)Z`), `render(LGuiGraphics;IIF)V`; `TextField#draw(LGuiGraphics;Ldev/ftb/mods/ftblibrary/client/gui/theme/Theme;IIII)V`, `@Shadow rawText:LComponent;`, `@Shadow formattedText:[Lnet/minecraft/util/FormattedCharSequence;` (the 2111 series stores FormattedCharSequence[], NOT the older FormattedText[]), `public int maxWidth`, `public float scale`. All javap-verified (`.analysis/optional-2111/x2111ftb/`). Mixin descriptors are mojmap (NeoForge production runtime is mojmap-named). Not runtime-exercised (no FTB in test client). |
| Iceberg (`compat/IcebergTooltipGatherCompat`, pure reflection, no mixin) | **EXACT vs `Iceberg-1.21.11-neoforge-1.4.0.1.jar`** (exact artifact for this MC version; declares `minecraft [1.21.11]`, `neoforge [21.11.42,)`). javap-verified (`.analysis/optional-2111/x2111ice/`): `events.client.RenderTooltipEvents.GATHER : Levents/Event;`, `Event#register(Ljava/lang/Object;)V`, `Gather#onGather(LItemStack;IILjava/util/List;II)LGatherResult;`, `GatherResult(Lnet/minecraft/world/InteractionResult;ILjava/util/List;)V`. All failures soft (try/catch + warn); gated on `ModList.get().isLoaded("iceberg")`. |

## Machine-audit PROBLEMS adjudication (2026-07-27)

`verify_12111.txt` lists three PROBLEMS rows; each was independently
re-checked with `javap` against `neoforge-21.11.42-merged.jar`:

1. `ChatComponentMixin: @Shadow void NOT FOUND` — script parser false
   positive (regex cannot parse the `abstract` modifier). Manual javap:
   `rescaleChat()V` and `allMessages:Ljava/util/List;` both exist. EXACT.
2. `CycleButtonTooltipMixin: INVOKE CycleButton#setTooltip NOT FOUND on
   owner` — `setTooltip` is inherited from `AbstractWidget`; the bytecode
   call-site in `CycleButton#updateTooltip` (pc 14) is
   `invokevirtual // Method setTooltip:(Lnet/minecraft/client/gui/components/Tooltip;)V`
   with owner `CycleButton`, matching the mixin `@At` target exactly. Also
   runtime-proven in the 05:37 world-entry run.
3. `SimpleTranslateMixinPlugin: no @Mixin target found` — the
   `IMixinConfigPlugin` gate class, not a mixin. Benign.

No row treats a clean build or compile success as runtime proof by itself;
the runtime column comes from the 2026-07-27 05:37 world-entry client run.
FTB/Wynntils/Iceberg application on real third-party installations remains
unexercised because the dedicated test client does not install those mods.

## 2026-07-28 packaging follow-up

The Mojang-named ModDevGradle target does not generate
`simple_translate.refmap.json`, so its stale config declaration was removed.
The clean rebuilt JAR has SHA256
`8CF47E787D3E90FB3B334C4D0BF14BE0E298715158CA6EC0B907AD427A01AC5F`.
`validation-refmap-rerun-20260728-060130.out.log` deployed it to exact
NeoForge `21.11.42` and reached `CodexSmokeWorld` at 06:02:27 (`entity id
38`) with zero refmap warnings, zero Mixin application failures, and no crash
report.
