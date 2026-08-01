# SimpleTranslate NeoForge 1.20.3 Mixin evidence

Exact build target: NeoForge `20.3.8-beta` (`net.neoforged` namespace,
`mods.toml` metadata era, NeoGradle userdev 7), Mojmap at dev time and at
runtime (no refmap required in the packaged jar). Evidence jars inspected with
`javap -p -s` / `javap -c` (2026-07-26):

- Vanilla/patched: `build/neoForm/neoFormJoined1.20.3-20231205.165107/steps/recompile/output.jar`
- Loader API: `neoforge-20.3.8-beta-universal.jar` (Gradle modules cache)

## Optional-compat gap (documented, intentional)

FTB Library and Iceberg publish **no NeoForge builds for Minecraft 1.20.3**
(see `docs/API_MIXIN_AUDIT_2026-07-26.md`). This target therefore ships no
`compat/` classes, no `mixin/compat/` pseudo mixins, and no
`SimpleTranslateMixinPlugin`; `simple_translate.mixins.json` lists exactly the
26 vanilla entries with no dormant entries referencing absent classes. This is
a dependency-nonexistence gap, not an omission.

## Vanilla mixin table (26 entries, all javap-verified against the 1.20.3 recompiled jar)

| Mixin | Exact target (javap verified) | Shadows / redirect targets |
| --- | --- | --- |
| `AbstractContainerScreenMixin` | `renderTooltip(GuiGraphics,II)V`; `keyPressed` | — |
| `AbstractWidgetTranslationMixin` | `AbstractWidget#getMessage()Component` | — |
| `AdvancementToastMixin` | `AdvancementToast#render(GuiGraphics,ToastComponent,J)Toast$Visibility` | `advancement:AdvancementHolder` |
| `AdvancementWidgetMixin` | `AdvancementWidget#drawHover(GuiGraphics,IIFII)V` (WrapMethod) | `advancementNode:AdvancementNode` |
| `BookEditScreenMixin` | `render`, `setCurrentPageText`, `appendPageToBook`, `rebuildDisplayCache` | shadows `pages`, `currentPage`, `isSigning`, `getCurrentPageText()`, `clearDisplayCache()`; redirects `getCurrentPageText()String`, `TextFieldHelper#getCursorPos()I`, `#getSelectionPos()I` |
| `BookViewScreenMixin` | `keyPressed`, `setBookAccess`, `render`; redirect `BookAccess#getPage(I)FormattedText` | shadows `bookAccess`, `cachedPage` |
| `BossHealthOverlayMixin` | `render(GuiGraphics)V`; ModifyArg `GuiGraphics#drawString(Font,Component,III)I` | — |
| `ChatComponentMixin` | `addMessage(Component,MessageSignature,GuiMessageTag)V` TAIL | shadows `allMessages`, `rescaleChat()` |
| `ChatScreenMixin` | `keyPressed` HEAD | shadow `input:EditBox` |
| `ClientTextTooltipAccessor` | `ClientTextTooltip.text` | accessor |
| `CycleButtonTooltipMixin` | `updateTooltip`; WrapOperation at `CycleButton;setTooltip(Tooltip)V` — the `invokevirtual` inside `updateTooltip` is owner `CycleButton` (javap -c verified; method declared in `AbstractWidget`) | — |
| `EntityRendererMixin` | `renderNameTag` ModifyVariable argsOnly ordinal 0 | — |
| `FontManagerMixin` / `FontManagerAccessor` | `apply`, `close`; accessor `fontSets` | — |
| `FontSetMixin` / `FontSetAccessor` | `getGlyphInfo`, `getGlyph`; accessor `missingGlyph` | — |
| `GuiGraphicsTranslationMixin` | six draw overloads (`drawString` Component/String/FormattedCharSequence x Z, `drawCenteredString(Font,Component,III)V`, `drawWordWrap(Font,FormattedText,IIII)V`) — all present | — |
| `HoverTooltipMixin` | `renderComponentHoverEffect`; `renderTooltipInternal(Font,List,II,ClientTooltipPositioner)V`; `renderTooltip(Font,List,Optional,II)V`; `renderTooltip(Font,ItemStack,II)V`; `renderTooltip(Font,List,ClientTooltipPositioner,II)V` | — |
| `PlayerTabOverlayMixin` | `render(GuiGraphics,I,Scoreboard,Objective)V`; redirect `Font#split(FormattedText,I)List` | — |
| `ScoreboardMixin` | `Gui#displayScoreboardSidebar(GuiGraphics,Objective)V`; **1.20.3 sidebar refactor**: the sidebar text draws moved into the synthetic `lambda$displayScoreboardSidebar$4([LGui$1DisplayEntry;ILGuiGraphics;LComponent;I)V` (drawManaged Runnable). The lambda and its three `GuiGraphics.drawString(Font,Component,IIIZ)I` invocations are confirmed in the exact 1.20.3 recompiled `Gui` bytecode (javap -c) — this replaces the older in-source comment that only cited 1.20.4 mappings. WrapOperations at `Objective#getDisplayName()`, `Font#width(FormattedText)I` in the outer method also confirmed | — |
| `ScreenComponentClickMixin` | `Screen#handleComponentClicked` | — |
| `ScreenGuiTranslationMixin` | `Screen#renderWithTooltip(GuiGraphics,IIF)V` HEAD/RETURN | whole-frame GUI window |
| `SignRendererMixin` | `renderSignText(BlockPos,SignText,PoseStack,MultiBufferSource,IIIZ)V`; redirect `getDarkColor(SignText)I` | — |
| `SignTextMixin` | `SignText#getRenderMessages` | — |
| `TextDisplayMixin` | `Display$TextDisplay#cacheDisplay` | shadow `textRenderState` |
| `TitleOverlayMixin` | `setTitle`, `setSubtitle`, `setOverlayMessage(Component,Z)V`, `render(GuiGraphics,F)V`, `clear()V`; INVOKE anchors `BossHealthOverlay#render(GuiGraphics)V`, `ChatComponent#render(GuiGraphics,III)V`, `Gui#displayScoreboardSidebar(...)`, `PlayerTabOverlay#render(GuiGraphics,I,Scoreboard,Objective)V`, `Font#width(FormattedText)I`, `GuiGraphics#drawString(Font,Component,III)I` — all present | shadows `title`, `subtitle`, `overlayMessageString` |

