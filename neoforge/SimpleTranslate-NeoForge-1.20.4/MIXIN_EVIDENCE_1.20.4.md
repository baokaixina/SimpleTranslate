# SimpleTranslate NeoForge 1.20.4 Mixin evidence

Exact build target: Minecraft `1.20.4`, NeoForge `20.4.251` (range floor
`[20.4.0-beta,20.5)`), Mojang official mappings. NeoForge 20.4 runs
Mojang-mapped names at runtime, verified at bytecode level against the test
client's `client-1.20.4-20240627.114801-srg.jar` (despite the `-srg` artifact
suffix, member names are Mojmap: e.g. `ClientTextTooltip.text`,
`AdvancementWidget.drawHover`). The packaged jar ships no refmap; the launch
warning `Reference map 'simple_translate.refmap.json' ... could not be read`
is therefore benign on this loader generation.

Evidence sources:

1. **Runtime application (bytecode-level)** — test client
   `<designated-test-client>\neoforge\1.20.4` (`neoforge-20.4.251`), session
   2026-07-26 09:15:09–09:15:36, jar SHA256
   `910F9F11...D6D4D7DB` identical in `build/libs` and the deployed mods
   folder; Quick Play single-player world "Midland" entered (chunks saved
   09:15:35). `debug.log` records 23 of 28 config entries applied
   (`Mixing X from simple_translate.mixins.json into ...`) with
   `injectors.defaultRequire=1` and zero injection errors or crash reports —
   a failed descriptor would have thrown during apply.
2. **`javap -p -s` against the exact client jar** — for the entries whose
   target classes did not load in that session.
