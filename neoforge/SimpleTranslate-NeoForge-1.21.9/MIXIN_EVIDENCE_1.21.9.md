# SimpleTranslate NeoForge 1.21.9 Mixin evidence (2026-07-26)

Exact build target: Minecraft 1.21.9, NeoForge `21.9.16-beta` (ModDevGradle,
Mojang official mappings at compile and runtime, Java 21). Evidence jar:
`build/moddev/artifacts/neoforge-21.9.16-beta.jar` (the exact NeoForge-patched
recompile this project builds against). All vanilla rows below were verified
with `javap -p -s` (and `javap -c` for every `@At(INVOKE)` call-site) against
that jar; the full machine-generated audit (131 OK checks, zero unresolved) is
`<workspace>\.analysis\nf-121c\verify_1219.txt`, produced by
`.analysis\nf-121c\verify_mixins.py`. Runtime proof: dedicated client
`<designated-test-client>\neoforge\1.21.9` (`1.21.9-NeoForge`), world entry PASS
2026-07-26 23:19 (`CodexTester ... logged in with entity id 1`), zero mixin
injection errors, no crash reports.

Mixin config: 31 client entries (28 vanilla incl. 4 accessors + 3 optional
compat), byte-identical to the Fabric 1.21.9 donor. `defaultRequire=1`.

## Vanilla mixins (exact 21.9.16-beta descriptors)

