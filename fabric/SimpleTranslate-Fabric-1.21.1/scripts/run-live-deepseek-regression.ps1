param(
    [string]$ProjectDir = (Resolve-Path "$PSScriptRoot\..").Path,
    [string]$FixturePath = "",
    [string]$ApiKey = "",
    [string]$ApiUrl = "",
    [string]$Model = "",
    [int]$MaxAttempts = 3
)

$ErrorActionPreference = "Stop"

function Get-ConfigValue {
    param([string]$ProjectDir, [string]$Key)

    $candidates = @(
        (Join-Path $ProjectDir "run\config\simple_translate\simple_translate-client.json"),
        (Join-Path $ProjectDir "run\config\simple_translate-client.json"),
        (Join-Path $ProjectDir "config\simple_translate-client.json")
    )

    foreach ($candidate in $candidates) {
        if (-not (Test-Path -LiteralPath $candidate)) {
            continue
        }
        try {
            $json = Get-Content -Raw -LiteralPath $candidate | ConvertFrom-Json
            $property = $json.PSObject.Properties[$Key]
            if ($property -and $null -ne $property.Value -and -not [string]::IsNullOrWhiteSpace([string]$property.Value)) {
                return [string]$property.Value
            }
        } catch {
            # Ignore malformed local config while resolving optional live-test settings.
        }
    }
    return ""
}

function Get-DirectPayload {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return ""
    }
    $start = $Text.IndexOf("<st-doc")
    $end = $Text.LastIndexOf("</st-doc>")
    if ($start -lt 0 -or $end -lt $start) {
        return ""
    }
    return $Text.Substring($start, $end + "</st-doc>".Length - $start).Trim()
}

function Get-TagAttributes {
    param([string]$Document, [string]$TagName)
    $pattern = '<' + [regex]::Escape($TagName) + '\s+([^>]*)>.*?</' + [regex]::Escape($TagName) + '>'
    $matches = [regex]::Matches($Document, $pattern, 'Singleline')
    $items = New-Object System.Collections.Generic.List[string]
    foreach ($match in $matches) {
        $attrs = $match.Groups[1].Value
        $id = [regex]::Match($attrs, 'id="([^"]*)"').Groups[1].Value
        $items.Add("id=$id")
    }
    return $items
}

function Assert-DirectDocument {
    param([string]$FixtureName, [string]$Original, [string]$Translated)

    $originalPayload = Get-DirectPayload -Text $Original
    if ([string]::IsNullOrWhiteSpace($originalPayload)) {
        throw "${FixtureName}: original fixture did not contain a direct <st-doc> payload"
    }
    $payload = Get-DirectPayload -Text $Translated
    if ([string]::IsNullOrWhiteSpace($payload)) {
        throw "${FixtureName}: response did not contain a direct <st-doc> payload"
    }
    if ($payload -match '@@@CTX|@@@S|@@@F|```') {
        throw "${FixtureName}: response contains old marker or markdown residue"
    }
    $originalLines = [regex]::Matches($originalPayload, '<line\s+[^>]*>', 'Singleline')
    $translatedLines = [regex]::Matches($payload, '<line\s+[^>]*>', 'Singleline')
    if ($originalLines.Count -ne $translatedLines.Count) {
        throw "${FixtureName}: line count changed from $($originalLines.Count) to $($translatedLines.Count)"
    }
    if ($originalPayload.Contains('mode="free"')) {
        $originalGroups = @(Get-TagAttributes -Document $originalPayload -TagName "g" | Sort-Object)
        $translatedGroups = @(Get-TagAttributes -Document $payload -TagName "g" | Sort-Object)
        if ($originalGroups.Count -ne $translatedGroups.Count) {
            throw "${FixtureName}: group count changed from $($originalGroups.Count) to $($translatedGroups.Count)"
        }
        for ($i = 0; $i -lt $originalGroups.Count; $i++) {
            if ($originalGroups[$i] -ne $translatedGroups[$i]) {
                throw "${FixtureName}: group attributes changed at sorted index $i; expected <$($originalGroups[$i])> but got <$($translatedGroups[$i])>"
            }
        }
    } else {
        $originalRuns = Get-TagAttributes -Document $originalPayload -TagName "run"
        $translatedRuns = Get-TagAttributes -Document $payload -TagName "run"
        if ($originalRuns.Count -ne $translatedRuns.Count) {
            throw "${FixtureName}: run count changed from $($originalRuns.Count) to $($translatedRuns.Count)"
        }
        for ($i = 0; $i -lt $originalRuns.Count; $i++) {
            if ($originalRuns[$i] -ne $translatedRuns[$i]) {
                throw "${FixtureName}: run attributes changed at index $i; expected <$($originalRuns[$i])> but got <$($translatedRuns[$i])>"
            }
        }
    }
    return $payload
}

