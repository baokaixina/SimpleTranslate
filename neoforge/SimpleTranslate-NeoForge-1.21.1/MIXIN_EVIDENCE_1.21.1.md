# Mixin / API evidence — SimpleTranslate NeoForge 1.21.1 (2026-07-27, NF-121A)

Exact artifacts used as evidence:

- Vanilla+NeoForge: `build/moddev/artifacts/neoforge-21.1.243-merged.jar`
  (ModDev-patched Mojmap jar for `neo_version=21.1.243`; runtime client is
  Mojmap — 21.x production. Test client runs NeoForge 21.1.233, inside the
  declared range).
- FTB Library: `.analysis/optional-121x/ftb-library-neoforge-2101.1.33.jar`
  — newest 2101 build on the official FTB maven (34 builds; no 2102/2103
  series exists for any loader); declares `minecraft [1.21.1,)`,
  `neoforge [21.1.0,)`, so it is the artifact that loads on 1.21.1.
- Iceberg: `.analysis/optional-121x/Iceberg-1.21.1-neoforge-1.3.2.jar`
  (newest exact MC 1.21.1 artifact, declares `minecraft [1.21.1]`) and
  `.analysis/optional-121x/Iceberg-1.21.1-neoforge-1.2.9.2.jar` (older
  1.21.1 line, declares `minecraft [1.21.1,)`); the bridge's reflected
  surface is javap-identical in both.

Audit method: `.analysis/nf-121a/audit_mixins.py` descriptor-exact check of
every enabled non-compat mixin vs the merged jar (108 descriptor checks,
output `.analysis/nf-121a/audit-1.21.1.txt`); FTB/Iceberg surfaces checked manually
with `javap -p -s` against the exact jars above.

## Vanilla mixins (26 non-compat entries)

| Surface | Exact target | Handler evidence | Runtime status |
| --- | --- | --- | --- |
| 22 eager mixins | descriptor-exact vs `neoforge-21.1.243-merged.jar` (audit script: all specs found) | compiled handlers in `simple_translate-1.21.1-neoforge-2.1.28.jar` | **Runtime-proven** 2026-07-27 05:47 client run: 22 distinct mixins applied, zero injection errors |
| Lazy: `ClientTextTooltipAccessor`, `SignTextMixin`, `AdvancementToastMixin`, `AdvancementWidgetMixin` | descriptor-exact vs same merged jar | same jar | Static bytecode evidence (screen/sign-load targets) |
| `CycleButtonTooltipMixin` `@At INVOKE` `CycleButton;setTooltip(Tooltip)V` | audit-script false positive: `setTooltip` is declared in `AbstractWidget`; `javap -c` on the exact jar's `CycleButton` shows the `invokevirtual setTooltip:(Tooltip)V` call-site | — | Runtime-proven |

MixinExtras: this target uses `@WrapMethod`
(`TitleOverlayMixin`, `HoverTooltipMixin`, `BossHealthOverlayMixin`,
`AdvancementToastMixin`, `AdvancementWidgetMixin`), which requires bundled
MixinExtras >= 0.4.1 at runtime. NeoForge bundles 0.4.1 starting exactly at
**21.1.22** (official changelog `#1463`); the test client (21.1.233) ships
0.5.3 and applied all wrap handlers cleanly.

## Metadata floor correction (this session)

`gradle.properties` `neo_version_range` changed
`[21.1.195,21.2)` → **`[21.1.22,21.2)`**. The old floor had no API
justification (21.1.195 is only a MixinExtras 0.5.0 renovate bump); the new
floor is the true minimum: `@WrapMethod` needs MixinExtras >= 0.4.1, bundled
from 21.1.22; every other used NeoForge API predates the 21.1 series
(config-screen UI 21.0.110-beta, `IConfigScreenFactory.createScreen(
ModContainer,Screen)` already in 21.0.x — javap-confirmed in
`neoforge-21.0.167-merged.jar` — payload API 20.5+, tick-event split
20.5.14-beta). Justified via official changelog evidence, not compile-at-floor
(same standard as NF-121B floors).

## FTB Library (2 pseudo-mixins, plugin-gated, `remap=false`)

javap vs `ftb-library-neoforge-2101.1.33.jar`: `ScreenWrapper.keyPressed(III)Z`,
`ScreenWrapper.render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V`,
`TextField.rawText: Lnet/minecraft/network/chat/Component;` (private),
`TextField.draw(LGuiGraphics;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V`,
public `setText(Component)` returning FTB `TextField` (reflective call) —
all exact; Mojmap names; `formattedText` deliberately NOT shadowed.
Dormant unless FTB Library installed; not runtime-exercised.

## Iceberg (reflection-only bridge, no mixin)

javap vs BOTH `Iceberg-1.21.1-neoforge-1.3.2.jar` and
`Iceberg-1.21.1-neoforge-1.2.9.2.jar`:
`events.client.RenderTooltipEvents.GATHER` public static final of type
`com.anthonyhilyard.iceberg.events.Event`, `Event.register(T)` erased
`(Ljava/lang/Object;)V`, `Gather.onGather(ItemStack,int,int,List,int,int)`,
public `GatherResult(InteractionResult,int,List)` ctor — identical surface in
both eras, exact match for the bridge. `isLoaded("iceberg")`-gated; dormant
on the test client.

## Client run (fresh this session — rebuild after floor fix)

- Rebuilt jar 2026-07-27 05:46:28, SHA256
  `8F14CB7A8A81BA711E728858557B4A28EA55069551C8FA6B80EE533146D01226`,
  packaged `neoforge.mods.toml` carries `[21.1.22,21.2)` (verified inside the
  jar). Jar audit: 156/156 top-level classes 1:1 with source, zero suspect
  entries, langs/icon/mixins.json present.
- Client check 05:47 (script-deployed, hash-identical in the client mods dir
  AND the temp version-dir): verdict
  `PASS: dedicated local test client reached world entry` — Quick Play
  singleplayer, integrated server login
  (`CodexTester[local:…] logged in with entity id 33`), chunks saved.
- 22 mixins applied, zero injection errors, no crash reports, no
  `Failed to handle packet` / `Network Protocol` lines. Test client also runs
  sodium+iris (NeoForge 0.8.12 / 1.8.14-beta.1) — no interaction issues.
- Metadata era proven at runtime (mod listed, initialized, resources loaded).

## 2026-07-28 packaging follow-up

The Mojang-named ModDevGradle target produces no
`simple_translate.refmap.json`, so its stale config declaration was removed.
The clean rebuilt JAR has SHA256
`F9CD3C173FD57E911EFF39AF222736CC6570047F4357B5189826CEDB0BFDF3B5`.
`validation-refmap-rerun-20260728-064100.out.log` deployed it to exact
NeoForge `21.1.243` and entered `CodexSmokeWorld` at 06:40:59 (`entity id
33`) with zero SimpleTranslate refmap warnings, zero SimpleTranslate Mixin
application failures, and no crash report. This client also loads Sodium/Iris;
their independent refmap and missing-optional-integration warnings remain
third-party noise, not SimpleTranslate diagnostics.
