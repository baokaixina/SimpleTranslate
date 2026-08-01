# SimpleTranslate NeoForge 1.21.5 Mixin evidence (2026-07-26)

Exact build target: Minecraft 1.21.5, NeoForge **21.5.97** (ModDevGradle,
Mojmap runtime), Java 21, mod 2.1.28. Vanilla evidence jar:
`build/moddev/artifacts/neoforge-21.5.97-merged.jar`. Runtime evidence: fresh
client check 2026-07-26 22:37 on
`<designated-test-client>\neoforge\1.21.5\versions\1.21.5-NeoForge` with Quick Play world
entry into `CodexSmokeWorld` (script PASS; chunks saved on exit).

`simple_translate.mixins.json`: 30 client entries, byte-identical client list
to the Fabric 1.21.5 donor, `defaultRequire=1`, plugin-gated compat trio.

## Runtime-proven entries (23)

Same set as 1.21.4 — `debug.log` shows `Mixing <name> from
simple_translate.mixins.json` for all 23 eager classes with zero
injection-error lines under `defaultRequire=1`:

AbstractContainerScreenMixin, AbstractWidgetTranslationMixin,
BookEditScreenMixin, BookViewScreenMixin, BossHealthOverlayMixin,
ChatComponentMixin, ChatScreenMixin, CycleButtonTooltipMixin,
EntityRendererMixin, FontManagerAccessor, FontManagerMixin,
FontPreparedTextBuilderMixin, FontSetAccessor, FontSetMixin,
GuiGraphicsTranslationMixin, HoverTooltipMixin, PlayerTabOverlayMixin,
ScoreboardMixin, ScreenComponentClickMixin, ScreenGuiTranslationMixin,
SignRendererMixin, TextDisplayMixin, TitleOverlayMixin.

## javap-verified lazy vanilla entries (4)

`javap -p -s` against `neoforge-21.5.97-merged.jar`:

| Mixin | Exact target evidence |
| --- | --- |
| `SignTextMixin` | `SignText#getRenderMessages` = `(ZLjava/util/function/Function;)[Lnet/minecraft/util/FormattedCharSequence;` |
| `ClientTextTooltipAccessor` | `ClientTextTooltip.text : Lnet/minecraft/util/FormattedCharSequence;` (private final) |
| `AdvancementToastMixin` | shadow `advancement : Lnet/minecraft/advancements/AdvancementHolder;`; `render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;J)V` |
| `AdvancementWidgetMixin` | shadow `advancementNode : Lnet/minecraft/advancements/AdvancementNode;`; `drawHover(Lnet/minecraft/client/gui/GuiGraphics;IIFII)V` |

## Optional-compat entries (3)

Evidence jars: `<workspace>\.analysis\optional-121x\`.

- **FTB compat pair — descriptor-exact vs the only loadable artifact.**
  Official FTB maven has no 2105.x series (nothing between 2101.x for 1.21.1
  and 2111.x for 1.21.11). `ftb-library-neoforge-2101.1.33.jar` declares
  `minecraft versionRange="[1.21.1,)"` and therefore CAN be installed on a
  1.21.5 client. `javap -p -s` on that jar: `ScreenWrapper.keyPressed(III)Z`,
  `ScreenWrapper.render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V`,
  `TextField.rawText : Lnet/minecraft/network/chat/Component;` (shadowed name
  AND type exact), `TextField.draw(Lnet/minecraft/client/gui/GuiGraphics;
  Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V`, reflective `setText(Component)`
  present; `formattedText : [Lnet/minecraft/network/chat/FormattedText;`
  correctly NOT shadowed. This target's mixin descriptors match that bytecode
  exactly (identical strings to the audited 1.21.4 target).
- **Wynntils compat — BLOCKED/dormant on this target (documented gap).**
  No Wynntils release exists for MC 1.21.5: Modrinth and GitHub releases both
  show the 3.x series is 1.21.4-only (`wynntils-3.4.5-neoforge+MC-1.21.4.jar`
  pins `minecraft versionRange="[1.21.4]"` in its `neoforge.mods.toml`) and
  the 4.x series is 1.21.11-only. FML therefore can never load any Wynntils
  build on a 1.21.5 client, `LoadingModList.getModFileById("wynntils")` stays
  null, and `SimpleTranslateMixinPlugin` permanently skips
  `compat.WynntilsOverlayManagerMixin`. The class carries the donor's
  1.21.11-era single-arg descriptor
  `renderOverlays(Lcom/wynntils/mc/event/RenderEvent;)V` (Fabric 1.21.5 donor
  parity); it is unverifiable against any 1.21.5-loadable artifact and is
  retained as dormant donor-parity code, not as exact evidence.

## Non-mixin compat: Iceberg

No 1.21.5-specific Iceberg exists; the newest loadable artifact is
`Iceberg-1.21.4-neoforge-1.2.13.jar`, which declares
`minecraft versionRange="[1.21.3,)"` (open-ended) and thus CAN load on 1.21.5.
`javap -p -s` on that jar confirms every reflected member used by
`IcebergTooltipGatherCompat` (same strings as the 1.21.4 audit):
`events.client.RenderTooltipEvents.GATHER : Levents/Event;`,
`Gather#onGather(ItemStack,int,int,List,int,int)`,
`GatherResult(InteractionResult,int,List)`, `Event#register(Object)`.
The bridge is reflection-only with graceful failure, gated on
`ModList.isLoaded("iceberg")`.

## NeoForge API surface

Runtime-proven on 21.5.97 by the fresh client run (mod init, key mappings,
payload registration, client tick/connection events, world join, zero
SimpleTranslate errors; only unrelated Realms-offline noise in the log).

## Metadata floors

`neoforge [21.5.74,21.6)` — 21.5.74 confirmed on maven.neoforged.net as the
first non-beta 21.5.x; `minecraft [1.21.5,1.21.6)`; `loader [1,)`. Floors, not
build pins (build uses 21.5.97).

## Notes

- Packaged jar is a 1:1 image of production source (165/165 top-level
  classes), zero OCR/retired-symbol matches; full Wynn feature surface present
  (>=1.21.4 gate), 485/485 matching lang keys.
- The Fabric donor's `src/test` JUnit classes are not yet carried by this
  target; normal builds run the Gradle `test` task as `NO_SOURCE` (not `-x`).
- Migration-strip exception: `ModConfig` `root.remove("general.wynncraftProfileMode")`
  (documented, asserted by logic checks).

## 2026-07-28 packaging follow-up

This Mojang-named ModDevGradle target does not generate
`simple_translate.refmap.json`; its stale Mixin-config declaration was
removed. The clean rebuilt JAR has SHA256
`6E94E0AE4DE485DDA21AF3CF941EAD607578AF6FFB9D71052664926FFC10216A`.
`validation-refmap-rerun-20260728-063350.out.log` deployed it to exact
NeoForge `21.5.97` and entered `CodexSmokeWorld` at 06:33:29 (`entity id
11`), with zero refmap warnings, zero Mixin application failures, and no
crash report.