| Mixin | Exact target evidence | Runtime |
| --- | --- | --- |
| AbstractContainerScreenMixin | `AbstractContainerScreen#renderTooltip(LGuiGraphics;II)V`; `keyPressed` unambiguous -> `(Lnet/minecraft/client/input/KeyEvent;)Z` (1.21.9 input-event API) | applied |
| AbstractWidgetTranslationMixin | `AbstractWidget#getMessage()LComponent;` | applied |
| AdvancementToastMixin | `AdvancementToast#render(LGuiGraphics;LFont;J)V`; `@Shadow advancement:LAdvancementHolder;` | applied |
| AdvancementWidgetMixin | `AdvancementWidget#drawHover(LGuiGraphics;IIFII)V` (WrapMethod); `@Shadow advancementNode:LAdvancementNode;` | applied |
| BookEditScreenMixin | `render(LGuiGraphics;IIF)V`, `appendPageToBook()V`, `init()V`, `updatePageContent()V`; redirects `MultiLineEditBox#setValueListener(LConsumer;)V` / `setValue(LString;Z)V` both present at call-sites in bytecode; shadows `pages:List`, `currentPage:I`, `updatePageContent()V` | applied (lazy; world-entry pass) |
| BookViewScreenMixin | `keyPressed(LKeyEvent;)Z`, `setBookAccess(LBookAccess;)V`, `render(LGuiGraphics;IIF)V`; redirect `BookViewScreen$BookAccess#getPage(I)LComponent;` at call-site; shadows `bookAccess`, `cachedPage:I` | applied (lazy) |
| BossHealthOverlayMixin | `render(LGuiGraphics;)V`; INVOKE `GuiGraphics#drawString(LFont;LComponent;III)V` at call-site | applied |
| ChatComponentMixin | `addMessage(LComponent;LMessageSignature;LGuiMessageTag;)V`; shadows `allMessages:List`, `rescaleChat()V` | applied |
| ChatScreenMixin | `keyPressed` -> `(LKeyEvent;)Z`; `@Shadow input:LEditBox;` | applied |
| CycleButtonTooltipMixin | `updateTooltip()V`; call-site invokes `setTooltip:(LTooltip;)V` with owner `CycleButton` (verified in `updateTooltip` bytecode, pc 14) | applied |
| EntityRendererMixin | `extractRenderState` unambiguous -> `(LEntity;LEntityRenderState;F)V` (render-state era) | applied |
| FontManagerMixin / FontManagerAccessor | `apply(LFontManager$Preparation;LProfilerFiller;)V`, `close()V` | applied |
| FontPreparedTextBuilderMixin | `Font$PreparedTextBuilder#accept(ILStyle;LBakedGlyph;)Z`; INVOKE `BakedGlyph#createGlyph(FFIILStyle;FF)LTextRenderable;` at call-site | applied |
| FontSetMixin / FontSetAccessor | `computeGlyphInfo(I)LFontSet$SelectedGlyphs;` | applied |
| GuiGraphicsTranslationMixin | all six draw overloads EXACT: `drawString(LFont;LComponent;IIIZ)V`, `drawString(LFont;LString;IIIZ)V`, `drawString(LFont;LFormattedCharSequence;IIIZ)V`, `drawString(LFont;LFormattedCharSequence;III)V`, `drawCenteredString(LFont;LComponent;III)V`, `drawWordWrap(LFont;LFormattedText;IIII)V` | applied |
| HoverTooltipMixin | `renderComponentHoverEffect` -> `(LFont;LStyle;II)V`; `renderTooltip(LFont;LList;IILClientTooltipPositioner;LResourceLocation;)V`; all five `setTooltipForNextFrame` overloads + `setTooltipForNextFrameInternal(...Z)V` EXACT; `@Shadow deferredTooltip:LRunnable;` (1.21.9 deferred-tooltip pipeline) | applied |
| PlayerTabOverlayMixin | `render` -> `(LGuiGraphics;ILScoreboard;LObjective;)V`; INVOKE `Font#split(LFormattedText;I)LList;` at call-site | applied |
| ScoreboardMixin | `Gui#displayScoreboardSidebar(LGuiGraphics;LObjective;)V`; INVOKEs `Objective#getDisplayName()`, `Font#width(LFormattedText;)I`, `GuiGraphics#drawString(LFont;LComponent;IIIZ)V` all at call-sites in `Gui` bytecode (1.21.9 sidebar is direct, no display-entry lambda) | applied |
| ScreenAccessor | `Screen` present | applied |
| ScreenComponentClickMixin | `Screen#handleComponentClicked` unambiguous -> `(LStyle;)Z` (1.21.9/1.21.10 donor-parity entry; 1.21.11 drops it) | applied |
| ScreenGuiTranslationMixin | `Screen#renderWithTooltipAndSubtitles(LGuiGraphics;IIF)V` (1.21.9 frame bracket); INVOKE `GuiGraphics#renderDeferredElements()V` at call-site | applied |
| SignRendererMixin | `AbstractSignRenderer#submitSignText(LSignRenderState;LPoseStack;LSubmitNodeCollector;Z)V`, `#submit(LSignRenderState;LPoseStack;LSubmitNodeCollector;LCameraRenderState;)V`; INVOKE `getDarkColor(LSignText;)I` at call-site (1.21.9 submit-node sign era) | applied (lazy) |
| SignTextMixin | `SignText#getRenderMessages` -> `(ZLFunction;)[LFormattedCharSequence;` | applied |
| TextDisplayMixin | `Display$TextDisplay#cacheDisplay` -> `(LLineSplitter;)LCachedInfo;` | applied (lazy) |
| TitleOverlayMixin | `setTitle/setSubtitle(LComponent;)V`, `setOverlayMessage(LComponent;Z)V`, `render(LGuiGraphics;LDeltaTracker;)V`, `renderBossOverlay/renderChat/renderScoreboardSidebar/renderTabList/renderOverlayMessage/renderTitle(LGuiGraphics;LDeltaTracker;)V`, `clearTitles()V`; INVOKEs `Font#width(LFormattedText;)I`, `GuiGraphics#drawStringWithBackdrop(LFont;LComponent;IIII)V` at call-sites | applied |
| ClientTextTooltipAccessor | `ClientTextTooltip` present; ctor `(LFormattedCharSequence;)V` | applied |

## Optional third-party compat (exact per-version artifact status)

