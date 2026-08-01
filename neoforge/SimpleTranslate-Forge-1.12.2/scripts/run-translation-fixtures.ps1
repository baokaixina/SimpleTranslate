param([Parameter(Mandatory = $true)][string]$ProjectDir)
$ErrorActionPreference = "Stop"
$project = (Resolve-Path -LiteralPath $ProjectDir).Path
$fixturePath = Join-Path $PSScriptRoot "translation-fixtures.json"
$fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
$required = @(
    "chat.context.batch.direct", "gui.component.visible_frame.v3",
    "hover.context.direct", "book.page.direct",
    "sign.manual.group.by_id.direct", "hud.actionbar.component.direct"
)
$surfaces = @($fixture.fixtures | ForEach-Object { [string]$_.surface })
foreach ($surface in $required) {
    if ($surfaces -notcontains $surface) { throw "Missing 1.12.2 fixture surface: $surface" }
}
. (Join-Path $PSScriptRoot "resolve-java.ps1")
Use-SimpleTranslateProjectJava -ProjectDir $project -Purpose Gradle | Out-Null
Push-Location $project
try {
    & .\gradlew.bat validatePortLogic --offline --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "translation fixture validation failed with exit code $LASTEXITCODE" }
    Write-Output "TRANSLATION_FIXTURES_OK count=$($fixture.fixtures.Count)"
} finally { Pop-Location }
