# Mixin / API evidence — SimpleTranslate NeoForge 1.21.2 (2026-07-27, NF-121A)

Exact artifacts used as evidence:

- Vanilla+NeoForge: `build/moddev/artifacts/neoforge-21.2.1-beta-merged.jar`
  (ModDev-patched Mojmap jar for `neo_version=21.2.1-beta`; the 21.2 series
  has exactly two builds on the official maven — 21.2.0-beta, 21.2.1-beta —
  both beta; the test client runs 21.2.1-beta).
- FTB Library: `.analysis/optional-121x/ftb-library-neoforge-2101.1.33.jar`
  — the newest artifact that can load on MC 1.21.2: it declares
  `minecraft [1.21.1,)` / `neoforge [21.1.0,)` (open ranges), and the
  official FTB maven has **no 2102 series at all** (series list:
  2004/2006/2100/2101/2111/26). Same evidence base as the audit doc's
  "FTB 2100/2101 系列" row, now artifact-exact.
- Iceberg: see the gap-closure section below.

Audit method: `.analysis/nf-121a/audit_mixins.py` descriptor-exact check of
every enabled non-compat mixin vs the merged jar (107 descriptor checks,
output `.analysis/nf-121a/audit-1.21.2.txt`); FTB/Iceberg surfaces checked manually
with `javap -p -s` against the exact jars.

## Vanilla mixins (26 non-compat entries)

| Surface | Exact target | Handler evidence | Runtime status |
| --- | --- | --- | --- |
| 22 eager mixins (incl. the 1.21.2-era `EntityRendererMixin` with `EntityRenderState` parameter — render-state refactor signature confirmed in the exact jar and in the runtime apply log) | descriptor-exact vs `neoforge-21.2.1-beta-merged.jar` (audit script: all specs found) | compiled handlers in `simple_translate-1.21.2-neoforge-2.1.28.jar` | **Runtime-proven** 2026-07-27 05:51 client run: 22 distinct mixins applied, zero injection errors |
| Lazy: `ClientTextTooltipAccessor`, `SignTextMixin`, `AdvancementToastMixin`, `AdvancementWidgetMixin` | descriptor-exact vs same merged jar | same jar | Static bytecode evidence |
| `CycleButtonTooltipMixin` `@At INVOKE` `CycleButton;setTooltip(Tooltip)V` | audit-script false positive: declared in `AbstractWidget`; `javap -c` on the exact jar's `CycleButton` shows the `invokevirtual setTooltip:(Tooltip)V` call-site | — | Runtime-proven |

MixinExtras: target uses `@WrapMethod` (5 mixins). Both 21.2 builds bundle
MixinExtras 0.4.1 (read from `neoforge-21.2.1-beta-universal.jar`
`META-INF/jarjar/metadata.json`; the series forked after the 21.1.22 bump to
0.4.1 — see the cumulative 21.2.1-beta changelog), which is the first
version with `@WrapMethod`. Floor `[21.2.0-beta,21.3)` is therefore the true
series-start minimum.

## Iceberg — borrowed-evidence gap CLOSED (this was the audit-doc gap owned by NF-121A)

`docs/API_MIXIN_AUDIT_2026-07-26.md` recorded "NeoForge 1.21.2 借用 1.21.3
Iceberg 证据". Resolution:

1. **No Iceberg build was ever released for MC 1.21.2 on any channel.**
   - Modrinth: full NeoForge version listing saved at
     `.analysis/optional-121x/iceberg_versions.json` (fetched 2026-07-26
     22:38) — 1.21-series entries are 1.21 (1.2.0–1.2.5), 1.21.1
     (1.2.7–1.2.9.2, 1.3.0–1.3.2), 1.21.3 (1.2.10), 1.21.4 (1.2.11–1.2.13),
     1.21.11 (1.4.0/1.4.0.1). **Zero 1.21.2 entries.**
   - CurseForge: files page checked 2026-07-27 with the 1.21.2 game-version
     filter — no files; 1.21.2 is absent from the version filter list
     (versions offered: 26.x, 1.21.11, 1.21.4, 1.21.3, 1.21.1, 1.21).
