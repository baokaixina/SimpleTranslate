# SimpleTranslate NeoForge 26.1.1 Mixin evidence (2026-07-27)

Exact build target: Minecraft 26.1.1, NeoForge `26.1.1.15-beta`, ModDevGradle
2.0.141, Mojang official names at runtime (26.x has no intermediary; every
descriptor below is Mojang-named; `remap` is irrelevant for vanilla entries
because compile classpath == runtime names).

Primary evidence artifact:
`build/moddev/artifacts/minecraft-patched-26.1.1.15-beta-merged.jar`
(the exact NeoForge-patched jar this target compiles against). All 30 enabled
entries in `simple_translate.mixins.json` were machine-checked against it with
`.analysis/optional-26/verify_mixins_26.py` (descriptor-exact: @Mixin targets,
injection `method=`/`target=` strings, @Shadow field name+type,
@Accessor/@Invoker members). Result: 29/30 OK plus the single known
CycleButton false negative (below). Deployable jar:
`build/libs/simple_translate-26.1.1-neoforge-2.1.28.jar`
(SHA256 C38E5E30FAF8F135E4780A657B57081AB9A57FC55EAEB94149C767EB8712DD8D).

Third-party artifacts (exact, in `<workspace>\.analysis\optional-26\`):

- FTB Library `ftb-library-neoforge-26.1.1.1.jar` and
  `ftb-library-neoforge-26.1.2.6.jar` (official maven.ftb.dev). Both declare
  `minecraft [26.1,27)` / `neoforge [26.1.0.0-beta,27)` in their own
  `neoforge.mods.toml`, so both are loadable on MC 26.1.1. The FTB pseudo
  mixins were verifier-checked descriptor-exact against **both** jars.
- Iceberg `Iceberg-26.1.2-neoforge-1.4.1.1.jar` — Modrinth release whose
  `game_versions` are exactly `26.1, 26.1.1, 26.1.2` (confirmed from the
  on-disk Modrinth dump `iceberg_versions.json`, fetched 2026-07-26): the
  exact-series artifact for this target, and 1.4.1.1 is the newest 26.1.x
  build.
- Wynntils: **no 26.x release exists** (`wynntils_versions.json` Modrinth dump,
  731 versions, zero with 26.x game_versions; newest is v4.2.3 for MC 1.21.11,
  published 2026-07-14). Nearest artifact
  `wynntils-4.2.3-neoforge+MC-1.21.11.jar` used for descriptor evidence only.

## Findings

1. **`SignRendererMixin` — 26.x descriptor confirmed for 26.1.1.** Direct
   `javap -p -s` on the exact 26.1.1 patched jar shows
   `private void submitSignText(SignRenderState,PoseStack,SubmitNodeCollector,SignText)` —
   identical to 26.1. The source (fixed from the stale 1.21.x-era
   `...SubmitNodeCollector,Z)V` boolean form on 2026-07-26) matches, and the
   packaged jar's annotation constant carries the exact SignText-form
   descriptor (verified inside the built class file). Front/back is derived by
   identity against `renderState.frontText`.
2. **`CycleButtonTooltipMixin` — verifier false negative, descriptor exact.**
   The script reports the INVOKE target
   `L...CycleButton;setTooltip(L...Tooltip;)V` as unmatched because
   `setTooltip` is declared in `AbstractWidget` (inherited). `javap -c` of the
   exact 26.1.1 `CycleButton` shows
   `invokevirtual // Method setTooltip:(L...Tooltip;)V` with the owner
   constant being `CycleButton` itself, and `javap -p -s` on `AbstractWidget`
   confirms `(Lnet/minecraft/client/gui/components/Tooltip;)V`. Exact.

## Vanilla surface highlights (26.1.1)

