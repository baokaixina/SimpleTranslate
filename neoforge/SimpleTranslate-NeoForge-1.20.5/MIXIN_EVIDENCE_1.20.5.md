# SimpleTranslate NeoForge 1.20.5 Mixin evidence

Exact build target: Minecraft `1.20.5`, NeoForge `20.5.21-beta` (declared
range `[20.5.14-beta,20.6)`), Mojang official mappings at runtime. The
packaged jar ships no refmap; the launch warning about
`simple_translate.refmap.json` is benign on this loader generation
(member names verified Mojmap in the exact client jar).

Declared floor justification: `20.5.14-beta` is the first NeoForge 1.20.5
build with the tick-event refactor (#542, per the official
`neoforge-20.5.14-beta-changelog.txt`), which introduced
`net.neoforged.neoforge.client.event.ClientTickEvent` and
`net.neoforged.neoforge.event.tick.ServerTickEvent` as used by this target
(`SimpleTranslateClientBootstrap`, `SharedCacheClient`, `SharedCacheServer`,
`HoldOriginalState`, `ModKeyBindings`). Earlier 20.5 betas only have the old
`TickEvent` shape, so the floor is the true minimum-compatible build.

Evidence sources:

1. **Runtime application (bytecode-level)** — test client
   `<designated-test-client>\neoforge\1.20.5` (`1.20.5-NeoForge`, NeoForge
   20.5.21-beta), session 2026-07-26 09:24:00–09:24:18, jar SHA256
   `ECB427EA...43CF830` identical in `build/libs` and the deployed mods
   folder; Quick Play world `CodexSmokeWorld` entered (chunks saved
   09:24:18); no crash reports. `debug.log` records 22 of 28 config entries
   applied with `injectors.defaultRequire=1` and zero injection errors.
2. **`javap -p -s` against `client-1.20.5-20240423.152201-srg.jar`**
   (Mojmap member names despite the artifact suffix) — for entries whose
   target classes did not load in that session.
3. **`javap -p -s` against `ftb-library-neoforge-2006.1.2.jar`**
   (`<workspace>\.analysis\optional-120x\`, official FTB maven).

Runtime-applied 09:24 (bytecode-proven): `ChatComponentMixin`,
`ChatScreenMixin`, `ScreenComponentClickMixin`, `CycleButtonTooltipMixin`,
`AbstractContainerScreenMixin`, `ScreenGuiTranslationMixin`,
`GuiGraphicsTranslationMixin`, `HoverTooltipMixin` (1.20.5 tooltip
signatures), `AbstractWidgetTranslationMixin`, `BookViewScreenMixin`,
`BookEditScreenMixin`, `SignRendererMixin`, `ScoreboardMixin`,
`PlayerTabOverlayMixin`, `BossHealthOverlayMixin`, `TitleOverlayMixin`,
`EntityRendererMixin`, `TextDisplayMixin`, `FontManagerMixin`,
`FontManagerAccessor`, `FontSetMixin`, `FontSetAccessor`.

Statically verified against the exact client jar (not loaded in session):

| Mixin entry | Exact target | Evidence |
| --- | --- | --- |
| `SignTextMixin` | `SignText#getRenderMessages(ZLjava/util/function/Function;)[Lnet/minecraft/util/FormattedCharSequence;` HEAD | javap: descriptor exact |
| `ClientTextTooltipAccessor` | `ClientTextTooltip.text:Lnet/minecraft/util/FormattedCharSequence;` (private final) | javap: field exact |
| `AdvancementToastMixin` | `AdvancementToast#render(GuiGraphics,ToastComponent,J)Toast$Visibility` WrapMethod; shadow `advancement:Lnet/minecraft/advancements/AdvancementHolder;` | javap: descriptor + field exact |
| `AdvancementWidgetMixin` | `AdvancementWidget#drawHover(GuiGraphics,IIFII)V` WrapMethod; shadow `advancementNode:Lnet/minecraft/advancements/AdvancementNode;` | javap: descriptor + field exact |
| `compat.FtbScreenWrapperTranslationMixin` | `dev.ftb.mods.ftblibrary.ui.ScreenWrapper` (extends `Screen`) `keyPressed(III)Z`; `render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V` HEAD/RETURN | javap vs ftb-library-neoforge-2006.1.2: exact |
| `compat.FtbTextFieldTranslationMixin` | `dev.ftb.mods.ftblibrary.ui.TextField` shadow `rawText:Lnet/minecraft/network/chat/Component;`; `draw(Lnet/minecraft/client/gui/GuiGraphics;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V`; reflective public `setText(Lnet/minecraft/network/chat/Component;)Ldev/ftb/mods/ftblibrary/ui/TextField;`; `formattedText` is `[Lnet/minecraft/network/chat/FormattedText;` | javap vs ftb-library-neoforge-2006.1.2: exact |

**FTB reachability caveat (documented):** `ftb-library-neoforge-2006.1.2`
declares `minecraft [1.20.6,)` in its `neoforge.mods.toml`, and no 2005.x
FTB Library build exists on the official FTB maven. On an official install
FTB Library therefore never loads on Minecraft 1.20.5 and both compat
mixins stay plugin-skipped (`SimpleTranslateMixinPlugin` gates on mod id
`ftblibrary` via `LoadingModList`). They are kept for donor parity (the
Fabric 1.20.5 donor ships the same dormant pair) and are descriptor-exact
against the nearest real artifact should a force-loaded install exist.

Loader hooks (non-Mixin), runtime-proven in the same session: NeoForge 20.5
payload API `RegisterPayloadHandlersEvent` / `PayloadRegistrar
.optional()` / `playBidirectional(TYPE, CODEC, handler)` with
`StreamCodec`/`CustomPacketPayload.Type` (20.5 has no
ClientPacketDistributor; side discriminated by context player type),
`RegisterKeyMappingsEvent`, post-#542 `ClientTickEvent.Post` shape,
`ClientPlayerNetworkEvent.LoggingIn/LoggingOut`, `GameShuttingDownEvent`,
`FMLPaths.CONFIGDIR`, config-screen extension point.

Iceberg: intentionally absent — no NeoForge Iceberg build exists for
Minecraft 1.20.5. `TooltipTranslationHelper.translateGatheredTooltipLines`
remains as donor-parity surface with no caller on this target; asserted by
`run-logic-checks.ps1`.

The Fabric donor's `simple_translate.accesswidener` is comment-only on
1.20.5 (no entries), so no NeoForge access transformer is required — a
documented non-difference, not a missing adaptation.

## 2026-07-28 packaging follow-up

- Removed the obsolete `refmap` property from the source mixin configuration.
  This Mojmap-at-runtime target packages no generated refmap, so retaining the
  declaration only caused an unnecessary runtime lookup.
- Clean rebuilt non-sources JAR:
  `simple_translate-1.20.5-neoforge-2.1.28.jar`, SHA-256
  `D5131C0B4B40221C8AFBFB98648E6751BD996CBA804AAEEF3397145FCAE5047B`.
- Exact client `20.5.21-beta` launched with the rebuilt JAR and entered the
  local test world at 06:56:51 (CodexTester entity id 34). `latest.log` has no
  SimpleTranslate refmap lookup, Mixin error, or crash.
