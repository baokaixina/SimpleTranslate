# SimpleTranslate Porting API Matrix

Baseline: `E:\mc\简单翻译\fabric\SimpleTranslate-Fabric-1.20.1`

This matrix is the required Agent 0 reference for syncing the Fabric 1.20.1 direct translation baseline to other maintained versions. Implementation agents must read the target version section before editing a target directory. If implementation finds an API mismatch not listed here, update this file first, then continue.

## Shared Porting Rules

- Preserve target Minecraft, loader, Java, Gradle, metadata, and dependency versions.
- Keep the 1.20.1 behavior as the feature baseline: direct formatted pipeline, direct surface facade, multi-lane request queue, categorized cache, runtime stale-response guards, language/style prompt cache isolation, tooltip/chat/hover/sign/HUD/scoreboard/bossbar/book/advancement/entity/text display entry points.
- Remove automatic map style summary code everywhere. Do not keep `MapStyleScreen`, `StyleProfileCache`, `STYLE_ENABLED`, `STYLE_AUTO_UPDATE`, `summarizeCurrentWorldStyle`, `maybeAutoUpdateStyleProfile`, `summarizeStyle`, or `screen.simple_translate.map_style*`.
- Keep manual style prompt support through `HUD_STYLE_PROMPT` and include it in cache isolation.
- Treat all helper APIs called from mixins as mixin-related. Validate target method signatures and prefer stable non-lambda hooks.
- Existing `require = 0` compatibility injections should be preserved for optional or version-fragile targets.
- Do not let formatted surfaces call raw or marker translation paths. `translateRaw(...)` is for API tests or plain text tools only.

## Fabric Targets

### Fabric 1.19.2

- API profile: `PoseStack` rendering, older `Button` APIs, `mouseScrolled(double,double,double)`, `getScrollbarPosition()`, `LiteralContents`.
- Tooltip / hover / item tooltip:
  - Hover target is `Screen#renderComponentHoverEffect(PoseStack, Style, int, int)`.
  - There is no `GuiGraphics` tooltip rewrite chain.
  - Do not copy 1.20.1 `GuiGraphics` tooltip calls directly.
- Chat:
  - Old `ClickEvent(Action, value)` and `HoverEvent(Action, value)` APIs.
  - `getClickedComponentStyleAt` and `handleChatQueueClicked` are available.
- Sign:
  - No `SignRendererMixin` path in the old project.
  - Existing sign hook is `SignBlockEntity#getRenderMessages`, not `SignText`.
- Entity / text display:
  - Entity names use `EntityRenderer#renderNameTag` variable modification.
  - No `TextDisplayMixin`.
- Advancement:
  - No `AdvancementTabMixin` or `AdvancementsScreenMixin` in the old project.
  - Toast/widget targets use older `Advancement`, `Font#drawShadow`, and `DisplayInfo#getTitle`.
- Screen / input / keybinding:
  - Keep old screen signatures and Fabric keybinding registration.
- Main risks:
  - Cannot directly sync `PlainTextContents`, `GuiGraphics`, `SignText`, or `TextDisplay` code.
  - Direct pipeline pure logic can be synced, but all Minecraft adapter and mixin call sites need 1.19.2-specific wrappers.

### Fabric 1.20.4

- API profile: Java 17, `PlainTextContents`, `Screen.renderBackground(GuiGraphics, int, int, float)`, `mouseScrolled(mouseX, mouseY, deltaX, deltaY)`.
- Tooltip / item tooltip:
  - `ItemStack#getTooltipLines(Player, TooltipFlag)`.
  - Hover path still uses `GuiGraphics#renderTooltip` / `renderComponentTooltip`.
- Chat:
  - Old click/hover event API.
  - `ChatComponentMixin#handleChatQueueClicked` remains available.
- Sign:
  - Targets `{SignRenderer, HangingSignRenderer}` and injects `renderSignText(BlockPos, SignText, PoseStack, MultiBufferSource, int, int, int, boolean)`.
  - Keep `require = 0`.
- Advancement:
  - Uses `AdvancementHolder` / `AdvancementNode`.
  - `GuiGraphics.drawString` returns `int`.
- Scoreboard / bossbar:
  - Scoreboard lambda redirects are fragile. Prefer non-lambda interception, or keep `require = 0` with documented risk.
