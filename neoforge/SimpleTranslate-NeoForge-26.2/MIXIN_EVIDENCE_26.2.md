# SimpleTranslate NeoForge 26.2 Mixin evidence (2026-07-28)

Exact build target: Minecraft 26.2, NeoForge `26.2.0.32-beta`, ModDevGradle
2.0.141, Mojang official names at runtime (26.x has no intermediary; every
descriptor below is Mojang-named; `remap` is irrelevant for vanilla entries
because compile classpath == runtime names).

Primary evidence artifact:
`build/moddev/artifacts/minecraft-patched-26.2.0.32-beta-merged.jar`
(the exact NeoForge-patched jar this target compiles against). All 30 enabled
entries in `simple_translate.mixins.json` were machine-checked against it with
`.analysis/optional-26/verify_mixins_26.py` (descriptor-exact: @Mixin targets,
injection `method=`/`target=` strings, @Shadow field name+type,
@Accessor/@Invoker members). Result: 29/30 OK plus the single known
CycleButton false negative (below). Deployable jar:
`build/libs/simple_translate-26.2-neoforge-2.1.28.jar`
(SHA256 6F7F644DF74E5285C04A1BC88056D51B913C9CB1F6366CFDD2D0269DB4EDAF13).

## 26.2-specific vanilla surface: the Gui→Hud split

26.2 moves the HUD surfaces out of `net.minecraft.client.gui.Gui` into
`net.minecraft.client.gui.Hud` (both classes exist in the exact patched jar;
26.1.x has no `Hud` class — verified by javap on both jars). This tree's
`TitleOverlayMixin` and `ScoreboardMixin` target `Hud`, and all their
injection strings (`setTitle/setSubtitle/setOverlayMessage/clearTitles`,
`extractRenderState(GuiGraphicsExtractor,DeltaTracker)V`,
`extractOverlayMessage(...)`, `Font.width(FormattedText)I`,
`GuiGraphicsExtractor.textWithBackdrop(Font,Component,IIII)V`) resolved
descriptor-exact against the exact 26.2 patched jar via the verifier.
The GUI capture remains extractor-based (`GuiGraphicsExtractor` owner for
`GuiGraphicsTranslationMixin` / `HoverTooltipMixin`).

## Findings and fixes

1. **`SignRendererMixin` — stale 1.21.x-era descriptor, FIXED this session
   (2026-07-27).** Same break as 26.1/26.1.1/26.1.2: exact 26.2 bytecode has
   `private void submitSignText(SignRenderState,PoseStack,SubmitNodeCollector,SignText)`
   (`javap -p -s`), while the tree still carried the `...,Z)V` boolean form —
   a silent no-op due to `require = 0` (sign translation dead, no crash).
   Both injections re-derived; front/back computed by identity against
   `renderState.frontText`. Fixed descriptor verified inside the packaged jar.
2. **`CycleButtonTooltipMixin` — verifier false negative, descriptor exact.**
   `javap -c` of the exact 26.2 `CycleButton` shows
   `invokevirtual // Method setTooltip:(L...Tooltip;)V` (owner constant
   `CycleButton`; declared in `AbstractWidget`, `(L...Tooltip;)V` confirmed).

## Third-party status (exact artifacts in `<workspace>\.analysis\optional-26\`)

