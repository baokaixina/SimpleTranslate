param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectDir,

    [int]$TimeoutSeconds = 150,

    [int]$ReadyGraceSeconds = 5,

    [string]$TestClientRoot = "",

    [string]$WorldName = "CodexSmokeWorld",

    [switch]$EnterWorld,

    [switch]$NoEnterWorld,

    [switch]$NoCreateWorld,

    [switch]$NoQuickPlay,

    [switch]$AllowInteractiveUi,

    [switch]$PreserveCache,

[ValidateSet("Auto", "Hidden", "Normal", "Minimized")]
[string]$WindowStyle = "Minimized"
)

$ErrorActionPreference = "Stop"

function Get-ChildProcessIds {
    param([int]$ParentPid)

    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$ParentPid" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        [int]$child.ProcessId
        Get-ChildProcessIds -ParentPid ([int]$child.ProcessId)
    }
}

function Stop-ProcessTree {
    param([int]$RootPid)

    $ids = @(Get-ChildProcessIds -ParentPid $RootPid)
    [array]::Reverse($ids)
    foreach ($id in $ids) {
        Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
    }
    Stop-Process -Id $RootPid -Force -ErrorAction SilentlyContinue
    if ($script:activeLocalSmokeServer) {
        Stop-LocalSmokeServer -Server $script:activeLocalSmokeServer
        $script:activeLocalSmokeServer = $null
    }
}

function Write-FileTail {
    param(
        [string]$Path,
        [string]$Label,
        [int]$MaxLines = 120
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return
    }
    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Output "$Label not found: $Path"
        return
    }

    Write-Output "$Label path: $Path"
    Write-Output "$Label tail:"
    try {
        Get-Content -LiteralPath $Path -Tail $MaxLines -ErrorAction Stop |
            ForEach-Object { Write-Output "  $_" }
    } catch {
        Write-Output "  Could not read $Label. $($_.Exception.Message)"
    }
}

function Get-NewCrashReports {
    param(
        [string]$CrashDir,
        [datetime]$StartTime,
        [hashtable]$BeforeCrashes
    )

    if ([string]::IsNullOrWhiteSpace($CrashDir) -or -not (Test-Path -LiteralPath $CrashDir)) {
        return @()
    }
    Get-ChildItem -LiteralPath $CrashDir -File -ErrorAction SilentlyContinue |
        Where-Object {
            $known = $false
            if ($BeforeCrashes) {
                $known = $BeforeCrashes.ContainsKey($_.FullName)
            }
            -not $known -and $_.LastWriteTime -ge $StartTime
        } |
        Sort-Object LastWriteTime -Descending
}

function Write-LaunchDiagnostics {
    param(
        [string]$LatestLog,
        [string]$CrashDir,
        [string]$Stdout,
        [string]$Stderr,
        [datetime]$StartTime,
        [hashtable]$BeforeCrashes
    )

    Write-Output "Launch diagnostics:"
    if ($runClientScreenshots -and $runClientScreenshots.Count -gt 0) {
        Write-Output "Captured Minecraft screenshots:"
        $runClientScreenshots | ForEach-Object { Write-Output "  $_" }
    }
    Write-FileTail -Path $LatestLog -Label "latest.log" -MaxLines 160
    Write-FileTail -Path $Stdout -Label "stdout" -MaxLines 80
    Write-FileTail -Path $Stderr -Label "stderr" -MaxLines 120

    $newCrashes = @(Get-NewCrashReports -CrashDir $CrashDir -StartTime $StartTime -BeforeCrashes $BeforeCrashes)
    if ($newCrashes.Count -eq 0) {
        Write-Output "No new crash reports found in: $CrashDir"
        return
    }

    Write-Output "New crash reports:"
    $newCrashes | Select-Object FullName, LastWriteTime, Length | Format-Table -AutoSize
    Write-FileTail -Path $newCrashes[0].FullName -Label "newest crash report" -MaxLines 180
}

function Test-SimpleTranslateLoaded {
    param(
        [string]$LatestLog,
        [string]$Stdout
    )

    $texts = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($LatestLog) -and (Test-Path -LiteralPath $LatestLog)) {
        $texts.Add((Get-Content -LiteralPath $LatestLog -Raw -ErrorAction SilentlyContinue))
    }
    if (-not [string]::IsNullOrWhiteSpace($Stdout) -and (Test-Path -LiteralPath $Stdout)) {
        $texts.Add((Get-Content -LiteralPath $Stdout -Raw -ErrorAction SilentlyContinue))
    }
    if ($texts.Count -eq 0) {
        return $false
    }

    $joined = [string]::Join("`n", $texts)
    return ($joined -match "(?i)(\bsimple_translate\b|Simple Translate)")
}

function Get-JavaProcessesUsingPath {
    param([string]$Path)

    $normalized = $Path.TrimEnd("\")
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -in @("java.exe", "javaw.exe") -and
            $_.CommandLine -and
            $_.CommandLine.Contains($normalized)
        }
}

function Get-PclMetadataFiles {
    param([string]$InstanceRoot)

    $files = New-Object System.Collections.Generic.List[string]
    $rootPcl = Join-Path $InstanceRoot "PCL.ini"
    $setupPcl = Join-Path $InstanceRoot "PCL\Setup.ini"
    if (Test-Path -LiteralPath $rootPcl) {
        $files.Add($rootPcl)
    }
    if (Test-Path -LiteralPath $setupPcl) {
        $files.Add($setupPcl)
    }
    return $files
}

function Get-PclValue {
    param(
        [string[]]$Files,
        [string]$Key
    )

    foreach ($file in @($Files)) {
        if (-not (Test-Path -LiteralPath $file)) {
            continue
        }
        foreach ($line in Get-Content -LiteralPath $file -ErrorAction SilentlyContinue) {
            $text = [string]$line
            if ($text -match "^\s*$([regex]::Escape($Key))\s*[:=]\s*(.*)$") {
                $value = $Matches[1].Trim()
                if (-not [string]::IsNullOrWhiteSpace($value)) {
                    return $value
                }
            }
        }
    }
    return $null
}

function Get-PclCardVersion {
    param([string]$CardValue)

    if ([string]::IsNullOrWhiteSpace($CardValue)) {
        return $null
    }
    foreach ($part in ([string]$CardValue).Split(":")) {
        $candidate = $part.Trim()
        if (-not [string]::IsNullOrWhiteSpace($candidate)) {
            return $candidate
        }
    }
    return $null
}

function Test-PclVersionExists {
    param(
        [string]$InstanceRoot,
        [string]$VersionId
    )

    if ([string]::IsNullOrWhiteSpace($VersionId)) {
        return $false
    }
    $versionJson = Join-Path $InstanceRoot "versions\$VersionId\$VersionId.json"
    return Test-Path -LiteralPath $versionJson
}

function Get-LatestPclVersionDirectory {
    param([string]$InstanceRoot)

    $versionsRoot = Join-Path $InstanceRoot "versions"
    if (Test-Path -LiteralPath $versionsRoot) {
        $dirs = Get-ChildItem -LiteralPath $versionsRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "$($_.Name).json") } |
            Sort-Object LastWriteTime -Descending
        if ($dirs.Count -gt 0) {
            return $dirs[0].Name
        }
    }
    return $null
}

function Get-PclVersionId {
    param([string]$InstanceRoot)

    $pclFiles = @(Get-PclMetadataFiles -InstanceRoot $InstanceRoot)
    $version = Get-PclValue -Files $pclFiles -Key "Version"
    if (-not [string]::IsNullOrWhiteSpace($version)) {
        if (Test-PclVersionExists -InstanceRoot $InstanceRoot -VersionId $version) {
            return $version
        }
    }

    $cardVersion = Get-PclCardVersion -CardValue (Get-PclValue -Files $pclFiles -Key "CardValue1")
    if (-not [string]::IsNullOrWhiteSpace($cardVersion)) {
        if (Test-PclVersionExists -InstanceRoot $InstanceRoot -VersionId $cardVersion) {
            return $cardVersion
        }
    }

    return Get-LatestPclVersionDirectory -InstanceRoot $InstanceRoot
}

function Get-SingleplayerWorldName {
    param([string]$VersionDir, [string]$PreferredWorldName)

    $savesDir = Join-Path $VersionDir "saves"
    if (-not (Test-Path -LiteralPath $savesDir)) {
        return $null
    }
    $preferred = Join-Path $savesDir $PreferredWorldName
    if (Test-Path -LiteralPath (Join-Path $preferred "level.dat")) {
        return $PreferredWorldName
    }
    $worlds = @(Get-ChildItem -LiteralPath $savesDir -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "level.dat") } |
        Sort-Object LastWriteTime -Descending)
    $world = $worlds |
        Where-Object { $_.Name -match '^[\x20-\x7E]+$' } |
        Select-Object -First 1
    if (-not $world) {
        $world = $worlds | Select-Object -First 1
    }
    if ($world) {
        return $world.Name
    }
    return $null
}

