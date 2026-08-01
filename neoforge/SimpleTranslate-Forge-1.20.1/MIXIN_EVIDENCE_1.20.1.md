# SimpleTranslate Forge 1.20.1 Mixin evidence

Exact build target: Forge `1.20.1-47.4.10`, official (Mojmap) mappings via
ForgeGradle 6. Vanilla + Forge descriptors were inspected with `javap -p -s`
(and `javap -c` where invocation owners mattered) against the exact compile
artifact `forge-1.20.1-47.4.10_mapped_official_1.20.1.jar` (ForgeGradle
`minecraft_user_repo`); dumps are kept in `.analysis/forge-1.20.1-47.4.10/`.
Optional-mod descriptors were inspected with `javap -p -s` against the exact
production jars kept in `libs/optional-evidence/`:
`ftb-library-forge-2001.2.13.jar` (newest 1.20.1-series FTB Library Forge
build) and `Iceberg-1.20.1-forge-1.1.25.jar` (the 1.20.1 Forge Iceberg build);
the same jars were independently verified by the NeoForge 1.20.1 sibling audit
(`.analysis/`, NF-120A 2026-07-26), including `ftb-library-forge-2001.2.6.jar`
for the older 2001.x series. Audit date: 2026-07-26; re-verified 2026-07-27
(fresh logic checks, fixtures, clean build, and a new `-EnterWorld` client
check: PASS with world entry, `CodexTester ... logged in with entity id 186`,
23 mixins applied, zero mixin warnings, no crash reports, deployed-jar SHA-256
identical to `build/libs`). Runtime smoke client:
`<designated-test-client>\forge\1.20.1forge` (Forge 47.4.20 per PCL `CardValue1`).

Follow-up on 2026-07-28 repeated exact-client world entry (entity id 186) and
used direct window key messages to open the live PauseScreen and invoke the K
whole-frame shortcut. The captured PauseScreen layout stayed stable and the
smoke config persisted its `PauseScreen` frame key; this exercises the
ForgeGui event path rather than only the static Mixin target.

