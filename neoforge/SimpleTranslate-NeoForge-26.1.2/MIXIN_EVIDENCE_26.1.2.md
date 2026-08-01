# SimpleTranslate NeoForge 26.1.2 Mixin evidence (2026-07-27)

Exact build target: Minecraft 26.1.2, NeoForge `26.1.2.86`, ModDevGradle
2.0.141, Mojang official names at runtime (26.x has no intermediary; every
descriptor below is Mojang-named; `remap` is irrelevant for vanilla entries
because compile classpath == runtime names).

Primary evidence artifact:
`build/moddev/artifacts/minecraft-patched-26.1.2.86-merged.jar`
(the exact NeoForge-patched jar this target compiles against). All 30 enabled
entries in `simple_translate.mixins.json` were machine-checked against it with
`.analysis/optional-26/verify_mixins_26.py` (descriptor-exact: @Mixin targets,
injection `method=`/`target=` strings, @Shadow field name+type,
@Accessor/@Invoker members). Result: 29/30 OK plus the single known
CycleButton false negative (below). Deployable jar:
`build/libs/simple_translate-26.1.2-neoforge-2.1.28.jar`
(SHA256 AA07A98D64E8BBBCCA2DA8838F64678FBA7AA597E8BF33CB449939F057D095DD).

Third-party artifacts (exact, in `<workspace>\.analysis\optional-26\`):

- FTB Library `ftb-library-neoforge-26.1.2.6.jar` (official maven.ftb.dev;
  26.1.2-series build) and `ftb-library-neoforge-26.1.1.1.jar`. Both declare
  `minecraft [26.1,27)` / `neoforge [26.1.0.0-beta,27)`, so both are loadable
  on MC 26.1.2. FTB pseudo mixins verifier-checked descriptor-exact against
  **both** jars.
- Iceberg `Iceberg-26.1.2-neoforge-1.4.1.1.jar` — Modrinth `game_versions`
  exactly `26.1, 26.1.1, 26.1.2` (on-disk dump `iceberg_versions.json`):
  the exact-series artifact for this target, newest 26.1.x build.
- Wynntils: **no 26.x release exists** (`wynntils_versions.json` Modrinth
  dump, 731 versions, zero with 26.x game_versions; newest v4.2.3 / MC
  1.21.11). Nearest artifact used for descriptor evidence only.

## Findings and fixes

1. **`SignRendererMixin` — stale 1.21.x-era descriptor, FIXED this session
   (2026-07-27).** The tree still carried
   `submitSignText(...SubmitNodeCollector,Z)V`; the exact 26.1.2 bytecode has
   `private void submitSignText(SignRenderState,PoseStack,SubmitNodeCollector,SignText)`
   (`javap -p -s` on the exact patched jar; identical break already fixed on
   26.1/26.1.1 on 2026-07-26). Because the old code used `require = 0`, the
   stale descriptor was a silent no-op (sign translation dead), not a crash.
   Both injections re-derived; front/back now computed by identity against
   `renderState.frontText`. The fixed descriptor string is present in the
   packaged class (verified inside the built jar).
2. **`CycleButtonTooltipMixin` — verifier false negative, descriptor exact.**
   `setTooltip` is declared in `AbstractWidget`; `javap -c` of the exact
   26.1.2 `CycleButton` shows
   `invokevirtual // Method setTooltip:(L...Tooltip;)V` with owner constant
   `CycleButton`, and `AbstractWidget.setTooltip(Tooltip)V` confirmed by
   `javap -p -s`. Exact.

## Vanilla surface highlights (26.1.2)

Identical API shape to 26.1/26.1.1 (hash diff between the three trees shows
only line endings, evidence comments, and the sign fix): extractor-based GUI
capture (`GuiGraphicsExtractor`), HUD/title owner still
`net.minecraft.client.gui.Gui` (Hud split arrives in 26.2), font pipeline and
accesstransformer members all resolved — machine-verified against the exact
26.1.2.86 patched jar this session.

## Optional-compat entries (plugin-gated pseudo mixins, `remap = false`)

| Entry | Exact artifact | Status |
| --- | --- | --- |
| `compat.FtbScreenWrapperTranslationMixin` | `ftb-library-neoforge-26.1.2.6.jar` (+26.1.1.1) | Descriptor-exact against both loadable jars. Dormant unless FTB Library installed; not runtime-exercised. |
| `compat.FtbTextFieldTranslationMixin` | `ftb-library-neoforge-26.1.2.6.jar` (+26.1.1.1) | Descriptor-exact (`TextField.rawText:Component` shadow; `formattedText` deliberately NOT shadowed). Same dormancy note. |
| `compat.WynntilsOverlayManagerMixin` | none for 26.x | **Permanently dormant: no Wynntils 26.x build exists** (documented Modrinth dump). Kept for donor parity; plugin skips it when `wynntils` absent, which on 26.1.2 is always. |

Iceberg integration is reflective (`compat/IcebergTooltipGatherCompat`), not a
mixin. All reflected members `javap -p -s`-verified this session in the exact
`Iceberg-26.1.2-neoforge-1.4.1.1.jar`: `RenderTooltipEvents.GATHER : Event`,
`Event.register(Object)V`, `Gather.onGather(ItemStack,II,List,II)` →
`GatherResult`, public ctor `GatherResult(InteractionResult,int,List)`.

## Metadata floors

`neoforge [26.1.2.0-beta,26.2)` — floor changed 2026-07-27 from
`[26.1.2,...)`: maven.neoforged.net release history shows the 26.1.2 series is
`26.1.2.0-beta` .. `26.1.2.87`, and the old floor string excluded
`26.1.2.0-beta` under Maven version ordering. `minecraft [26.1.2,26.2)`
unchanged (26.1.2 is the last 26.1.x patch). Floors are documented minimums,
not build pins (build pin is 26.1.2.86).

## Runtime status

Dedicated test client `<designated-test-client>\neoforge\26.1.2`
(`versions\neoforge-26.1.2.86`, provisioned 2026-07-27 from the official
`neoforge-26.1.2.86-installer.jar --install-client`; vanilla 26.1.2 jar SHA1
`4e618f09a0c649dde3fdf829df443ce0b8831e65` matched against piston-meta; asset
index 30 + smoke save seeded from the 26.1 instance): first launch died with
`EXCEPTION_ACCESS_VIOLATION` in `PalmInputTSF.dll` (third-party Windows IME
TSF hook, native crash outside the JVM while the desktop IME was in active
use — environmental, mod loaded cleanly before it). Immediate retry:
**PASS with world entry** 2026-07-27 06:59 (`CodexTester ... logged in with
entity id 107`), no mixin errors, no crash reports, no SimpleTranslate ERROR
lines; only offline-account Yggdrasil/Realms network errors. Optional-mod
mixins are not runtime-exercised (no FTB/Iceberg/Wynntils installed).

## 2026-07-28 packaging follow-up

Because this Mojang-named ModDevGradle target does not generate
`simple_translate.refmap.json`, its stale Mixin-config declaration was
removed. The clean rebuilt jar was deployed by
`validation-refmap-rerun-20260728-055800.out.log`; exact NeoForge `26.1.2.86`
entered `CodexSmokeWorld` at 05:58:08 (`entity id 107`) with zero refmap
warnings, zero Mixin application failures, and no crash report.
