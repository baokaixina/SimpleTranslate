# SimpleTranslate NeoForge 1.21.10 Mixin evidence (2026-07-26)

Exact build target: Minecraft 1.21.10, NeoForge `21.10.64` (ModDevGradle,
Mojang official mappings at compile and runtime, Java 21). Evidence jar:
`build/moddev/artifacts/neoforge-21.10.64.jar` (the exact NeoForge-patched
recompile this project builds against). All vanilla rows below were verified
with `javap -p -s` (and `javap -c` for every `@At(INVOKE)` call-site) against
that jar; the machine-generated audit (131 OK checks, zero unresolved) is
`<workspace>\.analysis\nf-121c\verify_12110.txt`, produced by
`.analysis\nf-121c\verify_mixins.py`. Runtime proof: dedicated client
`<designated-test-client>\neoforge\1.21.10` (`1.21.10-NeoForge`), world entry PASS
2026-07-26 (see latest.log; `CodexTester` joined `CodexSmokeWorld`), zero
mixin injection errors, no crash reports.

Mixin config: 31 client entries (28 vanilla incl. 4 accessors + 3 optional
compat), byte-identical to the Fabric 1.21.10 donor. `defaultRequire=1`.

## Vanilla mixins (exact 21.10.64 descriptors)

The 1.21.10 API surface for every hooked member is descriptor-identical to the
verified 1.21.9 set (`MIXIN_EVIDENCE_1.21.9.md` table); every row was
re-verified independently against the 21.10.64 jar, not assumed:

- `AbstractContainerScreen#renderTooltip(LGuiGraphics;II)V`; `keyPressed` ->
  `(Lnet/minecraft/client/input/KeyEvent;)Z` (input-event era)
- `AbstractWidget#getMessage()LComponent;`
- `AdvancementToast#render(LGuiGraphics;LFont;J)V` + `@Shadow
  advancement:LAdvancementHolder;`; `AdvancementWidget#drawHover(LGuiGraphics;IIFII)V`
  (WrapMethod) + `@Shadow advancementNode:LAdvancementNode;`
- `BookEditScreen` render/appendPageToBook/init/updatePageContent + redirects
  `MultiLineEditBox#setValueListener(LConsumer;)V` / `setValue(LString;Z)V`
  (call-sites confirmed in bytecode); shadows `pages:List`, `currentPage:I`,
  `updatePageContent()V`
- `BookViewScreen` keyPressed(LKeyEvent;)Z / setBookAccess / render + redirect
  `BookViewScreen$BookAccess#getPage(I)LComponent;`; shadows `bookAccess`,
  `cachedPage:I`
- `BossHealthOverlay#render(LGuiGraphics;)V` + INVOKE
  `GuiGraphics#drawString(LFont;LComponent;III)V`
- `ChatComponent#addMessage(LComponent;LMessageSignature;LGuiMessageTag;)V`;
  shadows `allMessages:List`, `rescaleChat()V`
- `ChatScreen#keyPressed(LKeyEvent;)Z`; `@Shadow input:LEditBox;`
- `CycleButton#updateTooltip()V`; call-site invokes `setTooltip:(LTooltip;)V`
  with owner `CycleButton` (pc 14 in `updateTooltip` bytecode)
- `EntityRenderer#extractRenderState(LEntity;LEntityRenderState;F)V`
- `FontManager#apply(LFontManager$Preparation;LProfilerFiller;)V`, `close()V`;
  `Font$PreparedTextBuilder#accept(ILStyle;LBakedGlyph;)Z` + INVOKE
  `BakedGlyph#createGlyph(FFIILStyle;FF)LTextRenderable;`;
  `FontSet#computeGlyphInfo(I)LFontSet$SelectedGlyphs;`
- `GuiGraphics` all six draw overloads EXACT (Component/String/
  FormattedCharSequence drawString ×4, drawCenteredString, drawWordWrap)
- `HoverTooltipMixin` on `GuiGraphics`: `renderComponentHoverEffect
  (LFont;LStyle;II)V`, `renderTooltip(LFont;LList;IILClientTooltipPositioner;
  LResourceLocation;)V`, all five `setTooltipForNextFrame` overloads +
  `setTooltipForNextFrameInternal(...Z)V`, `@Shadow deferredTooltip:LRunnable;`
- `PlayerTabOverlay#render(LGuiGraphics;ILScoreboard;LObjective;)V` + INVOKE
  `Font#split(LFormattedText;I)LList;`
- `Gui#displayScoreboardSidebar(LGuiGraphics;LObjective;)V` with INVOKEs
  `Objective#getDisplayName()`, `Font#width(LFormattedText;)I`,
  `GuiGraphics#drawString(LFont;LComponent;IIIZ)V` (direct method, no lambda)
- `Screen#handleComponentClicked(LStyle;)Z` (ScreenComponentClickMixin,
  donor-parity 1.21.9/1.21.10 entry)
- `Screen#renderWithTooltipAndSubtitles(LGuiGraphics;IIF)V` frame bracket +
  INVOKE `GuiGraphics#renderDeferredElements()V`
- `AbstractSignRenderer#submitSignText(LSignRenderState;LPoseStack;
  LSubmitNodeCollector;Z)V` / `#submit(...LCameraRenderState;)V` + INVOKE
  `getDarkColor(LSignText;)I`; `SignText#getRenderMessages(ZLFunction;)
  [LFormattedCharSequence;`