## Loader API evidence (`neoforge-20.3.8-beta-universal.jar`)

- `net.neoforged.neoforge.network.NetworkRegistry.newSimpleChannel(...)`,
  `SimpleChannel.messageBuilder(Class,int)` / `sendToServer` /
  `send(PacketDistributor$PacketTarget,MSG)` — the 20.3 line still ships
  SimpleChannel-era networking (payload registrar arrives in 20.4); target code
  matches.
- `RegisterKeyMappingsEvent.register(KeyMapping)` present.
- Metadata era: 20.3 FML still requires the Forge-era boolean `mandatory`
  dependency key (`type="required"` was proven to invalidate the whole mod file
  on the 20.2.93 sibling; the 20.3.8-beta runtime accepts `mandatory=true`,
  verified by the 2026-07-26 client run) and requires a root `pack.mcmeta`
  (pack_format 22 for 1.20.3) — NeoForge only auto-generates fallback pack
  metadata from 20.4 onward.
- Metadata floors: `neoforge [20.3.1-beta,20.4)` — 20.3.1-beta confirmed as the
  earliest published 20.3.x on maven.neoforged.net; `minecraft [1.20.3,1.20.4)`.
- Client/server split: `SimpleTranslateMod` imports no `net.minecraft.client.*`;
  client state lives in `SimpleTranslateClientBootstrap` behind
  `FMLEnvironment.dist == Dist.CLIENT`.

Runtime status: first-ever client check 2026-07-26 (see
`docs/sync-2026-07-26/NF-120A.md`).

## 2026-07-28 packaging follow-up

- Removed the obsolete `refmap` property from the source mixin configuration.
  This Mojmap-at-runtime target does not package a generated refmap, so the
  declaration could only cause an avoidable runtime lookup.
- Clean rebuilt non-sources JAR:
  `simple_translate-1.20.3-neoforge-2.1.28.jar`, SHA-256
  `68D6C22492BCF18500C54B23923E73A0DEE56172F2FD8C70DBEBA4B263857591`.
- Exact client `neoforge-20.3.8-beta` launched with the rebuilt JAR and entered
  the local test world at 06:53:38 (CodexTester entity id 184). `latest.log`
  has no SimpleTranslate refmap lookup, Mixin error, or crash.