Identical API shape to 26.1 (the 26.1 and 26.1.1 source trees are functionally
identical; hash diff shows only line endings and evidence comments):
extractor-based GUI capture (`GuiGraphicsExtractor` owner present in the exact
patched jar), HUD/title owner is still `net.minecraft.client.gui.Gui` (the
Gui→Hud split arrives in 26.2), font pipeline entries all resolved, and the
`META-INF/accesstransformer.cfg` members exist with matching descriptors —
all machine-verified against the exact 26.1.1 patched jar this session.

## Optional-compat entries (plugin-gated pseudo mixins, `remap = false`)

| Entry | Exact artifact | Status |
| --- | --- | --- |
| `compat.FtbScreenWrapperTranslationMixin` | `ftb-library-neoforge-26.1.1.1.jar` + `26.1.2.6.jar` | Descriptor-exact against both loadable jars. Dormant unless FTB Library installed; not runtime-exercised (test client has no FTB). |
| `compat.FtbTextFieldTranslationMixin` | `ftb-library-neoforge-26.1.1.1.jar` + `26.1.2.6.jar` | Descriptor-exact (`TextField.rawText:Component` shadow; `formattedText` deliberately NOT shadowed). Same dormancy note. |
| `compat.WynntilsOverlayManagerMixin` | none for 26.x | **Permanently dormant on 26.1.1: no Wynntils 26.x build exists** (documented Modrinth dump). Descriptors verified against nearest artifact `wynntils-4.2.3-neoforge+MC-1.21.11.jar` only. Kept for donor parity; the mixin plugin skips it when the `wynntils` mod is absent, which on 26.1.1 is always. |

Iceberg integration is **not** a mixin: `compat/IcebergTooltipGatherCompat`
is reflective. Every reflected member was `javap -p -s`-verified this session
in the exact `Iceberg-26.1.2-neoforge-1.4.1.1.jar`:
`RenderTooltipEvents.GATHER : Event` (public static final),
`Event.register(Object)V` (public), `Gather.onGather(ItemStack,II,List,II)`
returning `GatherResult`, and public ctor
`GatherResult(InteractionResult,int,List)`.

## Metadata floors

`neoforge [26.1.1.0-beta,26.1.2)` — floor changed 2026-07-27 from
`[26.1.1,...)`: maven.neoforged.net release history shows the 26.1.1 series is
`26.1.1.0-beta` .. `26.1.1.15-beta`, and the old floor string excluded
`26.1.1.0-beta` under Maven version ordering. The mod consumes no NeoForge API
introduced mid-series (key mapping event, payload registrar, FMLPaths, config
events — all present from the first 26.1.1 build). `minecraft
[26.1.1,26.1.2)` unchanged. Floors are documented minimums, not build pins.

## Runtime status

Dedicated test client `<designated-test-client>\neoforge\26.1.1`
(`versions\neoforge-26.1.1.15-beta`, provisioned 2026-07-27 from the official
`neoforge-26.1.1.15-beta-installer.jar --install-client`; vanilla 26.1.1 jar
SHA1 `377031a9e733ba8ab4d355959a8f6fb8eb707556` matched against piston-meta;
asset index 30 + objects seeded from the 26.1 instance; CodexSmokeWorld save
seeded from the 26.1 instance): **PASS with world entry** 2026-07-27 06:47
(`CodexTester ... logged in with entity id 107`), no mixin errors, no crash
reports, no SimpleTranslate ERROR lines; the only ERROR lines are the offline
test account's Yggdrasil/Realms network failures. Optional-mod mixins are not
runtime-exercised (the test client installs none of FTB/Iceberg/Wynntils).

## 2026-07-28 packaging follow-up

The Mojang-named ModDevGradle runtime does not generate a refmap. The stale
`simple_translate.refmap.json` declaration was removed, then a clean build
was deployed by `validation-refmap-rerun-20260728-055700.out.log`. The exact
NeoForge `26.1.1.15-beta` client reached `CodexSmokeWorld` at 05:57:07
(`entity id 115`) with zero refmap warnings, zero Mixin application failures,
and no crash report.
