param(
    [string]$WorkspaceRoot = "",
    [string]$TestRoot = "",
    [ValidateSet("Fabric", "NeoForge", "Forge", "All")]
    [string]$Loader = "All",
    [switch]$SkipExisting
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Join-Path "D:\mc" ([string]([char]0x7B80) + [string]([char]0x5355) + [string]([char]0x7FFB) + [string]([char]0x8BD1))
}
if ([string]::IsNullOrWhiteSpace($TestRoot)) {
    $TestRoot = Join-Path "D:\mc" ([string]([char]0x6A21) + [string]([char]0x7EC4) + [string]([char]0x6D4B) + [string]([char]0x8BD5))
}

function Read-GradleProperties {
    param([string]$ProjectDir)
    $props = @{}
    $file = Join-Path $ProjectDir "gradle.properties"
    if (-not (Test-Path -LiteralPath $file)) {
        return $props
    }
    Get-Content -LiteralPath $file | ForEach-Object {
        $line = ([string]$_).Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#")) {
            return
        }
        $idx = $line.IndexOf("=")
        if ($idx -le 0) {
            return
        }
        $props[$line.Substring(0, $idx).Trim()] = $line.Substring($idx + 1).Trim()
    }
    return $props
}

function Ensure-Dir {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function Write-LauncherFiles {
    param([string]$InstanceRoot, [string]$VersionId)
    $launcher = @{
        profiles = @{
            PCL = @{
                icon = "Grass"
                name = "PCL"
                lastVersionId = "latest-release"
                type = "latest-release"
                lastUsed = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.ffffZ")
            }
        }
        selectedProfile = "PCL"
        clientToken = "23323323323323323323323323323333"
    }
    $launcher | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $InstanceRoot "launcher_profiles.json") -Encoding UTF8
    @(
        ("InstanceCache:{0}" -f (Get-Random -Minimum 10000000 -Maximum 2147483647))
        "CardCount:1"
        "CardKey1:2"
        "CardValue1:$VersionId`:"
        "Version:$VersionId"
    ) | Set-Content -LiteralPath (Join-Path $InstanceRoot "PCL.ini") -Encoding UTF8
}

function Copy-TreeIfPresent {
    param([string]$Source, [string]$Destination)
    if (-not (Test-Path -LiteralPath $Source)) {
        return
    }
    Ensure-Dir $Destination
    & robocopy.exe $Source $Destination /E /NFL /NDL /NJH /NJS /NC /NS /NP | Out-Null
    if ($LASTEXITCODE -le 7) {
        $global:LASTEXITCODE = 0
        return
    }
    throw "robocopy failed from $Source to $Destination with exit code $LASTEXITCODE"
}

function Seed-InstanceData {
    param([string]$InstanceRoot, [string]$MinecraftVersion)

    $seedRoots = New-Object System.Collections.Generic.List[string]
    if ($MinecraftVersion -match '^1\.21') {
        $seedRoots.Add((Join-Path $TestRoot "neoforge\1.21.10"))
    }
    $seedRoots.Add((Join-Path $TestRoot "fabric\1.20.1fabric"))
    $seedRoots.Add((Join-Path $TestRoot "fabric\1.19.2fabric"))

    foreach ($seed in $seedRoots) {
        if ($seed -eq $InstanceRoot) {
            continue
        }
        if (Test-Path -LiteralPath $seed) {
            Copy-TreeIfPresent -Source (Join-Path $seed "assets") -Destination (Join-Path $InstanceRoot "assets")
            Copy-TreeIfPresent -Source (Join-Path $seed "libraries") -Destination (Join-Path $InstanceRoot "libraries")
        }
    }
}

function Get-MavenPath {
    param([string]$Coordinate, [string]$Extension = "jar")
    $parts = $Coordinate.Split(":")
    if ($parts.Count -lt 3) {
        throw "Invalid Maven coordinate: $Coordinate"
    }
    $groupPath = $parts[0].Replace(".", "/")
    $artifact = $parts[1]
    $version = $parts[2]
    $classifier = if ($parts.Count -ge 4) { "-" + $parts[3] } else { "" }
    return "$groupPath/$artifact/$version/$artifact-$version$classifier.$Extension"
}

