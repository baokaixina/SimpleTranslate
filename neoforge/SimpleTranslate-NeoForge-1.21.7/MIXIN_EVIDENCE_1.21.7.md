# SimpleTranslate NeoForge 1.21.7 Mixin evidence (2026-07-26)

Exact build target: Minecraft 1.21.7, NeoForge **21.7.25-beta** (ModDevGradle,
Mojmap runtime), Java 21, mod 2.1.28. Vanilla evidence jar:
`build/moddev/artifacts/neoforge-21.7.25-beta-merged.jar`. Runtime evidence:
fresh client check 2026-07-26 23:23 on
`<designated-test-client>\neoforge\1.21.7\versions\1.21.7-NeoForge` — script verdict
`PASS: dedicated local test client reached world entry` (`CodexTester ...
logged in with entity id 1` at 23:23:30 on the script's loopback smoke server,
`joined the game`, orderly stop with all chunks saved). Deployed jar
SHA256-identical to `build/libs/simple_translate-1.21.7-neoforge-2.1.28.jar`;
zero source files newer than the jar. The only crash report in the instance is
dated 2026-07-25 (pre-sync jar) and predates the tested jar.

`simple_translate.mixins.json`: **31 client entries**, byte-identical client
list to the Fabric 1.21.7 donor (same list as 1.21.6),
`injectors.defaultRequire=1`, plugin `SimpleTranslateMixinPlugin` gating the
three optional-compat entries on `ftblibrary` / `wynntils` presence in
`LoadingModList`.

## Runtime-proven entries (24)

`debug.log` of the 23:23 run shows `Mixing <name> from
simple_translate.mixins.json` for all 24 eager classes with **zero**
`InjectionError` / `InvalidInjectionException` / `Critical injection failure`
lines under `defaultRequire=1`, followed by world entry:

AbstractContainerScreenMixin, AbstractWidgetTranslationMixin,
BookEditScreenMixin, BookViewScreenMixin, BossHealthOverlayMixin,
ChatComponentMixin, ChatScreenMixin, CycleButtonTooltipMixin,
EntityRendererMixin, FontManagerAccessor, FontManagerMixin,
FontPreparedTextBuilderMixin, FontSetAccessor, FontSetMixin,
GuiGraphicsTranslationMixin, HoverTooltipMixin, PlayerTabOverlayMixin,
ScoreboardMixin, ScreenAccessor, ScreenComponentClickMixin,
ScreenGuiTranslationMixin, SignRendererMixin, TextDisplayMixin,
TitleOverlayMixin.

## javap-verified lazy vanilla entries (4)

`javap -p -s` against `neoforge-21.7.25-beta-merged.jar` (2026-07-26, this
session):

| Mixin | Exact target evidence |
| --- | --- |
| `SignTextMixin` | `net/minecraft/world/level/block/entity/SignText#getRenderMessages` = `(ZLjava/util/function/Function;)[Lnet/minecraft/util/FormattedCharSequence;` |
| `ClientTextTooltipAccessor` | `ClientTextTooltip.text : Lnet/minecraft/util/FormattedCharSequence;` (private final) |
| `AdvancementToastMixin` | shadow `advancement : Lnet/minecraft/advancements/AdvancementHolder;` (private final); `render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;J)V` |
| `AdvancementWidgetMixin` | shadow `advancementNode : Lnet/minecraft/advancements/AdvancementNode;` (private final); `drawHover(Lnet/minecraft/client/gui/GuiGraphics;IIFII)V` |

Source descriptors in this target match these strings exactly.

## Optional-compat entries (3)

