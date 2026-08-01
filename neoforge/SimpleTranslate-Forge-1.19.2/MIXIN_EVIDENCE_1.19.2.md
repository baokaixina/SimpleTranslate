# SimpleTranslate Forge 1.19.2 Mixin evidence

Exact build target: Forge `1.19.2-43.5.2`, official (Mojmap) mappings via
ForgeGradle 6. Vanilla + Forge descriptors were inspected with `javap -p -s`
against the exact compile artifact
`forge-1.19.2-43.5.2_mapped_official_1.19.2.jar` (ForgeGradle
`minecraft_user_repo`). Optional-mod descriptors were inspected with
`javap -p -s` against the exact production jars kept in
`libs/optional-evidence/`: `ftb-library-forge-1902.4.2-build.701.jar` (newest
1.19.2-series FTB Library Forge build) and `Iceberg-1.19.2-forge-1.1.4.jar`
(the only Forge Iceberg build declared for 1.19/1.19.1/1.19.2). Audit date:
2026-07-26; re-audited 2026-07-27 (Title/HUD Forge rework verified against
`forge-1.19.2-43.5.2_mapped_official` bytecode; dumps in
`.analysis/forge-1.19.2-43.5.2/`). Runtime smoke client:
`<designated-test-client>\forge\1.19.2forge` (Forge 43.5.2).

Follow-up validation on 2026-07-28 deployed the non-sources jar to that exact
client, created the isolated `CodexSmokeWorld`, and reached the world. The
integrated server logged `CodexTester` with entity id 140, the client script
reported world-entry PASS, and the captured in-world screenshot is
`validation-k-gui-pressed.png`. The 1.19.2 smoke helper now uses only its
named smoke save (never a pre-existing user save), applies per-monitor DPI
coordinates, and uses the title/select-world coordinates measured from the
exact client UI. A direct `WM_KEYDOWN/UP` check then opened the inventory with
`E`, sent `K`, captured the stable inventory layout, and confirmed that
`content.guiFrameScreenKeys` persisted both
`InventoryScreen` and the translated `container.crafting` frame key. This is
the K whole-frame GUI visual/state check for the exact client.

