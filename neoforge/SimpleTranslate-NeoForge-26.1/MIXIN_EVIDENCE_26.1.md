# SimpleTranslate NeoForge 26.1 Mixin evidence (2026-07-26, re-verified 2026-07-27)

2026-07-27 re-verification (fresh session; previous session terminated
mid-family): `verify_mixins_26.py` re-run against the same exact patched jar —
29/30 OK plus the single documented CycleButton false negative (see finding 2;
`javap -c` re-confirmed the `invokevirtual setTooltip:(Tooltip)V` constant with
owner `CycleButton`). `submitSignText(...SignText)V` re-confirmed by direct
`javap -p -s`. Full validation chain re-run: logic checks PASSED, component
JSON fixtures PASSED (pwsh 7.6.4), `clean build` OK, deployable
`build/libs/simple_translate-26.1-neoforge-2.1.28.jar`
(SHA256 762DC7DDAF2340423A6F46F214D2358036D266C7BFBAE1B7AE9AC93578873567),
client check PASS with world entry 2026-07-27 05:32 (entity id 145, no mixin
errors, no crash reports).

Exact build target: Minecraft 26.1, NeoForge `26.1.0.19-beta`, ModDevGradle
2.0.141, Mojang official names at runtime (26.x has no intermediary; every
descriptor string below is Mojang-named and `remap` is irrelevant for the
vanilla entries because compile classpath == runtime names).

Primary evidence artifact:
`build/moddev/artifacts/minecraft-patched-26.1.0.19-beta-merged.jar`
(the exact NeoForge-patched jar this target compiles against). All 30 enabled
entries in `simple_translate.mixins.json` were machine-checked against it with
`.analysis/optional-26/verify_mixins_26.py` (descriptor-exact: @Mixin targets,
injection `method=`/`target=` strings, @Shadow field name+type, @Accessor /
@Invoker members). Result: 30/30 verified; findings below.

