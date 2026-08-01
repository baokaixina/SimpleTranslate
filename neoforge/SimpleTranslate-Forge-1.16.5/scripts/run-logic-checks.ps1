param([string]$ProjectDir = ".")
$ErrorActionPreference = "Stop"

# BEGIN SimpleTranslate project JDK pin
. (Join-Path $PSScriptRoot "resolve-java.ps1")
Use-SimpleTranslateProjectJava -ProjectDir $ProjectDir -Purpose Gradle | Out-Null
# END SimpleTranslate project JDK pin

Set-Location $ProjectDir
Write-Host "SimpleTranslate Forge 1.16.5 logic checks (product sync from fabric 1.16.5 donor)"

function Assert-FileContains($Path, $Needle, $Message) {
  if (-not (Test-Path $Path)) { throw "$Message (missing file $Path)" }
  if (-not (Get-Content -Raw -LiteralPath $Path).Contains($Needle)) { throw "$Message (missing: $Needle)" }
}
function Assert-PathExists($Path, $Message) {
  if (-not (Test-Path $Path)) { throw $Message }
}
function Assert-PathMissing($Path, $Message) {
  if (Test-Path $Path) { throw $Message }
}
function Assert-FileNotContains($Path, $Needle, $Message) {
  if ((Get-Content -Raw -LiteralPath $Path).Contains($Needle)) { throw "$Message (found: $Needle)" }
}

$src = "src/main/java/com/yourname/simpletranslate"
$res = "src/main/resources"

$javaCount = (Get-ChildItem $src -Recurse -Filter *.java).Count
Write-Host "Java files: $javaCount"
if ($javaCount -lt 150) { throw "expected >=150 java files after donor product sync, got $javaCount" }

