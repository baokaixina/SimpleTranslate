# SimpleTranslate NeoForge 1.20.1 Mixin evidence

Exact build target: NeoForge `1.20.1-47.1.105` (Forge-fork era, `net.minecraftforge`
namespace, `mods.toml`), mappings channel `official` (Mojmap at dev time, SRG at
runtime). Evidence jar: ForgeGradle
`forge-1.20.1-47.1.105_mapped_official_1.20.1-recomp.jar` inspected with
`javap -p -s` (2026-07-26). All 26 vanilla mixin classes have resolved entries in
the packaged `simple_translate.refmap.json`
(`build/libs/simple_translate-1.20.1-neoforge-2.1.28.jar`); the two FTB pseudo
mixins are `remap = false` and intentionally absent from the refmap.

Third-party evidence jars (exact, `libs/optional-evidence/`):
`ftb-library-forge-2001.2.6.jar`, `ftb-library-forge-2001.2.13.jar`,
`Iceberg-1.20.1-forge-1.1.25.jar`. FTB and Iceberg ship only Forge builds for
1.20.1; NeoForge 20.1 is the Forge-compatible fork era, so the Forge jars are
the correct runtime artifacts and members carry SRG names for Minecraft
methods and source names for FTB's own members.

| Mixin | Exact target (javap verified) | Shadows / redirect targets | Notes |
| --- | --- | --- | --- |
| `AbstractContainerScreenMixin` | `AbstractContainerScreen#renderTooltip(GuiGraphics,II)V`; `keyPressed(III)Z` | — | HEAD/RETURN + optional key hook |
| `AbstractWidgetTranslationMixin` | `AbstractWidget#getMessage()Component` | — | RETURN |
| `AdvancementToastMixin` | `AdvancementToast#render(GuiGraphics,ToastComponent,J)Toast$Visibility` | `advancement:Advancement` (final field present) | |
| `AdvancementWidgetMixin` | `AdvancementWidget#drawHover(GuiGraphics,IIFII)V` (WrapMethod) | `advancement:Advancement` | MixinExtras embedded via Forge bootstrap jar |
| `BookEditScreenMixin` | `render`, `setCurrentPageText`, `appendPageToBook`, `rebuildDisplayCache` | shadows `pages:List`, `currentPage:I`, `isSigning:Z`, `getCurrentPageText()String`, `clearDisplayCache()V`; redirects `BookEditScreen#getCurrentPageText()String`, `TextFieldHelper#getCursorPos()I`, `TextFieldHelper#getSelectionPos()I` | all present in 47.1.105 bytecode |
| `BookViewScreenMixin` | `keyPressed`, `setBookAccess`, `render`; redirect `BookViewScreen$BookAccess#getPage(I)FormattedText` | shadows `bookAccess`, `cachedPage:I` | |
| `BossHealthOverlayMixin` | `BossHealthOverlay#render(GuiGraphics)V`; ModifyArg at `GuiGraphics#drawString(Font,Component,III)I` | — | |
| `ChatComponentMixin` | `ChatComponent#addMessage(Component,MessageSignature,GuiMessageTag)V` TAIL | shadows `allMessages:List<GuiMessage>`, `rescaleChat()V` | 1.20.1 3-arg overload confirmed |
| `ChatScreenMixin` | `ChatScreen#keyPressed` HEAD | shadow `input:EditBox` | |
| `ClientTextTooltipAccessor` | `ClientTextTooltip.text` | accessor | |
| `CycleButtonTooltipMixin` | `CycleButton#updateTooltip`; WrapOperation at `CycleButton#setTooltip(Tooltip)V` | — | |
| `EntityRendererMixin` | `EntityRenderer#renderNameTag` ModifyVariable argsOnly ordinal 0 | — | |
| `FontManagerMixin` / `FontManagerAccessor` | `FontManager#apply`, `#close`; accessor `fontSets` | — | |
| `FontSetMixin` / `FontSetAccessor` | `FontSet#getGlyphInfo`, `#getGlyph`; accessor `missingGlyph` | — | |
| `GuiGraphicsTranslationMixin` | `drawString(Font,Component,IIIZ)I`, `drawString(Font,String,IIIZ)I`, `drawString(Font,FormattedCharSequence,IIIZ)I`, `drawString(Font,FormattedCharSequence,III)I`, `drawCenteredString(Font,Component,III)V`, `drawWordWrap(Font,FormattedText,IIII)V` | — | all six overloads present in 47.1.105 |
| `HoverTooltipMixin` | `GuiGraphics#renderComponentHoverEffect`; `renderTooltipInternal(Font,List,II,ClientTooltipPositioner)V` (private, confirmed); `renderTooltip(Font,List,Optional,II)V`; `renderTooltip(Font,ItemStack,II)V`; `renderTooltip(Font,List,ClientTooltipPositioner,II)V` | — | |
| `PlayerTabOverlayMixin` | `PlayerTabOverlay#render`; redirect `Font#split(FormattedText,I)List` | — | |
| `ScoreboardMixin` | `Gui#displayScoreboardSidebar(GuiGraphics,Objective)V`; redirects `Objective#getDisplayName()Component`, `Font#width(FormattedText)I`, `GuiGraphics#drawString(Font,Component,IIIZ)I` | — | |
| `ScreenComponentClickMixin` | `Screen#handleComponentClicked` HEAD cancellable | — | |
| `ScreenGuiTranslationMixin` | `Screen#renderWithTooltip(GuiGraphics,IIF)V` HEAD/RETURN | — | whole-frame GUI window |
| `SignRendererMixin` | `SignRenderer#renderSignText(BlockPos,SignText,PoseStack,MultiBufferSource,IIIZ)V`; redirect `SignRenderer#getDarkColor(SignText)I` | — | 1.20.1 has separate `renderSignText` |
| `SignTextMixin` | `SignText#getRenderMessages` HEAD cancellable | — | |
| `TextDisplayMixin` | `Display$TextDisplay#cacheDisplay` HEAD cancellable | shadow `textRenderState:Display$TextDisplay$TextRenderState` | |
| `TitleOverlayMixin` | `Gui#setTitle(Component)V`, `#setSubtitle(Component)V`, `#setOverlayMessage(Component,Z)V`, `#render(GuiGraphics,F)V` | shadows `title`, `subtitle`, `overlayMessageString` (all `Component`) | |
| `compat.FtbScreenWrapperTranslationMixin` (`@Pseudo`) | `dev.ftb.mods.ftblibrary.ui.ScreenWrapper#m_7933_(III)Z` = `Screen#keyPressed`; `m_88315_(GuiGraphics,IIF)V` = `Screen#render` — declared in `ScreenWrapper.class` of both 2001.2.6 and 2001.2.13 | — | SRG member names confirmed byte-exact in both jars; `m_280273_` is `renderBackground`, not used |
| `compat.FtbTextFieldTranslationMixin` (`@Pseudo`) | `dev.ftb.mods.ftblibrary.ui.TextField#draw(GuiGraphics,Theme,IIII)V` | shadow `rawText:Component` (exact); `formattedText` is `[Lnet/minecraft/network/chat/FormattedText;` (NOT FormattedCharSequence[]) — mixin correctly shadows only `rawText`; `setText(Component)` public, invoked reflectively (FTB return type not linkable from pseudo mixin) | descriptors confirmed in 2001.2.6 and 2001.2.13 |

