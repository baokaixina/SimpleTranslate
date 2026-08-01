# SimpleTranslate NeoForge 1.21.8 Mixin evidence (2026-07-26)

Exact build target: Minecraft 1.21.8, NeoForge **21.8.53** (ModDevGradle,
Mojmap runtime), Java 21, mod 2.1.28. Vanilla evidence jar:
`build/moddev/artifacts/neoforge-21.8.53-merged.jar`. Runtime evidence: fresh
client check 2026-07-26 23:25 on
`<designated-test-client>\neoforge\1.21.8\versions\1.21.8-NeoForge` — script verdict
`PASS: dedicated local test client reached world entry`, via **Quick Play
singleplayer** into `CodexSmokeWorld` (`CodexTester[local:...] logged in with
entity id 2` on the integrated server at 23:25:26; clean exit with chunks
saved for all three dimensions at 23:25:33). Deployed jar SHA256-identical to
`build/libs/simple_translate-1.21.8-neoforge-2.1.28.jar`; zero source files
newer than the jar.

**1.21.8 lazy-mixin / network-protocol audit (per `LAZY_MIXIN_1.21.8.md`):**
world entry was performed (not main-menu-only); `latest.log` and `debug.log`
contain **zero** matches for `Failed to handle packet`, `Network Protocol`,
`Mixin transformation`, `InjectionError`, `InvalidInjectionException`, or
`Critical injection failure`; no crash reports were produced.

`simple_translate.mixins.json`: **31 client entries**, byte-identical client
list to the Fabric 1.21.8 donor (same list as 1.21.6/1.21.7),
`injectors.defaultRequire=1`, plugin `SimpleTranslateMixinPlugin` gating the
three optional-compat entries on `ftblibrary` / `wynntils` presence in
`LoadingModList`.

## Runtime-proven entries (24)

`debug.log` of the 23:25 run shows `Mixing <name> from
simple_translate.mixins.json` for all 24 eager classes with zero
injection-error lines under `defaultRequire=1`, followed by world entry:

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

`javap -p -s` against `neoforge-21.8.53-merged.jar` (2026-07-26, this
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
  CAN be installed on a 1.21.8 client. `javap -p -s` (re-run this session):
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
  No Wynntils release exists for MC 1.21.8: Modrinth version listing and the
  Wynntils GitHub releases API (last 100 releases, checked 2026-07-26) both
  show release assets only for `MC-1.21.4` (3.x) and `MC-1.21.11` (4.x). FML
  can never load any Wynntils build on a 1.21.8 client, so
  `SimpleTranslateMixinPlugin` permanently skips
  `compat.WynntilsOverlayManagerMixin`. The class carries the donor's
  1.21.11-era single-arg descriptor
  `renderOverlays(Lcom/wynntils/mc/event/RenderEvent;)V` (Fabric 1.21.8 donor
  parity); retained as dormant donor-parity code, not as exact evidence.

## Non-mixin compat: Iceberg (documented gap, loadable-artifact evidence)

No 1.21.5+ Iceberg build exists on Modrinth (any loader; checked 2026-07-26).
Newest loadable artifact: `Iceberg-1.21.4-neoforge-1.2.13.jar`, declaring
`minecraft versionRange="[1.21.3,)"` — installable on 1.21.8. `javap -p -s`
confirms every reflected member used by `IcebergTooltipGatherCompat`
(`RenderTooltipEvents.GATHER`, `Gather#onGather(ItemStack,int,int,List,int,
int)`, `GatherResult(InteractionResult,int,List)`, `Event#register(Object)`).
Reflection-only with graceful failure, gated on `ModList.isLoaded("iceberg")`.

## NeoForge API surface

Runtime-proven on 21.8.53 by the 23:25 run (mod construction, config
registration, key mappings, client tick/connection events, payload
registration, singleplayer world join, zero SimpleTranslate errors). API
shapes are the post-21.7 forms: `SharedCacheNetworking` is byte-identical to
the 1.21.7 target (separate client payload handler +
`ClientPacketDistributor`, both present since before the 21.8 series);
`SignSelectionHighlighter` uses `RenderLevelStageEvent.AfterEntities`
(sub-events present since 21.6.16-beta, i.e. from 21.8.0-beta on).

## Metadata floors

`neoforge [21.8.0-beta,21.9)` — justified minimum: every NeoForge API the mod
uses (client-network split, RenderLevelStageEvent sub-events, payload
registrar) predates the 21.8 series, so the series-start build 21.8.0-beta
(first entry in the official maven metadata; first stable is 21.8.9) is the
true floor. `minecraft [1.21.8,1.21.9)`; `loader [1,)`. Floors, not build
pins (build uses 21.8.53).

## Notes

- Packaged jar is a 1:1 image of production source (165/165 top-level
  classes), zero OCR/retired-symbol matches; full Wynn feature surface present
  (>=1.21.4 gate; `feature/wynn/` 6 classes + `ActionbarLayoutRenderer`,
  `hud.wynnOverlayEnabled` default-on, server-agnostic activation, no
  allowlist/profile mode); 485/485 matching lang keys incl.
  `screen.simple_translate.hud.wynn.*`.
- Truly lazy GUI classes (BookViewScreen/BookEditScreen/advancement screens)
  still only load when their screens open; their four lazy entries carry the
  javap evidence above, and the eager GUI/sign/entity render mixins were
  exercised through actual world rendering in this run.
- Runtime status of the three optional-compat mixins and the Iceberg bridge is
  unverified (test client does not install FTB/Wynntils/Iceberg); evidence is
  descriptor-exact bytecode only.
- Migration-strip exception: `ModConfig` keeps
  `root.remove("general.wynncraftProfileMode")` (documented, asserted by
  `run-logic-checks.ps1`); the config key itself stays removed.

## 2026-07-28 packaging follow-up

This Mojang-named ModDevGradle target does not generate
`simple_translate.refmap.json`, so the stale Mixin-config declaration was
removed. The clean rebuilt JAR has SHA256
`B1CBB192947C2657E901D32E04AE52819066423533D451F5AA823F866CA7B335`.
`validation-refmap-rerun-20260728-060400.out.log` deployed it to the exact
NeoForge client and reached `CodexSmokeWorld` at 06:05:38 (`entity id 66`),
with zero refmap warnings, zero Mixin application failures, and no crash
report.