function Download-FileIfNeeded {
    param(
        [string]$Url,
        [string]$Destination,
        [string]$Sha1 = ""
    )
    Ensure-Dir (Split-Path -Parent $Destination)
    if (Test-Path -LiteralPath $Destination) {
        if ([string]::IsNullOrWhiteSpace($Sha1)) {
            return
        }
        $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA1).Hash.ToLowerInvariant()
        if ($actual -eq $Sha1.ToLowerInvariant()) {
            return
        }
    }
    $lastError = $null
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            Invoke-WebRequest -Uri $Url -OutFile $Destination
            if ([string]::IsNullOrWhiteSpace($Sha1)) {
                return
            }
            $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA1).Hash.ToLowerInvariant()
            if ($actual -eq $Sha1.ToLowerInvariant()) {
                return
            }
            throw "SHA1 mismatch for $Destination"
        } catch {
            $lastError = $_
            Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds ([Math]::Min(2 * $attempt, 8))
        }
    }
    throw $lastError
}

function Invoke-JsonWithRetry {
    param([string]$Uri)
    $lastError = $null
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            return Invoke-RestMethod -Uri $Uri
        } catch {
            $lastError = $_
            Start-Sleep -Seconds ([Math]::Min(2 * $attempt, 10))
        }
    }
    throw $lastError
}

function Get-MojangVersionJson {
    param([string]$MinecraftVersion)
    $manifest = Invoke-JsonWithRetry -Uri "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    $entry = $manifest.versions | Where-Object { $_.id -eq $MinecraftVersion } | Select-Object -First 1
    if ($null -eq $entry) {
        throw "Minecraft version not found in Mojang manifest: $MinecraftVersion"
    }
    return Invoke-JsonWithRetry -Uri $entry.url
}

function Test-LibraryAllowedForWindows {
    param($Library)
    if ($null -eq $Library.rules) {
        return $true
    }
    $allowed = $false
    foreach ($rule in @($Library.rules)) {
        $osName = $null
        if ($rule.os -and $rule.os.name) {
            $osName = [string]$rule.os.name
        }
        if ($null -eq $osName -or $osName -eq "windows") {
            $allowed = ([string]$rule.action -eq "allow")
        }
    }
    return $allowed
}

function Download-MojangClient {
    param($VersionJson, [string]$InstanceRoot, [string]$VersionId)
    $versionDir = Join-Path $InstanceRoot "versions\$VersionId"
    Ensure-Dir $versionDir
    Download-FileIfNeeded -Url $VersionJson.downloads.client.url -Destination (Join-Path $versionDir "$VersionId.jar") -Sha1 $VersionJson.downloads.client.sha1

    $librariesRoot = Join-Path $InstanceRoot "libraries"
    foreach ($lib in @($VersionJson.libraries)) {
        if (-not (Test-LibraryAllowedForWindows -Library $lib)) {
            continue
        }
        if ($lib.downloads -and $lib.downloads.artifact -and $lib.downloads.artifact.url) {
            Download-FileIfNeeded -Url $lib.downloads.artifact.url -Destination (Join-Path $librariesRoot $lib.downloads.artifact.path) -Sha1 $lib.downloads.artifact.sha1
        }
        if ($lib.downloads -and $lib.downloads.classifiers) {
            foreach ($prop in $lib.downloads.classifiers.PSObject.Properties) {
                if ($prop.Name -match "natives-windows") {
                    $native = $prop.Value
                    if ($native.url -and $native.path) {
                        Download-FileIfNeeded -Url $native.url -Destination (Join-Path $librariesRoot $native.path) -Sha1 $native.sha1
                    }
                }
            }
        }
    }

    $assetIndexPath = Join-Path $InstanceRoot ("assets\indexes\{0}.json" -f $VersionJson.assetIndex.id)
    Download-FileIfNeeded -Url $VersionJson.assetIndex.url -Destination $assetIndexPath -Sha1 $VersionJson.assetIndex.sha1
    $assetIndex = Get-Content -LiteralPath $assetIndexPath -Raw | ConvertFrom-Json
    $objectsRoot = Join-Path $InstanceRoot "assets\objects"
    foreach ($prop in $assetIndex.objects.PSObject.Properties) {
        $hash = [string]$prop.Value.hash
        if ([string]::IsNullOrWhiteSpace($hash) -or $hash.Length -lt 2) {
            continue
        }
        $dest = Join-Path $objectsRoot (Join-Path $hash.Substring(0, 2) $hash)
        if (-not (Test-Path -LiteralPath $dest)) {
            Download-FileIfNeeded -Url "https://resources.download.minecraft.net/$($hash.Substring(0, 2))/$hash" -Destination $dest
        }
    }
}

