[CmdletBinding()]
param(
    [switch]$Detailed
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot ".." )).Path
$failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure([string]$message) {
    $failures.Add($message)
    Write-Output "FAIL: $message"
}

function Write-Check([string]$name, [bool]$passed, [string]$detail) {
    $label = if ($passed) { "PASS" } else { "FAIL" }
    Write-Output "${label}: $name - $detail"
}

Write-Output "TS3 Mobile build environment"

$wrapper = Join-Path $repositoryRoot "gradlew.bat"
if (Test-Path -LiteralPath $wrapper) {
    Write-Check "Gradle wrapper" $true "gradlew.bat is present"
} else {
    Add-Failure "gradlew.bat is missing"
}

$javaExecutable = $null
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $javaHomeExecutable = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path -LiteralPath $javaHomeExecutable) {
        $javaExecutable = $javaHomeExecutable
    } else {
        Add-Failure "JAVA_HOME is set but its bin\java.exe was not found"
    }
}
if ($null -eq $javaExecutable) {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $javaExecutable = $javaCommand.Source
    }
}
if ($null -eq $javaExecutable) {
    Add-Failure "java is not available through JAVA_HOME or PATH"
} else {
    # Java prints -version output to stderr; Windows PowerShell 5.1 wraps redirected
    # native stderr as error records, which would terminate the script under Stop.
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $javaVersionOutput = (& $javaExecutable -version 2>&1 | Out-String).Trim()
    $ErrorActionPreference = $previousErrorActionPreference
    $javaVersionMatch = [regex]::Match($javaVersionOutput, 'version "(?<version>[0-9]+)')
    $javaMajor = if ($javaVersionMatch.Success) { [int]$javaVersionMatch.Groups["version"].Value } else { 0 }
    if ($javaMajor -eq 17) {
        Write-Check "JDK" $true "Java major version 17"
    } else {
        Add-Failure "JDK 17 is required; detected major version $javaMajor"
    }
    if ($Detailed) {
        Write-Output $javaVersionOutput
    }
}

$localProperties = Join-Path $repositoryRoot "local.properties"
$localPropertiesHasSdk =
    (Test-Path -LiteralPath $localProperties) -and
    [bool](Select-String -LiteralPath $localProperties -Pattern '^\s*sdk\.dir\s*=' -Quiet)

if (Test-Path -LiteralPath $localProperties) {
    Write-Check "local.properties" $true "present and intentionally untracked"
} else {
    Write-Output "WARN: local.properties is missing; Android Gradle tasks may need sdk.dir"
}

if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME) -and
    [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT) -and
    -not $localPropertiesHasSdk) {
    Add-Failure "ANDROID_HOME, ANDROID_SDK_ROOT or local.properties sdk.dir is required"
} else {
    if ($localPropertiesHasSdk -and
        [string]::IsNullOrWhiteSpace($env:ANDROID_HOME) -and
        [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        Write-Check "Android SDK configuration" $true "local.properties sdk.dir is configured"
    } else {
        $androidSdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
        Write-Check "Android SDK configuration" $true "SDK root is configured"
        $sdkManager = Join-Path $androidSdk "cmdline-tools\latest\bin\sdkmanager.bat"
        if (-not (Test-Path -LiteralPath $sdkManager)) {
            Write-Output "WARN: sdkmanager.bat was not found at the conventional latest path"
        }
    }
}

try {
    $udpClient = [System.Net.Sockets.UdpClient]::new(0)
    $udpClient.Dispose()
    Write-Check "UDP bind" $true "temporary local bind succeeded"
} catch {
    Add-Failure "temporary UDP bind failed: $($_.Exception.GetBaseException().Message)"
    Write-Output "INFO: close network or hardware-monitoring utilities, or restart Windows, before retrying Gradle"
}

if ($failures.Count -gt 0) {
    Write-Output "Build environment is not ready: $($failures.Count) blocking check(s)."
    exit 2
}

Write-Output "Build environment checks passed. Run the release checklist's Gradle command next."