2. **Which artifacts COULD load on a real 1.21.2 install** (FML range
   check of the exact jars):
   - `Iceberg-1.21.3-neoforge-1.2.10` declares `minecraft [1.21.3,)` →
     **cannot** load on 1.21.2 (so 1.2.10 alone was never the right evidence
     for this target — the audit-doc row is corrected, not just confirmed).
   - `Iceberg-1.21.1-neoforge-1.3.2` declares `minecraft [1.21.1]` exact →
     cannot load on 1.21.2.
   - `Iceberg-1.21-neoforge-1.2.5` (`[1.21,)`) and
     `Iceberg-1.21.1-neoforge-1.2.9.2` (`[1.21.1,)`) have open ranges →
     FML would accept them on 1.21.2 if a user force-installs.
3. **The reflected surface is identical in every era artifact.** javap of
   all four jars (1.2.5, 1.2.9.2, 1.2.10, 1.3.2):
   `events.client.RenderTooltipEvents.GATHER : Event` (public static final),
   `Event.register(T)` erased `(Ljava/lang/Object;)V`,
   `Gather.onGather(ItemStack,int,int,List,int,int)`,
   public `GatherResult(InteractionResult,int,List)` ctor — all exact for
   the strings used by `compat/IcebergTooltipGatherCompat`.
4. **Status: dormant-by-absence, safe-by-construction.** The bridge is
   reflection-only (no mixin, no compile-time Iceberg reference), gated by
   `ModList.get().isLoaded("iceberg")` plus try/catch, and matches every
   artifact FML could possibly accept on 1.21.2. The in-source javadoc of
   `IcebergTooltipGatherCompat` already documents the 1.21.2 nonexistence.

## FTB Library (2 pseudo-mixins, plugin-gated, `remap=false`)

javap vs `ftb-library-neoforge-2101.1.33.jar` (the only loadable series on
1.21.2): `ScreenWrapper.keyPressed(III)Z`,
`ScreenWrapper.render(LGuiGraphics;IIF)V`, `TextField.rawText: Component`
(private), `TextField.draw(LGuiGraphics;LTheme;IIII)V`, public
`setText(Component)` (reflective) — all exact; `formattedText` NOT shadowed.
Dormant unless FTB Library installed.

## Client run (fresh this session; the two 2026-07-26 09:28/09:29 morning
runs died before ready and were NOT accepted)

- Jar `simple_translate-1.21.2-neoforge-2.1.28.jar` (2026-07-26 09:19:03,
  SHA256 `CC88EE360D6595E4FE845BFE58B8FD6C5D9F6B67AB2A777471ECEEA1C9FBE834`,
  zero source files newer). Jar audit: 156/156 classes 1:1, zero suspect
  entries; packaged toml carries floors `[21.2.0-beta,21.3)` /
  `[1.21.2,1.21.3)`.
- Client check 2026-07-27 05:51: script verdict
  `PASS: dedicated local test client reached world entry` — loopback smoke
  server on 127.0.0.1:25835, `CodexTester joined the game` 05:51:44, orderly
  stop with all chunks saved (script stops the server only after its
  post-entry audit). Deployed jar hash-identical in the client mods dir AND
  the temp version-dir.
- 22 mixins applied, zero injection errors, zero
  `Failed to handle packet` / `Network Protocol` lines, no crash reports.
- The morning failures left no crash report, no hs_err, and no error line —
  the client exited ~2 s after atlas creation on two consecutive runs, before
  any world target was passed; today's run on identical jar+script passed
  cleanly, so the failure is attributed to the launch environment, not the
  mod (kept as a watch item, not a defect).
- Metadata era proven at runtime (neoforge.mods.toml, `type="required"`,
  mod listed + initialized, resources loaded).

## 2026-07-28 packaging follow-up

This Mojang-named ModDevGradle target does not generate
`simple_translate.refmap.json`; its stale Mixin-config declaration was
removed. The clean rebuilt JAR has SHA256
`1B835428FE1A3D0A11CA2E57E6F622E12E75C83030A55D6EF669A549A8225DBE`.
`validation-refmap-rerun-20260728-063500.out.log` deployed it to exact
NeoForge `21.2.31` and entered `CodexSmokeWorld` at 06:36:17 (`entity id
11`), with zero refmap warnings, zero Mixin application failures, and no
crash report.