function Download-FabricLibrary {
    param($Library, [string]$LibrariesRoot)
    if (-not $Library.name) {
        return
    }
    $urlBase = if ($Library.url) { [string]$Library.url } else { "https://maven.fabricmc.net/" }
    if (-not $urlBase.EndsWith("/")) {
        $urlBase += "/"
    }
    $path = Get-MavenPath -Coordinate ([string]$Library.name)
    $dest = Join-Path $LibrariesRoot ($path -replace "/", "\")
    Download-FileIfNeeded -Url ($urlBase + $path) -Destination $dest
}

function Install-FabricClient {
    param([string]$ProjectDir)
    $props = Read-GradleProperties -ProjectDir $ProjectDir
    $mc = $props["minecraft_version"]
    $loaderVersion = $props["loader_version"]
    $fabricApi = $props["fabric_version"]
    if (-not $mc -or -not $loaderVersion) {
        throw "Missing minecraft_version or loader_version in $ProjectDir"
    }

    $instanceRoot = Join-Path $TestRoot "fabric\${mc}fabric"
    $versionId = "$mc-Fabric_$loaderVersion"
    $versionDir = Join-Path $instanceRoot "versions\$versionId"
    if ($SkipExisting -and (Test-Path -LiteralPath (Join-Path $versionDir "$versionId.json"))) {
        Write-Output "SKIP Fabric $mc ($versionId)"
        return
    }

    Write-Output "Installing Fabric $mc loader $loaderVersion -> $instanceRoot"
    Ensure-Dir $instanceRoot
    Seed-InstanceData -InstanceRoot $instanceRoot -MinecraftVersion $mc
    Write-LauncherFiles -InstanceRoot $instanceRoot -VersionId $versionId
    $mojang = Get-MojangVersionJson -MinecraftVersion $mc
    Download-MojangClient -VersionJson $mojang -InstanceRoot $instanceRoot -VersionId $versionId

    $fabricProfile = Invoke-JsonWithRetry -Uri "https://meta.fabricmc.net/v2/versions/loader/$mc/$loaderVersion/profile/json"
    foreach ($lib in @($fabricProfile.libraries)) {
        Download-FabricLibrary -Library $lib -LibrariesRoot (Join-Path $instanceRoot "libraries")
    }

    $merged = $mojang | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $merged.id = $versionId
    $merged.mainClass = $fabricProfile.mainClass
    $merged.type = "release"
    $libraries = @()
    $libraries += @($mojang.libraries)
    $libraries += @($fabricProfile.libraries)
    $merged.libraries = $libraries
    $merged | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $versionDir "$versionId.json") -Encoding UTF8

    $modsDir = Join-Path $versionDir "mods"
    Ensure-Dir $modsDir
    if ($fabricApi) {
        $apiPath = Get-MavenPath -Coordinate "net.fabricmc.fabric-api:fabric-api:$fabricApi"
        Download-FileIfNeeded -Url ("https://maven.fabricmc.net/" + $apiPath) -Destination (Join-Path $modsDir ($apiPath.Split("/")[-1]))
    }
    if (-not (Test-Path -LiteralPath (Join-Path $versionDir "options.txt"))) {
        "fullscreen:false" | Set-Content -LiteralPath (Join-Path $versionDir "options.txt") -Encoding UTF8
    }
}

function Get-InstallerUrl {
    param([string]$LoaderName, [string]$MinecraftVersion, [string]$LoaderVersion)
    if ($LoaderName -eq "NeoForge") {
        return "https://maven.neoforged.net/releases/net/neoforged/neoforge/$LoaderVersion/neoforge-$LoaderVersion-installer.jar"
    }
    if ($LoaderName -eq "Forge") {
        $artifactVersion = "$MinecraftVersion-$LoaderVersion"
        return "https://maven.minecraftforge.net/net/minecraftforge/forge/$artifactVersion/forge-$artifactVersion-installer.jar"
    }
    throw "Unknown installer loader: $LoaderName"
}

