function Use-SimpleTranslateProjectJava {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectDir,

        [ValidateSet("Gradle", "Client")]
        [string]$Purpose = "Gradle"
    )

    $project = (Resolve-Path -LiteralPath $ProjectDir).Path
    $propertiesPath = Join-Path $project "gradle.properties"
    if (-not (Test-Path -LiteralPath $propertiesPath)) {
        throw "Missing gradle.properties: $propertiesPath"
    }

    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $propertiesPath) {
        if ($line -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') {
            $properties[$matches[1]] = $matches[2]
        }
    }

    if ($Purpose -eq "Client") {
        $majorKey = "java_version"
        $homeKey = "client_java_home"
    } else {
        $majorKey = "gradle_java_version"
        $homeKey = "gradle_java_home"
    }
    $expectedMajor = 0
    if (-not [int]::TryParse([string]$properties[$majorKey], [ref]$expectedMajor)) {
        throw "Missing or invalid $majorKey in $propertiesPath"
    }
    $environmentKey = if ($Purpose -eq "Client") {
        "SIMPLETRANSLATE_CLIENT_JAVA_HOME"
    } else {
        "SIMPLETRANSLATE_GRADLE_JAVA_HOME"
    }
    $configuredHome = [Environment]::GetEnvironmentVariable($environmentKey)
    if ([string]::IsNullOrWhiteSpace($configuredHome)) {
        $configuredHome = [string]$properties[$homeKey]
    }
    if ([string]::IsNullOrWhiteSpace($configuredHome)) {
        $configuredHome = [string]$env:JAVA_HOME
    }
    if ([string]::IsNullOrWhiteSpace($configuredHome)) {
        throw "No Java $expectedMajor JDK configured for $Purpose. Set $environmentKey or JAVA_HOME."
    }
    $configuredHome = $configuredHome.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
    $java = Join-Path $configuredHome "bin\java.exe"
    $javac = Join-Path $configuredHome "bin\javac.exe"
    if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $javac)) {
        throw "Selected JDK is incomplete for Java $expectedMajor`: $configuredHome"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $versionText = [string]::Join("`n", @(& $java -version 2>&1))
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $versionMatch = [regex]::Match($versionText, '(?:version\s+")?(?<major>\d+)(?:\.|\")')
    if (-not $versionMatch.Success -or [int]$versionMatch.Groups["major"].Value -ne $expectedMajor) {
        throw "Selected JDK major does not match $majorKey=$expectedMajor`: $configuredHome"
    }

    $env:JAVA_HOME = $configuredHome
    $javaBin = Join-Path $configuredHome "bin"
    $pathParts = @($env:Path -split ';' | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_) -and
        -not [string]::Equals($_.TrimEnd('\'), $javaBin.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase)
    })
    $env:Path = [string]::Join(';', @($javaBin) + $pathParts)
    return $configuredHome
}
