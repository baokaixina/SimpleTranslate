param(
    [string]$ProjectDir = (Resolve-Path "$PSScriptRoot\..").Path
)

$ErrorActionPreference = "Stop"

function Assert-FileContains([string]$Path, [string]$Needle, [string]$Message) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Message (missing file: $Path)"
    }
    $content = Get-Content -Raw -LiteralPath $Path
    if (-not $content.Contains($Needle)) {
        throw "$Message (missing: $Needle)"
    }
}

function Assert-FileNotContains([string]$Path, [string]$Needle, [string]$Message) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $content = Get-Content -Raw -LiteralPath $Path
    if ($content.Contains($Needle)) {
        throw "$Message (unexpected: $Needle)"
    }
}

function Assert-PathMissing([string]$Path, [string]$Message) {
    if (Test-Path -LiteralPath $Path) {
        throw "$Message (still exists: $Path)"
    }
}

Push-Location $ProjectDir
try {
    & .\gradlew.bat compileJava --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "compileJava failed with exit code $LASTEXITCODE"
    }

    $src = Join-Path $ProjectDir "src/main/java/com/yourname/simpletranslate"
    $core = Join-Path $src "core"
    $config = Join-Path $src "config/ModConfig.java"
    $direct = Join-Path $core "DirectSurfaceTranslator.java"
    $jsonPipeline = Join-Path $core "JsonPassthroughPipeline.java"
    $cacheKeys = Join-Path $core "TranslationCacheKeys.java"
    $cache = Join-Path $src "cache/TranslationCache.java"
    $cacheEditor = Join-Path $src "cache/ComponentJsonCacheEditor.java"
    $cacheScreen = Join-Path $src "gui/CacheEditScreen.java"
    $service = Join-Path $src "transport/DeepSeekTranslationService.java"
    $jsonPrompt = Join-Path $src "transport/JsonPassthroughPrompts.java"
    $sign = Join-Path $src "feature/sign/SignJsonDocument.java"
    $signHelper = Join-Path $src "feature/sign/SignTranslationHelper.java"
    $signLayout = Join-Path $src "feature/sign/SignLayoutEngine.java"
    $signTextMixin = Join-Path $src "mixin/SignTextMixin.java"
    $signRendererMixin = Join-Path $src "mixin/SignRendererMixin.java"
    $translationResult = Join-Path $src "api/TranslationResult.java"
    $chatAuto = Join-Path $src "feature/chat/ChatAutoTranslator.java"
    $chatBatch = Join-Path $src "feature/chat/ChatContextBatchTranslator.java"

    Assert-FileContains $direct "JsonPassthroughPipeline.translateComponents(" "single facade must use component JSON"
    Assert-FileContains $direct "JsonPassthroughPipeline.translateComponentsAsync(" "async facade must use component JSON"
    Assert-FileContains $direct "List.of(source)" "single components and raw text must be wrapped as JSON arrays"
    Assert-FileNotContains $direct "DirectFormattedTranslationPipeline" "surface facade must not reference the old pipeline"

    Assert-FileContains $jsonPipeline "array.size() != originals.size()" "JSON responses must keep top-level count"
    Assert-FileContains $jsonPipeline "Component.Serializer.fromJson" "JSON responses must parse as Components"
    Assert-FileNotContains $jsonPipeline "structureFingerprint" "JSON pipeline must remain intentionally unlocked"
    Assert-FileContains $jsonPipeline "class JsonBatcher" "JSON micro-batching must be present"
    Assert-FileContains $jsonPipeline "retrying {} item(s) individually" "invalid micro-batches must retry as JSON singles"
    Assert-FileContains $jsonPipeline "Surface.directBatchCandidate" "only approved high-frequency surfaces are micro-batched"
    Assert-FileContains $jsonPipeline "stripHoverEvents(element)" "ordinary JSON requests must strip hidden hover payloads"
    Assert-FileContains $jsonPipeline "reattachOriginalHoverEvents(restored, originals)" "translated visible components must regain original hover events"
    Assert-FileContains $jsonPipeline "canUseContextlessLegacyCache" "contextual JSON requests must not reuse contextless legacy cache"
    Assert-FileContains $jsonPipeline "normalizeComponentJson" "extra-only model components must gain a compatible empty text root"

    Assert-FileContains $cacheKeys 'COMPONENT_JSON_FORMAT = "component_json_v1"' "cache keys must identify component JSON"
    Assert-FileContains $cacheKeys "legacyComponentJsonKey" "existing JSON cache keys must migrate lazily"
    Assert-FileContains $cache "putComponentJson" "cache must persist source and translated JSON"
    Assert-FileContains $cache "isSupportedComponentJsonKey" "legacy wire cache entries must stay inactive"
    Assert-FileContains $cacheEditor 'object.get("text")' "cache editor must enumerate JSON text nodes"
    Assert-FileContains $cacheEditor "replaceTextNodes" "cache editor must rewrite JSON text nodes"
    Assert-FileContains $cacheScreen "decodeEditorText" "cache screen must validate text-node count"
    Assert-FileNotContains $cacheScreen "StyledComponentSnapshot" "cache editor must not project guessed styles"

    Assert-FileContains $service 'new TranslationResult.Failed("component-json-required")' "transport must reject non-JSON translation payloads"
    Assert-FileContains $service "JsonPassthroughPrompts.buildSystemPrompt" "transport must use the JSON prompt"
    Assert-FileContains $jsonPrompt "Hidden hover events are" "JSON prompt docs must document local hover restoration"
    Assert-FileNotContains $jsonPrompt '\"hoverEvent\", \"insertion\"' "model prompt must not ask ordinary requests to translate hoverEvent payloads"
    Assert-FileContains $sign "restoreComponents" "sign groups must map translated JSON components positionally"
    Assert-FileContains $signLayout "findFourRowWrapWidth" "sign translations must reflow into four render rows"
    Assert-FileContains $signLayout "maxTextLineWidth / (float) widestLine" "overflowing sign text must scale to fit"
    Assert-FileContains $signTextMixin "data.renderLines" "sign text mixin must use the precomputed layout"
    Assert-FileContains $signRendererMixin "poseStack.scale(scale, scale, scale)" "sign renderer must apply scoped text scaling"
    Assert-FileContains $signHelper "Arrays.equals(existing.translatedComponents, componentCopy)" "unchanged sign layouts must be reused"
    Assert-FileContains $sign "fromCompactEntries" "manual sign panels must use one Component JSON entry per sign"
    Assert-FileContains $signHelper "partitionManualPanels" "manual selections must translate once per physical panel"
    Assert-FileContains $signHelper "Translate the panel as one continuous semantic document" "manual panel prompts must share one semantic context"
    Assert-FileNotContains $signHelper "retryManualChunkAsSingles" "manual panels must not fall back to context-divergent single-sign requests"
    Assert-FileNotContains $signHelper 'append(" selectionIndex=")' "ephemeral selection indexes must not affect prompt or cache context"
    Assert-FileNotContains $signHelper "semantic-residual-or-empty" "sign JSON must not be rejected by semantic heuristics"
    Assert-FileNotContains $signHelper "render-overflow" "sign JSON must not be rejected before render-time layout"
    Assert-FileNotContains $signHelper "ManualCandidateOutcome" "strict manual candidate rejection flow must be removed"
    Assert-FileNotContains $translationResult "Degraded" "translation result must not expose old degraded/plain fallback"
    Assert-FileNotContains $chatAuto "fallbackBatchToDirect" "chat auto translator must not fall back to single-message old flow"
    Assert-FileNotContains $chatAuto "isUsableAutoTranslation" "chat auto translator must not reject JSON output by semantic heuristics"
    Assert-FileNotContains $chatBatch "markFailedOrFallbackLocked" "chat batch translator must consume failed JSON entries instead of fallback"
    Assert-FileNotContains $chatBatch "PendingEntry" "old chat pending-entry batch queue must be removed"
    Assert-FileNotContains $chatBatch "BatchSnapshot" "old chat batch snapshot scheduler must be removed"
    Assert-FileNotContains $direct "translateTextAsync" "plain text wrapper must be removed from the JSON facade"
    Assert-FileNotContains $direct "getCachedText" "plain text cache wrapper must be removed from the JSON facade"

    Assert-FileNotContains $config "JSON_PASSTHROUGH_CHAT" "chat JSON toggle must be removed"
    Assert-FileNotContains $config "JSON_PASSTHROUGH_ITEM_TOOLTIP" "item JSON toggle must be removed"
    Assert-FileNotContains $config "JSON_PASSTHROUGH_CHAT_HOVER" "hover JSON toggle must be removed"
    Assert-FileNotContains $config "TRANSLATION_STYLE_PROMPT" "style prompt setting must be removed"

    $removed = @(
        "DirectFormattedTranslationPipeline.java",
        "TranslationDocument.java",
        "StyleRestorer.java",
        "StyleRestore.java",
        "RestoreOutcome.java",
        "StyledComponentSnapshot.java",
        "WireCodec.java",
        "ChatBatchTextRepair.java",
        "DocumentChecks.java",
        "EditableText.java",
        "NumberGuard.java",
        "StyleSignatures.java",
        "Placeholder.java"
    )
    foreach ($file in $removed) {
        Assert-PathMissing (Join-Path $core $file) "old formatted-restore class must be deleted"
    }
    Assert-PathMissing (Join-Path $src "feature/sign/SignDirectDocument.java") "old sign document must be deleted"
    Assert-PathMissing (Join-Path $src "transport/TranslationPrompts.java") "old numbered-wire prompt must be deleted"
    Assert-PathMissing (Join-Path $src "feature/chat/ChatLogicalBlockDetector.java") "old logical chat block detector must be deleted"

    $javaFiles = Get-ChildItem -LiteralPath (Join-Path $ProjectDir "src/main/java") -Recurse -File -Filter *.java
    $oldReferences = $javaFiles | Select-String -Pattern "DirectFormattedTranslationPipeline|TranslationDocument|StyleRestorer|StyledComponentSnapshot|WireCodec|SignDirectDocument"
    if ($oldReferences) {
        throw "old translation architecture references remain: $($oldReferences[0].Path):$($oldReferences[0].LineNumber)"
    }
    $compatGuiGraphics = Join-Path $src "compat/GuiGraphics.java"
    $textDisplayMixin = Join-Path $src "mixin/TextDisplayMixin.java"
    $expectedJavaFiles = 127
    if (Test-Path -LiteralPath $compatGuiGraphics) {
        $expectedJavaFiles++
    }
    if (-not (Test-Path -LiteralPath $textDisplayMixin)) {
        $expectedJavaFiles--
    }
    if ($javaFiles.Count -ne $expectedJavaFiles) {
        throw "unexpected Java file count after old pipeline removal: $($javaFiles.Count)"
    }

    $utf8 = [System.Text.Encoding]::UTF8
    [System.IO.File]::ReadAllText(
        (Join-Path $ProjectDir "src/main/resources/assets/simple_translate/lang/en_us.json"), $utf8
    ) | ConvertFrom-Json | Out-Null
    [System.IO.File]::ReadAllText(
        (Join-Path $ProjectDir "src/main/resources/assets/simple_translate/lang/zh_cn.json"), $utf8
    ) | ConvertFrom-Json | Out-Null

    Write-Host "SimpleTranslate component JSON logic checks passed"
}
finally {
    Pop-Location
}