# Wynncraft feature set is gated to Minecraft >= 1.21.4 and must not ship here.
Assert-PathMissing "$src/feature/wynn" "Wynn package must not ship on Minecraft 1.16.5"
Assert-PathMissing "$src/feature/hud/ActionbarLayoutRenderer.java" "ActionbarLayoutRenderer is Wynn-era only"
Assert-PathMissing "$src/mixin/FontPreparedTextBuilderMixin.java" "FontPreparedTextBuilderMixin is Wynn-era only"
Assert-PathMissing "$src/mixin/compat/WynntilsOverlayManagerMixin.java" "Wynntils compat mixin must not ship on 1.16.5"
Assert-FileNotContains "$res/simple_translate.mixins.json" "Wynntils" "Wynntils compat must not be registered"
Assert-FileNotContains "$res/simple_translate.mixins.json" "FontPreparedTextBuilderMixin" "Wynn-era font mixin must not be registered"
Assert-FileNotContains "$src/config/ModConfig.java" "WYNNCRAFT_PROFILE_MODE" "retired Wynn profile setting must be absent"
Assert-FileNotContains "$src/config/ModConfig.java" "WynncraftProfileMode" "retired Wynn profile enum must be absent"
Assert-FileNotContains "$src/config/ModConfig.java" "API_TEXT_CONTEXT_WYNN_DIALOGUE" "Wynn dialogue context switch must be absent"
Assert-FileNotContains "$src/config/ModConfig.java" "HUD_WYNN_OVERLAY_ENABLED" "Wynn overlay switch must not ship on 1.16.5"
Assert-FileNotContains "$src/config/ModConfig.java" "HUD_WYNN_DIALOGUE_PENDING_EFFECT_ENABLED" "Wynn pending feedback switch must be absent"
Assert-FileContains "$src/config/ModConfig.java" 'root.remove("general.wynncraftProfileMode")' "retired Wynn profile key must be removed during config migration"
Assert-FileContains "$src/config/ModConfig.java" 'root.remove("hud.wynnOverlayEnabled")' "retired Wynn overlay key must be removed during config migration"
Assert-FileNotContains "$res/assets/simple_translate/lang/zh_cn.json" "wynncraft_profile_mode" "retired zh Wynn profile keys must be absent"
Assert-FileNotContains "$res/assets/simple_translate/lang/en_us.json" "wynncraft_profile_mode" "retired en Wynn profile keys must be absent"
Assert-FileNotContains "$res/assets/simple_translate/lang/zh_cn.json" "hud.wynn" "zh Wynn HUD keys must not ship on 1.16.5"
Assert-FileNotContains "$res/assets/simple_translate/lang/en_us.json" "hud.wynn" "en Wynn HUD keys must not ship on 1.16.5"
Assert-FileNotContains "$res/assets/simple_translate/lang/zh_cn.json" "server_adapters" "retired zh server adapter page"
Assert-FileNotContains "$res/assets/simple_translate/lang/en_us.json" "server_adapters" "retired en server adapter page"
Assert-PathMissing "$src/gui/ServerAdapterScreen.java" "retired server adapter screen must be deleted"
Assert-PathMissing "$src/gui/WynncraftSettingsScreen.java" "retired Wynn server settings screen must be deleted"
Assert-FileNotContains "$src/feature/hud/HudFeature.java" "Wynn" "HudFeature must carry no Wynn layer on 1.16.5"
Assert-FileNotContains "$src/feature/hud/HudFeature.java" "ActionbarLayoutRenderer" "HudFeature must not reference the Wynn-era layout renderer"
Assert-PathMissing "$src/feature/hud/anchor" "retired HUD anchor package must stay deleted"
Assert-PathMissing "$src/gui/HudAnchorMappingScreen.java" "retired HUD anchor screen must stay deleted"
Assert-PathMissing "$src/gui/HudAnchorPickScreen.java" "retired HUD anchor picker must stay deleted"
Assert-FileNotContains "$src/mixin/TitleOverlayMixin.java" "HudAnchor" "title overlay must not reference retired HUD anchors"
Assert-FileNotContains "$src/mixin/ScoreboardMixin.java" "HudAnchor" "scoreboard must not reference retired HUD anchors"
Assert-FileNotContains "$src/mixin/BossHealthOverlayMixin.java" "HudAnchor" "bossbar must not reference retired HUD anchors"
Assert-FileNotContains "$src/gui/HudTranslationScreen.java" "HudAnchor" "HUD settings must not reference retired HUD anchors"
Assert-FileNotContains "$src/gui/HudTranslationScreen.java" "wynnOverlay" "HUD settings must not reference the Wynn overlay switch"

Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "shouldOpenScreenFrame" "idle GUI frames require an explicit ownership gate"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "activeFrame() == null" "GUI materialization must skip inactive frames"
Assert-FileNotContains "$src/feature/gui/GuiTranslationHelper.java" "Wynn" "GUI helper must carry no Wynn overlay layer on 1.16.5"
Assert-FileNotContains "$src/feature/gui/GuiTranslationHelper.java" "DialogScreen" "1.16.5 has no dialog screen API"
Assert-FileNotContains "$src/feature/gui/GuiTranslationHelper.java" "hud.visible_frame.component.v1" "retired unstable HUD surface"
# Forge 1.16.5 keeps MCP class names (ITextComponent, not 1.17+ Component).
Assert-FileContains "$src/feature/gui/GuiLayoutProgramRenderer.java" "IdentityHashMap<ITextComponent, IdentityDetection>" "layout detection must memoize stable Component identities"
Assert-FileContains "$src/feature/hud/ScoreboardTranslationHelper.java" "matchesPrevious()" "unchanged scoreboard frames must short-circuit"
Assert-FileContains "$src/feature/hud/ScoreboardTranslationHelper.java" "sameOrderedFrameKeys" "scoreboard reuse must preserve row order"
Assert-FileContains "$src/mixin/EntityRendererMixin.java" "direct.translated" "entity memo retries every non-translated result"
Assert-FileContains "$src/mixin/EntityRendererMixin.java" "WeakHashMap" "entity name memoization must not retain entities strongly"
Assert-FileContains "$src/mixin/EntityRendererMixin.java" "getRuntimeRevision()" "entity name memoization must honor runtime invalidation"
Assert-FileContains "$src/cache/CacheKey.java" 'PROTOCOL = "stx2"' "stx2 protocol"
Assert-FileContains "$src/transport/TranslationPromptPolicy.java" "component_visual_projection_v7" "semantic revision"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "gui.component.visible_frame.v3" "GUI surface"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "hud.visible_frame.component.v2" "stable missing-only HUD surface"
Assert-FileNotContains "$src/feature/gui/GuiTranslationHelper.java" "hud.wynn" "no Wynn overlay cache surface may ship on 1.16.5"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "merged.putIfAbsent(key, value)" "accepted frame translations must be first-wins"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "isFrameTranslationPending(frame.screenKey)" "whole-frame requests must coalesce per frame"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "isHudTelemetryComponent" "HUD telemetry must not churn translation context"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "requestCurrentHudTranslation" "HUD K request"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "Whole-frame Component translation rejected" "manual whole-frame failures need non-sensitive runtime diagnostics"
Assert-FileNotContains "$src/core/JsonPassthroughPipeline.java" "boolean wholeGuiFrame =" "GUI and HUD recovery must retain original-Component partition fallback"
Assert-FileNotContains "$src/core/JsonPassthroughPipeline.java" ".wynn." "pipeline must carry no Wynn recovery branches on 1.16.5"
Assert-FileContains "$src/config/ModConfig.java" "CONTENT_HUD_FRAME_ACTIVE" "HUD K activation persistence required"
Assert-FileContains "$src/SimpleTranslateMod.java" "migrateLegacyHudFrameActivation" "existing HUD K cache must migrate to persistent activation"
Assert-FileContains "$src/cache/TranslationCache.java" "hasSurface(String surface)" "HUD activation migration must not copy the complete cache"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "sequence == null || !isActive() || CAPTURE_SUPPRESSION_DEPTH.get() > 0" "dedicated glyph sequences must bypass K without reconstruction"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "screen instanceof ChatScreen" "chat screens must remain permanently outside K ownership"
# 1.16.5 MCP screen names: ReadBookScreen / EditBookScreen.
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "screen instanceof ReadBookScreen" "book reading screens must remain permanently outside K ownership"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "screen instanceof EditBookScreen" "book editing screens must remain permanently outside K ownership"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "screen instanceof AdvancementsScreen" "advancement screens must remain permanently outside K ownership"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "rebindItemTooltipSemanticTranslation" "item tooltip async results must rebind during a continuous hover"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "semantic=" "item tooltip frame identity must ignore animated visual values"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "beginHudFrame" "HUD frame begin"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "beginDetachedFrame" "shared detached K frame begin"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "endDetachedFrame" "shared detached K frame end"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "frame_context_kind=" "GUI frames must identify their semantic context kind"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "<visual-row>" "GUI context must distinguish visual wraps from sentence boundaries"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "gui.component.visible_frame.item_tooltip.v1" "item tooltips must have an isolated K-frame cache surface"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "gui.component.visible_frame.advancement.v1" "advancements must have an isolated K-frame cache surface"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "surfaceForFrame(frame)" "K-frame cache and request paths must select their product surface"
Assert-FileContains "$src/transport/JsonPassthroughPrompts.java" "frame_context_kind=item_tooltip" "detached item frames must receive item-tooltip prompt policy"
Assert-FileContains "$src/transport/JsonPassthroughPrompts.java" "in N games/matches" "quest objective scope must cross visual wraps"
Assert-FileContains "$src/transport/JsonPassthroughPrompts.java" "COMMON / WEEKLY QUEST" "item-frame prompt must translate all-caps quest badges"
Assert-FileContains "$src/transport/JsonPassthroughPrompts.java" "数量 + 剩余" "item-frame prompt must avoid unnatural retained-count order"
Assert-FileNotContains "$src/transport/JsonPassthroughPrompts.java" "Wynncraft" "prompts must carry no Wynn branches on 1.16.5"
Assert-FileContains "$src/core/JsonPassthroughPipeline.java" "semanticPromptSourceShape(item.originals())" "single Component requests must mask live numbers in prompt context"
Assert-FileContains "$src/core/TextContextMemory.java" "maskPromptDynamicNumbers(callerContext)" "prompt metadata boundary must mask live numbers for every caller"
Assert-FileContains "$src/core/ComponentJsonBatcher.java" "semanticPromptSourceShape(batchItem.item().originals())" "batched Component requests must mask live numbers in prompt context"
Assert-FileContains "$src/core/TextContextMemory.java" "maskPromptDynamicNumbers(example.source())" "historical source examples must not leak live numbers back into prompts"
Assert-FileContains "$src/core/TextContextMemory.java" "maskPromptDynamicNumbers(example.translation())" "historical translations must not reintroduce stale numbers into responses"
Assert-FileNotContains "$src/transport/JsonPassthroughPrompts.java" "Survive at least 4m" "system prompt must not expose exact example values owned by the client"
Assert-FileContains "$src/mixin/ScreenGuiTranslationMixin.java" "render(Lcom/mojang/blaze3d/matrix/MatrixStack;IIF)V" "1.16.5 GUI frame scope uses Screen.render(MatrixStack,int,int,float)"
Assert-FileContains "$src/mixin/HoverTooltipMixin.java" "beginItemTooltipFrame" "item tooltip must synchronously probe cached frame translations"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "hydrateItemTooltipFrameFromCache" "cached item tooltips must bypass hover dwell"
# 1.16.5 has no renderTooltipInternal/ClientTooltipComponent (both are 1.17+).
# Tooltips funnel through Screen.renderComponentTooltip on the semantic
# ITextComponent rows BEFORE visual splitting, so the frame captures the exact
# accepted rows directly and no ClientTextTooltipAccessor exists or is needed.
Assert-FileContains "$src/mixin/HoverTooltipMixin.java" "renderComponentTooltip(Lcom/mojang/blaze3d/matrix/MatrixStack;Ljava/util/List;II)V" "1.16.5 item tooltips funnel through Screen.renderComponentTooltip"
Assert-FileContains "$src/mixin/HoverTooltipMixin.java" "beginItemTooltipSubmission" "item tooltip frames must only arm inside ItemStack tooltip submission"
Assert-PathMissing "$src/mixin/ClientTextTooltipAccessor.java" "ClientTextTooltip is 1.17+; the accessor must not ship on 1.16.5"
Assert-FileNotContains "$res/simple_translate.mixins.json" "ClientTextTooltipAccessor" "1.17+ tooltip accessor must not be registered on 1.16.5"
Assert-FileNotContains "$src/mixin/HoverTooltipMixin.java" "isCurrentScreenTranslationRequested()" "K must never arm the independently controlled item-tooltip translator"
Assert-FileContains "$src/mixin/AdvancementWidgetMixin.java" "beginDetachedFrame" "advancement widget must use its isolated Component frame"
Assert-FileContains "$src/mixin/AdvancementToastMixin.java" "beginDetachedFrame" "advancement toast must use its isolated Component frame"
Assert-FileContains "$src/mixin/AdvancementWidgetMixin.java" "captureSuppressed" "disabled advancement widgets must remain outside K ownership"
Assert-FileContains "$src/mixin/AdvancementToastMixin.java" "captureSuppressed" "disabled advancement toasts must remain outside K ownership"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" "layoutActionbarSource" "layout actionbar hooks"
Assert-FileNotContains "$src/mixin/TitleOverlayMixin.java" "Wynn" "title overlay must carry no Wynn hooks on 1.16.5"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" "endHudFrame" "HUD frame end"
# Forge 1.16.5 ships Mixin 0.8.5 whose @Inject has no `order` member (0.8.7+);
# HEAD/RETURN placement is retained, donor ordering flags are documented away.
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" 'at = @At("HEAD")' "HUD frame must open before late overlays"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" 'at = @At("RETURN")' "HUD frame must close after late overlays"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" "Mixin 0.8.5" "order removal must stay documented for Mixin 0.8.5"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" "renderDedicatedHudText" "inline title/actionbar draws must stay outside K ownership"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" '"render(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V"' "1.16.5 HUD frame must hang on the exact IngameGui.render owner"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" "finally" "dedicated HUD ownership scope must close after render failures"
Assert-FileContains "$src/mixin/GuiGraphicsTranslationMixin.java" 'leaveDirectDrawIfTop("draw.component")' "cancelled layout replay must balance direct-draw scope"
Assert-PathMissing "$src/mixin/TextDisplayMixin.java" "Display entity is 1.19.4+ only; text display mixin must not ship on 1.16.5"
Assert-FileNotContains "$res/simple_translate.mixins.json" "TextDisplayMixin" "text display mixin must not be registered on 1.16.5"
Assert-FileContains "$src/mixin/HoverTooltipMixin.java" "return ModConfig.GLOBAL_ENABLED.get()" "book hover must honor global translation before interception"
Assert-FileContains "$src/feature/sign/SignTranslationHelper.java" "!ModConfig.GLOBAL_ENABLED.get()" "sign cache reads must honor global translation"
Assert-FileContains "$src/keybind/ShortcutAction.java" "TOGGLE_GLOBAL_TRANSLATION" "bindable global translation action required"
Assert-FileNotContains "$src/keybind/ShortcutAction.java" "boolean hold" "retired shortcut hold flag residue"
Assert-FileContains "$src/config/ModConfig.java" "shortcuts.toggleGlobalTranslation" "global translation shortcut persistence required"
Assert-FileNotContains "$src/config/ModConfig.java" "CHAT_CONTEXT_BATCH_INTERVAL_MS" "retired chat batch config residue"
Assert-FileNotContains "$src/config/ModConfig.java" "CHAT_CONTEXT_COLLECT_WINDOW_MS" "retired chat collect-window config residue"
Assert-FileNotContains "$src/config/ModConfig.java" "CACHE_SERVER_SHARE_ENABLED" "retired global shared-cache config residue"
Assert-FileContains "$src/config/ModConfig.java" 'root.remove("cache.serverShareEnabled")' "retired shared-cache setting must be removed from persisted config"
Assert-FileContains "$src/keybind/KeyChord.java" "return code >= 0;" "unbound checks must not initialize GLFW"
Assert-FileContains "$src/keybind/ModKeyBindings.java" "!editingModSettings" "shortcut recording/settings screens must not dispatch live actions"
Assert-FileContains "$src/keybind/ModKeyBindings.java" '"key.category.simple_translate.general"' "1.16.5 KeyBinding category is a plain translation-key string"
Assert-FileContains "$res/assets/simple_translate/lang/zh_cn.json" "shortcuts.action.toggle_global_translation" "zh global shortcut label"
Assert-FileContains "$res/assets/simple_translate/lang/en_us.json" "shortcuts.action.toggle_global_translation" "en global shortcut label"
Assert-FileContains "$res/assets/simple_translate/lang/zh_cn.json" '"screen.simple_translate.gui_translation": "当前界面所有内容翻译"' "current-screen translation wording"
Assert-FileContains "$src/cache/SharedCacheClient.java" "cache.save();" "shared-cache import must schedule persistence off the render tick"
Assert-FileContains "$src/cache/SharedCachePayload.java" 'new ResourceLocation("simple_translate", "cache_sync/v1")' "1.16.5 shared cache uses a classic channel"
Assert-FileNotContains "$src/cache/SharedCachePayload.java" "CustomPacketPayload" "1.16.5 has no CustomPacketPayload API"
Assert-FileContains "$res/simple_translate.mixins.json" "GuiGraphicsTranslationMixin" "GUI draw mixin registered"
Assert-FileContains "$res/simple_translate.mixins.json" "TitleOverlayMixin" "HUD mixin registered"
Assert-FileNotContains "$res/simple_translate.mixins.json" "ItemStackMixin" "early item tooltip replacement mixin must be retired"
Assert-PathMissing "$src/feature/advancement/AdvancementTranslationHelper.java" "dedicated advancement translation path must be retired"
# Forge 1.16.5 (36.x) has no RenderTooltipEvent.GatherComponents (1.18.2+
# tooltip rework) and the Fabric 1.16.5 donor ships no Iceberg compat either;
# the documented gap lives in MIXIN_EVIDENCE_1.16.5.md.
Assert-PathMissing "$src/compat/IcebergTooltipGatherCompat.java" "Iceberg GatherComponents compat has no 1.16.5 API surface and must not ship"
Assert-FileNotContains "$src/mixin/SimpleTranslateMixinPlugin.java" "AdvancementPlaques" "retired advancement plaque integration residue"

Assert-FileContains "$src/feature/chat/ChatAutoTranslationFilter.java" "TranslationTextDetector.normalizeForDetection" "AUTO chat classification must normalize fullwidth Latin text"
Assert-FileContains "$src/feature/chat/ChatContextHelper.java" "return isKnownPlayerName(leading);" "multi-word colon prefixes must require an actually online leading player"
Assert-FileContains "$src/feature/chat/ChatAutoTranslationFilter.java" "Stats.from(body).wordCount > 0" "ambiguous colon prefixes must not strip every translatable word"

$ocr = Get-ChildItem $src -Recurse -Filter *.java | Select-String -Pattern "\bOCR\b|ocrEnabled|OcrFeature" | Where-Object { $_.Line -notmatch '^\s*//' }
if ($ocr) { throw "OCR residue: $($ocr[0].Path):$($ocr[0].LineNumber)" }

$extractorHits = Get-ChildItem $src -Recurse -Filter *.java | Select-String -Pattern "GuiGraphicsExtractor" -SimpleMatch
if ($extractorHits) { throw "GuiGraphicsExtractor residue remains: $($extractorHits[0])" }

& "$env:JAVA_HOME\bin\java.exe" "-Dorg.gradle.appname=gradlew" `
  -classpath "gradle/wrapper/gradle-wrapper.jar" `
  org.gradle.wrapper.GradleWrapperMain compileJava --no-daemon
if ($LASTEXITCODE -ne 0) { throw "compileJava failed" }

Write-Host "Forge 1.16.5 logic checks PASSED"