- `Display$TextDisplay#cacheDisplay(LLineSplitter;)LCachedInfo;`
- `Gui` title/overlay set: `setTitle/setSubtitle(LComponent;)V`,
  `setOverlayMessage(LComponent;Z)V`, `render(LGuiGraphics;LDeltaTracker;)V`,
  `renderBossOverlay/renderChat/renderScoreboardSidebar/renderTabList/
  renderOverlayMessage/renderTitle`, `clearTitles()V`, INVOKEs `Font#width`,
  `GuiGraphics#drawStringWithBackdrop(LFont;LComponent;IIII)V`
- Accessors: `ScreenAccessor`, `ClientTextTooltipAccessor`
  (`ClientTextTooltip(LFormattedCharSequence;)V`), `FontManagerAccessor`,
  `FontSetAccessor` — target classes present.

## Optional third-party compat (exact per-version artifact status)

| Integration | Exact-artifact status on MC 1.21.10 |
| --- | --- |
| Wynntils (`compat.WynntilsOverlayManagerMixin`, pseudo, plugin-gated on `wynntils`) | **Permanently dormant: no Wynntils build exists for MC 1.21.10 on any loader.** Modrinth full list (731 versions, `.analysis/nf-121c/wynntils_versions.json`) has zero 1.21.9/1.21.10 entries; 1.21.11 builds pin `minecraft [1.21.11]` (verified in `wynntils-4.2.3-neoforge+MC-1.21.11.jar`), so none can load here. API shape `OverlayManager#renderOverlays(Lcom/wynntils/mc/event/RenderEvent;)V` javap-verified in 4.1.22 / 4.2.2 / 4.2.3 neoforge+MC-1.21.11 jars — neighbouring evidence for donor parity only (in-source javadoc updated 2026-07-26 to state this). |
| FTB Library (pseudo pair, `remap=false`, plugin-gated on `ftblibrary`) | **Loadable and live**: official maven jumps 2101.1.33 -> 2111.1.0 (no 2110 series), but `ftb-library-neoforge-2101.1.33` declares `minecraft [1.21.1,)` / `neoforge [21.1.0,)` open-ended, so it installs on 1.21.10. Descriptor-exact vs that jar: `ScreenWrapper#keyPressed(III)Z`, `#render(LGuiGraphics;IIF)V`; `TextField#draw(LGuiGraphics;LTheme;IIII)V`, `@Shadow rawText:LComponent;`, reflective `setText(Component)->TextField`; `formattedText:[LFormattedText;` deliberately NOT shadowed. Not runtime-exercised (no FTB in test client). |
| Iceberg (`compat/IcebergTooltipGatherCompat`, reflection only) | No Iceberg build targets 1.21.10 (Modrinth neoforge list jumps 1.21.4 -> 1.21.11). Nearest installable is `Iceberg-1.21.4-neoforge-1.2.13` (`minecraft [1.21.3,)` open-ended); all reflective touch-points verified in that jar: `events.client.RenderTooltipEvents.GATHER`, `Event#register(LObject;)V`, `Gather#onGather(LItemStack;IILList;II)LGatherResult;`, `GatherResult(LInteractionResult;ILList;)V`. Soft-fail by design. |

No row treats compile success as runtime proof; runtime status comes from the
2026-07-26 world-entry client run on the exact 1.21.10 NeoForge instance.
FTB/Wynntils pseudo application on real third-party installs remains
unexercised (test client has no optional mods).

## Machine-audit PROBLEMS adjudication (re-verified 2026-07-27)

`verify_12110.txt` lists three PROBLEMS rows; each was independently
re-checked with `javap` against `neoforge-21.10.64-merged.jar` this session:

1. `ChatComponentMixin: @Shadow void NOT FOUND` — script parser false positive
   (regex cannot parse the `abstract` modifier). Manual javap:
   `rescaleChat()V` exists on `ChatComponent`. Row is EXACT.
2. `CycleButtonTooltipMixin: INVOKE CycleButton#setTooltip NOT FOUND on owner`
   — `setTooltip` is inherited from `AbstractWidget`; the call-site in
   `CycleButton#updateTooltip` (pc 14) is
   `invokevirtual // Method setTooltip:(Lnet/minecraft/client/gui/components/Tooltip;)V`
   with owner `CycleButton`, matching the mixin `@At` target exactly. Also
   runtime-proven in the 23:25 world-entry run (mixed, zero errors,
   `defaultRequire=1`).
3. `SimpleTranslateMixinPlugin: no @Mixin target found` — the
   `IMixinConfigPlugin` gate class, not a mixin. Benign.

## 2026-07-28 packaging follow-up

This Mojang-named ModDevGradle target generates no
`simple_translate.refmap.json`; its stale Mixin-config declaration was
removed. The clean rebuilt JAR has SHA256
`53F679AD464713A77403AD49BA35228DF80D6E3612063A6DFE8055AC10B9AF69`.
`validation-refmap-rerun-20260728-060000.out.log` deployed it to exact
NeoForge `21.10.64` and entered `CodexSmokeWorld` at 06:00:55 (`entity id
56`), with zero refmap warnings, zero Mixin application failures, and no
crash report.
