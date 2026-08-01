# SimpleTranslate NeoForge 1.20.6 Mixin evidence

Exact build target: Minecraft `1.20.6`, NeoForge `20.6.139` (declared range
`[20.6.1-beta,20.7)`; `20.6.1-beta` is the first build published on the
official NeoForge maven for the 20.6 series), Mojang official mappings at
runtime. The packaged jar ships no refmap; the launch warning about
`simple_translate.refmap.json` is benign on this loader generation.

Evidence sources:

1. **Runtime application (bytecode-level)** — test client
   `<designated-test-client>\neoforge\1.20.6` (`1.20.6-NeoForge`, NeoForge 20.6.139),
   session 2026-07-26 09:24:54–09:25:10, jar SHA256 `AF79A294...D0FA7E5B`
   identical in `build/libs` and the deployed mods folder; Quick Play world
   "Ted's Insanity - Chapter 01 [1.20.6]" entered (chunks saved 09:25:10).
   `debug.log` records 23 of 28 config entries applied with
   `injectors.defaultRequire=1` and zero injection errors. The two crash
   reports in the instance are dated 2026-07-25 (pre-fix state, see below)
   and predate this session.
2. **`javap -p -s` against `client-1.20.6-20240627.102356-srg.jar`**
   (Mojmap member names) — for entries whose target classes did not load.
3. **`javap -p -s` against `ftb-library-neoforge-2006.1.2.jar`**
   (`<workspace>\.analysis\optional-120x\`, official FTB maven;
   its `neoforge.mods.toml` declares `minecraft [1.20.6,)`, so it loads on
   this target).

## ScoreboardMixin regression fixed by this sync (runtime-proven)

`crash-2026-07-25_14.02.07-client.txt` shows the previous jar failing with
`InvalidInjectionException: @WrapOperation ... could not find any targets
matching 'lambda$displayScoreboardSidebar$9' in net.minecraft.client.gui.Gui`.
Root cause: NeoForge binpatches add extra lambdas to `Gui`, shifting the
vanilla lambda index (`$9` in the srg jar, `$14` in the transformed runtime
class). The current mixin targets the sidebar entry lambda by its unique
descriptor wildcard
`*([Lnet/minecraft/client/gui/Gui$1DisplayEntry;Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/network/chat/Component;I)V`;
`javap` confirms exactly one method in `Gui` carries that descriptor, and the
2026-07-26 09:24 session applied `ScoreboardMixin` cleanly and entered a
world with an active scoreboard-capable save.

Runtime-applied 09:24:54 session (bytecode-proven): `ChatComponentMixin`,
`ChatScreenMixin`, `ScreenComponentClickMixin`, `CycleButtonTooltipMixin`,
`AbstractContainerScreenMixin`, `ScreenGuiTranslationMixin`,
`GuiGraphicsTranslationMixin`, `HoverTooltipMixin` (1.20.6 tooltip
signatures), `AbstractWidgetTranslationMixin`, `BookViewScreenMixin`,
`BookEditScreenMixin`, `SignRendererMixin`, `SignTextMixin`,
`ScoreboardMixin`, `PlayerTabOverlayMixin`, `BossHealthOverlayMixin`,
`TitleOverlayMixin`, `EntityRendererMixin`, `TextDisplayMixin`,
`FontManagerMixin`, `FontManagerAccessor`, `FontSetMixin`,
`FontSetAccessor`.

Statically verified against the exact client jar (not loaded in session):

| Mixin entry | Exact target | Evidence |
| --- | --- | --- |
| `ClientTextTooltipAccessor` | `ClientTextTooltip.text:Lnet/minecraft/util/FormattedCharSequence;` (private final) | javap: field exact |
| `AdvancementToastMixin` | `AdvancementToast#render(GuiGraphics,ToastComponent,J)Toast$Visibility` WrapMethod; shadow `advancement:Lnet/minecraft/advancements/AdvancementHolder;` | javap: descriptor + field exact |
| `AdvancementWidgetMixin` | `AdvancementWidget#drawHover(GuiGraphics,IIFII)V` WrapMethod; shadow `advancementNode:Lnet/minecraft/advancements/AdvancementNode;` | javap: descriptor + field exact |
| `compat.FtbScreenWrapperTranslationMixin` | `dev.ftb.mods.ftblibrary.ui.ScreenWrapper` (extends `Screen`) `keyPressed(III)Z`; `render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V` HEAD/RETURN | javap vs ftb-library-neoforge-2006.1.2: exact |
| `compat.FtbTextFieldTranslationMixin` | `dev.ftb.mods.ftblibrary.ui.TextField` shadow `rawText:Lnet/minecraft/network/chat/Component;` (private, name+descriptor exact); `draw(Lnet/minecraft/client/gui/GuiGraphics;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V` HEAD/RETURN; reflective public `setText(Lnet/minecraft/network/chat/Component;)Ldev/ftb/mods/ftblibrary/ui/TextField;`; `formattedText` confirmed `[Lnet/minecraft/network/chat/FormattedText;` (not shadowed) | javap vs ftb-library-neoforge-2006.1.2: fields + methods descriptor-exact |

Both FTB mixins are gated by `SimpleTranslateMixinPlugin` on mod id
`ftblibrary` (`LoadingModList` during mixin prepare); the test client does
not install FTB Library, so their runtime status is plugin-skipped and the
descriptor-exact `javap` audit above is the correctness evidence.

Loader hooks (non-Mixin), runtime-proven in the same session: NeoForge 20.6
payload API `RegisterPayloadHandlersEvent` / `PayloadRegistrar.optional()` /
`playBidirectional(TYPE, CODEC, handler)` with `StreamCodec` /
`CustomPacketPayload.Type` (no ClientPacketDistributor in 20.6; side
discriminated by context player type), `RegisterKeyMappingsEvent`,
post-refactor `ClientTickEvent` / `net.neoforged.neoforge.event.tick
.ServerTickEvent`, `ClientPlayerNetworkEvent.LoggingIn/LoggingOut`,
`GameShuttingDownEvent`, `FMLPaths.CONFIGDIR`, config-screen extension
point.

Iceberg: intentionally absent — no NeoForge Iceberg build exists for
Minecraft 1.20.6. `TooltipTranslationHelper.translateGatheredTooltipLines`
remains as donor-parity surface with no caller on this target; asserted by
`run-logic-checks.ps1`.

The Fabric donor's `simple_translate.accesswidener` is comment-only on this
version (no entries), so no NeoForge access transformer is required.

## 2026-07-28 packaging follow-up

- Removed the obsolete `refmap` property from the source mixin configuration.
  This Mojmap-at-runtime target packages no generated refmap, so retaining the
  declaration only caused an unnecessary runtime lookup.
- Clean rebuilt non-sources JAR:
  `simple_translate-1.20.6-neoforge-2.1.28.jar`, SHA-256
  `D07DE72B4F238BA145A2806DE7CD1CE9C06E246B9FF8A981D40396FF876E555C`.
- Exact client `20.6.139` launched with the rebuilt JAR and entered the local
  test world at 06:58:39 (CodexTester entity id 29). `latest.log` has no
  SimpleTranslate refmap lookup, Mixin error, or crash. The test-client API
  credential was rejected, and the normal Component-JSON passthrough fallback
  was observed without a client failure.
