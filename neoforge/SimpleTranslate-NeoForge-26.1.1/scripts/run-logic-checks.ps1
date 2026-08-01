param([string]$ProjectDir = ".")
$ErrorActionPreference = "Stop"

# BEGIN SimpleTranslate project JDK pin
. (Join-Path $PSScriptRoot "resolve-java.ps1")
Use-SimpleTranslateProjectJava -ProjectDir $ProjectDir -Purpose Gradle | Out-Null
# END SimpleTranslate project JDK pin

Set-Location $ProjectDir
Write-Host "SimpleTranslate NeoForge 26.1.1 logic checks"

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
if ($javaCount -lt 160) { throw "expected >=160 java files after 26.1.1 product sync, got $javaCount" }

Assert-PathExists "$src/feature/wynn/WynncraftProfile.java" "WynncraftProfile required"
Assert-PathExists "$src/feature/wynn/WynnDialogueProjection.java" "WynnDialogueProjection required"
Assert-PathExists "$src/feature/wynn/WynnDialogueRenderPlan.java" "WynnDialogueRenderPlan required"
Assert-PathExists "$src/feature/wynn/WynnActionbarGlyphOverlayPlan.java" "WynnActionbarGlyphOverlayPlan required"
Assert-PathExists "$src/feature/hud/ActionbarLayoutRenderer.java" "ActionbarLayoutRenderer required"
Assert-FileNotContains "$src/config/ModConfig.java" "WYNNCRAFT_PROFILE_MODE" "retired Wynn profile setting must be absent"
Assert-FileNotContains "$src/config/ModConfig.java" "WynncraftProfileMode" "retired Wynn profile enum must be absent"
Assert-FileNotContains "$src/config/ModConfig.java" "API_TEXT_CONTEXT_WYNN_DIALOGUE" "Wynn dialogue uses the general HUD context switch"
Assert-FileContains "$src/config/ModConfig.java" "HUD_WYNN_OVERLAY_ENABLED" "independent Wynntils overlay switch required"
Assert-FileContains "$src/config/ModConfig.java" 'root.remove("general.wynncraftProfileMode")' "retired Wynn profile key must be removed during config migration"
Assert-FileNotContains "$src/config/ModConfig.java" "HUD_WYNN_DIALOGUE_PENDING_EFFECT_ENABLED" "Wynn pending feedback is automatic"
Assert-FileNotContains "$res/assets/simple_translate/lang/zh_cn.json" "wynncraft_profile_mode" "retired zh Wynn profile keys must be absent"
Assert-FileNotContains "$res/assets/simple_translate/lang/en_us.json" "wynncraft_profile_mode" "retired en Wynn profile keys must be absent"
Assert-FileNotContains "$res/assets/simple_translate/lang/zh_cn.json" "server_adapters" "retired zh server adapter page"
Assert-FileNotContains "$res/assets/simple_translate/lang/en_us.json" "server_adapters" "retired en server adapter page"
Assert-PathMissing "$src/gui/ServerAdapterScreen.java" "retired server adapter screen must be deleted"
Assert-PathMissing "$src/gui/WynncraftSettingsScreen.java" "retired Wynn server settings screen must be deleted"
Assert-FileContains "$src/feature/wynn/WynncraftProfile.java" 'font.contains("hud/selector") || font.contains("/selector/")' "Wynn selector ownership must require selector fonts"
Assert-FileNotContains "$src/feature/wynn/WynncraftProfile.java" 'font.startsWith("minecraft:hud/")' "generic HUD fonts must not activate Wynn selector ownership"
Assert-FileContains "$src/feature/hud/HudFeature.java" "WYNN_ACTIONBAR_FAILURE_RETRY_MS" "failed Wynn selector requests require render-loop backoff"
Assert-FileNotContains "$src/feature/wynn/WynncraftProfile.java" "getCurrentServer" "Wynn ownership must be server-agnostic"
Assert-FileNotContains "$src/feature/wynn/WynncraftProfile.java" "isWynnServerAddress" "Wynn ownership must not parse server hosts"
Assert-FileNotContains "$src/feature/hud/HudFeature.java" "isActionbarEnabled" "Wynn HUD ownership must be structural"
Assert-FileNotContains "$src/feature/hud/HudFeature.java" "WynncraftProfile.isActive" "Wynn dialogue ownership must be structural"
Assert-PathExists "$src/feature/wynn/WynnSemanticStyle.java" "shader-safe Wynn semantic style required"
Assert-FileContains "$src/feature/wynn/WynnSemanticStyle.java" 'result = result.withColor(raw.getColor())' "source RGB must stay on translated Wynn prose"
Assert-PathMissing "$src/feature/hud/anchor" "retired HUD anchor package must stay deleted"
Assert-PathMissing "$src/gui/HudAnchorMappingScreen.java" "retired HUD anchor screen must stay deleted"
Assert-PathMissing "$src/gui/HudAnchorPickScreen.java" "retired HUD anchor picker must stay deleted"
Assert-FileNotContains "$src/mixin/TitleOverlayMixin.java" "HudAnchor" "title overlay must not reference retired HUD anchors"
Assert-FileNotContains "$src/mixin/ScoreboardMixin.java" "HudAnchor" "scoreboard must not reference retired HUD anchors"
Assert-FileNotContains "$src/mixin/BossHealthOverlayMixin.java" "HudAnchor" "bossbar must not reference retired HUD anchors"
Assert-FileNotContains "$src/gui/HudTranslationScreen.java" "HudAnchor" "HUD settings must not reference retired HUD anchors"

Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "shouldOpenScreenFrame" "idle GUI frames require an explicit ownership gate"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "activeFrame() == null" "GUI materialization must skip inactive frames"
Assert-FileContains "$src/feature/gui/GuiLayoutProgramRenderer.java" "IdentityHashMap<Component, IdentityDetection>" "layout detection must memoize stable Component identities"
Assert-FileContains "$src/feature/hud/ScoreboardTranslationHelper.java" "matchesPrevious()" "unchanged scoreboard frames must short-circuit"
Assert-FileContains "$src/feature/hud/ScoreboardTranslationHelper.java" "sameOrderedFrameKeys" "scoreboard reuse must preserve row order"
Assert-FileContains "$src/mixin/EntityRendererMixin.java" "direct.translated" "entity memo retries every non-translated result"
Assert-FileContains "$src/mixin/EntityRendererMixin.java" "WeakHashMap" "entity name memoization must not retain entities strongly"
Assert-FileContains "$src/mixin/EntityRendererMixin.java" "getRuntimeRevision()" "entity name memoization must honor runtime invalidation"
Assert-FileContains "$src/feature/wynn/WynnDialogueProjection.java" "paragraph.v5" "Wynn dialogue fingerprints must version paragraph BODY requests"
Assert-FileContains "$src/feature/wynn/WynnDialogueProjection.java" "runIndex" "Wynn BODY anchors must identify exact source style runs"
Assert-FileContains "$src/feature/wynn/WynnDialogueProjection.java" "matchSourceAppearance" "Wynn BODY translated spans must map back to source appearances"
Assert-FileNotContains "$src/feature/wynn/WynnDialogueProjection.java" "hasOverlaySafeUniformBodyStyle" "retired uniform-colour BODY gate must stay absent"
Assert-FileContains "$src/feature/wynn/WynnDialogueRenderPlan.java" "measureBodyParagraph" "Wynn BODY must use measured paragraph flow"
Assert-FileContains "$src/feature/wynn/WynnDialogueRenderPlan.java" "body-paragraph-layout-unavailable" "Wynn BODY paragraph flow must fail closed"
Assert-FileNotContains "$src/feature/wynn/WynnDialogueRenderPlan.java" "measureBodyRuns" "retired source-fragment renderer must stay absent"
Assert-FileNotContains "$src/feature/wynn/WynnDialogueRenderPlan.java" "DIALOGUE_EFFECT_FONT_PREFIX" "Wynn dialogue effects must remain in the source replay stream"
Assert-FileContains "$src/feature/hud/HudFeature.java" "content.paragraph.v5" "Wynn dialogue cache surface must invalidate fragment entries"
Assert-FileContains "$src/transport/JsonPassthroughPrompts.java" "one complete spoken paragraph" "Wynn dialogue prompt must request coherent BODY prose"
Assert-FileContains "$src/cache/CacheKey.java" 'PROTOCOL = "stx2"' "stx2 protocol"
Assert-FileContains "$src/transport/TranslationPromptPolicy.java" "component_visual_projection_v7" "semantic revision"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "gui.component.visible_frame.v3" "GUI surface"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "hud.visible_frame.component.v2" "stable missing-only HUD surface"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "hud.wynn.overlay_frame.component.v1" "independent persistent Wynn overlay surface"
Assert-FileNotContains "$src/feature/gui/GuiTranslationHelper.java" "hud.visible_frame.component.v1" "retired unstable HUD surface"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "merged.putIfAbsent(key, value)" "accepted frame translations must be first-wins"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "isFrameTranslationPending(frame.screenKey)" "whole-frame requests must coalesce per frame"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "isHudTelemetryComponent" "HUD telemetry must not churn translation context"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "requestCurrentHudTranslation" "HUD K request"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "Whole-frame Component translation rejected" "manual whole-frame failures need non-sensitive runtime diagnostics"
Assert-FileNotContains "$src/core/JsonPassthroughPipeline.java" "boolean wholeGuiFrame =" "GUI and HUD recovery must retain original-Component partition fallback"
Assert-FileContains "$src/config/ModConfig.java" "CONTENT_HUD_FRAME_ACTIVE" "HUD K activation persistence required"
Assert-FileContains "$src/SimpleTranslateClientBootstrap.java" "migrateLegacyHudFrameActivation" "existing HUD K cache must migrate to persistent activation (NeoForge: client bootstrap owns client init)"
Assert-FileContains "$src/cache/TranslationCache.java" "hasSurface(String surface)" "HUD activation migration must not copy the complete cache"
Assert-FileNotContains "$src/feature/gui/GuiTranslationHelper.java" "isDedicatedWynnHudComponent" "Wynn ownership must not be guessed from arbitrary Component structure"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "beginWynnOverlayFrame" "late Wynntils overlays need an exact independent render frame"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "shouldTranslateWynnOverlays" "Wynn overlay activation must use its independent gate"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "ModConfig.HUD_WYNN_OVERLAY_ENABLED.get()" "Wynn overlay production path must read its switch"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "WynnOverlayCaptureMode.SUPPRESSED" "disabled Wynn overlays must be isolated only during their exact manager draw window"
Assert-FileNotContains "$src/feature/gui/GuiTranslationHelper.java" ("WynnOverlayCaptureMode." + "BORROWED") "Wynn overlays must never borrow K ownership"
Assert-FileNotContains "$src/feature/gui/GuiTranslationHelper.java" ("requestedWynnOverlay" + "FrameUntil") "retired K-to-Wynn request timer residue"
Assert-FileNotContains "$src/feature/gui/GuiTranslationHelper.java" ("armWynnOverlay" + "Request") "K must not arm Wynn overlays"
Assert-FileNotContains "$src/feature/gui/GuiTranslationHelper.java" ("shouldOwnWynnOverlay" + "Frame") "retired K/HUD-dependent Wynn ownership predicate"
Assert-PathMissing "$src/feature/wynn/WynnOverlayRenderScope.java" "retired whole-manager translation gate must be deleted"
Assert-FileNotContains "$src/feature/gui/GuiTranslationHelper.java" "WynncraftProfile.isActive()" "Wynn overlay ownership must not depend on server-profile detection"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "sequence == null || !isActive() || CAPTURE_SUPPRESSION_DEPTH.get() > 0" "dedicated glyph sequences must bypass K without reconstruction"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "screen instanceof ChatScreen" "chat screens must remain permanently outside K ownership"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "screen instanceof BookViewScreen" "book reading screens must remain permanently outside K ownership"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "screen instanceof BookEditScreen" "book editing screens must remain permanently outside K ownership"
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
Assert-FileContains "$src/core/JsonPassthroughPipeline.java" "semanticPromptSourceShape(item.originals())" "single Component requests must mask live numbers in prompt context"
Assert-FileContains "$src/core/TextContextMemory.java" "maskPromptDynamicNumbers(callerContext)" "prompt metadata boundary must mask live numbers for every caller"
Assert-FileContains "$src/core/ComponentJsonBatcher.java" "semanticPromptSourceShape(batchItem.item().originals())" "batched Component requests must mask live numbers in prompt context"
Assert-FileContains "$src/core/TextContextMemory.java" "maskPromptDynamicNumbers(example.source())" "historical source examples must not leak live numbers back into prompts"
Assert-FileContains "$src/core/TextContextMemory.java" "maskPromptDynamicNumbers(example.translation())" "historical translations must not reintroduce stale numbers into responses"
Assert-FileNotContains "$src/transport/JsonPassthroughPrompts.java" "Survive at least 4m" "system prompt must not expose exact example values owned by the client"
Assert-FileContains "$src/mixin/ScreenGuiTranslationMixin.java" "extractRenderStateWithTooltipAndSubtitles" "GUI frame scope"
Assert-FileContains "$src/mixin/HoverTooltipMixin.java" "beginItemTooltipFrame" "item tooltip must synchronously probe cached frame translations"
Assert-FileContains "$src/feature/gui/GuiTranslationHelper.java" "hydrateItemTooltipFrameFromCache" "cached item tooltips must bypass hover dwell"
Assert-FileContains "$src/mixin/HoverTooltipMixin.java" 'simple_translate$finalTextRows' "item tooltip cache hydration must use the exact deferred visual rows"
Assert-FileContains "$src/mixin/ClientTextTooltipAccessor.java" '@Accessor("text")' "exact deferred tooltip text must be available before first draw"
Assert-FileContains "$res/simple_translate.mixins.json" "ClientTextTooltipAccessor" "deferred tooltip text accessor must be enabled"
Assert-FileNotContains "$src/mixin/HoverTooltipMixin.java" "isCurrentScreenTranslationRequested()" "K must never arm the independently controlled item-tooltip translator"
Assert-FileContains "$src/mixin/AdvancementWidgetMixin.java" "beginDetachedFrame" "advancement widget must use its isolated Component frame"
Assert-FileContains "$src/mixin/AdvancementToastMixin.java" "beginDetachedFrame" "advancement toast must use its isolated Component frame"
Assert-FileContains "$src/mixin/AdvancementWidgetMixin.java" "captureSuppressed" "disabled advancement widgets must remain outside K ownership"
Assert-FileContains "$src/mixin/AdvancementToastMixin.java" "captureSuppressed" "disabled advancement toasts must remain outside K ownership"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" "layoutActionbarSource" "Wynn/layout actionbar hooks"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" "renderWynnDialoguePendingEffect" "Wynn pending effect hook"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" "endHudFrame" "HUD frame end"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" 'at = @At("HEAD"), order = 900' "HUD frame must open before default-order late overlays"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" 'at = @At("RETURN"), order = 1100' "HUD frame must close after default-order late overlays"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" "renderDedicatedHudSurface" "known dedicated HUD surfaces must be permanently isolated from K"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" '"extractOverlayMessage(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"' "Wynn dialogue/actionbar isolation must use the exact vanilla render owner"
Assert-FileContains "$src/mixin/TitleOverlayMixin.java" "finally" "dedicated HUD ownership scope must close after render failures"
Assert-FileContains "$src/mixin/compat/WynntilsOverlayManagerMixin.java" 'renderOverlays(Lcom/wynntils/mc/event/RenderEvent;)V' "exact Wynntils all-overlay target required"
Assert-FileContains "$src/mixin/compat/WynntilsOverlayManagerMixin.java" "GuiTranslationHelper.beginWynnOverlayFrame" "Wynntils overlay frame begin required"
Assert-FileContains "$src/mixin/compat/WynntilsOverlayManagerMixin.java" "GuiTranslationHelper.endWynnOverlayFrame" "Wynntils overlay frame end required"
Assert-FileContains "$src/mixin/GuiGraphicsTranslationMixin.java" 'leaveDirectDrawIfTop("draw.component")' "cancelled layout replay must balance direct-draw scope"
Assert-FileContains "$src/mixin/TextDisplayMixin.java" "ModConfig.GLOBAL_ENABLED.get()" "text displays must honor global translation immediately"
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
Assert-FileContains "$res/assets/simple_translate/lang/zh_cn.json" "shortcuts.action.toggle_global_translation" "zh global shortcut label"
Assert-FileContains "$res/assets/simple_translate/lang/en_us.json" "shortcuts.action.toggle_global_translation" "en global shortcut label"
Assert-FileContains "$res/assets/simple_translate/lang/zh_cn.json" "screen.simple_translate.hud.wynn_overlay" "zh independent Wynn overlay setting"
Assert-FileContains "$res/assets/simple_translate/lang/en_us.json" "screen.simple_translate.hud.wynn_overlay" "en independent Wynn overlay setting"
Assert-FileContains "$res/assets/simple_translate/lang/zh_cn.json" '"screen.simple_translate.gui_translation": "当前界面所有内容翻译"' "current-screen translation wording"
Assert-FileContains "$src/cache/SharedCacheClient.java" "cache.save();" "shared-cache import must schedule persistence off the render tick"
Assert-FileContains "$res/simple_translate.mixins.json" "GuiGraphicsTranslationMixin" "GUI draw mixin registered"
Assert-FileContains "$res/simple_translate.mixins.json" "TitleOverlayMixin" "HUD mixin registered"
Assert-FileNotContains "$res/simple_translate.mixins.json" "ItemStackMixin" "early item tooltip replacement mixin must be retired"
Assert-PathMissing "$src/feature/advancement/AdvancementTranslationHelper.java" "dedicated advancement translation path must be retired"
Assert-FileContains "$src/compat/IcebergTooltipGatherCompat.java" "RenderTooltipEvents" "Iceberg gather compat must use the official public event"
Assert-FileContains "$src/compat/IcebergTooltipGatherCompat.java" "translateGatheredTooltipLines" "Iceberg gather compat must translate through the shared semantic projection pipeline"
Assert-FileNotContains "$src/mixin/SimpleTranslateMixinPlugin.java" "AdvancementPlaques" "retired advancement plaque integration residue"

Assert-FileContains "$src/feature/chat/ChatAutoTranslationFilter.java" "TranslationTextDetector.normalizeForDetection" "AUTO chat classification must normalize fullwidth Latin text"
Assert-FileContains "$src/feature/chat/ChatContextHelper.java" "return isKnownPlayerName(leading);" "multi-word colon prefixes must require an actually online leading player"
Assert-FileContains "$src/feature/chat/ChatAutoTranslationFilter.java" "Stats.from(body).wordCount > 0" "ambiguous colon prefixes must not strip every translatable word"

$ocr = Get-ChildItem $src -Recurse -Filter *.java | Select-String -Pattern "\bOCR\b|ocrEnabled|OcrFeature" | Where-Object { $_.Line -notmatch '^\s*//' }
if ($ocr) { throw "OCR residue: $($ocr[0].Path):$($ocr[0].LineNumber)" }

$guiGraphicsHits = Get-ChildItem $src -Recurse -Filter *.java | Select-String -CaseSensitive -Pattern "\bGuiGraphics\b(?!Extractor)"
if ($guiGraphicsHits) { throw "GuiGraphics residue remains: $($guiGraphicsHits[0])" }

& "$env:JAVA_HOME\bin\java.exe" "-Dorg.gradle.appname=gradlew" `
  -classpath "gradle/wrapper/gradle-wrapper.jar" `
  org.gradle.wrapper.GradleWrapperMain compileJava --no-daemon
if ($LASTEXITCODE -ne 0) { throw "compileJava failed" }

Write-Host "NeoForge 26.1.1 logic checks PASSED"