Third-party artifacts (exact, in `<workspace>\.analysis\optional-26\`):

- FTB Library `ftb-library-neoforge-26.1.1.1.jar` (official maven.ftb.dev;
  earliest 26.x NeoForge build; declares `minecraft [26.1,27)` /
  `neoforge [26.1.0.0-beta,27)` in its own `neoforge.mods.toml`, so it is the
  loadable artifact for MC 26.1). Note (2026-07-27): `ftb-library-neoforge-
  26.1.2.6.jar` declares the same `minecraft [26.1,27)` window, so it is also
  loadable on MC 26.1; both jars carry the same `ScreenWrapper`/`TextField`
  API shape (verified for 26.1.2.6 during the 26.1.2 target audit).
- Iceberg `Iceberg-26.1.2-neoforge-1.4.1.1.jar` (Modrinth release whose
  `game_versions` are exactly `26.1, 26.1.1, 26.1.2` — exact-series artifact
  for this target)
- Wynntils: **no 26.x release exists** (Modrinth full version list checked
  2026-07-26; newest is v4.2.3 for MC 1.21.11). Nearest artifact
  `wynntils-4.2.3-neoforge+MC-1.21.11.jar` used for descriptor evidence only.

## Findings and fixes

1. **`SignRendererMixin` — real 26.x descriptor break, FIXED (this session).**
   The 1.21.x-era target
   `submitSignText(SignRenderState,PoseStack,SubmitNodeCollector,Z)V` does not
   exist in 26.1. Exact 26.1 bytecode has
   `private void submitSignText(SignRenderState,PoseStack,SubmitNodeCollector,SignText)`
   — the `boolean front` parameter was replaced by the `SignText` instance
   (the caller passes `state.frontText` / `state.backText` directly). Both
   injections were re-derived: front/back is now computed by identity against
   `renderState.frontText`. Because the old code used `require = 0`, the stale
   descriptor was a **silent no-op** (sign translation dead), not a crash.
   The same-version Fabric donor `fabric/SimpleTranslate-Fabric-26.1` still
   carries the stale boolean descriptor (donor-side fix is out of this
   target's scope; reported).
2. **`CycleButtonTooltipMixin` — verifier false negative, descriptor exact.**
   The script reports the INVOKE target
   `Lnet/.../CycleButton;setTooltip(Lnet/.../Tooltip;)V` as unmatched because
   `setTooltip` is declared in `AbstractWidget` (inherited). `javap -c` of the
   exact 26.1 `CycleButton.updateTooltip()` shows
   `invokevirtual ... // Method setTooltip:(Lnet/minecraft/client/gui/components/Tooltip;)V`
   with the owner constant being `CycleButton` itself — the mixin's INVOKE
   owner/descriptor is exact.

## Vanilla surface highlights (26.x era)

- GUI capture is extractor-based: `GuiGraphicsTranslationMixin` and
  `HoverTooltipMixin` target `net.minecraft.client.gui.GuiGraphicsExtractor`
  (present in the exact patched jar; owner verified).
- HUD/title owner on 26.1 is still `net.minecraft.client.gui.Gui`
  (`TitleOverlayMixin`, `ScoreboardMixin`) — unlike 26.2 where the owner is
  `Hud`. Verified against the exact patched jar.
- Font pipeline entries (`FontManagerMixin/Accessor`, `FontSetMixin/Accessor`,
  `FontPreparedTextBuilderMixin` on `Font$PreparedTextBuilder`) all resolved
  against the exact jar. `META-INF/accesstransformer.cfg` members
  (`FontSet$SelectedGlyphs`, `missingSelectedGlyphs`,
  `getGlyph(I)LFontSet$SelectedGlyphs;`) exist with matching descriptors.
- `SignTextMixin`, `TextDisplayMixin` (`Display$TextDisplay`),
  `EntityRendererMixin`, advancement pair, chat pair, book pair, tab/boss
  overlays: all owners and descriptor strings matched the exact patched jar.

## Optional-compat entries (plugin-gated pseudo mixins, `remap = false`)

| Entry | Exact artifact | Status |
| --- | --- | --- |
| `compat.FtbScreenWrapperTranslationMixin` | `ftb-library-neoforge-26.1.1.1.jar` | Descriptor-exact (`ScreenWrapper.keyPressed(III)Z`, `render(GuiGraphicsExtractor…)`-era method set re-checked by verifier). Dormant unless FTB Library installed; not runtime-exercised (test client has no FTB). |
| `compat.FtbTextFieldTranslationMixin` | `ftb-library-neoforge-26.1.1.1.jar` | Descriptor-exact (`TextField.rawText:Component` shadow; `formattedText` deliberately NOT shadowed). Same dormancy note. |
| `compat.WynntilsOverlayManagerMixin` | none for 26.x | **Permanently dormant on 26.1: no Wynntils 26.x build exists.** Descriptors verified against nearest artifact `wynntils-4.2.3-neoforge+MC-1.21.11.jar` (OverlayManager owner/members matched). Kept for donor parity — the Fabric 26.1 donor ships the identical gated entry. Mixin plugin skips it when the `wynntils` mod is absent, which on 26.1 is always. |

Iceberg integration is **not** a mixin: `compat/IcebergTooltipGatherCompat`
is reflective. Every reflected member was `javap -p -s`-verified in the exact
`Iceberg-26.1.2-neoforge-1.4.1.1.jar`:
`RenderTooltipEvents.GATHER : Event` (public static final),
`Event.register(Object)V` (public), `Gather.onGather(ItemStack,II,List,II)`
returning `GatherResult`, and public ctor
`GatherResult(InteractionResult,int,List)`. The 26.x jar keeps the same API
form as the 1.21.x series, so the shipped code is correct against the exact
artifact (previous audit's "1.21.1-era form, not exact" gap is now closed
with exact-jar evidence).

## Runtime status

Dedicated test client `<designated-test-client>\neoforge\26.1`
(`versions\neoforge-26.1.0.19-beta`, provisioned 2026-07-26 from the official
`neoforge-26.1.0.19-beta-installer.jar --install-client`, vanilla 26.1
jar SHA1-matched against piston-meta): PASS with world entry
(`CodexTester … logged in with entity id 145`), no mixin errors, no crash
reports, no SimpleTranslate ERROR lines. Optional-mod mixins are not runtime-exercised (the
test client installs none of FTB/Iceberg/Wynntils).

## 2026-07-28 packaging follow-up

ModDevGradle does not generate `simple_translate.refmap.json` for this
Mojang-named runtime, so the stale config declaration was removed instead of
shipping an inert placeholder. Clean build completed and
`validation-refmap-rerun-2-20260728-055500.out.log` reached
`CodexSmokeWorld` at 05:55:58 (`entity id 107`) with zero refmap warnings,
zero Mixin application failures, and no crash report. An immediately prior
attempt initialized SimpleTranslate but hit a native `PalmInputTSF.dll` IME
access violation before the main menu; the unchanged-JAR retry passed.
