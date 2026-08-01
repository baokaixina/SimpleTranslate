# SimpleTranslate Forge 1.16.5 Mixin evidence

Exact build target: Forge `1.16.5-36.2.42`, official (Mojmap) mappings via
ForgeGradle 6 (Gradle 8.4, build JVM Adoptium 21), mod bytecode Java 17
(`options.release = 17`). Runtime client: Forge `36.2.42` on **Java 17**
(Adoptium 17.0.13) at `<designated-test-client>\forge\1.16.5forge`
(version `1.16.5-forge-36.2.42`, provisioned 2026-07-27: Forge installer
`--installClient`, vanilla libraries/assets from Mojang piston-meta, 0 missing
objects).

## Vanilla / Forge target evidence

Every vanilla Mixin target in `src/main/java/.../mixin` was verified
descriptor-exact against the exact compile artifact
`forge-1.16.5-36.2.42_mapped_official_1.16.5.jar` with the automated
`javap -p -s` checker `.analysis/forge-1.16.5-remap/verify_mixin_targets.py`:
**93 member checks OK, 0 failures** (2026-07-27 run; the two FTB pseudo
mixins are covered separately below). Call-site-count-sensitive injections
were additionally verified against the **binpatched production client jar**
(`forge-1.16.5-36.2.42-client.jar`) with `javap -c`.

Runtime status: 23 mixins from `simple_translate.mixins.json` applied in the
production client (debug.log "Mixing ..." count), `defaultRequire = 1`, zero
injection errors, world entry verified (sessions 1165E/1165G/1165FINAL below).

## Runtime-driven fixes (2026-07-27)

The first live launches falsified several compile-time assumptions; each fix
was re-derived from exact jars and re-verified in a real client run:

1. **Mixin floor**: Forge 1.16.5-36.2.42 bundles **Mixin 0.8.4**
   (`libraries/org/spongepowered/mixin/0.8.4/`), not 0.8.5. The config's
   `minVersion` was lowered to 0.8.4 (first launch died with
   `MixinInitialisationError: requires mixin subsystem version 0.8.5`).
2. **MixinExtras delivery**: FML 36.x predates JarJar and silently ignores
   `META-INF/jarjar` nested jars — mixin application crashed with
   `ClassMetadataNotFoundException:
   com.llamalad7.mixinextras...Operation`. `mixinextras-common:0.4.1` is now
   shaded directly into the production jar and bootstrapped via
   `SimpleTranslateMixinPlugin#onLoad → MixinExtrasBootstrap.init()`.
3. **TitleOverlayMixin HUD wrap counts**: `IngameGui#render(MatrixStack,F)V`
   contains exactly **2×** `FontRenderer#drawShadow(MatrixStack,ITextComponent,FFI)I`
   (title + subtitle, SRG `func_243246_a`) and **1×**
   `#draw(MatrixStack,ITextComponent,FFI)I` (actionbar, SRG `func_243248_b`)
   — verified with `javap -c` in BOTH the mapped dev jar and the binpatched
   production `IngameGui` (`func_238445_a_`). The donor-copied single
   `@WrapOperation(..., require = 3)` on drawShadow could never match and
   crashed injection (`(2/3) succeeded`). Now two wraps
   (`drawShadow require = 2`, `draw require = 1`) share one `@Unique` body;
   note a `@WrapOperation` handler must not call another handler directly
   (MixinExtras rewiring broke the second wrap until the shared body was
   extracted).
4. **mods.toml logoFile**: 1.16.5 `ModListScreen` resolves `logoFile` as a
   jar-root resource and **crashes the whole client** with
   "Root resources can only be filenames, not paths" when the mod entry is
   selected (crash-2026-07-27_07.54.31 reproduced live). The icon is now
   copied to the jar root as `simple_translate_logo.png`; the 1.18-era
   `displayTest` key was also removed (not a 36.x key).
5. **Font definition**: `assets/simple_translate/font/cjk.json` used the
   `space` provider type, which does not exist before 1.19 —
   `FontResourceManager` rejected the whole definition
   ("Unable to read definition 'simple_translate:cjk'"). Reduced to the
   era-correct `legacy_unicode` provider; warning gone in the final session.