function Ensure-AsciiQuickPlayWorldAlias {
    param(
        [string]$VersionDir,
        [string]$WorldName,
        [string]$PreferredWorldName
    )

    if ([string]::IsNullOrWhiteSpace($WorldName) -or $WorldName -match '^[\x20-\x7E]+$') {
        return $WorldName
    }
    if ([string]::IsNullOrWhiteSpace($PreferredWorldName)) {
        $PreferredWorldName = "CodexSmokeWorld"
    }
    if ($PreferredWorldName -notmatch '^[\x20-\x7E]+$') {
        $PreferredWorldName = "CodexSmokeWorld"
    }

    $savesDir = Join-Path $VersionDir "saves"
    $source = Join-Path $savesDir $WorldName
    $alias = Join-Path $savesDir $PreferredWorldName
    if (-not (Test-Path -LiteralPath (Join-Path $source "level.dat"))) {
        return $null
    }
    if (Test-Path -LiteralPath $alias) {
        if (Test-Path -LiteralPath (Join-Path $alias "level.dat")) {
            return $PreferredWorldName
        }
        return $WorldName
    }

    try {
        New-Item -ItemType Junction -Path $alias -Target $source | Out-Null
        Write-Warning "Created ASCII Quick Play save alias '$PreferredWorldName' for non-ASCII save '$WorldName'."
        return $PreferredWorldName
    } catch {
        Write-Warning "Could not create ASCII Quick Play save alias for '$WorldName'. $($_.Exception.Message)"
        return $WorldName
    }
}

function Ensure-User32 {
    if ([type]::GetType("CodexUser32", $false)) {
        return
    }
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public class CodexUser32 {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
}
public struct RECT {
  public int Left;
  public int Top;
  public int Right;
  public int Bottom;
}
"@
}

function Get-MinecraftWindowHandle {
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        $proc = Get-Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.MainWindowHandle -ne 0 -and
                ($_.MainWindowTitle -match "Minecraft|Fabric Loader|NeoForge|Forge")
            } |
            Sort-Object StartTime -Descending |
            Select-Object -First 1
        if ($proc) {
            return $proc.MainWindowHandle
        }
        Start-Sleep -Milliseconds 500
    }
    return [IntPtr]::Zero
}

function Minimize-ClientWindows {
    if ($AllowInteractiveUi) {
        return
    }
    try {
        Ensure-User32
        Get-Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.MainWindowHandle -ne 0 -and
                ($_.MainWindowTitle -match "Minecraft|Fabric Loader|NeoForge|Forge|PCL|Plain Craft Launcher")
            } |
            ForEach-Object {
                [CodexUser32]::ShowWindow($_.MainWindowHandle, 6) | Out-Null
            }
    } catch {
        Write-Host "INFO: could not minimize background client windows. $($_.Exception.Message)"
    }
}

function Invoke-WindowClick {
    param([IntPtr]$Handle, [double]$XRatio, [double]$YRatio)

    if (-not $AllowInteractiveUi) {
        throw "Interactive UI automation is disabled for background client checks. Provide an existing Quick Play save or rerun with -AllowInteractiveUi only when foreground mouse/keyboard control is explicitly permitted."
    }

    Ensure-User32
    [CodexUser32]::ShowWindow($Handle, 9) | Out-Null
    [CodexUser32]::SetForegroundWindow($Handle) | Out-Null
    Start-Sleep -Milliseconds 250
    $rect = New-Object RECT
    [CodexUser32]::GetWindowRect($Handle, [ref]$rect) | Out-Null
    $x = [int]($rect.Left + (($rect.Right - $rect.Left) * $XRatio))
    $y = [int]($rect.Top + (($rect.Bottom - $rect.Top) * $YRatio))
    [CodexUser32]::SetCursorPos($x, $y) | Out-Null
    Start-Sleep -Milliseconds 100
    [CodexUser32]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 80
    [CodexUser32]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
}

function Save-MinecraftWindowScreenshot {
    param(
        [string]$ProjectPath,
        [string]$Label
    )

    try {
        $handle = Get-MinecraftWindowHandle
        if ($handle -eq [IntPtr]::Zero) {
            return $null
        }
        Ensure-User32
        $foreground = [CodexUser32]::GetForegroundWindow()
        if ($foreground -ne $handle) {
            Write-Host "INFO: skipped Minecraft screenshot '$Label' because the window is not foreground; background checks do not steal focus."
            return $null
        }

        $rect = New-Object RECT
        [CodexUser32]::GetWindowRect($handle, [ref]$rect) | Out-Null
        $width = $rect.Right - $rect.Left
        $height = $rect.Bottom - $rect.Top
        if ($width -le 0 -or $height -le 0) {
            return $null
        }

        Add-Type -AssemblyName System.Drawing
        $safeLabel = if ([string]::IsNullOrWhiteSpace($Label)) { "window" } else { $Label -replace '[^A-Za-z0-9_.-]', '_' }
        $path = Join-Path $ProjectPath ("runclient-check-screenshot-{0}-{1}.png" -f (Get-Date -Format "yyyyMMdd-HHmmss"), $safeLabel)
        $bitmap = New-Object System.Drawing.Bitmap($width, $height)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size($width, $height)))
            $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $graphics.Dispose()
            $bitmap.Dispose()
        }
        return $path
    } catch {
        Write-Warning "Could not capture Minecraft screenshot '$Label'. $($_.Exception.Message)"
        return $null
    }
}

function Add-RunClientScreenshot {
    param([string]$Label)

    if (-not $AllowInteractiveUi) {
        Write-Host "INFO: skipped Minecraft screenshot '$Label' because background checks must not use foreground-only capture."
        return
    }

    $shot = Save-MinecraftWindowScreenshot -ProjectPath $project -Label $Label
    if (-not [string]::IsNullOrWhiteSpace($shot) -and (Test-Path -LiteralPath $shot)) {
        $runClientScreenshots.Add($shot) | Out-Null
        Write-Output "Captured Minecraft screenshot: $shot"
    }
}

function Send-KeysToGame {
    param([string]$Keys)
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.SendKeys]::SendWait($Keys)
}

function Invoke-CreateSingleplayerWorldUi {
    param([string]$WorldName)

    $handle = Get-MinecraftWindowHandle
    if ($handle -eq [IntPtr]::Zero) {
        throw "Could not find Minecraft window for world creation."
    }

    # Main menu: Singleplayer. 1.21.9+ places the main button stack lower than
    # older assumptions; clicking too high can land outside Singleplayer.
    Invoke-WindowClick -Handle $handle -XRatio 0.50 -YRatio 0.52
    Start-Sleep -Seconds 2

    # With no saves, 1.20.1 opens the Create World screen directly. If a
    # no-valid-worlds list is shown instead, the first bottom-left click opens
    # that screen and the second one creates the world.
    Invoke-WindowClick -Handle $handle -XRatio 0.50 -YRatio 0.31
    Start-Sleep -Milliseconds 300
    try {
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.Clipboard]::SetText($WorldName)
        Send-KeysToGame "^a"
        Start-Sleep -Milliseconds 100
        Send-KeysToGame "^v"
    } catch {
        Write-Output "WARN: could not set preferred smoke-test world name; using Minecraft's default name. $($_.Exception.Message)"
    }
    Start-Sleep -Milliseconds 500

    # Create New World bottom-left action.
    Invoke-WindowClick -Handle $handle -XRatio 0.31 -YRatio 0.93
    Start-Sleep -Seconds 2
    Invoke-WindowClick -Handle $handle -XRatio 0.31 -YRatio 0.93
}

function Invoke-EnterSingleplayerWorldUi {
    $handle = Get-MinecraftWindowHandle
    if ($handle -eq [IntPtr]::Zero) {
        throw "Could not find Minecraft window for singleplayer world entry."
    }

    # Main menu: Singleplayer. 1.21.9+ places the main button stack lower than
    # older assumptions; clicking too high can land outside Singleplayer.
    Invoke-WindowClick -Handle $handle -XRatio 0.50 -YRatio 0.52
    Start-Sleep -Seconds 2

    # World list: select the first visible world, then activate it. Double-click
    # works on older versions, and the bottom-left play button is a fallback.
    Invoke-WindowClick -Handle $handle -XRatio 0.50 -YRatio 0.28
    Start-Sleep -Milliseconds 250
    Invoke-WindowClick -Handle $handle -XRatio 0.50 -YRatio 0.28
    Start-Sleep -Milliseconds 600
    Invoke-WindowClick -Handle $handle -XRatio 0.33 -YRatio 0.93
}

function Invoke-AcceptExperimentalWorldWarningUi {
    $handle = Get-MinecraftWindowHandle
    if ($handle -eq [IntPtr]::Zero) {
        throw "Could not find Minecraft window for experimental-world warning."
    }

    # Experimental datapack/map saves show an intermediate warning before
    # world entry. The right-side button is "I know what I'm doing" in English
    # and "我知道我在做什么！" in Chinese. If the warning is absent, this
    # click lands on an inert main-menu area in the configured test window.
    Invoke-WindowClick -Handle $handle -XRatio 0.68 -YRatio 0.58
}

function Invoke-AcceptThirdPartyOnlinePlayWarningUi {
    $handle = Get-MinecraftWindowHandle
    if ($handle -eq [IntPtr]::Zero) {
        throw "Could not find Minecraft window for third-party online play warning."
    }

    # Newer clients can show a "Caution: Third-Party Online Play" gate while
    # entering a quick-play world. The left bottom button is "Proceed".
    Invoke-WindowClick -Handle $handle -XRatio 0.32 -YRatio 0.80
}

function Test-VersionSupportsQuickPlaySingleplayer {
    param($VersionJson)

    if (-not $VersionJson.arguments -or -not $VersionJson.arguments.game) {
        return $false
    }
    try {
        return ((ConvertTo-Json -InputObject $VersionJson.arguments.game -Depth 20 -Compress) -match "quickPlaySingleplayer")
    } catch {
        return $false
    }
}