| Integration | Exact-artifact status on MC 1.21.9 |
| --- | --- |
| Wynntils (`compat.WynntilsOverlayManagerMixin`, pseudo, plugin-gated on mod id `wynntils`) | **Permanently dormant: no Wynntils build exists for MC 1.21.9 on any loader.** Modrinth full version list (731 versions, `.analysis/nf-121c/wynntils_versions.json`, fetched 2026-07-26) has zero 1.21.9 or 1.21.10 entries; the 1.21 family jumps 1.21.4 -> 1.21.11, and the 1.21.11 builds pin `minecraft` `[1.21.11]` exactly (verified in `wynntils-4.2.3-neoforge+MC-1.21.11.jar` `neoforge.mods.toml`), so none can load here. API shape `OverlayManager#renderOverlays(Lcom/wynntils/mc/event/RenderEvent;)V` javap-verified in 4.1.22 / 4.2.2 / 4.2.3 neoforge+MC-1.21.11 jars (`.analysis/optional-2111/`) — neighbouring evidence retained for donor parity only. |
| FTB Library (`compat.FtbScreenWrapperTranslationMixin`, `compat.FtbTextFieldTranslationMixin`, pseudo, `remap=false`, plugin-gated on `ftblibrary`) | **Loadable and live**: no 2109-series exists (official maven jumps 2101.1.33 -> 2111.1.0), but `ftb-library-neoforge-2101.1.33` declares `minecraft [1.21.1,)` / `neoforge [21.1.0,)` (open-ended, verified in its `neoforge.mods.toml`), so it installs on 1.21.9. Descriptor-exact vs that jar (`.analysis/optional-121x/`): `ScreenWrapper#keyPressed(III)Z`, `#render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V`; `TextField#draw(LGuiGraphics;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V`, `@Shadow rawText:Lnet/minecraft/network/chat/Component;`, reflective `setText(Component)` returns `TextField`; `formattedText` confirmed `[Lnet/minecraft/network/chat/FormattedText;` and deliberately NOT shadowed. Not runtime-exercised (test client installs no FTB). |
| Iceberg (`compat/IcebergTooltipGatherCompat`, pure reflection, no mixin) | No Iceberg build targets 1.21.9 (Modrinth neoforge list jumps 1.21.4 -> 1.21.11, `.analysis/optional-121x/iceberg_versions.json`). Nearest installable artifact is `Iceberg-1.21.4-neoforge-1.2.13` (declares `minecraft [1.21.3,)`, open-ended): every reflective touch-point verified in that jar — `events.client.RenderTooltipEvents.GATHER : Levents/Event;`, `Event#register(Ljava/lang/Object;)V`, `Gather#onGather(LItemStack;IILjava/util/List;II)LGatherResult;`, `GatherResult(LInteractionResult;ILjava/util/List;)V`. All failures soft (try/catch + warn). |

No row treats a clean build or `defaultRequire` compile success as runtime
proof by itself; the runtime column comes from the 2026-07-26 23:19 world-entry
client run. FTB/Wynntils pseudo application on real third-party installations
remains unexercised because the dedicated test client does not install those
mods.

## Machine-audit PROBLEMS adjudication (re-verified 2026-07-27)

`verify_1219.txt` lists three PROBLEMS rows; each was independently re-checked
with `javap` against `neoforge-21.9.16-beta-merged.jar` this session:

1. `ChatComponentMixin: @Shadow void NOT FOUND` — script parser false positive
   (its regex does not handle the `abstract` modifier and read the member name
   as `void`). Manual javap: `rescaleChat()V` and `allMessages:Ljava/util/List;`
   both exist on `ChatComponent`. Row is EXACT.
2. `CycleButtonTooltipMixin: INVOKE CycleButton#setTooltip NOT FOUND on owner`
   — script limitation: `setTooltip` is declared on the parent
   `AbstractWidget`, so it is absent from `javap CycleButton`'s declared
   members; the bytecode call-site in `CycleButton#updateTooltip` (pc 14) is
   `invokevirtual // Method setTooltip:(Lnet/minecraft/client/gui/components/Tooltip;)V`
   with owner `CycleButton` itself, exactly matching the mixin's `@At` target.
   Also runtime-proven: `CycleButtonTooltipMixin` was mixed in the 23:19 run
   with zero errors under `defaultRequire=1`.
3. `SimpleTranslateMixinPlugin: no @Mixin target found` — not a mixin; it is
   the `IMixinConfigPlugin` gate class. Benign.

## 2026-07-28 packaging follow-up

The Mojang-named ModDevGradle target generates no
`simple_translate.refmap.json`; its stale config declaration was removed.
The clean rebuilt JAR has SHA256
`18C74FAC94CDAC63C20D783013E8888C43786EFBE148E5756490DE673998913B`.
The first post-build launch initialized SimpleTranslate but exited in native
window startup before the menu (Windows fail-fast, no Mixin/Java failure).
The unchanged-JAR retry `validation-refmap-rerun-2-20260728-060800.out.log`
reached `CodexSmokeWorld` at 06:09:04 (`entity id 15`) with zero refmap
warnings, zero Mixin application failures, and no crash report.
