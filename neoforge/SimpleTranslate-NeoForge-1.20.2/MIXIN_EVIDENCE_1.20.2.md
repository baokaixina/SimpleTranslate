# SimpleTranslate NeoForge 1.20.2 Mixin evidence

Exact build target: NeoForge `20.2.93` (`net.neoforged` namespace, `mods.toml`
metadata era, NeoGradle userdev 7.0.61), Mojmap at dev time and at runtime
(NeoForge 20.2+ ships official names, so no refmap is required in the packaged
jar). Evidence jars inspected with
`javap -p -s` (2026-07-26):

- Vanilla/patched: `build/neoForm/neoFormJoined1.20.2-20231019.002635/steps/recompile/output.jar`
- Loader API: `neoforge-20.2.93-universal.jar` (Gradle modules cache)

## Optional-compat gap (documented, intentional)

FTB Library and Iceberg publish **no NeoForge builds for Minecraft 1.20.2**
(CurseForge/Modrinth checked during the 2026-07-26 audit; see
`docs/API_MIXIN_AUDIT_2026-07-26.md`). Therefore this target ships:

- no `compat/` classes, no `mixin/compat/` pseudo mixins, no
  `SimpleTranslateMixinPlugin` (nothing to gate);
- `simple_translate.mixins.json` lists exactly the 26 vanilla entries below and
  no dormant entries referencing absent classes.

This is a dependency-nonexistence gap, not an omission to fix. If FTB/Iceberg
ever publish 1.20.2 NeoForge artifacts, port the compat surface from the
NeoForge 1.20.1 target with new exact-jar evidence.

## Vanilla mixin table (26 entries, all javap-verified)

| Mixin | Exact target (javap verified) | Shadows / redirect targets |
| --- | --- | --- |
| `AbstractContainerScreenMixin` | `renderTooltip(GuiGraphics,II)V`; `keyPressed` | — |
| `AbstractWidgetTranslationMixin` | `AbstractWidget#getMessage()Component` | — |
| `AdvancementToastMixin` | `AdvancementToast#render(GuiGraphics,ToastComponent,J)Toast$Visibility` | `advancement:AdvancementHolder` (1.20.2 holder refactor, confirmed) |
| `AdvancementWidgetMixin` | `AdvancementWidget#drawHover(GuiGraphics,IIFII)V` (WrapMethod) | `advancementNode:AdvancementNode` (1.20.2 node refactor, confirmed) |
| `BookEditScreenMixin` | `render`, `setCurrentPageText`, `appendPageToBook`, `rebuildDisplayCache` | shadows `pages`, `currentPage`, `isSigning`, `getCurrentPageText()`, `clearDisplayCache()`; redirects `getCurrentPageText()String`, `TextFieldHelper#getCursorPos()I`, `#getSelectionPos()I` |
| `BookViewScreenMixin` | `keyPressed`, `setBookAccess`, `render`; redirect `BookAccess#getPage(I)FormattedText` | shadows `bookAccess`, `cachedPage` |
| `BossHealthOverlayMixin` | `render(GuiGraphics)V`; ModifyArg `GuiGraphics#drawString(Font,Component,III)I` | — |
| `ChatComponentMixin` | `addMessage(Component,MessageSignature,GuiMessageTag)V` TAIL | shadows `allMessages`, `rescaleChat()` |
| `ChatScreenMixin` | `keyPressed` HEAD | shadow `input:EditBox` |
| `ClientTextTooltipAccessor` | `ClientTextTooltip.text` | accessor |
| `CycleButtonTooltipMixin` | `updateTooltip`; WrapOperation `setTooltip(Tooltip)V` | — |
| `EntityRendererMixin` | `renderNameTag` ModifyVariable argsOnly ordinal 0 | — |
| `FontManagerMixin` / `FontManagerAccessor` | `apply`, `close`; accessor `fontSets` | — |
| `FontSetMixin` / `FontSetAccessor` | `getGlyphInfo`, `getGlyph`; accessor `missingGlyph` | — |
| `GuiGraphicsTranslationMixin` | six draw overloads: `drawString(Font,Component,IIIZ)I`, `(Font,String,IIIZ)I`, `(Font,FormattedCharSequence,IIIZ)I`, `(Font,FormattedCharSequence,III)I`, `drawCenteredString(Font,Component,III)V`, `drawWordWrap(Font,FormattedText,IIII)V` | — |
| `HoverTooltipMixin` | `renderComponentHoverEffect`; `renderTooltipInternal(Font,List,II,ClientTooltipPositioner)V`; `renderTooltip(Font,List,Optional,II)V`; `renderTooltip(Font,ItemStack,II)V`; `renderTooltip(Font,List,ClientTooltipPositioner,II)V` | — |
| `PlayerTabOverlayMixin` | `render(GuiGraphics,I,Scoreboard,Objective)V`; redirect `Font#split(FormattedText,I)List` | — |
| `ScoreboardMixin` | `Gui#displayScoreboardSidebar(GuiGraphics,Objective)V` | redirects `Objective#getDisplayName()Component`, `Font#width(FormattedText)I`, `GuiGraphics#drawString(Font,Component,IIIZ)I` |
| `ScreenComponentClickMixin` | `Screen#handleComponentClicked` | — |
| `ScreenGuiTranslationMixin` | `Screen#renderWithTooltip(GuiGraphics,IIF)V` HEAD/RETURN | whole-frame GUI window |
| `SignRendererMixin` | `renderSignText(BlockPos,SignText,PoseStack,MultiBufferSource,IIIZ)V`; redirect `getDarkColor(SignText)I` | — |
| `SignTextMixin` | `SignText#getRenderMessages` | — |
| `TextDisplayMixin` | `Display$TextDisplay#cacheDisplay` | shadow `textRenderState` |
| `TitleOverlayMixin` | `setTitle(Component)V`, `setSubtitle(Component)V`, `setOverlayMessage(Component,Z)V`, `render(GuiGraphics,F)V`, `clear()V`; INVOKE anchors `BossHealthOverlay#render(GuiGraphics)V`, `ChatComponent#render(GuiGraphics,III)V`, `Gui#displayScoreboardSidebar(...)`, `PlayerTabOverlay#render(GuiGraphics,I,Scoreboard,Objective)V`, `Font#width(FormattedText)I`, `GuiGraphics#drawString(Font,Component,III)I` — all present in the 1.20.2 recompiled jar | shadows `title`, `subtitle`, `overlayMessageString` |