- Main risks:
  - Target has partial older sign result structures. Sync component-preserving `TranslationResult` and sign identity overloads together with direct sign logic.

### Fabric 1.20.6

- API profile: Java 21.
- Tooltip:
  - `ItemStack#getTooltipLines(Item.TooltipContext, Player, TooltipFlag)`.
  - Hover still uses `renderTooltip`.
- Chat:
  - Old click/hover event API.
- Scoreboard:
  - Sidebar path is closer to HEAD cancellable self-render and uses `PlayerScoreEntry`.
- Advancement:
  - Uses `AdvancementHolder` / `AdvancementNode`.
  - `GuiGraphics.drawString` returns `int`.
- Book:
  - Old edit-book hooks still exist here, unlike 1.21.8+.
- Main risks:
  - Adapt item tooltip signature and preserve Java 21 toolchain.

### Fabric 1.21.1

- API profile: Java 21. Some `drawString` redirects use `void` signatures.
- Tooltip / chat:
  - `ItemStack#getTooltipLines(Item.TooltipContext, Player, TooltipFlag)`.
  - Hover still uses `renderTooltip`.
  - Chat still uses old click/hover event API.
- Scoreboard:
  - `PlayerScoreEntryMixin` path exists.
- Advancement:
  - `AdvancementsScreenMixin` exists.
  - Tooltips use `setTooltipForNextFrame` in advancement screen.
  - `AdvancementHolder` / `AdvancementNode`.
- Sign / entity:
  - Sign still uses `renderSignText`.
  - Entity name path still appears as `renderNameTag` variable modification in the project.
- Main risks:
  - Do not copy the 1.21.1 advancement screen mixin back into 1.20.x.

### Fabric 1.21.4

- API profile: `AbstractSelectionList#scrollBarX()` for list scrollbar placement.
- Tooltip / chat:
  - Hover still uses `renderTooltip`.
  - Chat still uses old click/hover event API.
- Entity:
  - Project still shows `renderNameTag` modification. Verify MC source; if render states are present, translate in `extractRenderState`.
- Sign:
  - Project still targets `{SignRenderer, HangingSignRenderer}#renderSignText`.
  - Keep `require = 0`.
- UI:
  - Some list screens may still mutate `children()` directly. For 1.21.10+ this is unsafe; prefer protected list refresh methods now where practical.
- Main risks:
  - Entity render-state transition may require target-specific mixin.

### Fabric 1.21.5

- Chat:
  - Typed events: `new ClickEvent.SuggestCommand(value)`, `new HoverEvent.ShowText(component)`.
  - Read with `instanceof ClickEvent.SuggestCommand` / `instanceof HoverEvent.ShowText`.
  - Old `getAction()` / `getValue()` does not compile.
- Tooltip:
  - Tooltip rendering still uses `renderTooltip` / `renderComponentTooltip`.
  - Hover events are typed.
- Scoreboard / advancement:
  - `GuiGraphics.drawString` is `void`.
  - `PlayerScoreEntry` path exists.
- Main risks:
  - All chat button and hover tooltip code must be adapted to typed events.

### Fabric 1.21.8

- Tooltip:
  - Use `setTooltipForNextFrame(...)` / `setComponentTooltipForNextFrame(...)`.
  - Do not redirect or call old `renderTooltip(Font, Component, int, int)` overloads.
- Book:
  - `BookEditScreen` uses `MultiLineEditBox`.
  - Old hooks such as `rebuildDisplayCache`, `getCurrentPageText`, `setCurrentPageText`, `isSigning`, `clearDisplayCache` no longer exist.
- Chat:
  - Typed click/hover event API.
  - `mouseClicked(double, double, int)` remains in this version.
- Entity:
  - Translate names through `EntityRenderer#extractRenderState(T, S, float)` by editing `state.nameTag`.
- Advancement / bossbar:
  - `GuiGraphics.drawString` returns `void`.
- Main risks:
  - Tooltip and book mixins need version-specific source inspection before porting.

### Fabric 1.21.10

- Screen / input:
  - Input methods use event records: `keyPressed(KeyEvent)`, `mouseClicked(MouseButtonEvent, boolean)`, `mouseDragged(MouseButtonEvent, double, double)`.
  - `ObjectSelectionList.Entry#renderContent(...)` plus `getX()` / `getY()`.