function Get-LocalSmokeServerJar {
    param([string]$MinecraftVersion)

    if ([string]::IsNullOrWhiteSpace($MinecraftVersion)) {
        return $null
    }
    $loomJar = Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\$MinecraftVersion\minecraft-server.jar"
    if (Test-Path -LiteralPath $loomJar) {
        return $loomJar
    }
    return $null
}

function Stop-LocalSmokeServer {
    param($Server)

    if ($null -eq $Server -or $null -eq $Server.Process) {
        return
    }
    try {
        if (-not $Server.Process.HasExited) {
            try {
                $Server.Process.StandardInput.WriteLine("stop")
            } catch {
            }
            Start-Sleep -Seconds 3
        }
        if (-not $Server.Process.HasExited) {
            $Server.Process.Kill()
        }
    } catch {
    }
}

function Start-LocalSmokeServer {
    param(
        [string]$MinecraftVersion,
        [string]$JavaExe,
        [string]$ServerJar
    )

    if ([string]::IsNullOrWhiteSpace($ServerJar) -or -not (Test-Path -LiteralPath $ServerJar)) {
        throw "Missing local smoke server jar for Minecraft $MinecraftVersion."
    }

    $port = Get-Random -Minimum 25580 -Maximum 25980
    $work = Join-Path $env:TEMP ("st-local-smoke-server-{0}-{1}" -f $MinecraftVersion, [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    Set-Content -LiteralPath (Join-Path $work "eula.txt") -Value "eula=true" -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $work "server.properties") -Encoding ASCII -Value @(
        "level-name=CodexSmokeWorld",
        "gamemode=creative",
        "difficulty=peaceful",
        "online-mode=false",
        "spawn-protection=0",
        "view-distance=2",
        "simulation-distance=2",
        "max-players=2",
        "allow-flight=true",
        "generate-structures=false",
        "level-type=minecraft:flat",
        "sync-chunk-writes=false",
        "max-tick-time=-1",
        "server-port=$port"
    )

    $lastOutput = ""
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        $psi = [System.Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = $JavaExe
        $psi.Arguments = "-Xmx768M -Xms256M -jar `"$ServerJar`" nogui"
        $psi.WorkingDirectory = $work
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $psi.RedirectStandardInput = $true
        # The modern bundled server jar can emit enough bootstrap output to
        # fill redirected pipes before logs/latest.log exists. Since this
        # helper does not consume those pipes asynchronously, inheriting the
        # parent streams avoids a background startup deadlock.
        $psi.RedirectStandardOutput = $false
        $psi.RedirectStandardError = $false
        $proc = [System.Diagnostics.Process]::Start($psi)
        $deadline = (Get-Date).AddSeconds(90)
        $logPath = Join-Path $work "logs\latest.log"

        while ((Get-Date) -lt $deadline) {
            if (Test-Path -LiteralPath $logPath) {
                $tail = (Get-Content -LiteralPath $logPath -Tail 40 -ErrorAction SilentlyContinue) -join "`n"
                if ($tail -match "Done \(" -or $tail -match "For help, type") {
                    return [pscustomobject]@{
                        Process = $proc
                        Port = $port
                        WorkDir = $work
                        LogPath = $logPath
                    }
                }
            }
            if ($proc.HasExited) {
                break
            }
            Start-Sleep -Milliseconds 750
        }

        if ($proc.HasExited) {
            if ($attempt -lt 2) {
                continue
            }
        }
        Stop-LocalSmokeServer -Server ([pscustomobject]@{ Process = $proc })
    }

    $logText = ""
    $latestLog = Join-Path $work "logs\latest.log"
    if (Test-Path -LiteralPath $latestLog) {
        $logText = (Get-Content -LiteralPath $latestLog -Tail 80 -ErrorAction SilentlyContinue) -join "`n"
    }
    throw "Local smoke server for Minecraft $MinecraftVersion did not become ready. $lastOutput $logText"
}

function Get-TestClientMapping {
    param([string]$ProjectPath, [string]$Root)

    $leaf = Split-Path -Leaf $ProjectPath
    $props = Get-GradlePropertyMap -ProjectPath $ProjectPath
    $minecraftVersion = $props["minecraft_version"]
    if ([string]::IsNullOrWhiteSpace($minecraftVersion)) {
        if ($leaf -match "(\d+\.\d+(?:\.\d+)?)") {
            $minecraftVersion = $Matches[1]
        }
    }
    if ([string]::IsNullOrWhiteSpace($minecraftVersion)) {
        throw "Could not determine Minecraft version for project '$leaf'."
    }

    $loader = $null
    $clientRoot = $null
    if ($leaf -match "Fabric") {
        $loader = "fabric"
        $clientRoot = Join-Path $Root "fabric\${minecraftVersion}fabric"
    } elseif ($leaf -match "NeoForge") {
        $loader = "neoforge"
        $clientRoot = Join-Path $Root "neoforge\$minecraftVersion"
    } elseif ($leaf -match "Forge") {
        $loader = "forge"
        $clientRoot = Join-Path $Root "forge\${minecraftVersion}forge"
    } else {
        throw "Could not determine loader for project '$leaf'."
    }

    if (-not (Test-Path -LiteralPath $clientRoot)) {
        throw "Missing dedicated local test client for project '$leaf': $clientRoot"
    }

    $versionId = Get-PclVersionId -InstanceRoot $clientRoot
    if ([string]::IsNullOrWhiteSpace($versionId)) {
        throw "Could not read launch Version from PCL.ini or PCL\Setup.ini in $clientRoot"
    }

    return [pscustomobject]@{
        Loader = $loader
        MinecraftVersion = $minecraftVersion
        ClientRoot = $clientRoot
        VersionId = $versionId
        ModPattern = "simple_translate*.jar"
    }
}

function Get-BuiltModJar {
    param([string]$ProjectPath)

    $libs = Join-Path $ProjectPath "build\libs"
    if (-not (Test-Path -LiteralPath $libs)) {
        throw "Missing build libs directory: $libs. Build the project jar first."
    }

    $jars = Get-ChildItem -LiteralPath $libs -Filter "*.jar" -File |
        Where-Object { $_.Name -notmatch "(-sources|-javadoc|dev-shadow|all)\.jar$" } |
        Sort-Object LastWriteTime -Descending

    if ($jars.Count -eq 0) {
        throw "No deployable jar found under $libs. Build the project jar first."
    }
    return $jars[0]
}

function Get-GradlePropertyMap {
    param([string]$ProjectPath)

    $props = @{}
    $file = Join-Path $ProjectPath "gradle.properties"
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
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        $props[$key] = $value
    }
    return $props
}

function Find-GradleCachedModuleJar {
    param([string]$Group, [string]$Artifact, [string]$Version)

    $cacheRoot = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1"
    $moduleRoot = Join-Path $cacheRoot (Join-Path $Group (Join-Path $Artifact $Version))
    if (-not (Test-Path -LiteralPath $moduleRoot)) {
        return $null
    }

    return Get-ChildItem -LiteralPath $moduleRoot -Recurse -Filter "$Artifact-$Version.jar" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Deploy-TestClientDependencies {
    param([string]$ProjectPath, $Mapping, [string]$ModsDir)

    $deployed = New-Object System.Collections.Generic.List[string]

    if ($Mapping.Loader -eq "fabric") {
        $props = Get-GradlePropertyMap -ProjectPath $ProjectPath
        $fabricVersion = $props["fabric_version"]
        if (-not [string]::IsNullOrWhiteSpace($fabricVersion)) {
            $fabricApi = Find-GradleCachedModuleJar -Group "net.fabricmc.fabric-api" -Artifact "fabric-api" -Version $fabricVersion
            if ($null -eq $fabricApi) {
                throw "Could not find fabric-api $fabricVersion in Gradle cache. Run the project build first."
            }

            Get-ChildItem -LiteralPath $ModsDir -Filter "fabric-api*.jar" -File -ErrorAction SilentlyContinue |
                Remove-Item -Force
            Copy-Item -LiteralPath $fabricApi.FullName -Destination (Join-Path $ModsDir $fabricApi.Name) -Force
            $deployed.Add($fabricApi.FullName)
        }
    }

    return $deployed
}

function Clear-SimpleTranslateModCache {
    param([string]$VersionDir)

    $cleared = New-Object System.Collections.Generic.List[string]
    $cacheDir = Join-Path $VersionDir "config\simple_translate\cache"
    if (Test-Path -LiteralPath $cacheDir) {
        Remove-Item -LiteralPath $cacheDir -Recurse -Force -ErrorAction SilentlyContinue
        $cleared.Add($cacheDir)
    }
    return $cleared
}

function Set-MinecraftSmokeTestOptions {
    param([string]$VersionDir)

    $optionsPath = Join-Path $VersionDir "options.txt"
    $desired = [ordered]@{
        "onboardAccessibility" = "false"
        "narrator" = "0"
        "tutorialStep" = "none"
        "skipMultiplayerWarning" = "true"
        "joinedFirstServer" = "true"
    }

    $lines = New-Object System.Collections.Generic.List[string]
    if (Test-Path -LiteralPath $optionsPath) {
        Get-Content -LiteralPath $optionsPath -ErrorAction SilentlyContinue |
            ForEach-Object { $lines.Add([string]$_) | Out-Null }
    }

    foreach ($entry in $desired.GetEnumerator()) {
        $found = $false
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match "^\s*$([regex]::Escape($entry.Key))\s*:") {
                $lines[$i] = "$($entry.Key):$($entry.Value)"
                $found = $true
                break
            }
        }
        if (-not $found) {
            $lines.Add("$($entry.Key):$($entry.Value)") | Out-Null
        }
    }

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($optionsPath, @($lines), $utf8NoBom)
}