## Loader API evidence (`neoforge-20.2.93-universal.jar`)

- `net.neoforged.neoforge.network.NetworkRegistry.newSimpleChannel(ResourceLocation,Supplier,Predicate,Predicate)`,
  `SimpleChannel.messageBuilder(Class,int)` / `sendToServer` /
  `send(PacketDistributor$PacketTarget,MSG)`, `NetworkEvent` — the 20.2 line
  still ships SimpleChannel-era networking (the payload registrar API arrives
  in 20.4); target code matches.
- `net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent.register(KeyMapping)`.
- `net.neoforged.neoforge.event.TickEvent` present (client tick hooks).
- Metadata floors: `neoforge [20.2.3-beta,20.3)` — 20.2.3-beta confirmed as the
  earliest published 20.2.x on maven.neoforged.net; `minecraft [1.20.2,1.20.3)`.
- Client/server split: `SimpleTranslateMod` imports no `net.minecraft.client.*`;
  client state lives in `SimpleTranslateClientBootstrap` behind
  `FMLEnvironment.dist == Dist.CLIENT`.

Runtime status: first-ever client check 2026-07-26 (see
`docs/sync-2026-07-26/NF-120A.md`).

## 2026-07-28 packaging follow-up

- Removed the obsolete `refmap` property from the source mixin configuration.
  This Mojmap-at-runtime target packages no generated refmap, so retaining the
  declaration only caused an unnecessary runtime lookup.
- The original project's legacy NeoGradle build directory remains held by an
  unrelated external Gradle process. Its source mixin configuration was hashed
  before building and matched a clean isolated copy exactly. That copy was
  manually cleared before `build`, because the legacy `clean` task creates and
  then attempts to delete its own `expanded.lock`.
- Clean rebuilt non-sources JAR from that byte-identical source:
  `simple_translate-1.20.2-neoforge-2.1.28.jar`, SHA-256
  `B6BE532EE20CD7305A6EF9A738341B077A300FA24F536CBA4A2CD1F7445AADE3`.
  Its packaged `simple_translate.mixins.json` has no `refmap` entry.
- Exact client `neoforge-20.2.93` launched with the rebuilt JAR and entered the
  local test world at 07:09:31 (CodexTester entity id 148). `latest.log` has no
  SimpleTranslate refmap lookup, Mixin error, or crash.