| Surface | Exact target descriptor | Handler / field evidence | Runtime status |
| --- | --- | --- | --- |
| Chat AUTO/collect | `ChatComponent#addMessage(Component,MessageSignature,GuiMessageTag)V` TAIL | shadow `allMessages:List<GuiMessage>` (final), `rescaleChat()V` | client check 2026-07-26 + 2026-07-27 world entry |
| Chat send key | `ChatScreen#keyPressed(III)Z` HEAD | shadow `input:EditBox` (protected via Forge patch) | client check 2026-07-26 + 2026-07-27 world entry |
| Chat click | `Screen#handleComponentClicked(Style)Z` HEAD (`ScreenComponentClickMixin`) | cancellable callback | client check 2026-07-26 + 2026-07-27 world entry |
| Hover text | `GuiGraphics#renderComponentHoverEffect(Font,Style,II)V` HEAD | cancellable callback (1.20.1 moved hover drawing from `Screen` to `GuiGraphics`) | client check 2026-07-26 + 2026-07-27 world entry |
| Tooltip pipeline | `GuiGraphics#renderTooltipInternal(Font,List,II,ClientTooltipPositioner)V` HEAD/TAIL; `renderTooltip(Font,List,Optional,II)V`; `renderTooltip(Font,ItemStack,II)V`; `renderTooltip(Font,List,ClientTooltipPositioner,II)V` | all overloads present in the 47.4.10 mapped jar; `ClientTextTooltip.text:FormattedCharSequence` accessor | client check 2026-07-26 + 2026-07-27 world entry |
| Container tooltip frame | `AbstractContainerScreen#renderTooltip(GuiGraphics,II)V` HEAD/RETURN; `keyPressed(III)Z` | callbacks verified | client check 2026-07-26 + 2026-07-27 world entry |
| Whole-frame GUI | `Screen#renderWithTooltip(GuiGraphics,IIF)V` HEAD/RETURN | `GuiTranslationHelper.beginFrame/endFrame` | client check 2026-07-26 + 2026-07-27 world entry; 2026-07-28 live PauseScreen K visual/state check |
| GUI draw hooks | `GuiGraphics#drawString(Font,Component,IIIZ)I`, `(Font,String,IIIZ)I`, `(Font,FormattedCharSequence,IIIZ)I`, `(Font,FormattedCharSequence,III)I`, `drawCenteredString(Font,Component,III)V`, `drawWordWrap(Font,FormattedText,IIII)V` | all overloads present (instance methods on `GuiGraphics`, not the 1.19.x `GuiComponent` statics) | client check 2026-07-26 + 2026-07-27 world entry |
| Widget label | `AbstractWidget#getMessage()Component` RETURN | return-value callback | client check 2026-07-26 + 2026-07-27 world entry |
| Cycle button tooltip | `CycleButton#updateTooltip()V` WrapOperation INVOKE `setTooltip(Tooltip)V` | `javap -c`: invokevirtual owner is `CycleButton` (declared in `AbstractWidget`) | client check 2026-07-26 + 2026-07-27 world entry |
| Book view | `BookViewScreen#keyPressed`, `#setBookAccess(BookViewScreen$BookAccess)V`, `#render(GuiGraphics,IIF)V`; redirect `BookAccess#getPage(I)FormattedText` | shadow `bookAccess`, `cachedPage:int` | client check 2026-07-26 + 2026-07-27 world entry |
| Book edit | `BookEditScreen#render`, `#setCurrentPageText(String)V`, `#appendPageToBook()V`, `#rebuildDisplayCache()`; redirects `getCurrentPageText()String`, `TextFieldHelper#getCursorPos()I`, `#getSelectionPos()I` | shadow `pages:List<String>` (final), `currentPage:int`, `isSigning:boolean` | client check 2026-07-26 + 2026-07-27 world entry |
| Sign | `SignRenderer#renderSignText(BlockPos,SignText,PoseStack,MultiBufferSource,IIIZ)V` HEAD + INVOKE `getDarkColor(SignText)I` (static) | 1.20.1 has the dedicated `renderSignText` method (unlike 1.19.2's inline `render`) | client check 2026-07-26 + 2026-07-27 world entry |
| Sign text | `SignText#getRenderMessages(Z,Function)[FormattedCharSequence` HEAD | cancellable; `(ZLjava/util/function/Function;)[Lnet/minecraft/util/FormattedCharSequence;` exact | client check 2026-07-26 + 2026-07-27 world entry |
| Scoreboard | `Gui#displayScoreboardSidebar(GuiGraphics,Objective)V` HEAD/RETURN; WrapOperation `Objective#getDisplayName()Component`, `Font#width(FormattedText)I`, `GuiGraphics#drawString(Font,Component,IIIZ)I` | MixinExtras `@WrapOperation`; `ForgeGui` does not override `displayScoreboardSidebar`, so the vanilla body (and these wraps) run under the SCOREBOARD overlay | client check 2026-07-26 + 2026-07-27 world entry |
| Tab list | `PlayerTabOverlay#render(GuiGraphics,I,Scoreboard,Objective)V` redirect `Font#split(FormattedText,I)List` | redirect verified; called by ForgeGui PLAYER_LIST overlay | client check 2026-07-26 + 2026-07-27 world entry |
| Boss bar | `BossHealthOverlay#render(GuiGraphics)V` WrapOperation `GuiGraphics#drawString(Font,Component,III)I` | `ForgeGui.renderBossHealth` invokes `BossHealthOverlay.render(GuiGraphics)` (javap -c), so the wrap is live | client check 2026-07-26 + 2026-07-27 world entry |
| Title/HUD | setter/clear hooks on vanilla `Gui`: `setTitle(Component)V`, `setSubtitle(Component)V`, `setOverlayMessage(Component,Z)V`, `clear()V` TAIL + `<init>` TAIL listener registration. Per-frame swap and whole-HUD K frame driven by `RenderGuiEvent.Pre/Post`; dedicated-surface capture suppression by `RenderGuiOverlayEvent.Pre/Post` on `VanillaGuiOverlay` ids `BOSS_EVENT_PROGRESS`, `CHAT_PANEL`, `SCOREBOARD`, `PLAYER_LIST`, `TITLE_TEXT`, `RECORD_OVERLAY` | **Forge-specific rework (2026-07-26):** `Minecraft` instantiates `ForgeGui`, whose `render(GuiGraphics,F)` override posts `RenderGuiEvent.Pre`, runs the `GuiOverlayManager` overlay list (per-overlay `RenderGuiOverlayEvent.Pre/Post`), and posts `RenderGuiEvent.Post` without calling vanilla `Gui.render` — donor-style `Gui.render` injections are dead code on Forge (all verified by `javap -c` on the 47.4.10 jar). `ForgeGui.renderTitle`/`renderRecordOverlay` read the shadowed `title`/`subtitle`/`overlayMessageString` fields directly (getfield verified), so the event-time field swap is behavior-identical to the donor's inline wraps; `HudFeature.renderLayoutActionbar` is a `false` stub below 1.21.4, so swapping the actionbar field to `layoutActionbarSource` preserves both width and draw. `IEventBus.addListener(EventPriority,boolean,Class,Consumer)` explicit-class overload confirmed in eventbus 6.0.5 | client check 2026-07-26 + 2026-07-27 world entry |
| Advancement toast | `AdvancementToast#render(GuiGraphics,ToastComponent,J)Toast$Visibility` | shadow `advancement:Advancement` (final; 1.20.1 still pre-`AdvancementHolder`) | client check 2026-07-26 + 2026-07-27 world entry |
| Advancement widget | `AdvancementWidget#drawHover(GuiGraphics,IIFII)V` WrapMethod | shadow `advancement:Advancement` (final) | client check 2026-07-26 + 2026-07-27 world entry |
| Entity name | `EntityRenderer#renderNameTag(T,Component,PoseStack,MultiBufferSource,I)V` ModifyVariable argsOnly ordinal 0 | Component arg is ordinal-0 Component | client check 2026-07-26 + 2026-07-27 world entry |
| Text display | `Display$TextDisplay#cacheDisplay(Display$TextDisplay$LineSplitter)Display$TextDisplay$CachedInfo` HEAD cancellable | 1.20.1 has Display entities (added 1.19.4) | client check 2026-07-26 + 2026-07-27 world entry |
| Font manager / glyphs | `FontManager#apply(FontManager$Preparation,ProfilerFiller)V` RETURN, `#close()V` HEAD; `FontSet#getGlyphInfo(IZ)GlyphInfo`, `#getGlyph(I)BakedGlyph` RETURN | accessor `fontSets:Map<ResourceLocation,FontSet>`, `missingGlyph:BakedGlyph` | client check 2026-07-26 + 2026-07-27 world entry |
| FTB wrapper (optional) | `dev.ftb.mods.ftblibrary.ui.ScreenWrapper` `m_7933_(III)Z` (SRG Screen#keyPressed), `m_88315_(Lnet/minecraft/client/gui/GuiGraphics;IIF)V` (SRG Screen#render) | pseudo mixin, `remap=false`; SRG member names present in exact FTB Forge jar bytecode (class names stay Mojmap); `m_280273_(GuiGraphics)V` confirmed to be renderBackground, not hooked; gated by `SimpleTranslateMixinPlugin` on `ftblibrary` | not installed in test client; static bytecode evidence only |
| FTB text field (optional) | `dev.ftb.mods.ftblibrary.ui.TextField#draw(Lnet/minecraft/client/gui/GuiGraphics;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V` HEAD/RETURN | shadow `rawText:Lnet/minecraft/network/chat/Component;` (private, exact descriptor); `formattedText` is `[Lnet/minecraft/network/chat/FormattedText;` and is NOT shadowed; `setText(Component)` public, invoked reflectively (FTB-typed return `TextField` not linkable from pseudo mixin) | not installed in test client; static bytecode evidence only |
| Iceberg gather (optional, event not mixin) | `com.anthonyhilyard.iceberg.events.GatherComponentsExtEvent extends net.minecraftforge.client.event.RenderTooltipEvent$GatherComponents`; ctor `(ItemStack,II,List,II)V`; `getIndex()I` | registered reflectively by `compat.IcebergTooltipGatherCompat` only when `iceberg` is loaded; no Iceberg mixin | not installed in test client; static bytecode evidence only |

Loader API evidence (47.4.10 bytecode):

- SimpleChannel-era networking: `NetworkRegistry.newSimpleChannel(ResourceLocation,Supplier,Predicate,Predicate)`,
  `SimpleChannel.messageBuilder(Class,int[,NetworkDirection])`,
  `PacketDistributor.PLAYER` — correct era for 1.20.1 Forge.
- `RegisterKeyMappingsEvent.register(KeyMapping)V` present.
- `ConfigScreenHandler.ConfigScreenFactory` extension point present.
- `IEventBus.addListener(EventPriority, boolean, Class, Consumer)` present in
  eventbus 6.0.5 (the version 1.20.1 Forge ships).

Notes:

- The Title/HUD surface is the only donor Mixin whose runtime reachability
  differs on Forge: every other hooked vanilla method is still executed by the
  `ForgeGui` overlay pipeline (`renderBossHealth` → `BossHealthOverlay.render`,
  SCOREBOARD overlay → non-overridden `displayScoreboardSidebar`, PLAYER_LIST →
  `PlayerTabOverlay.render`, CHAT_PANEL → `ChatComponent.render`) or is not on
  the `Gui.render` path at all.
- MixinExtras (`@WrapOperation`/`@WrapMethod`) is compiled against
  `mixinextras-common:0.4.1` and shipped via JarJar; its annotation processor
  stays on the AP path so SRG runtimes get remapped operation targets.
- Optional FTB/Iceberg surfaces are not runtime-verified because the
  designated test client does not install those mods; their evidence is
  descriptor-exact bytecode from the exact jars named above.