function Test-RuleAllowsWindows {
    param($Rules)

    if ($null -eq $Rules) {
        return $true
    }

    $allowed = $false
    foreach ($rule in @($Rules)) {
        $osName = $null
        if ($rule.os -and $rule.os.name) {
            $osName = [string]$rule.os.name
        }
        $matchesOs = ($null -eq $osName -or $osName -eq "windows")
        if ($matchesOs) {
            $allowed = ([string]$rule.action -eq "allow")
        }
    }
    return $allowed
}

function Expand-NativeLibraries {
    param($VersionJson, [string]$LibrariesRoot, [string]$NativesDir)

    Remove-Item -LiteralPath $NativesDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $NativesDir -Force | Out-Null

    foreach ($lib in @($VersionJson.libraries)) {
        if (-not (Test-RuleAllowsWindows -Rules $lib.rules)) {
            continue
        }
        $classifier = $null
        if ($lib.natives -and $lib.natives.windows) {
            $classifier = [string]$lib.natives.windows
            $classifier = $classifier.Replace('${arch}', '64')
        }
        if (-not $classifier) {
            continue
        }
        $nativeDownload = $lib.downloads.classifiers.$classifier
        if (-not $nativeDownload -or -not $nativeDownload.path) {
            continue
        }
        $nativeJar = Join-Path $LibrariesRoot ([string]$nativeDownload.path)
        if (-not (Test-Path -LiteralPath $nativeJar)) {
            continue
        }
        tar -xf $nativeJar -C $NativesDir
    }

    Get-ChildItem -LiteralPath $NativesDir -Recurse -Directory -Filter "META-INF" -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
}