## FTB Library compat (optional, plugin-gated)

Verified with `javap -p -s` against the exact
`.analysis/optional-1.16.5/ftb-library-forge-1605.3.5-build.724.jar`
(newest 1605-series FTB Library Forge build):

| Mixin | Exact target | Evidence |
| --- | --- | --- |
| `compat.FtbScreenWrapperTranslationMixin` (@Pseudo, remap=false) | `dev.ftb.mods.ftblibrary.ui.ScreenWrapper#func_231046_a_(III)Z` (SRG keyPressed), `#func_230430_a_(Lcom/mojang/blaze3d/matrix/MatrixStack;IIF)V` (SRG render) | both descriptors present in the exact jar |
| `compat.FtbTextFieldTranslationMixin` (@Pseudo, remap=false) | `dev.ftb.mods.ftblibrary.ui.TextField#draw(Lcom/mojang/blaze3d/matrix/MatrixStack;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V`; shadow `component:Lnet/minecraft/util/text/ITextComponent;` (public); reflective `setText(ITextComponent)` returns `TextField` | all descriptors present in the exact jar |

Gated by `SimpleTranslateMixinPlugin#shouldApplyMixin` on modid `ftblibrary`.
The dedicated test client installs no optional mods, so this pair is static
bytecode evidence only (project convention for modern targets).

Iceberg/LegendaryTooltips gather compat is intentionally absent on 1.16.5:
the Forge `RenderTooltipEvent$GatherComponents` shape the baseline bridge
depends on does not exist in the 36.x era, and no exact-target adapter was
derived. Documented gap, not an oversight.

## Runtime sessions (2026-07-27, world `新的世界`, player STXTester)

- `runclient-flegacy-1165E-…0741.out.log`: first fully-fixed boot; 23 mixins
  applied, no injection errors; a world was created and entered (07:42:19
  `logged in with entity id 322`) and shut down cleanly.
- `runclient-flegacy-1165G-…0801.out.log`: attributable session — menu →
  world entry (08:02:07), in-world render OK, classic sectioned settings hub
  opened via U (screenshot `evidence-20260727/m05-settings.png`), tab list
  exercised; clean quit; no crash reports.
- `runclient-flegacy-1165FINAL-…0806.out.log`: final shipping jar
  (`simple_translate-1.16.5-forge-2.1.28.jar`, SHA-256
  `D04C425D4EC5F9FF6F5B99C265804C75156A820A180F6C897E3D74BF8C0B8687`,
  clean-built 08:05:38): menu → world entry 08:07:07, **zero**
  `Unable to read definition` warnings, no crash reports.

Java-runtime note: the mod is Java-17 bytecode on a 1.16.5 loader whose
vanilla launcher profile defaults to Java 8. Forge 36.2.42's own version json
ships modern-JVM flags (`--add-exports java.base/sun.security.util=…`,
`--add-opens java.base/java.util.jar=…`, `-XX:+IgnoreUnrecognizedVMOptions`)
and the full client chain (boot → mixin apply → world entry) is
runtime-proven on Java 17. A stock Java-8 launch cannot load this jar
(class-file version 61) — deployment requires a Java-17-configured launcher
profile. Recorded as a deployment constraint in the family report.

## 2026-07-28 checker compatibility follow-up

- The shared client checker now recognizes legacy `1.8` version strings,
  avoids Java-8-incompatible response files, expands legacy
  `minecraftArguments`, and resolves inherited PCL version JARs. These changes
  enabled the separate Forge 1.12.2 Java-8 runtime check without changing this
  target's product JAR.
- Current-source `run-logic-checks.ps1` and
  `run-translation-fixtures.ps1` both passed. A Forge `36.2.42` launch with
  this target's deployed JAR reached the main menu and logged SimpleTranslate
  initialization without a Mixin error or crash. Its fresh UI-only world-entry
  attempt landed in vanilla chat settings and timed out, so it is not counted
  as a new world pass; the descriptor-exact 2026-07-27 world-entry evidence
  above remains the product runtime evidence.