- Keybinding:
  - `KeyMapping.Category.register(ResourceLocation.fromNamespaceAndPath(...))`.
  - `InputConstants.isKeyDown(Window, keyCode)` takes `Window`.
- Chat:
  - Typed click/hover event API.
  - Some old `ChatComponent` clickable shadows may be gone or unstable; prefer `ChatScreen` click collection if needed.
- Book:
  - `BookViewScreen.mouseClicked(MouseButtonEvent, boolean)`.
- UI:
  - Avoid mutating `ObjectSelectionList.children()` directly.
  - `EditBox#setFormatter` is unavailable.
- Main risks:
  - Client can compile but crash during Mixin apply if old mouse signatures remain.

### Fabric 1.21.11

- Resource identifiers:
  - `ResourceLocation` is renamed to `Identifier`.
  - Use `Identifier.fromNamespaceAndPath(...)`.
- CycleButton:
  - Use `CycleButton.builder(formatter, currentValue)`.
  - No `withInitialValue(...)`.
- Chat:
  - `ChatComponent#getClickedComponentStyleAt` and `handleChatQueueClicked` are gone.
  - Handle chat button clicks from `ChatScreen.mouseClicked(MouseButtonEvent, boolean)` with `ActiveTextCollector.ClickableStyleFinder` and `ChatComponent.captureClickableText(...)`.
- Tooltip / input / entity:
  - Same 1.21.10+ event, next-frame tooltip, and render-state entity expectations.
- UI:
  - Avoid direct `children().add(...)` in list screens.
- Main risks:
  - Sign renderer may have moved to render-state APIs. Confirm target before retaining `renderSignText`.

## Forge / NeoForge Targets

### Forge 1.19.2

- Loader / config:
  - `@Mod(SimpleTranslateMod.MODID)`, `mods.toml`, `FMLPaths.CONFIGDIR`.
  - Keybindings via `RegisterKeyMappingsEvent`.
  - Client tick via `TickEvent.ClientTickEvent`.
- Tooltip / hover:
  - `Screen#renderComponentHoverEffect(PoseStack, Style, int, int)`.
- Chat:
  - Old click/hover event API.
- Sign:
  - No `SignRendererMixin`; use `SignBlockEntity#getRenderMessages`.
- Entity / text display:
  - Entity names via `renderNameTag`.
  - No `TextDisplayMixin`.
- Advancement:
  - Old `Advancement` targets.
- Main risks:
  - Same 1.19.2 Fabric API limitations plus Forge loader event differences.

### Forge 1.20.1 (`MDK-Forge-1.20.1-ModDevGradle-main`)

- Loader / config:
  - `@Mod`, `mods.toml`, `ModLoadingContext.get().registerConfig(Type.CLIENT, ModConfig.SPEC)`, `FMLPaths.CONFIGDIR`.
  - Keybindings via `RegisterKeyMappingsEvent`.
  - Client tick via Forge client tick events.
- Tooltip:
  - `GuiGraphics` path.
  - `ItemStack#getTooltipLines(Player, TooltipFlag)`.
- Chat:
  - Old click/hover event API.
- Sign:
  - `{SignRenderer, HangingSignRenderer}#renderSignText`.
- Text display:
  - `Display.TextDisplay#cacheDisplay(LineSplitter)`.
- Main risks:
  - Loader entrypoints and config screen registration differ from Fabric, but MC APIs are close to baseline.

### NeoForge 1.20.4

- Loader / config:
  - Uses `mods.toml`.
  - Old no-arg mod constructor plus `FMLJavaModLoadingContext.get().getModEventBus()`.
  - Config path through `FMLPaths.CONFIGDIR`.
- Tooltip:
  - `ItemStack#getTooltipLines(Player, TooltipFlag)`.
  - Hover uses `GuiGraphics#renderTooltip` / `renderComponentTooltip`.
- Chat:
  - Old click/hover event API.
- Sign:
  - `{SignRenderer, HangingSignRenderer}#renderSignText`.
- Advancement:
  - `AdvancementHolder` / `AdvancementNode`.
- Main risks:
  - Preserve NeoForge event registration and metadata while syncing direct logic.

