# Forge 1.18.2 Mixin evidence

Validated on 2026-07-28 against the exact production client used by the
designated Forge test instance:

- Minecraft `1.18.2`, Forge `40.2.21`, ModLauncher `9.1.3`, Mixin `0.8.5`,
  Java `17.0.13`.
- Production SRG client jar:
  `client-1.18.2-20220404.173914-srg.jar`, SHA-256
  `C6CB2530269663ED0D4B9B53815ABC521E26B4CC895AEF58257763E1AE87A12F`.
- Packaged non-sources mod jar:
  `simple_translate-1.18.2-forge-2.1.28.jar`, SHA-256
  `98B6024EC8C91C5FEFF30ED081E891439AEFE475232824C220B75AF684661712`.

## Exact target-bytecode audit

`javap -p -s -c` against the above SRG client jar established the following
runtime selectors before editing the Mixins:

| Mixin | Runtime owner and selector | Verified invocation count |
| --- | --- | --- |
| `ScoreboardMixin` | `Gui.m_93036_(PoseStack,Objective)V`: `Objective.m_83322_()Component`, `Font.m_92852_(FormattedText)I`, and `Font.m_92889_(PoseStack,Component,FFI)I` | 1, 2, 1 |
| `TitleOverlayMixin` | `Gui.m_93030_(PoseStack,F)V`: `BossHealthOverlay.m_93704_(PoseStack)V`, `ChatComponent.m_93780_(PoseStack,I)V`, `Gui.m_93036_(PoseStack,Objective)V`, `PlayerTabOverlay.m_94544_(PoseStack,I,Scoreboard,Objective)V` | 1 each |
| `TitleOverlayMixin` | `Gui.m_93030_`: `Font.m_92852_(FormattedText)I`, `Font.m_92763_(PoseStack,Component,FFI)I`, `Font.m_92889_(PoseStack,Component,FFI)I` | 3, 2, 1 |
| `BossHealthOverlayMixin` | `BossHealthOverlay.m_93704_(PoseStack)V`: `Font.m_92763_(PoseStack,Component,FFI)I` | 1 |

Forge 40.2.21 runs these SRG names. MixinExtras 0.4.1 did not remap nested
`@At` invocation targets; additionally, its sole-injector
`BossHealthOverlayMixin` did not receive a refmap method selector. The
affected `@WrapOperation` sites therefore use the verified SRG target strings
with `remap = false`; the normal `@Inject` method selectors remain refmap
mapped.

The compiled handlers were inspected with `javap -p -s` after the clean build:

- Scoreboard title: `(Objective, Operation)Component`; width:
  `(Font, FormattedText, Operation)I`; text draw:
  `(Font, PoseStack, Component, float, float, int, Operation)I`.
- Title overlay boss/chat/sidebar/tab handlers preserve their exact receiver
  and target argument types; width and text draw handlers use the matching
  `Font`, `FormattedText`/`Component`, `PoseStack`, and primitive parameters.
- Boss-bar handler is
  `(Font, PoseStack, Component, float, float, int, Operation)I`.

## MixinExtras packaging audit

The 0.4.1 common runtime is shaded and initialized by
`SimpleTranslateMixinPlugin`. The Forge adapter is deliberately not shaded:
the packaged jar contains zero
`com/llamalad7/mixinextras/platform/forge/` entries and exactly one
`META-INF/mods.toml` (Simple Translate). This prevents Forge from discovering
an undeclared second `mixinextras` mod.

## Validation

- `gradlew clean build --no-daemon --console=plain`: passed (12 tasks).
- `scripts/run-logic-checks.ps1`: passed.
- `scripts/run-translation-fixtures.ps1`: passed.
- The non-sources jar was deployed to the designated exact Forge 1.18.2 test
  client. It reached the main menu, loaded `Simple Translate Forge mod
  initialized`, created the isolated `CodexSmokeWorld`, and entered it. The
  server-side integrated-world log records `CodexTester` logging in with entity
  id 195. The client script reported `PASS: dedicated local test client
  reached world entry` and found no new crash report.

The 1.18.2 UI entry helper was also corrected to request per-monitor-v2 DPI
coordinates before translating client-area clicks. The prior DPI-virtualized
coordinates could hit the Multiplayer button; the successful world-entry run
uses the corrected coordinate path.