Loader API evidence (same recomp jar, `net.minecraftforge`):

- `NetworkRegistry.newSimpleChannel(ResourceLocation,Supplier,Predicate,Predicate)` and
  `SimpleChannel.messageBuilder(Class,int)` / `sendToServer` / `send(PacketTarget,MSG)`
  present in 47.1.105 — SimpleChannel-era networking is correct for this target.
- `RegisterKeyMappingsEvent.register(KeyMapping)` present (mod event bus).
- Client/server split: `SimpleTranslateMod` (loaded both sides) imports no
  `net.minecraft.client.*`; all client state lives in
  `SimpleTranslateClientBootstrap` behind `FMLEnvironment.dist == Dist.CLIENT`.
- Iceberg compat is reflection-gated (`ModList.isLoaded` + `Class.forName`), no
  Iceberg mixin. `GatherComponentsExtEvent extends
  net.minecraftforge.client.event.RenderTooltipEvent$GatherComponents`
  confirmed by javap against `Iceberg-1.20.1-forge-1.1.25.jar`; no `GATHER`
  static field in the Forge jar, registration via Forge event bus is correct.

Runtime status: client check 2026-07-26 (see `docs/sync-2026-07-26/NF-120A.md`).
Optional FTB/Iceberg paths are not installed in the test client; their evidence
is bytecode-exact only.