function Install-InstallerClient {
    param([string]$ProjectDir, [string]$LoaderName, [string]$MinecraftVersion, [string]$LoaderVersion, [string]$InstanceRoot, [string]$PreferredVersionId)

    $versionsRoot = Join-Path $InstanceRoot "versions"
    if ($SkipExisting -and (Test-Path -LiteralPath $versionsRoot) -and (Get-ChildItem -LiteralPath $versionsRoot -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne "libraries" })) {
        Write-Output "SKIP $LoaderName $MinecraftVersion"
        return
    }

    Write-Output "Installing $LoaderName $MinecraftVersion loader $LoaderVersion -> $InstanceRoot"
    Ensure-Dir $InstanceRoot
    Write-LauncherFiles -InstanceRoot $InstanceRoot -VersionId $PreferredVersionId
    $installerUrl = Get-InstallerUrl -LoaderName $LoaderName -MinecraftVersion $MinecraftVersion -LoaderVersion $LoaderVersion
    $installerPath = Join-Path $env:TEMP ([IO.Path]::GetFileName($installerUrl))
    Download-FileIfNeeded -Url $installerUrl -Destination $installerPath

    $java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        Join-Path $env:JAVA_HOME "bin\java.exe"
    } else {
        "java.exe"
    }
    & $java -jar $installerPath --installClient $InstanceRoot
    if ($LASTEXITCODE -ne 0) {
        throw "$LoaderName installer failed for $MinecraftVersion $LoaderVersion"
    }

    $installed = Get-ChildItem -LiteralPath (Join-Path $InstanceRoot "versions") -Directory |
        Where-Object { $_.Name -match [regex]::Escape($LoaderVersion) -or $_.Name -match $LoaderName.ToLowerInvariant() -or $_.Name -match $MinecraftVersion } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($installed) {
        Write-LauncherFiles -InstanceRoot $InstanceRoot -VersionId $installed.Name
    }
}

if ($Loader -eq "Fabric" -or $Loader -eq "All") {
    Get-ChildItem -LiteralPath (Join-Path $WorkspaceRoot "fabric") -Directory |
        Where-Object { $_.Name -like "SimpleTranslate-Fabric-*" } |
        Sort-Object Name |
        ForEach-Object { Install-FabricClient -ProjectDir $_.FullName }
}

if ($Loader -eq "NeoForge" -or $Loader -eq "All") {
    Get-ChildItem -LiteralPath (Join-Path $WorkspaceRoot "neoforge") -Directory |
        Where-Object { $_.Name -like "SimpleTranslate-NeoForge-*" } |
        Sort-Object Name |
        ForEach-Object {
            $props = Read-GradleProperties -ProjectDir $_.FullName
            $mc = $props["minecraft_version"]
            $neo = $props["neo_version"]
            Install-InstallerClient -ProjectDir $_.FullName -LoaderName "NeoForge" -MinecraftVersion $mc -LoaderVersion $neo -InstanceRoot (Join-Path $TestRoot "neoforge\$mc") -PreferredVersionId "$mc-NeoForge_$neo"
        }
}

if ($Loader -eq "Forge" -or $Loader -eq "All") {
    Get-ChildItem -LiteralPath (Join-Path $WorkspaceRoot "neoforge") -Directory |
        Where-Object { $_.Name -like "SimpleTranslate-Forge-*" -or $_.Name -like "MDK-Forge-*" } |
        Sort-Object Name |
        ForEach-Object {
            $props = Read-GradleProperties -ProjectDir $_.FullName
            $mc = $props["minecraft_version"]
            $forge = $props["forge_version"]
            if ($mc -and $forge) {
                Install-InstallerClient -ProjectDir $_.FullName -LoaderName "Forge" -MinecraftVersion $mc -LoaderVersion $forge -InstanceRoot (Join-Path $TestRoot "forge\${mc}forge") -PreferredVersionId "$mc-Forge_$forge"
            }
        }
}

Write-Output "Done."