function Get-ClassPath {
    param($VersionJson, [string]$LibrariesRoot, [string]$VersionDir, [string]$VersionId)

    function Get-VersionSortKey([string]$Version) {
        $numeric = ($Version -replace '[^0-9\.].*$', '')
        try {
            return [version]$numeric
        } catch {
            return [version]"0.0.0.0"
        }
    }

    $records = New-Object System.Collections.Generic.List[object]
    $index = 0
    foreach ($lib in @($VersionJson.libraries)) {
        if (-not (Test-RuleAllowsWindows -Rules $lib.rules)) {
            continue
        }
        $key = $null
        $version = $null
        if ($lib.name) {
            $nameParts = ([string]$lib.name).Split(":")
            if ($nameParts.Count -ge 3) {
                $classifierKey = if ($nameParts.Count -ge 4) { ":$($nameParts[3])" } else { "" }
                $key = "$($nameParts[0]):$($nameParts[1])$classifierKey"
                $version = $nameParts[2]
            }
        }
        if ($lib.downloads -and $lib.downloads.artifact -and $lib.downloads.artifact.path) {
            $path = Join-Path $LibrariesRoot ([string]$lib.downloads.artifact.path)
            if (Test-Path -LiteralPath $path) {
                $records.Add([pscustomobject]@{
                    Key = $key
                    Version = $version
                    SortVersion = Get-VersionSortKey $version
                    Index = $index
                    Path = $path
                })
                $index++
            }
            continue
        }
        if ($lib.name) {
            $parts = ([string]$lib.name).Split(":")
            if ($parts.Count -ge 3) {
                $groupPath = $parts[0].Replace(".", "\")
                $artifactId = $parts[1]
                $version = $parts[2]
                $classifier = if ($parts.Count -ge 4) { "-" + $parts[3] } else { "" }
                $fallbackPath = Join-Path $LibrariesRoot (Join-Path $groupPath (Join-Path $artifactId (Join-Path $version "$artifactId-$version$classifier.jar")))
                if (Test-Path -LiteralPath $fallbackPath) {
                    $classifierKey = if ($parts.Count -ge 4) { ":$($parts[3])" } else { "" }
                    $records.Add([pscustomobject]@{
                        Key = "$($parts[0]):$($parts[1])$classifierKey"
                        Version = $version
                        SortVersion = Get-VersionSortKey $version
                        Index = $index
                        Path = $fallbackPath
                    })
                    $index++
                }
            }
        }
    }
    $paths = New-Object System.Collections.Generic.List[string]
    $records |
        Group-Object { if ([string]::IsNullOrWhiteSpace($_.Key)) { "__path__$($_.Index)" } else { $_.Key } } |
        ForEach-Object {
            $_.Group |
                Sort-Object SortVersion, Index -Descending |
                Select-Object -First 1
        } |
        Sort-Object Index |
        ForEach-Object { $paths.Add($_.Path) }
    $versionsRoot = Split-Path -Parent $VersionDir
    $versionJarIds = @()
    if ($VersionJson.PSObject.Properties.Name -contains "CodexVersionJarIds") {
        $versionJarIds = @($VersionJson.CodexVersionJarIds)
    }
    if ($versionJarIds.Count -eq 0) {
        $versionJarIds = @($VersionId)
    }
    foreach ($versionJarId in $versionJarIds) {
        if ([string]::IsNullOrWhiteSpace([string]$versionJarId)) {
            continue
        }
        $versionJar = Join-Path $versionsRoot (Join-Path ([string]$versionJarId) "$versionJarId.jar")
        if (Test-Path -LiteralPath $versionJar) {
            $paths.Add($versionJar)
        }
    }
    return ($paths -join [IO.Path]::PathSeparator)
}

function Get-JsonArrayProperty {
    param($Object, [string]$Name)

    if ($null -eq $Object) {
        return @()
    }
    if (-not ($Object.PSObject.Properties.Name -contains $Name)) {
        return @()
    }
    $value = $Object.$Name
    if ($null -eq $value) {
        return @()
    }
    return @($value)
}

function Set-JsonProperty {
    param($Object, [string]$Name, $Value)

    if ($Object.PSObject.Properties.Name -contains $Name) {
        $Object.$Name = $Value
    } else {
        $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force
    }
}

function Resolve-VersionJsonWithInheritance {
    param([string]$VersionJsonPath)

    $versionJson = Get-Content -LiteralPath $VersionJsonPath -Raw | ConvertFrom-Json
    $inheritsFrom = $null
    if ($versionJson.PSObject.Properties.Name -contains "inheritsFrom") {
        $inheritsFrom = [string]$versionJson.inheritsFrom
    }
    if ([string]::IsNullOrWhiteSpace($inheritsFrom)) {
        Set-JsonProperty -Object $versionJson -Name "CodexVersionJarIds" -Value @([string]$versionJson.id)
        return $versionJson
    }

    $versionsRoot = Split-Path -Parent (Split-Path -Parent $VersionJsonPath)
    $parentJsonPath = Join-Path $versionsRoot (Join-Path $inheritsFrom "$inheritsFrom.json")
    if (-not (Test-Path -LiteralPath $parentJsonPath)) {
        throw "Version '$($versionJson.id)' inherits from '$inheritsFrom', but the parent version json is missing: $parentJsonPath"
    }

    $parentJson = Resolve-VersionJsonWithInheritance -VersionJsonPath $parentJsonPath
    Set-JsonProperty -Object $versionJson -Name "libraries" -Value @((Get-JsonArrayProperty -Object $parentJson -Name "libraries") + (Get-JsonArrayProperty -Object $versionJson -Name "libraries"))

    $mergedArguments = [pscustomobject]@{
        jvm = @((Get-JsonArrayProperty -Object $parentJson.arguments -Name "jvm") + (Get-JsonArrayProperty -Object $versionJson.arguments -Name "jvm"))
        game = @((Get-JsonArrayProperty -Object $parentJson.arguments -Name "game") + (Get-JsonArrayProperty -Object $versionJson.arguments -Name "game"))
    }
    Set-JsonProperty -Object $versionJson -Name "arguments" -Value $mergedArguments

    foreach ($propertyName in @("assets", "assetIndex", "javaVersion", "downloads", "complianceLevel", "type", "logging")) {
        $hasChildValue = ($versionJson.PSObject.Properties.Name -contains $propertyName) -and $null -ne $versionJson.$propertyName -and -not [string]::IsNullOrWhiteSpace([string]$versionJson.$propertyName)
        $hasParentValue = ($parentJson.PSObject.Properties.Name -contains $propertyName) -and $null -ne $parentJson.$propertyName
        if (-not $hasChildValue -and $hasParentValue) {
            Set-JsonProperty -Object $versionJson -Name $propertyName -Value $parentJson.$propertyName
        }
    }

    $jarIds = @((Get-JsonArrayProperty -Object $parentJson -Name "CodexVersionJarIds") + @([string]$versionJson.id))
    Set-JsonProperty -Object $versionJson -Name "CodexVersionJarIds" -Value $jarIds
    return $versionJson
}

function Resolve-LaunchTemplate {
    param(
        [string]$Value,
        [hashtable]$Variables
    )

    $result = [string]$Value
    foreach ($key in $Variables.Keys) {
        $result = $result.Replace('${' + $key + '}', [string]$Variables[$key])
    }
    return $result
}

function Test-LaunchRulesAllow {
    param(
        $Rules,
        [hashtable]$Features
    )

    if ($null -eq $Rules) {
        return $true
    }

    $allowed = $false
    foreach ($rule in @($Rules)) {
        $osName = $null
        if ($rule.os -and $rule.os.name) {
            $osName = [string]$rule.os.name
        }
        $matchesOs = ($null -eq $osName -or $osName -eq "windows")
        if (-not $matchesOs) {
            continue
        }

        $matchesFeatures = $true
        if ($rule.features) {
            foreach ($featureProperty in $rule.features.PSObject.Properties) {
                $expected = [bool]$featureProperty.Value
                $actual = $false
                if ($Features.ContainsKey($featureProperty.Name)) {
                    $actual = [bool]$Features[$featureProperty.Name]
                }
                if ($actual -ne $expected) {
                    $matchesFeatures = $false
                    break
                }
            }
        }
        if ($matchesFeatures) {
            $allowed = ([string]$rule.action -eq "allow")
        }
    }
    return $allowed
}

function Expand-LaunchArguments {
    param(
        $Entries,
        [hashtable]$Variables,
        [hashtable]$Features
    )

    $args = New-Object System.Collections.Generic.List[string]
    foreach ($entry in @($Entries)) {
        if ($entry -is [string]) {
            $args.Add((Resolve-LaunchTemplate -Value $entry -Variables $Variables))
            continue
        }
        if ($entry -and $entry.PSObject.Properties.Name -contains "value") {
            if (-not (Test-LaunchRulesAllow -Rules $entry.rules -Features $Features)) {
                continue
            }
            foreach ($value in @($entry.value)) {
                $args.Add((Resolve-LaunchTemplate -Value ([string]$value) -Variables $Variables))
            }
        }
    }
    return @($args)
}

function Remove-EmptyFmlLayerArguments {
    param([string[]]$Arguments)

    $filtered = New-Object System.Collections.Generic.List[string]
    foreach ($arg in @($Arguments)) {
        if ($arg -eq "-Dfml.pluginLayerLibraries=" -or $arg -eq "-Dfml.gameLayerLibraries=") {
            continue
        }
        $filtered.Add($arg)
    }
    return @($filtered)
}

function Quote-ArgFileValue {
    param([string]$Value)

    $escaped = $Value.Replace('"', '\"')
    return '"' + $escaped + '"'
}

function Format-ArgFileValue {
    param([string]$Value)

    if ($null -eq $Value) {
        return '""'
    }
    if ($Value -match '[\s"]') {
        return Quote-ArgFileValue -Value $Value
    }
    return $Value
}

function Get-AsciiClientRoot {
    param([string]$RealClientRoot, [string]$Loader, [string]$VersionId)

    if ($RealClientRoot -match '^[\x00-\x7F]+$') {
        return $RealClientRoot
    }

    $base = Join-Path $env:TEMP "codex-mc-test-clients"
    New-Item -ItemType Directory -Path $base -Force | Out-Null
    $aliasName = ($Loader + "-" + $VersionId) -replace '[^A-Za-z0-9_.-]', '_'
    $aliasPath = Join-Path $base $aliasName

    if (Test-Path -LiteralPath $aliasPath) {
        $item = Get-Item -LiteralPath $aliasPath
        if (-not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "ASCII test-client alias path exists but is not a junction: $aliasPath"
        }
        $target = if ($item.Target -is [array]) { [string]::Join("", $item.Target) } else { [string]$item.Target }
        $expected = (Resolve-Path -LiteralPath $RealClientRoot).Path.TrimEnd("\")
        $actual = $target.TrimEnd("\")
        if (-not [string]::Equals($actual, $expected, [System.StringComparison]::OrdinalIgnoreCase)) {
            try {
                Remove-Item -LiteralPath $aliasPath -Force
            } catch {
                [System.IO.Directory]::Delete($aliasPath, $false)
            }
            New-Item -ItemType Junction -Path $aliasPath -Target $RealClientRoot | Out-Null
        }
        return $aliasPath
    }

    New-Item -ItemType Junction -Path $aliasPath -Target $RealClientRoot | Out-Null
    return $aliasPath
}

$project = (Resolve-Path -LiteralPath $ProjectDir).Path
if ([string]::IsNullOrWhiteSpace($TestClientRoot)) {
    $TestClientRoot = Join-Path "D:\mc" ([string]([char]0x6A21) + [string]([char]0x7EC4) + [string]([char]0x6D4B) + [string]([char]0x8BD5))
}
$mapping = Get-TestClientMapping -ProjectPath $project -Root $TestClientRoot
$realClientRoot = (Resolve-Path -LiteralPath $mapping.ClientRoot).Path
$clientRoot = Get-AsciiClientRoot -RealClientRoot $realClientRoot -Loader $mapping.Loader -VersionId $mapping.VersionId
$versionDir = Join-Path $clientRoot "versions\$($mapping.VersionId)"
$realVersionDir = Join-Path $realClientRoot "versions\$($mapping.VersionId)"
$versionJsonPath = Join-Path $versionDir "$($mapping.VersionId).json"
$modsDir = Join-Path $versionDir "mods"
$librariesRoot = Join-Path $clientRoot "libraries"
$assetsRoot = Join-Path $clientRoot "assets"
$nativesDir = Join-Path $versionDir "codex-natives"

if (-not (Test-Path -LiteralPath $versionJsonPath)) {
    throw "Missing test client version json: $versionJsonPath"
}
if (-not (Test-Path -LiteralPath $modsDir)) {
    New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
}

$builtJar = Get-BuiltModJar -ProjectPath $project
$runningClients = @(Get-JavaProcessesUsingPath -Path $realVersionDir)
$foreignClients = New-Object System.Collections.Generic.List[object]
foreach ($runningClient in $runningClients) {
    $commandLine = [string]$runningClient.CommandLine
    if ($commandLine -match "CodexTestClient") {
        Stop-ProcessTree -RootPid ([int]$runningClient.ProcessId)
    } else {
        $foreignClients.Add($runningClient)
    }
}
if ($foreignClients.Count -gt 0) {
    Write-Output "FAIL: matching dedicated test client is already running outside this script; close it before deploying the jar."
    Write-Output "Test client: $realVersionDir"
    foreach ($foreignClient in $foreignClients) {
        Write-Output "  PID $($foreignClient.ProcessId): $($foreignClient.Name)"
    }
    exit 1
}
Get-ChildItem -LiteralPath $modsDir -Filter $mapping.ModPattern -File -ErrorAction SilentlyContinue |
    Remove-Item -Force
$deployedJarPath = Join-Path $modsDir $builtJar.Name
Copy-Item -LiteralPath $builtJar.FullName -Destination $deployedJarPath -Force
$builtHash = (Get-FileHash -LiteralPath $builtJar.FullName -Algorithm SHA256).Hash
$deployedHash = (Get-FileHash -LiteralPath $deployedJarPath -Algorithm SHA256).Hash
if ($builtHash -ne $deployedHash) {
    throw "Deployed jar hash mismatch. Source=$($builtJar.FullName) Destination=$deployedJarPath"
}
$deployedDependencies = Deploy-TestClientDependencies -ProjectPath $project -Mapping $mapping -ModsDir $modsDir
$clearedCaches = if ($PreserveCache) {
    @()
} else {
    Clear-SimpleTranslateModCache -VersionDir $realVersionDir
}
Set-MinecraftSmokeTestOptions -VersionDir $realVersionDir

$versionJson = Resolve-VersionJsonWithInheritance -VersionJsonPath $versionJsonPath
Expand-NativeLibraries -VersionJson $versionJson -LibrariesRoot $librariesRoot -NativesDir $nativesDir
$classPath = Get-ClassPath -VersionJson $versionJson -LibrariesRoot $librariesRoot -VersionDir $versionDir -VersionId $mapping.VersionId
if ([string]::IsNullOrWhiteSpace($classPath)) {
    throw "Could not build classpath for test client $($mapping.VersionId)."
}
$versionSupportsQuickPlaySingleplayer = Test-VersionSupportsQuickPlaySingleplayer -VersionJson $versionJson

$enterWorld = $EnterWorld -and -not $NoEnterWorld
$worldToEnter = $null
$needsWorldCreation = $false
if ($enterWorld) {
    $worldToEnter = Get-SingleplayerWorldName -VersionDir $realVersionDir -PreferredWorldName $WorldName
    if ([string]::IsNullOrWhiteSpace($worldToEnter)) {
        if ($NoCreateWorld) {
            throw "No singleplayer save exists in $realVersionDir\saves and -NoCreateWorld was specified."
        }
        $needsWorldCreation = $true
    } else {
        $worldToEnter = Ensure-AsciiQuickPlayWorldAlias -VersionDir $realVersionDir -WorldName $worldToEnter -PreferredWorldName $WorldName
        if ([string]::IsNullOrWhiteSpace($worldToEnter)) {
            if ($NoCreateWorld) {
                throw "No Quick Play-compatible singleplayer save exists in $realVersionDir\saves and -NoCreateWorld was specified."
            }
            $needsWorldCreation = $true
        }
    }
}
$usingQuickPlaySingleplayer = [bool]($enterWorld -and -not $NoQuickPlay -and $versionSupportsQuickPlaySingleplayer -and -not [string]::IsNullOrWhiteSpace($worldToEnter))
$localSmokeServerJar = Get-LocalSmokeServerJar -MinecraftVersion $mapping.MinecraftVersion
$useLocalSmokeServer = [bool]($enterWorld -and -not $AllowInteractiveUi -and -not $usingQuickPlaySingleplayer -and -not [string]::IsNullOrWhiteSpace($localSmokeServerJar))
$legacyNoBackgroundWorldEntry = [bool]($enterWorld -and -not $AllowInteractiveUi -and -not $usingQuickPlaySingleplayer -and $mapping.MinecraftVersion -match '^1\.19\.')
if ($legacyNoBackgroundWorldEntry) {
    $useLocalSmokeServer = $false
}

$quickPlayPath = Join-Path $versionDir "codex-quickplay.json"

$launchVariables = @{
    "natives_directory" = $nativesDir
    "launcher_name" = "CodexTestClient"
    "launcher_version" = "1"
    "classpath" = $classPath
    "classpath_separator" = [IO.Path]::PathSeparator
    "library_directory" = $librariesRoot
    "version_name" = [string]$mapping.VersionId
    "primary_jar_name" = "$($mapping.VersionId).jar"
    "auth_player_name" = "CodexTester"
    "game_directory" = $versionDir
    "assets_root" = $assetsRoot
    "assets_index_name" = [string]$versionJson.assets
    "auth_uuid" = "00000000000000000000000000000000"
    "auth_access_token" = "0"
    "clientid" = "0"
    "auth_xuid" = "0"
    "user_type" = "legacy"
    "version_type" = "release"
    "resolution_width" = "854"
    "resolution_height" = "480"
    "quickPlaySingleplayer" = if ($worldToEnter) { $worldToEnter } else { "" }
    "quickPlayMultiplayer" = ""
    "quickPlayRealms" = ""
    "quickPlayPath" = $quickPlayPath
}

$launchFeatures = @{
    "is_demo_user" = $false
    "has_custom_resolution" = $true
    "has_quick_plays_support" = [bool]$versionSupportsQuickPlaySingleplayer
    "is_quick_play_singleplayer" = [bool]$usingQuickPlaySingleplayer
    "is_quick_play_multiplayer" = $false
    "is_quick_play_realms" = $false
}
$needsWorldEntryUi = [bool]($enterWorld -and -not $needsWorldCreation -and -not $usingQuickPlaySingleplayer)
if ($legacyNoBackgroundWorldEntry) {
    $needsWorldCreation = $false
    $needsWorldEntryUi = $false
    Write-Output "INFO: Minecraft $($mapping.MinecraftVersion) has no reliable non-interactive world-entry path in this local client; checking minimized initialization and logs instead."
}
if ($useLocalSmokeServer) {
    $needsWorldCreation = $false
    $needsWorldEntryUi = $false
    Write-Output "INFO: Quick Play singleplayer is unavailable; using a temporary local smoke server on loopback."
}
if ($enterWorld -and -not $AllowInteractiveUi -and ($needsWorldCreation -or $needsWorldEntryUi) -and -not $useLocalSmokeServer) {
    if ($needsWorldCreation) {
        throw "No singleplayer save exists in $realVersionDir\saves. Background checks cannot create a world via UI without stealing mouse/keyboard; create or copy a smoke save first, or explicitly rerun with -AllowInteractiveUi."
    }
    throw "This client cannot enter the selected save through Quick Play and would require UI clicks. Background checks will not steal mouse/keyboard; provide a Quick Play-compatible smoke save or explicitly rerun with -AllowInteractiveUi."
}

$latestLog = Join-Path $realVersionDir "logs\latest.log"
$crashDir = Join-Path $realVersionDir "crash-reports"
$stdout = Join-Path $project "runclient-check.out.log"
$stderr = Join-Path $project "runclient-check.err.log"
$argFile = Join-Path $project "runclient-check.args"
$runClientScreenshots = New-Object System.Collections.Generic.List[string]

$startTime = Get-Date
$beforeCrashes = @{}
if (Test-Path -LiteralPath $crashDir) {
    Get-ChildItem -LiteralPath $crashDir -File -ErrorAction SilentlyContinue | ForEach-Object {
        $beforeCrashes[$_.FullName] = $true
    }
}

Remove-Item -LiteralPath $stdout, $stderr, $latestLog, $argFile, $quickPlayPath -ErrorAction SilentlyContinue
Get-ChildItem -LiteralPath $project -Filter "runclient-check-screenshot-*.png" -File -ErrorAction SilentlyContinue |
    Remove-Item -Force

function Test-JavaHome([string]$JavaHome) {
    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        return $false
    }
    try {
        return Test-Path -LiteralPath ([System.IO.Path]::Combine($JavaHome, "bin", "java.exe"))
    } catch {
        return $false
    }
}

function Select-TestJavaHome {
    param([object]$VersionJson)

    $major = 0
    try {
        if ($VersionJson.javaVersion -and $VersionJson.javaVersion.majorVersion) {
            $major = [int]$VersionJson.javaVersion.majorVersion
        }
    } catch {
        $major = 0
    }

    $java17Home = "C:\Program Files\eclipse adoptium\jdk-17.0.13.11-hotspot"
    $java21Home = "C:\Program Files\zulu\zulu-21"

    if ($major -gt 0 -and $major -le 17 -and (Test-JavaHome $java17Home)) {
        return $java17Home
    }
    if ($major -ge 21 -and (Test-JavaHome $java21Home)) {
        return $java21Home
    }
    if (Test-JavaHome $env:JAVA_HOME) {
        return $env:JAVA_HOME
    }
    if (Test-JavaHome $java21Home) {
        return $java21Home
    }
    if (Test-JavaHome $java17Home) {
        return $java17Home
    }
    return $null
}

$selectedJavaHome = Select-TestJavaHome -VersionJson $versionJson
if (Test-JavaHome $selectedJavaHome) {
    $env:JAVA_HOME = $selectedJavaHome
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    $javaExe = [System.IO.Path]::Combine($env:JAVA_HOME, "bin", "java.exe")
} else {
    $javaExe = "java.exe"
}

$localSmokeServer = $null
if ($useLocalSmokeServer) {
    $localSmokeServer = Start-LocalSmokeServer -MinecraftVersion $mapping.MinecraftVersion -JavaExe $javaExe -ServerJar $localSmokeServerJar
    $script:activeLocalSmokeServer = $localSmokeServer
    Register-EngineEvent -SourceIdentifier PowerShell.Exiting -Action {
        if ($script:activeLocalSmokeServer) {
            Stop-LocalSmokeServer -Server $script:activeLocalSmokeServer
        }
    } | Out-Null
    Write-Output "INFO: local smoke server ready on 127.0.0.1:$($localSmokeServer.Port)."
    if ($versionSupportsQuickPlaySingleplayer) {
        $launchVariables["quickPlayMultiplayer"] = "127.0.0.1:$($localSmokeServer.Port)"
        $launchFeatures["is_quick_play_singleplayer"] = $false
        $launchFeatures["is_quick_play_multiplayer"] = $true
    }
}

$jvmArgs = @("-Xmx2G", "-Xms512M")
if ($versionJson.arguments -and $versionJson.arguments.jvm) {
    $jvmArgs += Expand-LaunchArguments -Entries $versionJson.arguments.jvm -Variables $launchVariables -Features $launchFeatures
} else {
    $jvmArgs += @(
        "-Djava.library.path=$nativesDir",
        "-Djna.tmpdir=$nativesDir",
        "-Dorg.lwjgl.system.SharedLibraryExtractPath=$nativesDir",
        "-Dio.netty.native.workdir=$nativesDir",
        "-Dminecraft.launcher.brand=CodexTestClient",
        "-Dminecraft.launcher.version=1",
        "-cp",
        $classPath
    )
}
if ([string]::Equals([string]$versionJson.mainClass, "cpw.mods.bootstraplauncher.BootstrapLauncher", [System.StringComparison]::Ordinal) -and
    -not ($jvmArgs | Where-Object { $_ -like "-DlegacyClassPath=*" -or $_ -like "-DlegacyClassPath.file=*" })) {
    $jvmArgs += "-DlegacyClassPath=$classPath"
}
$jvmArgs += [string]$versionJson.mainClass

if ($versionJson.arguments -and $versionJson.arguments.game) {
    $gameArgs = Expand-LaunchArguments -Entries $versionJson.arguments.game -Variables $launchVariables -Features $launchFeatures
} else {
    $gameArgs = @(
        "--username", "CodexTester",
        "--version", [string]$mapping.VersionId,
        "--gameDir", $versionDir,
        "--assetsDir", $assetsRoot,
        "--assetIndex", [string]$versionJson.assets,
        "--uuid", "00000000000000000000000000000000",
        "--accessToken", "0",
        "--clientId", "0",
        "--xuid", "0",
        "--userType", "legacy",
        "--versionType", "release",
        "--width", "854",
        "--height", "480"
    )
    if ($enterWorld -and -not [string]::IsNullOrWhiteSpace($worldToEnter)) {
        $gameArgs += @("--quickPlaySingleplayer", $worldToEnter)
    }
}
if ($useLocalSmokeServer -and $localSmokeServer -and -not $versionSupportsQuickPlaySingleplayer) {
    $gameArgs += @("--server", "127.0.0.1", "--port", [string]$localSmokeServer.Port)
}

$argLines = @($jvmArgs + $gameArgs | ForEach-Object { Format-ArgFileValue -Value ([string]$_) })
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($argFile, $argLines, $utf8NoBom)

$launchWindowStyle = $WindowStyle
if ($launchWindowStyle -eq "Auto") {
    $launchWindowStyle = "Minimized"
}
if (-not $AllowInteractiveUi -and $launchWindowStyle -eq "Normal") {
    Write-Output "INFO: overriding -WindowStyle Normal to Minimized because background checks must not steal focus. Use -AllowInteractiveUi only when explicitly permitted."
    $launchWindowStyle = "Minimized"
}

$startProcessArgs = @{
    FilePath = $javaExe
    ArgumentList = @("@$argFile")
    WorkingDirectory = $versionDir
    RedirectStandardOutput = $stdout
    RedirectStandardError = $stderr
    PassThru = $true
    WindowStyle = $launchWindowStyle
}

$process = Start-Process @startProcessArgs
Minimize-ClientWindows

$initializationPatterns = @(
    "Simple Translate Fabric mod initialized",
    "Simple Translate .* initialized",
    "Sound engine started",
    "Created: .*minecraft:textures/atlas/mob_effects\.png-atlas",
    "Created: .*minecraft:textures/atlas/gui\.png-atlas"
)

$worldReadyPatterns = @(
    "logged in with entity id",
    "Reset SimpleTranslate runtime state: world-switch",
    "Switched to world data for"
)

$uiCreationReadyPatterns = @(
    "Sound engine started",
    "Created: .*minecraft:textures/atlas/mob_effects\.png-atlas",
    "Created: .*minecraft:textures/atlas/gui\.png-atlas"
)

$nonInteractiveWorldEntryUnavailable = [bool]($legacyNoBackgroundWorldEntry -or ($useLocalSmokeServer -and $mapping.MinecraftVersion -match '^1\.19\.'))
$readyPatterns = if ($enterWorld -and -not $nonInteractiveWorldEntryUnavailable) { $worldReadyPatterns } else { $initializationPatterns }

$fatalPatterns = @(
    "Incompatible mods found",
    "Mod resolution failed",
    "HARD_DEP_NO_CANDIDATE",
    "FormattedException",
    "Failed to handle packet",
    "Network Protocol",
    "Mixin transformation",
    "Mixin apply .* failed",
    "InvalidInjectionException",
    "InjectionError",
    "Critical injection failure",
    "MixinApplyError",
    "MixinTransformerError",
    "Game crashed",
    "---- Minecraft Crash Report ----",
    "Exception in thread `"main`""
)

$ready = $false
$readyAt = $null
$lastLog = ""
$worldCreationTriggered = $false
$worldEntryTriggered = $false
$quickPlayFallbackTriggered = $false
$quickPlayWarningAccepted = $false
$thirdPartyOnlineWarningAccepted = $false
$experimentalWarningAccepted = $false
$lastBackgroundMinimizeAt = (Get-Date).AddSeconds(-10)

while (((Get-Date) - $startTime).TotalSeconds -lt $TimeoutSeconds) {
    if (-not $AllowInteractiveUi -and ((Get-Date) - $lastBackgroundMinimizeAt).TotalSeconds -ge 1) {
        Minimize-ClientWindows
        $lastBackgroundMinimizeAt = Get-Date
    }

    $combinedLaunchOutput = ""
    if (Test-Path -LiteralPath $stdout) {
        $combinedLaunchOutput += Get-Content -LiteralPath $stdout -Raw -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $stderr) {
        $combinedLaunchOutput += Get-Content -LiteralPath $stderr -Raw -ErrorAction SilentlyContinue
    }
    if (-not [string]::IsNullOrWhiteSpace($combinedLaunchOutput)) {
        foreach ($pattern in $fatalPatterns) {
            if ($combinedLaunchOutput -match $pattern) {
                Add-RunClientScreenshot -Label "fatal-launch-output"
                Stop-ProcessTree -RootPid $process.Id
                Write-Output "FAIL: launch output matched fatal pattern: $pattern"
                Write-Output "Test client: $realVersionDir"
                Write-Output "Deployed jar: $($builtJar.FullName)"
                if ($deployedDependencies.Count -gt 0) {
                    Write-Output "Deployed dependencies:"
                    $deployedDependencies | ForEach-Object { Write-Output "  $_" }
                }
                if ($clearedCaches.Count -gt 0) {
                    Write-Output "Cleared SimpleTranslate cache:"
                    $clearedCaches | ForEach-Object { Write-Output "  $_" }
                }
                Write-LaunchDiagnostics -LatestLog $latestLog -CrashDir $crashDir -Stdout $stdout -Stderr $stderr -StartTime $startTime -BeforeCrashes $beforeCrashes
                exit 1
            }
        }
    }

    if (Test-Path -LiteralPath $latestLog) {
        $lastLog = Get-Content -LiteralPath $latestLog -Raw -ErrorAction SilentlyContinue

        foreach ($pattern in $fatalPatterns) {
            if ($lastLog -match $pattern) {
                Add-RunClientScreenshot -Label "fatal-runtime-log"
                Stop-ProcessTree -RootPid $process.Id
                Write-Output "FAIL: runtime log matched fatal pattern: $pattern"
                Write-Output "Test client: $realVersionDir"
                Write-Output "Deployed jar: $($builtJar.FullName)"
                if ($deployedDependencies.Count -gt 0) {
                    Write-Output "Deployed dependencies:"
                    $deployedDependencies | ForEach-Object { Write-Output "  $_" }
                }
                if ($clearedCaches.Count -gt 0) {
                    Write-Output "Cleared SimpleTranslate cache:"
                    $clearedCaches | ForEach-Object { Write-Output "  $_" }
                }
                Write-LaunchDiagnostics -LatestLog $latestLog -CrashDir $crashDir -Stdout $stdout -Stderr $stderr -StartTime $startTime -BeforeCrashes $beforeCrashes
                exit 1
            }
        }

        if ($needsWorldCreation -and -not $worldCreationTriggered) {
            foreach ($pattern in $uiCreationReadyPatterns) {
                if ($lastLog -match $pattern) {
                    $worldCreationTriggered = $true
                    try {
                        Start-Sleep -Seconds 3
                        Invoke-CreateSingleplayerWorldUi -WorldName $WorldName
                    } catch {
                        Add-RunClientScreenshot -Label "create-world-ui-failed"
                        Stop-ProcessTree -RootPid $process.Id
                        Write-Output "FAIL: could not create singleplayer world via UI. $($_.Exception.Message)"
                        Write-Output "Test client: $realVersionDir"
                        Write-Output "Deployed jar: $($builtJar.FullName)"
                        Write-LaunchDiagnostics -LatestLog $latestLog -CrashDir $crashDir -Stdout $stdout -Stderr $stderr -StartTime $startTime -BeforeCrashes $beforeCrashes
                        exit 1
                    }
                    break
                }
            }
        }

        if ($needsWorldEntryUi -and -not $worldEntryTriggered) {
            foreach ($pattern in $uiCreationReadyPatterns) {
                if ($lastLog -match $pattern) {
                    $worldEntryTriggered = $true
                    try {
                        Start-Sleep -Seconds 3
                        Invoke-EnterSingleplayerWorldUi
                    } catch {
                        Add-RunClientScreenshot -Label "enter-world-ui-failed"
                        Stop-ProcessTree -RootPid $process.Id
                        Write-Output "FAIL: could not enter singleplayer world via UI. $($_.Exception.Message)"
                        Write-Output "Test client: $realVersionDir"
                        Write-Output "Deployed jar: $($builtJar.FullName)"
                        Write-LaunchDiagnostics -LatestLog $latestLog -CrashDir $crashDir -Stdout $stdout -Stderr $stderr -StartTime $startTime -BeforeCrashes $beforeCrashes
                        exit 1
                    }
                    break
                }
            }
        }

        if ($usingQuickPlaySingleplayer -and -not $ready -and -not $quickPlayFallbackTriggered -and (((Get-Date) - $startTime).TotalSeconds -ge 35)) {
            $quickPlayFallbackTriggered = $true
            Add-RunClientScreenshot -Label "quickplay-wait"
            Stop-ProcessTree -RootPid $process.Id
            Write-Output "FAIL: Quick Play did not reach a world within 35 seconds. Screenshot captured; fix the save identifier or launch arguments instead of waiting on the error screen."
            Write-Output "Quick Play world: $worldToEnter"
            Write-Output "Test client: $realVersionDir"
            Write-Output "Deployed jar: $($builtJar.FullName)"
            Write-LaunchDiagnostics -LatestLog $latestLog -CrashDir $crashDir -Stdout $stdout -Stderr $stderr -StartTime $startTime -BeforeCrashes $beforeCrashes
            exit 1
        }

        if ($usingQuickPlaySingleplayer -and -not $ready -and -not $thirdPartyOnlineWarningAccepted -and (((Get-Date) - $startTime).TotalSeconds -ge 12)) {
            $thirdPartyOnlineWarningAccepted = $true
            if ($AllowInteractiveUi) {
                Add-RunClientScreenshot -Label "quickplay-before-third-party-warning-click"
                try {
                    Invoke-AcceptThirdPartyOnlinePlayWarningUi
                } catch {
                    Write-Warning "Could not click Quick Play third-party online warning confirmation. $($_.Exception.Message)"
                }
            } else {
                Write-Output "INFO: background check will not click a possible Quick Play third-party warning."
            }
        }

        if ($usingQuickPlaySingleplayer -and -not $ready -and -not $quickPlayWarningAccepted -and (((Get-Date) - $startTime).TotalSeconds -ge 22)) {
            $quickPlayWarningAccepted = $true
            if ($AllowInteractiveUi) {
                Add-RunClientScreenshot -Label "quickplay-before-experimental-warning-click"
                try {
                    Invoke-AcceptExperimentalWorldWarningUi
                } catch {
                    Write-Warning "Could not click Quick Play experimental-world warning confirmation. $($_.Exception.Message)"
                }
            } else {
                Write-Output "INFO: background check will not click a possible Quick Play experimental-world warning."
            }
        }

        if (($needsWorldCreation -or $needsWorldEntryUi) -and -not $ready -and -not $experimentalWarningAccepted) {
            foreach ($pattern in $uiCreationReadyPatterns) {
                if ($lastLog -match $pattern) {
                    $experimentalWarningAccepted = $true
                    try {
                        Start-Sleep -Seconds 3
                        Invoke-AcceptExperimentalWorldWarningUi
                    } catch {
                        Write-Output "WARN: could not click experimental-world warning confirmation. $($_.Exception.Message)"
                    }
                    break
                }
            }
        }

        if (-not $ready) {
            $readyText = $lastLog
            if ($useLocalSmokeServer -and $localSmokeServer -and
                    (Test-Path -LiteralPath $localSmokeServer.LogPath)) {
                $readyText += "`n" + (Get-Content -LiteralPath $localSmokeServer.LogPath -Raw -ErrorAction SilentlyContinue)
            }
            foreach ($pattern in $readyPatterns) {
                if ($readyText -match $pattern) {
                    $ready = $true
                    $readyAt = Get-Date
                    break
                }
            }
        }
    }

    if ($ready -and ((Get-Date) - $readyAt).TotalSeconds -ge $ReadyGraceSeconds) {
        Add-RunClientScreenshot -Label "ready"
        Stop-ProcessTree -RootPid $process.Id

        $newCrashes = @()
        if (Test-Path -LiteralPath $crashDir) {
            $newCrashes = Get-ChildItem -LiteralPath $crashDir -File -ErrorAction SilentlyContinue |
                Where-Object { -not $beforeCrashes.ContainsKey($_.FullName) -and $_.LastWriteTime -ge $startTime }
        }
        if ($newCrashes.Count -gt 0) {
            Write-Output "FAIL: new crash report created after launch."
            $newCrashes | Select-Object FullName, LastWriteTime, Length | Format-Table -AutoSize
            Write-LaunchDiagnostics -LatestLog $latestLog -CrashDir $crashDir -Stdout $stdout -Stderr $stderr -StartTime $startTime -BeforeCrashes $beforeCrashes
            exit 1
        }
        if (-not (Test-SimpleTranslateLoaded -LatestLog $latestLog -Stdout $stdout)) {
            Write-Output "FAIL: SimpleTranslate did not appear in latest.log/stdout; the client may have launched without loading the mod."
            Write-Output "Test client: $realVersionDir"
            Write-Output "Deployed jar: $($builtJar.FullName)"
            Write-LaunchDiagnostics -LatestLog $latestLog -CrashDir $crashDir -Stdout $stdout -Stderr $stderr -StartTime $startTime -BeforeCrashes $beforeCrashes
            exit 1
        }

        $readyScope = if ($enterWorld -and -not $nonInteractiveWorldEntryUnavailable) { "world entry" } elseif ($enterWorld -and $nonInteractiveWorldEntryUnavailable) { "normal initialization; background world entry is unavailable for this 1.19.x client without UI clicks" } else { "normal initialization" }
        Write-Output "PASS: dedicated local test client reached $readyScope; process tree was stopped automatically."
        Write-Output "Test client: $realVersionDir"
        Write-Output "Deployed jar: $($builtJar.FullName)"
        if ($deployedDependencies.Count -gt 0) {
            Write-Output "Deployed dependencies:"
            $deployedDependencies | ForEach-Object { Write-Output "  $_" }
        }
        if ($clearedCaches.Count -gt 0) {
            Write-Output "Cleared SimpleTranslate cache:"
            $clearedCaches | ForEach-Object { Write-Output "  $_" }
        }
        exit 0
    }

    $process.Refresh()
    if ($process.HasExited) {
        if (-not $ready -and (Test-Path -LiteralPath $latestLog)) {
            $lastLog = Get-Content -LiteralPath $latestLog -Raw -ErrorAction SilentlyContinue
            foreach ($pattern in $readyPatterns) {
                if ($lastLog -match $pattern) {
                    $ready = $true
                    $readyAt = Get-Date
                    break
                }
            }
        }
        if ($process.ExitCode -eq 0 -and $ready) {
            if (-not (Test-SimpleTranslateLoaded -LatestLog $latestLog -Stdout $stdout)) {
                Write-Output "FAIL: SimpleTranslate did not appear in latest.log/stdout; the client may have launched without loading the mod."
                Write-Output "Test client: $realVersionDir"
                Write-Output "Deployed jar: $($builtJar.FullName)"
                Write-LaunchDiagnostics -LatestLog $latestLog -CrashDir $crashDir -Stdout $stdout -Stderr $stderr -StartTime $startTime -BeforeCrashes $beforeCrashes
                exit 1
            }
            $readyScope = if ($enterWorld -and -not $nonInteractiveWorldEntryUnavailable) { "singleplayer world entry" } elseif ($enterWorld -and $nonInteractiveWorldEntryUnavailable) { "normal initialization; background world entry is unavailable for this 1.19.x client without UI clicks" } else { "normal initialization" }
            Write-Output "PASS: dedicated local test client exited normally after reaching $readyScope."
            Write-Output "Test client: $realVersionDir"
            Write-Output "Deployed jar: $($builtJar.FullName)"
            if ($deployedDependencies.Count -gt 0) {
                Write-Output "Deployed dependencies:"
                $deployedDependencies | ForEach-Object { Write-Output "  $_" }
            }
            if ($clearedCaches.Count -gt 0) {
                Write-Output "Cleared SimpleTranslate cache:"
                $clearedCaches | ForEach-Object { Write-Output "  $_" }
            }
            exit 0
        }
        Write-Output "FAIL: dedicated local test client exited before initialization. ExitCode=$($process.ExitCode)"
        Add-RunClientScreenshot -Label "exited-before-ready"
        Write-Output "Test client: $realVersionDir"
        Write-Output "Deployed jar: $($builtJar.FullName)"
        if ($deployedDependencies.Count -gt 0) {
            Write-Output "Deployed dependencies:"
            $deployedDependencies | ForEach-Object { Write-Output "  $_" }
        }
        if ($clearedCaches.Count -gt 0) {
            Write-Output "Cleared SimpleTranslate cache:"
            $clearedCaches | ForEach-Object { Write-Output "  $_" }
        }
        Write-LaunchDiagnostics -LatestLog $latestLog -CrashDir $crashDir -Stdout $stdout -Stderr $stderr -StartTime $startTime -BeforeCrashes $beforeCrashes
        exit 1
    }

    Start-Sleep -Seconds 2
}

