# SimpleTranslate NeoForge 1.21.4 Mixin evidence (2026-07-26)

Exact build target: Minecraft 1.21.4, NeoForge **21.4.157** (ModDevGradle 2.0.141,
Mojmap runtime), Java 21, mod 2.1.28. Vanilla evidence jar:
`build/moddev/artifacts/neoforge-21.4.157-merged.jar` (the exact NeoForge-patched,
Mojmap-named compile artifact). Runtime evidence: fresh client check 2026-07-26
22:29 on `<designated-test-client>\neoforge\1.21.4\versions\1.21.4-NeoForge` with Quick Play
world entry (`CodexTester ... logged in with entity id 1`, world `CodexSmokeWorld`).

`simple_translate.mixins.json` carries 30 client entries (byte-identical client
list to the Fabric 1.21.4 donor), `injectors.defaultRequire=1`, plugin
`SimpleTranslateMixinPlugin` gating the three optional-compat entries.

## Runtime-proven entries (23)

`debug.log` shows `Mixing <name> from simple_translate.mixins.json` with zero
`InjectionError` / `InvalidInjectionException` / `Critical injection failure`
lines and `defaultRequire=1` in force — every injection in these classes found
its exact target or the run would have failed:

AbstractContainerScreenMixin, AbstractWidgetTranslationMixin,
BookEditScreenMixin, BookViewScreenMixin, BossHealthOverlayMixin,
ChatComponentMixin, ChatScreenMixin, CycleButtonTooltipMixin,
EntityRendererMixin, FontManagerAccessor, FontManagerMixin,
FontPreparedTextBuilderMixin, FontSetAccessor, FontSetMixin,
GuiGraphicsTranslationMixin, HoverTooltipMixin, PlayerTabOverlayMixin,
ScoreboardMixin, ScreenComponentClickMixin, ScreenGuiTranslationMixin,
SignRendererMixin, TextDisplayMixin, TitleOverlayMixin.

## javap-verified lazy vanilla entries (4)

Verified with `javap -p -s` against `neoforge-21.4.157-merged.jar`:

| Mixin | Exact target evidence |
| --- | --- |
| `SignTextMixin` | `net/minecraft/world/level/block/entity/SignText#getRenderMessages` = `(ZLjava/util/function/Function;)[Lnet/minecraft/util/FormattedCharSequence;` |
| `ClientTextTooltipAccessor` | `ClientTextTooltip.text : Lnet/minecraft/util/FormattedCharSequence;` (private final) |
| `AdvancementToastMixin` | shadow `advancement : Lnet/minecraft/advancements/AdvancementHolder;` (private final); `render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;J)V` |
| `AdvancementWidgetMixin` | shadow `advancementNode : Lnet/minecraft/advancements/AdvancementNode;` (private final); `drawHover(Lnet/minecraft/client/gui/GuiGraphics;IIFII)V` |

## Optional-compat entries (3) — exact per-version artifacts