3. **`javap -p -s` against `ftb-library-neoforge-2004.2.5.jar`**
   (`<workspace>\.analysis\optional-120x\`, downloaded from the official
   FTB maven `maven.ftb.dev`) — for the two `@Pseudo` FTB compat mixins,
   which the mixin plugin gates on mod id `ftblibrary` via `LoadingModList`.

| Mixin entry | Exact target (owner + descriptor) | Evidence |
| --- | --- | --- |
| `ChatComponentMixin` | `ChatComponent#addMessage(Component,MessageSignature,GuiMessageTag)V` TAIL | runtime-applied 09:15 |
| `ChatScreenMixin` | `ChatScreen#keyPressed(III)Z` HEAD | runtime-applied 09:15 |
| `ScreenComponentClickMixin` | `Screen#handleComponentClicked` HEAD | runtime-applied 09:15 |
| `CycleButtonTooltipMixin` | `CycleButton#updateTooltip` WrapOperation | runtime-applied 09:15 |
| `AbstractContainerScreenMixin` | `AbstractContainerScreen#renderTooltip(GuiGraphics,II)V`; `keyPressed` (require=0) | runtime-applied 09:15; `keyPressed(III)Z` javap-verified present |
| `ScreenGuiTranslationMixin` | `Screen#renderWithTooltip(GuiGraphics,IIF)V` HEAD/RETURN (`public final`, javap-confirmed — frame brackets the tooltip pass) | runtime-applied 09:15 |
| `GuiGraphicsTranslationMixin` | `GuiGraphics#drawString(Font,Component,IIIZ)I`, `drawString(Font,String,IIIZ)I`, `drawString(Font,FormattedCharSequence,IIIZ)I` + `(…III)I`, `drawCenteredString(Font,Component,III)V`, `drawWordWrap(Font,FormattedText,IIII)V` | runtime-applied 09:15 |
| `HoverTooltipMixin` | `GuiGraphics#renderComponentHoverEffect` HEAD; `renderTooltip(Font,ItemStack,II)V` / `(Font,List,Optional,II)V` / `(Font,List,Optional,ItemStack,II)V` / `(Font,List,ClientTooltipPositioner,II)V`; `renderTooltipInternal(Font,List,II,ClientTooltipPositioner)V` WrapMethod | runtime-applied 09:15 |
| `AbstractWidgetTranslationMixin` | `AbstractWidget#getMessage()Lnet/minecraft/network/chat/Component;` RETURN | runtime-applied 09:15 |
| `BookViewScreenMixin` | `BookViewScreen#setBookAccess`, `render`, `keyPressed` (require=0, javap-verified present) | runtime-applied 09:15 |
| `BookEditScreenMixin` | `BookEditScreen#render`, `setCurrentPageText`, `appendPageToBook`, redirects in `rebuildDisplayCache` | runtime-applied 09:15 |
| `SignRendererMixin` | `SignRenderer#renderSignText(BlockPos,SignText,PoseStack,MultiBufferSource,IIIZ)V` (1.20.4 keeps the dedicated method) | runtime-applied 09:15 |
| `SignTextMixin` | `SignText#getRenderMessages` HEAD | runtime-applied 09:15 |
| `ScoreboardMixin` | `Gui#displayScoreboardSidebar(GuiGraphics,Objective)V` + `lambda$displayScoreboardSidebar$4` | runtime-applied 09:15 |
| `PlayerTabOverlayMixin` | `PlayerTabOverlay#render` | runtime-applied 09:15 |
| `BossHealthOverlayMixin` | `BossHealthOverlay#render(GuiGraphics)V` | runtime-applied 09:15 |
| `TitleOverlayMixin` | `Gui#setTitle/setSubtitle/setOverlayMessage` TAIL; `Gui#render(GuiGraphics,F)V` redirects (1.20.4 `Gui.render` takes float partialTick, no DeltaTracker) | runtime-applied 09:15 |
| `AdvancementToastMixin` | `AdvancementToast#render(GuiGraphics,ToastComponent,J)Toast$Visibility` WrapMethod; shadow `advancement:Lnet/minecraft/advancements/AdvancementHolder;` (private final) | javap vs client jar: descriptor + field exact |
| `AdvancementWidgetMixin` | `AdvancementWidget#drawHover(GuiGraphics,IIFII)V` WrapMethod; shadow `advancementNode:Lnet/minecraft/advancements/AdvancementNode;` (private final) | javap vs client jar: descriptor + field exact |
| `EntityRendererMixin` | `EntityRenderer#renderNameTag` ModifyVariable argsOnly | runtime-applied 09:15 |
| `TextDisplayMixin` | `Display$TextDisplay#cacheDisplay` HEAD | runtime-applied 09:15 |
| `FontManagerMixin` / `FontManagerAccessor` | `FontManager#apply/close`; accessor `fontSets` | runtime-applied 09:15 |
| `FontSetMixin` / `FontSetAccessor` | `FontSet#getGlyphInfo/getGlyph` RETURN; accessor `missingGlyph` | runtime-applied 09:15 |
| `ClientTextTooltipAccessor` | `ClientTextTooltip.text:Lnet/minecraft/util/FormattedCharSequence;` (private final) | javap vs client jar: field exact |
| `compat.FtbScreenWrapperTranslationMixin` | `dev.ftb.mods.ftblibrary.ui.ScreenWrapper` (extends `Screen`, javap-confirmed) `keyPressed(III)Z` HEAD; `render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V` HEAD/RETURN | javap vs ftb-library-neoforge-2004.2.5: descriptors exact |
| `compat.FtbTextFieldTranslationMixin` | `dev.ftb.mods.ftblibrary.ui.TextField` shadow `rawText:Lnet/minecraft/network/chat/Component;` (private, name+descriptor exact); `draw(Lnet/minecraft/client/gui/GuiGraphics;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V` HEAD/RETURN; reflective `setText(Component)` → public `(Lnet/minecraft/network/chat/Component;)Ldev/ftb/mods/ftblibrary/ui/TextField;`; `formattedText` confirmed `[Lnet/minecraft/network/chat/FormattedText;` (not shadowed) | javap vs ftb-library-neoforge-2004.2.5: fields + methods descriptor-exact |

Loader hooks (non-Mixin), all runtime-proven in the same session: NeoForge
20.4 payload API `RegisterPayloadHandlerEvent` / `IPayloadRegistrar
.versioned("1").optional()` / `PlayPayloadContext` (renamed in 20.5 — do not
copy forward), `PacketDistributor.SERVER.noArg()` / `.PLAYER.with(player)`,
`RegisterKeyMappingsEvent` on the mod bus, `TickEvent.ClientTickEvent` with
`Phase.END`, `ClientPlayerNetworkEvent.LoggingIn/LoggingOut`,
`GameShuttingDownEvent`, `FMLPaths.CONFIGDIR`,
`ConfigScreenHandler.ConfigScreenFactory` extension point.

Iceberg: intentionally absent — Iceberg has no NeoForge artifact for
Minecraft 1.20.4 (Modrinth 5faXoLqX ships a legacy-Forge-only 1.20.4 build
depending on mod id `forge` [49,)). `TooltipTranslationHelper
.translateGatheredTooltipLines` remains as donor-parity product surface with
no caller on this target; the gap is asserted by `run-logic-checks.ps1`.

FTB compat runtime status: the test client does not install FTB Library, so
both pseudo mixins are plugin-skipped at runtime; their correctness evidence
is the descriptor-exact `javap` audit above, not a live FTB session.

## 2026-07-28 packaging follow-up

- Removed the obsolete `refmap` property from the source mixin configuration.
  This Mojmap-at-runtime target packages no generated refmap, so retaining the
  declaration only caused an unnecessary runtime lookup.
- Clean rebuilt non-sources JAR:
  `simple_translate-1.20.4-neoforge-2.1.28.jar`, SHA-256
  `EB121450DD90FB431F8ACEC0D019286094F4CBF408C0FE5A4A4B8DA0DC67094F`.
- Exact client `neoforge-20.4.251` launched with the rebuilt JAR and entered
  the local test world at 06:55:15 (CodexTester entity id 295). The current
  launch section contains no SimpleTranslate refmap lookup, Mixin error, or
  crash; unrelated installed content reports its own data/model errors.