| Integration | Exact artifact | Status |
| --- | --- | --- |
| FTB Library (`compat.FtbScreenWrapperTranslationMixin`, `compat.FtbTextFieldTranslationMixin`) | `ftb-library-neoforge-26.1.2.6.jar` | **No 26.2-series FTB build exists**: maven.ftb.dev `ftb-library-neoforge` metadata (checked 2026-07-27) lists the 26.x series as 26.1.1.1, 26.1.2.1–26.1.2.6 and nothing newer. However 26.1.2.6 declares `minecraft [26.1,27)` in its own `neoforge.mods.toml`, so it **is loadable on MC 26.2**, making it the exact artifact a 26.2 user would install. Both pseudo mixins verifier-checked descriptor-exact against it. Dormant unless FTB Library installed; not runtime-exercised. |
| Iceberg (`compat/IcebergTooltipGatherCompat`, reflective, not a mixin) | `Iceberg-26.2-neoforge-1.4.2.1.jar` | Exact 26.2 artifact (Modrinth `game_versions` exactly `26.2`; newest 26.2 build). All reflected members `javap -p -s`-verified this session: `RenderTooltipEvents.GATHER : Event`, `Event.register(Object)V`, `Gather.onGather(ItemStack,II,List,II)` → `GatherResult`, public ctor `GatherResult(InteractionResult,int,List)`. |
| Wynntils (`compat.WynntilsOverlayManagerMixin`) | none for 26.x | **Permanently dormant: no Wynntils 26.x build exists** (Modrinth dump `wynntils_versions.json`, 731 versions, zero 26.x; newest v4.2.3 / MC 1.21.11). Kept for donor parity; mixin plugin skips it when `wynntils` is absent, which on 26.2 is always. |

## Metadata floors

`neoforge [26.2.0.0-beta,26.3)` — floor changed 2026-07-27 from
`[26.2.0,...)`: maven.neoforged.net release history shows the 26.2.0 series is
`26.2.0.0-beta` .. `26.2.0.35-beta` (no snapshot alphas), and the old floor
string excluded `26.2.0.0-beta` under Maven version ordering.
`minecraft [26.2,26.3)` unchanged. Floors are documented minimums, not build
pins (build pin is 26.2.0.32-beta).

## Runtime status

Dedicated test client `<designated-test-client>\neoforge\26.2`
(`versions\neoforge-26.2.0.32-beta`, provisioned 2026-07-27 from the official
`neoforge-26.2.0.32-beta-installer.jar --install-client`; vanilla 26.2 jar
SHA1 `2dc72797acbc1b63fc16a11c4ac393605f453754` matched against piston-meta;
asset index **32** downloaded from piston-meta with 470 new objects fetched on
top of the index-30 store seeded from the 26.1 instance; CodexSmokeWorld save
seeded from the 26.1 instance and upgraded by the 26.2 client on load):
**PASS with world entry** 2026-07-27 07:30 (`CodexTester ... logged in with
entity id 107`), no mixin errors, no crash reports, no SimpleTranslate
ERROR lines; the only ERROR lines are the offline test account's
Yggdrasil/Realms/profile-keypair network failures. The refmap packaging
warning discovered on 2026-07-28 is addressed below. Optional-mod mixins are
not runtime-exercised (no FTB/Iceberg/Wynntils installed).

## 2026-07-28 runtime follow-up

The shipped 26.2 jar was missing `simple_translate.refmap.json` while its
Mixin config still declared it. Unlike the Fabric baseline, this ModDevGradle
target does not generate a refmap, and 26.2 compiles and runs using the same
Mojang names. The stale declaration was therefore removed rather than adding
an inert file; the clean rebuilt jar and the exact-client rerun below are the
post-fix evidence.

Before that packaging correction, the exact dedicated client entered
`CodexSmokeWorld` in `validation-world-k-20260728-053444.out.log` and the
held visual check `validation-k-hold-20260728-053826.out.log`. The latter logged
`CodexTester ... entity id 120` at 05:38:39, initialized both SimpleTranslate
client entry points, reached world entry, and ended automatically with PASS at
05:44:40. The post-fix clean build (hash above) was then deployed by
`validation-refmap-rerun-20260728-054830.out.log`; it reached the same world
at 05:48:47 (`entity id 87`) and ended with PASS at 05:49:10. Its exact client
log contained zero `simple_translate.refmap`/`Reference map` warnings, no
Mixin application error, and no crash report. The remaining ERROR lines were
the offline test account's Mojang/Realms authentication failures.

For the whole-frame GUI regression surface, the held exact-client check opened
the `CreativeModeInventoryScreen` and dispatched the configured K key directly
to the game window. The persisted GUI-frame state recorded that screen and the
before/after captures
`validation-postmessage-screen.png` / `validation-postmessage-k-screen.png`
show a stable creative-inventory layout with no clipping, coordinate drift, or
crash. This is a visual/state-path check, not an assertion that the normal
localization resources themselves were translated by the feature.
