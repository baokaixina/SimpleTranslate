# Mixin / API evidence — SimpleTranslate NeoForge 1.21.3 (2026-07-27, NF-121A)

Exact artifacts used as evidence:

- Vanilla+NeoForge: `build/moddev/artifacts/neoforge-21.3.97-merged.jar`
  (ModDev-patched Mojmap jar for `neo_version=21.3.97`; the test client also
  runs NeoForge **21.3.97**, so compile evidence and runtime jar are the same
  build).
- FTB Library: `.analysis/optional-121x/ftb-library-neoforge-2101.1.33.jar`
  — newest 2101 build; declares `minecraft [1.21.1,)` / `neoforge [21.1.0,)`
  so it loads on 1.21.3; the official FTB maven has no 2102/2103 series.
- Iceberg: `.analysis/optional-121x/Iceberg-1.21.3-neoforge-1.2.10.jar` —
  the **exact** MC 1.21.3 NeoForge artifact (Modrinth listing saved at
  `.analysis/optional-121x/iceberg_versions.json`; 1.2.10 is the only 1.21.3
  build); declares `minecraft [1.21.3,)`, `neoforge [21.3.0,)`.

Audit method: `.analysis/nf-121a/audit_mixins.py` descriptor-exact check of
every enabled non-compat mixin vs the merged jar (107 descriptor checks,
output `.analysis/nf-121a/audit-1.21.3.txt`); FTB/Iceberg surfaces checked manually
with `javap -p -s` against the exact jars above.

## Vanilla mixins (26 non-compat entries)

| Surface | Exact target | Handler evidence | Runtime status |
| --- | --- | --- | --- |
| 22 eager mixins (1.21.2/1.21.3-era signatures incl. `EntityRenderState`-based `EntityRendererMixin`) | descriptor-exact vs `neoforge-21.3.97-merged.jar` (audit script: all specs found) | compiled handlers in `simple_translate-1.21.3-neoforge-2.1.28.jar` | **Runtime-proven** 2026-07-27 05:54 client run: 22 distinct mixins applied, zero injection errors |
| Lazy: `ClientTextTooltipAccessor`, `SignTextMixin`, `AdvancementToastMixin`, `AdvancementWidgetMixin` | descriptor-exact vs same merged jar | same jar | Static bytecode evidence |
| `CycleButtonTooltipMixin` `@At INVOKE` `CycleButton;setTooltip(Tooltip)V` | audit-script false positive: declared in `AbstractWidget`; `javap -c` on the exact jar's `CycleButton` shows the `invokevirtual setTooltip:(Tooltip)V` call-site | — | Runtime-proven |

MixinExtras: target uses `@WrapMethod` (5 mixins), requiring bundled
MixinExtras >= 0.4.1; the 21.3 series forked long after the 21.1.22 bump to
0.4.1 (runtime-proven — all wrap handlers applied cleanly on 21.3.97).
Floor `[21.3.0-beta,21.4)` is the series-start minimum (series runs
21.3.0-beta … 21.3.97, first stable 21.3.56; every used API predates the
series).

## FTB Library (2 pseudo-mixins, plugin-gated, `remap=false`)

javap vs `ftb-library-neoforge-2101.1.33.jar`:
`ScreenWrapper.keyPressed(III)Z`, `ScreenWrapper.render(LGuiGraphics;IIF)V`,
`TextField.rawText: Component` (private),
`TextField.draw(LGuiGraphics;LTheme;IIII)V`, public `setText(Component)`
returning FTB `TextField` (reflective call) — all exact; Mojmap names;
`formattedText` deliberately NOT shadowed. Dormant unless FTB Library
installed; not runtime-exercised.

## Iceberg (reflection-only bridge, no mixin) — EXACT artifact

javap vs `Iceberg-1.21.3-neoforge-1.2.10.jar`:
`events.client.RenderTooltipEvents.GATHER : Event` (public static final),
`Event.register(T)` erased `(Ljava/lang/Object;)V`,
`Gather.onGather(ItemStack,int,int,List,int,int)` → `GatherResult`,
public `GatherResult(InteractionResult,int,List)` ctor — all exact for the
strings used by `compat/IcebergTooltipGatherCompat`. This target's Iceberg
evidence is now first-party exact (it is also the artifact the audit doc's
1.21.2 row used to borrow). `isLoaded("iceberg")`-gated; dormant on the test
client.

## Client run (fresh this session — the 2026-07-25 17:27 run was REJECTED
because its deployed jar predated the 2026-07-26 09:19 build)

- Jar `simple_translate-1.21.3-neoforge-2.1.28.jar` (2026-07-26 09:19:01,
  SHA256 `17A25EEDD92E75C2871C1D144A92C01F68DD73C28C14639394B29A935C01DEAB`,
  zero source files newer). Jar audit: 156/156 classes 1:1, zero suspect
  entries; packaged toml carries floors `[21.3.0-beta,21.4)` /
  `[1.21.3,1.21.4)`.
- Client check 2026-07-27 05:54: the script redeployed the current jar
  (hash-identical afterwards in the client mods dir AND the temp
  version-dir) and returned
  `PASS: dedicated local test client reached world entry` — loopback smoke
  server on 127.0.0.1:25792, `CodexTester joined the game` 05:54:33, orderly
  stop with all chunks saved.
- Runtime NeoForge 21.3.97 (same as compile target), 22 mixins applied, zero
  injection errors, zero `Failed to handle packet` / `Network Protocol`
  lines, no crash reports, both SimpleTranslate init lines present.
- Metadata era proven at runtime (neoforge.mods.toml, `type="required"`,
  mod listed + initialized, resources loaded).

## 2026-07-28 packaging follow-up

The Mojang-named ModDevGradle target produces no
`simple_translate.refmap.json`, so its stale config declaration was removed.
The clean rebuilt JAR has SHA256
`25B4B87CA1D32F1B373224A186CC12E0613076473C375D62A4326F3BC2DFD0F1`.
`validation-refmap-rerun-20260728-063700.out.log` deployed it to exact
NeoForge `21.3.97` and reached `CodexSmokeWorld` at 06:37:22 (`entity id
33`) with zero refmap warnings, zero Mixin application failures, and no crash
report.