function Invoke-DirectFixture {
    param(
        [string]$FixtureName,
        [string]$Document,
        [string]$ApiUrl,
        [string]$ApiKey,
        [string]$Model,
        [int]$MaxAttempts
    )

    $systemPrompt = "You translate SimpleTranslate direct formatted documents. Use <st-plain-context> only for complete meaning and return only <st-doc>. Translate every visible English word or phrase into Chinese; do not leave English UI labels such as Abbreviations, Hover on their names, Mana, Damage, Ability, Objectives, or Enemies unchanged. For mode=""fixed"", preserve all <run> tags in place. For mode=""free"", translate naturally and you may move <g id=""...""> tags inside the same line; every original <g id> must appear exactly once with unchanged id attributes, and do not add style/editable attributes to <g>. Do not output markdown, JSON, notes, or explanations."
    $body = @{
        model = $Model
        stream = $false
        temperature = 0.1
        messages = @(
            @{ role = "system"; content = $systemPrompt },
            @{ role = "user"; content = $Document }
        )
    } | ConvertTo-Json -Depth 8

    $headers = @{
        "Content-Type" = "application/json"
        "Authorization" = "Bearer $ApiKey"
    }

    $lastError = $null
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            $response = Invoke-RestMethod -Method Post -Uri $ApiUrl -Headers $headers -Body $body -TimeoutSec 90
            $content = [string]$response.choices[0].message.content
            $payload = Assert-DirectDocument -FixtureName $FixtureName -Original $Document -Translated $content
            Write-Host "PASS live direct fixture $FixtureName"
            return $payload
        } catch {
            $lastError = $_.Exception.Message
            Start-Sleep -Seconds ([Math]::Min(2 * $attempt, 6))
        }
    }
    throw "${FixtureName}: live direct regression failed after $MaxAttempts attempt(s): $lastError"
}

Push-Location $ProjectDir
try {
    if ([string]::IsNullOrWhiteSpace($ApiKey)) {
        $ApiKey = $env:SIMPLE_TRANSLATE_API_KEY
    }
    if ([string]::IsNullOrWhiteSpace($ApiKey)) {
        $ApiKey = Get-ConfigValue -ProjectDir $ProjectDir -Key "api.apiKey"
    }
    if ([string]::IsNullOrWhiteSpace($ApiUrl)) {
        $ApiUrl = $env:SIMPLE_TRANSLATE_API_URL
    }
    if ([string]::IsNullOrWhiteSpace($ApiUrl)) {
        $ApiUrl = Get-ConfigValue -ProjectDir $ProjectDir -Key "api.apiUrl"
    }
    if ([string]::IsNullOrWhiteSpace($ApiUrl)) {
        $ApiUrl = "https://api.deepseek.com/chat/completions"
    }
    if ([string]::IsNullOrWhiteSpace($Model)) {
        $Model = $env:SIMPLE_TRANSLATE_MODEL
    }
    if ([string]::IsNullOrWhiteSpace($Model)) {
        $Model = Get-ConfigValue -ProjectDir $ProjectDir -Key "api.model"
    }
    if ([string]::IsNullOrWhiteSpace($Model)) {
        $Model = "deepseek-v4-flash"
    }
    if ([string]::IsNullOrWhiteSpace($ApiKey)) {
        Write-Host "SKIP live DeepSeek regression: API key not configured. Set -ApiKey or SIMPLE_TRANSLATE_API_KEY to run it."
        exit 0
    }
    if ($MaxAttempts -lt 1) {
        $MaxAttempts = 1
    }
    if ($MaxAttempts -gt 5) {
        $MaxAttempts = 5
    }

    $fixtures = @(
        @{
            name = "tooltip-ring"
            doc = '<st-plain-context surface="tooltip.item.direct" role="tooltip" fixed="false"><line i="0">Ring Amulet Slot</line><line i="1">Place a Ring Amulet here to receive its When Worn bonuses and activate its Ability.</line></st-plain-context><st-doc v="direct-v4" mode="free" surface="tooltip.item.direct" role="tooltip" fixed="false"><line i="0" base="color=aqua;bold=true;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=false;hover=false"><g id="r0_0">Ring</g><g id="r0_1"> Amulet Slot</g></line><line i="1" base="color=gray;bold=false;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=false;hover=false"><g id="r1_0">Place a Ring Amulet here to receive its When Worn bonuses and activate its Ability.</g></line></st-doc>'
        },
        @{
            name = "sign-click"
            doc = '<st-plain-context surface="sign.lines.direct" role="sign-lines" fixed="true"><line i="0">Start Game</line><line i="1">From Here</line><line i="2">[Click to TP]</line><line i="3"></line></st-plain-context><st-doc v="direct-v4" mode="fixed" surface="sign.lines.direct" role="sign-lines" fixed="true"><line i="0" base="color=white;bold=false;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=false;hover=false"><run id="r0_0" editable="true" style="color=white;bold=false;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=false;hover=false">Start Game</run></line><line i="1" base="color=white;bold=false;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=false;hover=false"><run id="r1_0" editable="true" style="color=white;bold=false;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=false;hover=false">From Here</run></line><line i="2" base="color=green;bold=false;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=true;hover=false"><run id="r2_0" editable="true" style="color=green;bold=false;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=true;hover=false">[Click to TP]</run></line><line i="3" base="color=;bold=false;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=false;hover=false"></line></st-doc>'
        },
        @{
            name = "scoreboard"
            doc = '<st-plain-context surface="scoreboard.component.direct" role="scoreboard" fixed="true"><line i="0">Score Leaderboard</line><line i="1">JekNJok 391455</line></st-plain-context><st-doc v="direct-v4" mode="fixed" surface="scoreboard.component.direct" role="scoreboard" fixed="true"><line i="0" base="color=white;bold=true;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=false;hover=false"><run id="r0_0" editable="true" style="color=white;bold=true;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=false;hover=false">Score Leaderboard</run></line><line i="1" base="color=green;bold=false;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=false;hover=false"><run id="r1_0" editable="false" style="color=green;bold=false;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=false;hover=false">JekNJok</run><run id="r1_1" editable="false" style="color=red;bold=false;italic=false;underlined=false;strikethrough=false;obfuscated=false;click=false;hover=false"> 391455</run></line></st-doc>'
        }
    )

    foreach ($fixture in $fixtures) {
        Invoke-DirectFixture -FixtureName $fixture.name -Document $fixture.doc -ApiUrl $ApiUrl -ApiKey $ApiKey -Model $Model -MaxAttempts $MaxAttempts | Out-Null
    }
} finally {
    Pop-Location
}

