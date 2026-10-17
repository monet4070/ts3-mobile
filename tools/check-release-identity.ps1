[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-zA-Z0-9._:-]+$')]
    [string]$DeviceSerial,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$ApkPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    $SdkRoot = $env:ANDROID_SDK_ROOT
}
if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    throw "Set ANDROID_HOME/ANDROID_SDK_ROOT or pass -SdkRoot."
}
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $repositoryRoot "app/build/outputs/apk/release/app-release-unsigned.apk"
}
$isWindowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$adbName = if ($isWindowsHost) { "adb.exe" } else { "adb" }
$d8Name = if ($isWindowsHost) { "d8.bat" } else { "d8" }
$adb = Join-Path $SdkRoot "platform-tools/$adbName"
$d8 = Join-Path $SdkRoot "build-tools/35.0.0/$d8Name"
$androidJar = Join-Path $SdkRoot "platforms/android-35/android.jar"
$source = Join-Path $repositoryRoot "app/src/releaseSmokeTest/java/io/github/ts3mobile/app/release/IdentityRoundTripProbe.java"
foreach ($requiredFile in @($ApkPath, $adb, $d8, $androidJar, $source)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file not found: $requiredFile"
    }
}
$ApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
$javacName = if ($isWindowsHost) { "javac.exe" } else { "javac" }
$javac = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME "bin/$javacName"
} else {
    (Get-Command $javacName -ErrorAction Stop).Source
}

function Invoke-CheckedNative {
    param([string]$Executable, [string[]]$Arguments, [string]$Operation)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed with exit code $LASTEXITCODE."
    }
}

$runId = [guid]::NewGuid().ToString("N")
$outputDirectory = Join-Path $repositoryRoot "build/release-identity-smoke/$runId"
$classesDirectory = Join-Path $outputDirectory "classes"
[void][IO.Directory]::CreateDirectory($classesDirectory)
$probeJar = Join-Path $outputDirectory "probe.jar"
$probeClass = Join-Path $classesDirectory "io/github/ts3mobile/app/release/IdentityRoundTripProbe.class"
Invoke-CheckedNative $javac @("--release", "8", "-encoding", "UTF-8", "-d", $classesDirectory, $source) "Probe compilation"
Invoke-CheckedNative $d8 @("--min-api", "26", "--lib", $androidJar, "--output", $probeJar, $probeClass) "Probe dex compilation"

# The tested APK is neither installed nor recompiled. Only its actual packaged
# code is on the classpath, alongside the independent probe (no dependency jars).
$remoteDirectory = "/data/local/tmp/ts3-release-identity-$runId"
$remoteCreated = $false
try {
    Invoke-CheckedNative $adb @("-s", $DeviceSerial, "shell", "mkdir", $remoteDirectory) "Device scratch directory creation"
    $remoteCreated = $true
    Invoke-CheckedNative $adb @("-s", $DeviceSerial, "push", $probeJar, "$remoteDirectory/probe.jar") "Probe transfer"
    Invoke-CheckedNative $adb @("-s", $DeviceSerial, "push", $ApkPath, "$remoteDirectory/app.apk") "APK transfer"
    Invoke-CheckedNative $adb @("-s", $DeviceSerial, "shell", "chmod", "444", "$remoteDirectory/probe.jar", "$remoteDirectory/app.apk") "Dex file permissions"
    $command = "CLASSPATH=$remoteDirectory/probe.jar:$remoteDirectory/app.apk app_process /system/bin io.github.ts3mobile.app.release.IdentityRoundTripProbe"
    $probeOutput = @(& $adb -s $DeviceSerial shell $command 2>&1)
    $probeExitCode = $LASTEXITCODE
    $probeOutput | Set-Content -LiteralPath (Join-Path $outputDirectory "result.log") -Encoding utf8
    $probeOutput | ForEach-Object { Write-Output $_ }
    Write-Output "Probe output: $outputDirectory"
    if ($probeExitCode -ne 0 -or $probeOutput -notcontains "RELEASE_IDENTITY_SMOKE_OK") {
        throw "Packaged APK identity regression failed (exit $probeExitCode)."
    }
} finally {
    if ($remoteCreated) {
        & $adb -s $DeviceSerial shell rm -f "$remoteDirectory/probe.jar" "$remoteDirectory/app.apk"
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not remove probe files from the device scratch directory."
        }
        & $adb -s $DeviceSerial shell rmdir $remoteDirectory
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not remove the device scratch directory."
        }
    }
}