### NeoForge 1.20.6

- Loader / config:
  - Uses `neoforge.mods.toml`.
  - Old no-arg constructor plus mod event bus lookup.
  - Client tick uses `ClientTickEvent.Post`.
- Tooltip:
  - `ItemStack#getTooltipLines(Item.TooltipContext, Player, TooltipFlag)`.
- Chat:
  - Old click/hover event API.
- Sign:
  - `{SignRenderer, HangingSignRenderer}#renderSignText`.
- Main risks:
  - Java 21 and item tooltip signature adaptation.

### NeoForge 1.21.1

- Loader / config:
  - Constructor injection `SimpleTranslateMod(IEventBus modEventBus, ModContainer modContainer)`.
  - `modContainer.registerConfig(...)`.
- Tooltip:
  - Same general `GuiGraphics` rendering path.
- Chat:
  - Old click/hover event API.
- Sign:
  - `{SignRenderer, HangingSignRenderer}#renderSignText`.
- Entity:
  - Entity names still through `renderNameTag` in the project.
- Main risks:
  - Keep constructor injection and NeoForge tick/keybinding events.

### NeoForge 1.21.4

- Sign:
  - Target `AbstractSignRenderer#renderSignText(BlockPos, SignText, PoseStack, MultiBufferSource, int, int, int, boolean)`.
- Entity:
  - Use render-state extraction: `EntityRenderer#extractRenderState(T, S, float)` and mutate `state.nameTag`.
- Chat:
  - Old click/hover event API through this version.
- Clear title hook:
  - `Gui.clearTitles`.
- Main risks:
  - Sign target changes from concrete sign renderers to `AbstractSignRenderer`.

### NeoForge 1.21.5

- Chat:
  - Typed click/hover events.
- Sign:
  - `AbstractSignRenderer#renderSignText(...)`.
- Entity:
  - Render-state extraction.
- Main risks:
  - Chat button and hover event code must be typed-event compatible.

### NeoForge 1.21.8

- Tooltip:
  - Next-frame tooltip APIs: `setTooltipForNextFrame(...)` / `setComponentTooltipForNextFrame(...)`.
- Sign:
  - `AbstractSignRenderer#renderSignText(...)`.
- Advancement / bossbar:
  - `GuiGraphics.drawString` returns `void`.
- Book:
  - Book mixin targets may differ from Fabric; optional redirects should use `require = 0`.
- Main risks:
  - Tooltip and book hooks require source-confirmed descriptors.

### NeoForge 1.21.10

- Sign:
  - `AbstractSignRenderer.submitSignText(SignRenderState, PoseStack, SubmitNodeCollector, boolean)`.
  - Read `state.blockPos`, `state.frontText/backText`, `state.maxTextLineWidth`.
- Input / screen:
  - Mouse and key events use event objects.
- Keybinding:
  - `KeyMapping.Category.register(ResourceLocation.fromNamespaceAndPath(...))`.
  - `InputConstants.isKeyDown(Window, keyCode)`.
- Chat:
  - Typed click/hover events.
  - `ChatScreen.mouseClicked(MouseButtonEvent, boolean)`.
- Book:
  - `BookViewScreen.mouseClicked(MouseButtonEvent, boolean)`.
- Main risks:
  - Old sign and input descriptors will fail Mixin apply.

### NeoForge 1.21.11

- Resource identifiers:
  - `ResourceLocation` replaced by `Identifier`.
- Sign:
  - `AbstractSignRenderer.submitSignText(SignRenderState, PoseStack, SubmitNodeCollector, boolean)`.
- Chat:
  - Use `ChatScreen.mouseClicked(MouseButtonEvent, boolean)`, `ActiveTextCollector.ClickableStyleFinder`, `ChatComponent.captureClickableText(...)`.
  - Do not shadow removed `ChatComponent` click methods.
- CycleButton:
  - Use builder with current value, not `withInitialValue`.
- Main risks:
  - Requires the most version-specific UI/input/chat/sign adaptation.

## Required Per-Version Report Fields

Every implementation agent must report:

- Matrix entries used.
- Feature groups synced.
- Mixin/API adaptation points.
- Automatic map style summary residuals removed.
- Build result and jar path.
- Client launch result.
- Remaining warnings or risks.
