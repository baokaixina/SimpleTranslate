param([Parameter(Mandatory = $true)][string]$ProjectDir)
$ErrorActionPreference = "Stop"
$project = (Resolve-Path -LiteralPath $ProjectDir).Path
. (Join-Path $PSScriptRoot "resolve-java.ps1")
Use-SimpleTranslateProjectJava -ProjectDir $project -Purpose Gradle | Out-Null
Push-Location $project
try {
    & .\gradlew.bat validatePortLogic --offline --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "validatePortLogic failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
