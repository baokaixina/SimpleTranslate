# Mixin / API evidence — SimpleTranslate NeoForge 1.21 (2026-07-27, NF-121A)

Exact artifacts used as evidence:

- Vanilla+NeoForge: `build/moddev/artifacts/neoforge-21.0.167-merged.jar`
  (ModDev-patched Mojmap jar for the exact `neo_version=21.0.167`; NeoForge
  21.x production runtime is Mojmap-named, confirmed by clean runtime mixin
  application in the production client, which loads
  `client-1.21-20240613.152323-srg.jar` per `runclient-check.out.log`).
- FTB Library: `.analysis/optional-121x/ftb-library-neoforge-2100.1.4.jar`
  (2100 series = the MC 1.21 series; jar declares `minecraft [1.21,)`,
  `neoforge [21.0.143,)`; official FTB maven `ftb-library-neoforge`
  metadata has series 2004/2006/2100/2101/2111/26 only — 2100.1.4 is the
  newest 2100 build).
- Iceberg: `.analysis/optional-121x/Iceberg-1.21-neoforge-1.2.5.jar`
  (exact Modrinth artifact for MC 1.21 NeoForge, the newest 1.21 build:
  1.2.0–1.2.5; declares `minecraft [1.21,)`, `neoforge [21.0.143,)`).

Audit method: `.analysis/nf-121a/audit_mixins.py` resolves every enabled
non-compat mixin's `@Mixin` targets, `method=` specs, `@At` targets, and
`@Shadow` members and checks them descriptor-exact with `javap -p -s` against
the merged jar (117 descriptor checks, output `.analysis/nf-121a/audit-1.21.txt`);
compat pseudo-mixins and the Iceberg reflection bridge were checked manually
with `javap -p -s` against the exact third-party jars above.

## Vanilla mixins (26 non-compat entries in simple_translate.mixins.json)

| Surface | Exact target | Handler evidence | Runtime status |
| --- | --- | --- | --- |
| 22 eager mixins (Chat, Screen, GUI frame/draw, widget, book view/edit, sign renderer, hover tooltip, scoreboard, tab list, boss bar, title/HUD, entity name, text display, font manager/set + accessors) | descriptor-exact vs `neoforge-21.0.167-merged.jar` (audit script: all specs found) | compiled handlers in `simple_translate-1.21-neoforge-2.1.28.jar` | **Runtime-proven** 2026-07-26 09:25–09:26 client run: 22 distinct mixins applied (`debug.log`), zero injection errors under `required=true` config |
| Lazy: `ClientTextTooltipAccessor`, `SignTextMixin`, `AdvancementToastMixin`, `AdvancementWidgetMixin` | descriptor-exact vs the same merged jar (audit script) | same jar | Static bytecode evidence (targets load only on screen/sign use) |
| `CycleButtonTooltipMixin` `@At INVOKE` `CycleButton;setTooltip(Tooltip)V` | The audit script reports this single spec "not found" because `setTooltip` is **declared in `AbstractWidget`**; `javap -c` on the exact jar's `CycleButton` shows the `invokevirtual setTooltip:(Lnet/minecraft/client/gui/components/Tooltip;)V` call-site with owner `CycleButton` — the @At target is correct | — | Runtime-proven (applied 09:25 run) |

MixinExtras: NeoForge 21.0 bundles MixinExtras **0.3.5** (jarJar of
`neoforge-21.0.167-universal.jar`, seen in the run log). This target therefore
uses only `@WrapOperation` and deliberately has **no `@WrapMethod`**
(documented in `TitleOverlayMixin`/`BossHealthOverlayMixin` comments and
asserted by `scripts/run-logic-checks.ps1`). This is a required divergence
from the 1.21.1+ NeoForge targets.

## FTB Library (2 pseudo-mixins, plugin-gated, `remap=false`)

| Member | Evidence (javap vs ftb-library-neoforge-2100.1.4.jar) |
| --- | --- |
| `ScreenWrapper.keyPressed` | `(III)Z` — exact |
| `ScreenWrapper.render` | `(Lnet/minecraft/client/gui/GuiGraphics;IIF)V` — exact |
| `TextField.rawText` | `Lnet/minecraft/network/chat/Component;` (private) — exact `@Shadow` name+type |
| `TextField.draw` | `(Lnet/minecraft/client/gui/GuiGraphics;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V` — exact |
| `TextField.setText(Component)` | public, returns `dev.ftb.mods.ftblibrary.ui.TextField` — invoked reflectively (return type not linkable from a pseudo mixin) |

Mojmap member names (no Fabric intermediary strings) — correct for the
NeoForge runtime. `formattedText` is deliberately NOT shadowed. Runtime
status: dormant unless FTB Library is installed (plugin-gated); not
runtime-exercised (test client has no FTB).

## Iceberg (reflection-only bridge `compat/IcebergTooltipGatherCompat`, no mixin)

javap vs `Iceberg-1.21-neoforge-1.2.5.jar` (exact MC 1.21 artifact):
`events.client.RenderTooltipEvents.GATHER : Lcom/anthonyhilyard/iceberg/events/Event;`
(public static final), `Event.register(T)` erased `(Ljava/lang/Object;)V`,
`Gather.onGather(ItemStack,int,int,List,int,int)` →
`(Lnet/minecraft/world/item/ItemStack;IILjava/util/List;II)L...$GatherResult;`,
`GatherResult(InteractionResult,int,List)` public ctor — all exact matches
for the strings/shapes used by the bridge. Gated by
`ModList.get().isLoaded("iceberg")` + try/catch; dormant on the test client.

## Client run accepted as completion evidence (morning run, fully re-proven)

- Jar `build/libs/simple_translate-1.21-neoforge-2.1.28.jar` built
  2026-07-26 09:18:59, SHA256
  `ACD070740EB6932B9B9065FF3B1CA8C5C521A76C64FC8EC85D2FD1955385C782`;
  byte-identical copy in
  `<designated-test-client>\neoforge\1.21\versions\1.21-NeoForge\mods` AND in the temp
  version-dir the launcher actually loads
  (`codex-mc-test-clients\version-dirs\neoforge-1.21-NeoForge\mods`).
  Zero source files newer than the jar (newest source 09:17:37).
- Launch 09:25:53 → world entry on the script's loopback smoke server
  (`st-local-smoke-server-1.21-72c41d4c…\logs\latest.log`:
  `CodexTester joined the game` 09:26:03, orderly `Stopping the server`
  09:26:10 with all chunks saved — the script stops the server only after its
  post-entry audit).
- `debug.log`: 22 distinct simple_translate mixins applied, zero
  injection-error patterns; no crash-reports directory exists.
- Metadata era proven at runtime: `neoforge.mods.toml` + `type="required"`
  (correct 21.x form), mod listed and initialized, resource reload includes
  `mod/simple_translate` (no root `pack.mcmeta` needed on 20.4+).

## 2026-07-28 packaging follow-up

This Mojang-named ModDevGradle target does not generate
`simple_translate.refmap.json`; its stale Mixin-config declaration was
removed. The clean rebuilt JAR has SHA256
`C3C7406ACA8EDCCD24AAF5AFA961D108E6A1B11BCDECD6EC279755146971019F`.
`validation-refmap-rerun-20260728-063850.out.log` deployed it to exact
NeoForge `21.0.167` and reached `CodexSmokeWorld` at 06:39:50 (`entity id
23`), with zero SimpleTranslate refmap warnings, zero SimpleTranslate Mixin
application failures, and no crash report.