if ($ready) {
    Add-RunClientScreenshot -Label "ready-timeout"
    Stop-ProcessTree -RootPid $process.Id
    if (-not (Test-SimpleTranslateLoaded -LatestLog $latestLog -Stdout $stdout)) {
        Write-Output "FAIL: SimpleTranslate did not appear in latest.log/stdout; the client may have launched without loading the mod."
        Write-Output "Test client: $realVersionDir"
        Write-Output "Deployed jar: $($builtJar.FullName)"
        Write-LaunchDiagnostics -LatestLog $latestLog -CrashDir $crashDir -Stdout $stdout -Stderr $stderr -StartTime $startTime -BeforeCrashes $beforeCrashes
        exit 1
    }
    $readyScope = if ($enterWorld -and -not $nonInteractiveWorldEntryUnavailable) { "singleplayer world entry" } elseif ($enterWorld -and $nonInteractiveWorldEntryUnavailable) { "normal initialization; background world entry is unavailable for this 1.19.x client without UI clicks" } else { "normal initialization" }
    Write-Output "PASS: dedicated local test client reached $readyScope before timeout; process tree was stopped automatically."
    Write-Output "Test client: $realVersionDir"
    Write-Output "Deployed jar: $($builtJar.FullName)"
    if ($deployedDependencies.Count -gt 0) {
        Write-Output "Deployed dependencies:"
        $deployedDependencies | ForEach-Object { Write-Output "  $_" }
    }
    if ($clearedCaches.Count -gt 0) {
        Write-Output "Cleared SimpleTranslate cache:"
        $clearedCaches | ForEach-Object { Write-Output "  $_" }
    }
    exit 0
}

Add-RunClientScreenshot -Label "timeout"
Stop-ProcessTree -RootPid $process.Id
Write-Output "FAIL: timed out before dedicated local test client initialization."
Write-Output "Test client: $realVersionDir"
Write-Output "Deployed jar: $($builtJar.FullName)"
if ($deployedDependencies.Count -gt 0) {
    Write-Output "Deployed dependencies:"
    $deployedDependencies | ForEach-Object { Write-Output "  $_" }
}
if ($clearedCaches.Count -gt 0) {
    Write-Output "Cleared SimpleTranslate cache:"
    $clearedCaches | ForEach-Object { Write-Output "  $_" }
}
Write-LaunchDiagnostics -LatestLog $latestLog -CrashDir $crashDir -Stdout $stdout -Stderr $stderr -StartTime $startTime -BeforeCrashes $beforeCrashes
exit 2