Evidence jars kept in `<workspace>\.analysis\optional-121x\`.

| Entry | Artifact | Evidence (`javap -p -s`) |
| --- | --- | --- |
| `compat.WynntilsOverlayManagerMixin` | `wynntils-3.4.5-neoforge+MC-1.21.4.jar` — **exact**: v3.4.5 (2026-02-14) is the newest Wynntils release for MC 1.21.4 (Modrinth + GitHub releases both checked; 3.x series is 1.21.4-only, 4.x is 1.21.11-only) | `com.wynntils.core.consumers.overlays.OverlayManager` has private `renderOverlays(Lcom/wynntils/mc/event/RenderEvent;Lcom/wynntils/core/consumers/overlays/RenderState;)V`; public `onRenderPre(RenderEvent$Pre)` / `onRenderPost(RenderEvent$Post)` both delegate to it — HEAD/RETURN bracket matches the mixin exactly |
| `compat.FtbScreenWrapperTranslationMixin` | `ftb-library-neoforge-2101.1.33.jar` — newest FTB Library NeoForge build that can load on 1.21.4: official maven has no 2104–2108 series, and 2101.1.33 declares `minecraft versionRange="[1.21.1,)"` (open-ended), so it installs and loads on 1.21.4 clients | `dev.ftb.mods.ftblibrary.ui.ScreenWrapper` : `keyPressed(III)Z`, `render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V` — both public, descriptor-exact, `remap=false` correct (FTB ships Mojmap names; NeoForge 21.4 runtime is Mojmap) |
| `compat.FtbTextFieldTranslationMixin` | same jar | `dev.ftb.mods.ftblibrary.ui.TextField` : `@Shadow rawText : Lnet/minecraft/network/chat/Component;` (name AND type exact); `draw(Lnet/minecraft/client/gui/GuiGraphics;Ldev/ftb/mods/ftblibrary/ui/Theme;IIII)V`; reflective `setText(Component)` exists returning `TextField`; `formattedText : [Lnet/minecraft/network/chat/FormattedText;` correctly NOT shadowed (historic crash trap) |

All three are `@Pseudo` + `remap=false` and plugin-gated on `ftblibrary` /
`wynntils` presence in `LoadingModList`; they never load their target classes
when the mods are absent (confirmed: not applied in the test-client run).

## Non-mixin compat: Iceberg (exact)

`Iceberg-1.21.4-neoforge-1.2.13.jar` (Modrinth, 2025-02-12 — the newest and
only 1.21.4 NeoForge Iceberg series; no 1.21.5–1.21.8 builds exist).
`IcebergTooltipGatherCompat` is reflection-only, gated on
`ModList.isLoaded("iceberg")`. `javap -p -s` confirms:
`com.anthonyhilyard.iceberg.events.client.RenderTooltipEvents.GATHER :
Lcom/anthonyhilyard/iceberg/events/Event;`,
`Gather#onGather(Lnet/minecraft/world/item/ItemStack;IILjava/util/List;II)Lcom/...$GatherResult;`,
public record ctor `GatherResult(Lnet/minecraft/world/InteractionResult;ILjava/util/List;)V`,
and public `Event#register(Ljava/lang/Object;)V`. This closes the
"neighboring evidence" gap flagged in `docs/API_MIXIN_AUDIT_2026-07-26.md` for
this target — the 1.2.13 claim in the source javadoc is now backed by the jar
in `.analysis`.

## NeoForge API surface

Runtime-proven on 21.4.157 by the same run: mod construction, config
registration, key-mapping registration, client tick/connection events, payload
registration (`SharedCachePayload` via the 21.x `PacketDistributor`
`sendToServer`/`sendToPlayer` API) all executed during a full client boot +
dedicated smoke-server world join with zero SimpleTranslate errors.

## Metadata floors

`neoforge [21.4.121,21.5)` — 21.4.121 confirmed on maven.neoforged.net metadata
as the first non-beta 21.4.x (series is 21.4.0-beta…, first stable 21.4.121);
`minecraft [1.21.4,1.21.5)`; `loader [1,)`. Floors, not build pins (build uses
21.4.157).

## Notes

- Runtime status of the three optional-compat mixins and the Iceberg bridge is
  unverified (test client does not install FTB/Wynntils/Iceberg); evidence is
  descriptor-exact bytecode only.
- Migration-strip exception: `ModConfig` keeps
  `root.remove("general.wynncraftProfileMode")` (documented, asserted by
  `run-logic-checks.ps1`); the config key itself stays removed.

## 2026-07-28 build and packaging follow-up

The baseline regression fixtures are now wired through ModDevGradle's
`unitTest` environment (`testedMod = simple_translate`), rather than a raw JVM
that lacks Minecraft's text/registry bootstrap. All 40 tests passed from the
temporary ASCII validation junction. The ordinary Windows build guards only
test execution when the project path is non-ASCII (the same documented
constraint as the Fabric baseline); test sources still compile, and the
loader-backed suite remains runnable from an ASCII checkout/junction.

This Mojang-named target does not generate `simple_translate.refmap.json`, so
the stale Mixin-config declaration was removed. The clean rebuilt JAR has
SHA256 `EFAB0D940B10EB11BC59B049284513FF372ED905F8B0DAB7CB44C8E55A66CBAB`.
`validation-refmap-rerun-20260728-063250.out.log` deployed it to exact
NeoForge `21.4.157` and reached `CodexSmokeWorld` at 06:31:39 (`entity id
21`), with zero refmap warnings, zero Mixin application failures, and no
crash report.