| Surface | Exact target descriptor | Handler / field evidence | Runtime status |
| --- | --- | --- | --- |
| Chat AUTO/collect | `ChatComponent#addMessage(Component,MessageSignature,GuiMessageTag)V` TAIL | shadow `allMessages:List<GuiMessage>` (final), `rescaleChat()V` | client check 2026-07-27 startup verified; in-world interaction pending |
| Chat send key | `ChatScreen#keyPressed(III)Z` HEAD | shadow `input:EditBox` (protected via Forge patch) | client check 2026-07-27 startup verified |
| Chat click | `Screen#handleComponentClicked(Style)Z` HEAD | callback `(Style,CallbackInfoReturnable)` | client check 2026-07-27 startup verified |
| Hover text | `Screen#renderComponentHoverEffect(PoseStack,Style,II)V` HEAD | cancellable callback | client check 2026-07-27 startup verified |
| Tooltip pipeline | `Screen#renderTooltipInternal(PoseStack,List,II)V` HEAD/TAIL; `renderTooltip(PoseStack,List,Optional,II)V`; `renderTooltip(PoseStack,ItemStack,II)V`; `renderTooltip(PoseStack,List,II)V` (FormattedCharSequence overload) | all four overloads present in mapped jar; `ClientTextTooltip.text:FormattedCharSequence` accessor | client check 2026-07-27 startup verified; in-world interaction pending |
| Container tooltip frame | `AbstractContainerScreen#renderTooltip(PoseStack,II)V` HEAD/RETURN; `keyPressed(III)Z` | callbacks verified | client check 2026-07-27 startup verified |
| Whole-frame GUI | `Screen#render(PoseStack,IIF)V` HEAD/RETURN (1.19.2 has no `renderWithTooltip`; tooltips draw inside `render`) | `GuiTranslationHelper.beginFrame/endFrame` | 2026-07-28 exact-client world entry plus InventoryScreen K check verified; persisted frame key and stable inventory screenshot |
| GUI draw hooks | `GuiComponent.drawString(PoseStack,Font,Component,III)V` static, `drawCenteredString(...)` same, String and FormattedCharSequence overloads | all six static overloads present | client check 2026-07-27 startup verified |
| Font draw hooks | `Font#draw(PoseStack,Component,FFI)I`, `#drawShadow(PoseStack,Component,FFI)I`, `#draw(PoseStack,String,FFI)I`, `#draw(PoseStack,FormattedCharSequence,FFI)I` | all overloads present | client check 2026-07-27 startup verified |
| Widget label | `AbstractWidget#getMessage()Component` RETURN | return-value callback | client check 2026-07-27 startup verified |
| Book view | `BookViewScreen#keyPressed`, `#setBookAccess(BookAccess)V`, `#render(PoseStack,IIF)V`; redirect `BookAccess#getPage(I)FormattedText` | shadow `bookAccess`, `cachedPage:int` | client check 2026-07-27 startup verified |
| Book edit | `BookEditScreen#render`, `#setCurrentPageText(String)V`, `#appendPageToBook()V`, `#rebuildDisplayCache()`; redirects `getCurrentPageText()String`, `TextFieldHelper#getCursorPos()I`, `#getSelectionPos()I` | shadow `pages:List<String>` (final), `currentPage:int`, `isSigning:boolean` | client check 2026-07-27 startup verified |
| Sign | `SignRenderer#render(SignBlockEntity,F,PoseStack,MultiBufferSource,II)V` HEAD + INVOKE `getDarkColor(SignBlockEntity)I` (private static) | callbacks verified | client check 2026-07-27 startup verified; in-world interaction pending |
| Sign text | `SignBlockEntity#getRenderMessages(Z,Function)FormattedCharSequence[]` HEAD | cancellable | client check 2026-07-27 startup verified |
| Scoreboard | `Gui#displayScoreboardSidebar(PoseStack,Objective)V` HEAD/RETURN; WrapOperation `Objective#getDisplayName()Component`, `Font#width(FormattedText)I`, `Font#draw(PoseStack,Component,FFI)I` | MixinExtras `@WrapOperation`; AP on annotationProcessor path (see build.gradle note) | client check 2026-07-27 startup verified |
| Tab list | `PlayerTabOverlay#render(PoseStack,I,Scoreboard,Objective)V` redirect `Font#split(FormattedText,I)List` | redirect verified | client check 2026-07-27 startup verified |
| Boss bar | `BossHealthOverlay#render(PoseStack)V` WrapOperation `Font#drawShadow(PoseStack,Component,FFI)I` | verified | client check 2026-07-27 startup verified |
| Title/HUD | setter/clear hooks on vanilla `Gui`: `setTitle(Component)V`, `setSubtitle(Component)V`, `setOverlayMessage(Component,Z)V`, `clear()V` TAIL + `<init>` TAIL listener registration. Per-frame swap and whole-HUD K frame driven by `RenderGuiEvent.Pre/Post`; dedicated-surface capture suppression by `RenderGuiOverlayEvent.Pre/Post` on `VanillaGuiOverlay` ids `BOSS_EVENT_PROGRESS`, `CHAT_PANEL`, `SCOREBOARD`, `PLAYER_LIST`, `TITLE_TEXT`, `RECORD_OVERLAY` | **Forge-specific rework (2026-07-26/27):** `Minecraft` instantiates `net.minecraftforge.client.gui.overlay.ForgeGui`, whose `render(PoseStack,F)` override posts `RenderGuiEvent.Pre`, runs the `GuiOverlayManager.getOverlays()` list, and posts `RenderGuiEvent.Post` without ever calling vanilla `Gui.render` — donor-style `Gui.render` injections/wraps are dead code on Forge (all verified by `javap -c` on the 43.5.2 mapped jar, `.analysis/forge-1.19.2-43.5.2/ForgeGui.javap-c.txt`). `ForgeGui.renderTitle`/`renderRecordOverlay` read the shadowed `title`/`subtitle`/`overlayMessageString` fields directly (getfield verified; `renderRecordOverlay` uses `overlayMessageString` for both `Font.width` and `Font.drawShadow`), so the event-time field swap is behavior-identical to the donor's inline wraps; `HudFeature.renderLayoutActionbar` is a `false` stub below 1.21.4, so swapping the actionbar field to `layoutActionbarSource` preserves both width and draw. The SCOREBOARD overlay lambda invokes non-overridden `ForgeGui.displayScoreboardSidebar` (javap -c on `VanillaGuiOverlay`), so `ScoreboardMixin` stays live; `BossHealthOverlay.render` / `ChatComponent.render` / `PlayerTabOverlay.render` calls confirmed inside ForgeGui. `IEventBus.addListener(EventPriority,boolean,Class,Consumer)` explicit-class overload confirmed in eventbus 6.0.3 (the version the 43.5.2 client ships) and compiles at the 43.0.0 floor | 2026-07-28 exact-client world entry verified (entity id 140) and InventoryScreen K visual/state check verified |
| Advancement toast | `AdvancementToast#render(PoseStack,ToastComponent,J)Toast$Visibility` | shadow `advancement:Advancement` (final) | client check 2026-07-27 startup verified |
| Advancement widget | `AdvancementWidget#drawHover(PoseStack,IIFII)V` WrapMethod | shadow `advancement:Advancement` (final) | client check 2026-07-27 startup verified |
| Entity name | `EntityRenderer#renderNameTag(T,Component,PoseStack,MultiBufferSource,I)V` ModifyVariable argsOnly ordinal 0 | Component arg is ordinal-0 Component | client check 2026-07-27 startup verified |
| Font manager / glyphs | `FontManager#<init>` RETURN, `#close()V` HEAD; `FontManager$1#apply(Map,ResourceManager,ProfilerFiller)V` RETURN; `FontSet#getGlyphInfo(IZ)GlyphInfo`, `#getGlyph(I)BakedGlyph` RETURN | accessor `fontSets:Map<ResourceLocation,FontSet>` (package-final), `missingGlyph:BakedGlyph` | client check 2026-07-27 startup verified |
| FTB wrapper (optional) | `dev.ftb.mods.ftblibrary.ui.ScreenWrapper` `m_7933_(III)Z` (SRG Screen#keyPressed), `m_6305_(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V` (SRG Screen#render) | pseudo mixin, `remap=false`; SRG names present in exact FTB Forge jar bytecode; gated by `SimpleTranslateMixinPlugin` on `ftblibrary` | not installed in test client; static bytecode evidence only |
| FTB text field (optional) | `dev.ftb.mods.ftblibrary.ui.TextField#draw(Lcom/mojang/blaze3d/vertex/PoseStack;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V` | shadow `component:Lnet/minecraft/network/chat/Component;` (public, exact descriptor); `setText(Component)` invoked reflectively (FTB-typed return `TextField` not linkable from pseudo mixin) | not installed in test client; static bytecode evidence only |
| Iceberg gather (optional, event not mixin) | `com.anthonyhilyard.iceberg.events.GatherComponentsExtEvent extends net.minecraftforge.client.event.RenderTooltipEvent$GatherComponents`; ctor `(ItemStack,II,List,II)V`; `getIndex()I` | Forge base getters verified in mapped jar: `getTooltipElements()List<Either<FormattedText,TooltipComponent>>`, `getItemStack()`, `getScreenWidth()`, `getScreenHeight()`, `getMaxWidth()`; registered reflectively only when `iceberg` is loaded | not installed in test client; static bytecode evidence only |

Notes:

- 1.19.2 `Screen` has no `renderWithTooltip`; the whole-frame GUI window is
  `render(PoseStack,IIF)` HEAD/RETURN, and tooltips drawn on the same screen
  pass through `renderTooltipInternal` inside that window.
- MixinExtras (`@WrapOperation`/`@WrapMethod`) is compiled against
  `mixinextras-common:0.4.1` and shipped via JarJar
  (`mixinextras-forge:[0.4.1,0.5.0)`); its annotation processor must stay on
  the AP path or SRG runtimes fail injection (see build.gradle comment).
- Optional FTB/Iceberg surfaces are not runtime-verified because the
  designated test client does not install those mods; their evidence is
  descriptor-exact bytecode from the exact jars named above.
