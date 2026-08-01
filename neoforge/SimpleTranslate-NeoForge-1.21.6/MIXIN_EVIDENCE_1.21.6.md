# SimpleTranslate NeoForge 1.21.6 Mixin evidence (2026-07-26)

Exact build target: Minecraft 1.21.6, NeoForge **21.6.20-beta** (ModDevGradle,
Mojmap runtime), Java 21, mod 2.1.28. Vanilla evidence jar:
`build/moddev/artifacts/neoforge-21.6.20-beta-merged.jar` (the exact
NeoForge-patched, Mojmap-named compile artifact). Runtime evidence: fresh
client check 2026-07-26 22:41 on
`<designated-test-client>\neoforge\1.21.6\versions\1.21.6-NeoForge` with Quick Play world
entry into `CodexSmokeWorld` via the script's loopback smoke server
(`CodexTester ... logged in with entity id 1` at 22:41:48, `joined the game`,
orderly stop with chunks saved at 22:41:56). Deployed jar SHA256-identical to
`build/libs/simple_translate-1.21.6-neoforge-2.1.28.jar`; zero source files
newer than the jar.

`simple_translate.mixins.json`: **31 client entries**, byte-identical client
list to the Fabric 1.21.6 donor (= the 1.21.5 list plus `ScreenAccessor`,
which the 1.21.6+ tree uses eagerly), `injectors.defaultRequire=1`, plugin
`SimpleTranslateMixinPlugin` gating the three optional-compat entries on
`ftblibrary` / `wynntils` presence in `LoadingModList`.

## Runtime-proven entries (24)

`debug.log` of the 22:41 run shows `Mixing <name> from
simple_translate.mixins.json` for all 24 eager classes with **zero**
`InjectionError` / `InvalidInjectionException` / `Critical injection failure`
lines under `defaultRequire=1`, followed by world entry — every injection in
these classes found its exact 1.21.6 target (GUI render-pipeline churn
included) or the run would have failed:

AbstractContainerScreenMixin, AbstractWidgetTranslationMixin,
BookEditScreenMixin, BookViewScreenMixin, BossHealthOverlayMixin,
ChatComponentMixin, ChatScreenMixin, CycleButtonTooltipMixin,
EntityRendererMixin, FontManagerAccessor, FontManagerMixin,
FontPreparedTextBuilderMixin, FontSetAccessor, FontSetMixin,
GuiGraphicsTranslationMixin, HoverTooltipMixin, PlayerTabOverlayMixin,
ScoreboardMixin, ScreenAccessor, ScreenComponentClickMixin,
ScreenGuiTranslationMixin, SignRendererMixin, TextDisplayMixin,
TitleOverlayMixin.

No crash reports were produced; the only log noise is Realms-offline and
`DynamicUniformStorage` capacity resizes (vanilla 1.21.6 render-pipeline INFO).

## javap-verified lazy vanilla entries (4)

`javap -p -s` against `neoforge-21.6.20-beta-merged.jar` (2026-07-26, this
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
  Official FTB maven (`maven.ftb.dev`) `ftb-library-neoforge` metadata lists
  only series 2004 / 2006 / 2100 / 2101 / 2111 / 26 — there is **no
  2104–2108 series**. `ftb-library-neoforge-2101.1.33.jar` (newest 2101.x)
  declares `minecraft versionRange="[1.21.1,)"` (verified inside the jar's
  `neoforge.mods.toml`) and therefore CAN be installed and loaded on a 1.21.6
  client. `javap -p -s` on that jar (re-run this session):
  `ScreenWrapper.keyPressed(III)Z`,
  `ScreenWrapper.render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V`,
  `TextField.rawText : Lnet/minecraft/network/chat/Component;` (shadowed name
  AND type exact),
  `TextField.draw(Lnet/minecraft/client/gui/GuiGraphics;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V`,
  reflective `setText(Component)` present returning `TextField`;
  `formattedText : [Lnet/minecraft/network/chat/FormattedText;` correctly NOT
  shadowed (historic crash trap). This target's mixin descriptors are
  string-identical to that bytecode. `@Pseudo` + `remap=false` correct (FTB
  ships Mojmap names; NeoForge 21.6 runtime is Mojmap).
- **Wynntils compat — BLOCKED/dormant on this target (documented gap).**
  No Wynntils release exists for MC 1.21.6: Modrinth version listing and the
  Wynntils GitHub releases API (last 100 releases, checked 2026-07-26) both
  show release assets only for `MC-1.21.4` (3.x series) and `MC-1.21.11`
  (4.x series). FML can therefore never load any Wynntils build on a 1.21.6
  client, `LoadingModList` never contains `wynntils`, and
  `SimpleTranslateMixinPlugin` permanently skips
  `compat.WynntilsOverlayManagerMixin`. The class carries the donor's
  1.21.11-era single-arg descriptor
  `renderOverlays(Lcom/wynntils/mc/event/RenderEvent;)V` (Fabric 1.21.6 donor
  parity); it is unverifiable against any 1.21.6-loadable artifact and is
  retained as dormant donor-parity code, not as exact evidence.

## Non-mixin compat: Iceberg (documented gap, loadable-artifact evidence)

No 1.21.5+ Iceberg build exists on Modrinth (any loader; checked 2026-07-26).
The newest loadable artifact is `Iceberg-1.21.4-neoforge-1.2.13.jar`, which
declares `minecraft versionRange="[1.21.3,)"` (verified inside the jar) and
thus CAN be installed on 1.21.6. `javap -p -s` on that jar confirms every
reflected member used by `IcebergTooltipGatherCompat`:
`events.client.RenderTooltipEvents.GATHER`, `Gather#onGather(ItemStack,int,
int,List,int,int)`, record ctor `GatherResult(InteractionResult,int,List)`,
`Event#register(Object)`. The bridge is reflection-only with graceful failure,
gated on `ModList.isLoaded("iceberg")`.

## NeoForge API surface

Runtime-proven on 21.6.20-beta by the 22:41 run (mod construction, config
registration, key mappings, client tick/connection events, payload
registration, world join, zero SimpleTranslate errors). Version-specific:
`feature/sign/SignSelectionHighlighter` uses the 1.21.6-era
`RenderLevelStageEvent.AfterEntities` **sub-event class** (introduced in
NeoForge 21.6.16-beta, PR #2357 per the official changelog), replacing the
1.21.5 `getStage()` pattern.

## Metadata floors

`neoforge [21.6.16-beta,21.7)` — justified minimum: the entire 21.6 series is
beta-only (maven.neoforged.net metadata, 21.6.0-beta…21.6.20-beta, no stable),
and the mod requires the `RenderLevelStageEvent` explicit sub-events added in
exactly 21.6.16-beta. `minecraft [1.21.6,1.21.7)`; `loader [1,)`. Floors, not
build pins (build uses 21.6.20-beta).

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

The Mojang-named ModDevGradle target does not generate
`simple_translate.refmap.json`; the stale Mixin-config declaration was
removed. The clean rebuilt JAR has SHA256
`0E3EEFA25E400F841E374ECA97066F5F4AF791EA2526A3A63033ABC0AF1A4329`.
`validation-refmap-rerun-20260728-061100.out.log` deployed it to exact
NeoForge `21.6.20-beta` and reached `CodexSmokeWorld` at 06:13:09 (`entity
id 21`) with zero refmap warnings, zero Mixin application failures, and no
crash report.