Evidence jars: `<workspace>\.analysis\optional-121x\`.

- **FTB compat pair — descriptor-exact vs the only loadable artifact.**
  Official FTB maven `ftb-library-neoforge` metadata lists only series
  2004 / 2006 / 2100 / 2101 / 2111 / 26 — no 2104–2108 series exists.
  `ftb-library-neoforge-2101.1.33.jar` declares
  `minecraft versionRange="[1.21.1,)"` (verified inside the jar) and therefore
  CAN be installed on a 1.21.7 client. `javap -p -s` (re-run this session):
  `ScreenWrapper.keyPressed(III)Z`,
  `ScreenWrapper.render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V`,
  `TextField.rawText : Lnet/minecraft/network/chat/Component;` (shadowed name
  AND type exact),
  `TextField.draw(Lnet/minecraft/client/gui/GuiGraphics;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V`,
  reflective `setText(Component)` present;
  `formattedText : [Lnet/minecraft/network/chat/FormattedText;` correctly NOT
  shadowed. This target's mixin descriptors are string-identical to that
  bytecode. `@Pseudo` + `remap=false` correct.
- **Wynntils compat — BLOCKED/dormant on this target (documented gap).**
  No Wynntils release exists for MC 1.21.7: Modrinth version listing and the
  Wynntils GitHub releases API (last 100 releases, checked 2026-07-26) both
  show release assets only for `MC-1.21.4` (3.x) and `MC-1.21.11` (4.x). FML
  can never load any Wynntils build on a 1.21.7 client, so
  `SimpleTranslateMixinPlugin` permanently skips
  `compat.WynntilsOverlayManagerMixin`. The class carries the donor's
  1.21.11-era single-arg descriptor
  `renderOverlays(Lcom/wynntils/mc/event/RenderEvent;)V` (Fabric 1.21.7 donor
  parity); retained as dormant donor-parity code, not as exact evidence.

## Non-mixin compat: Iceberg (documented gap, loadable-artifact evidence)

No 1.21.5+ Iceberg build exists on Modrinth (any loader; checked 2026-07-26).
Newest loadable artifact: `Iceberg-1.21.4-neoforge-1.2.13.jar`, declaring
`minecraft versionRange="[1.21.3,)"` — installable on 1.21.7. `javap -p -s`
confirms every reflected member used by `IcebergTooltipGatherCompat`
(`RenderTooltipEvents.GATHER`, `Gather#onGather(ItemStack,int,int,List,int,
int)`, `GatherResult(InteractionResult,int,List)`, `Event#register(Object)`).
Reflection-only with graceful failure, gated on `ModList.isLoaded("iceberg")`.

## NeoForge API surface

Runtime-proven on 21.7.25-beta by the 23:23 run (mod construction, config
registration, key mappings, client tick/connection events, payload
registration, world join, zero SimpleTranslate errors). Version-specific:
`cache/SharedCacheNetworking` uses the 21.7 API shape — separate client-side
payload handler in `playBidirectional(type, codec, serverHandler,
clientHandler)` plus `net.neoforged.neoforge.client.network.
ClientPacketDistributor.sendToServer` (client-network split introduced in
exactly 21.7.1-beta, PR #2272 per the official changelog).

## Metadata floors

`neoforge [21.7.1-beta,21.8)` — justified minimum: the 21.7 series is
beta-only (maven.neoforged.net metadata, 21.7.0-beta…21.7.25-beta, no stable),
and the mod requires the separate client-side payload handler registration
added in exactly 21.7.1-beta. `minecraft [1.21.7,1.21.8)`; `loader [1,)`.
Floors, not build pins (build uses 21.7.25-beta).

## Notes

- Packaged jar is a 1:1 image of production source (165/165 top-level
  classes), zero OCR/retired-symbol matches; full Wynn feature surface present
  (>=1.21.4 gate; `feature/wynn/` 6 classes + `ActionbarLayoutRenderer`,
  `hud.wynnOverlayEnabled` default-on, server-agnostic activation, no
  allowlist/profile mode); 485/485 matching lang keys incl.
  `screen.simple_translate.hud.wynn.*`.
- Runtime status of the three optional-compat mixins and the Iceberg bridge is
  unverified (test client does not install FTB/Wynntils/Iceberg); evidence is
  descriptor-exact bytecode only.
- Migration-strip exception: `ModConfig` keeps
  `root.remove("general.wynncraftProfileMode")` (documented, asserted by
  `run-logic-checks.ps1`); the config key itself stays removed.

## 2026-07-28 packaging follow-up

This Mojang-named ModDevGradle target does not generate
`simple_translate.refmap.json`; its stale Mixin-config declaration was
removed. The clean rebuilt JAR has SHA256
`1C799F770C4F18D49499CA94AD773425763CA487D1ABA029742B740FAB8AC264`.
`validation-refmap-rerun-20260728-061400.out.log` deployed it to exact
NeoForge `21.7.11-beta` and reached `CodexSmokeWorld` at 06:14:48 (`entity
id 30`) with zero refmap warnings, zero Mixin application failures, and no
crash report.
