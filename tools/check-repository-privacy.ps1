[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sensitiveNames = [System.Collections.Generic.HashSet[string]]::new(
    [string[]]@("local.properties", ".env"),
    [System.StringComparer]::OrdinalIgnoreCase
)
$sensitiveExtensions = [System.Collections.Generic.HashSet[string]]::new(
    [string[]]@(".aab", ".apk", ".jks", ".key", ".keystore", ".p12", ".pem", ".pfx"),
    [System.StringComparer]::OrdinalIgnoreCase
)
$textExtensions = [System.Collections.Generic.HashSet[string]]::new(
    [string[]]@(
        ".bat", ".c", ".cmake", ".cpp", ".gradle", ".h", ".java", ".json",
        ".kt", ".kts", ".md", ".properties", ".pro", ".ps1", ".sh", ".toml",
        ".txt", ".xml", ".yaml", ".yml"
    ),
    [System.StringComparer]::OrdinalIgnoreCase
)
$teamSpeakScheme = "ts3server" + "://"
$ipv4Pattern = [regex]'(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])'

Push-Location $repositoryRoot
try {
    $repositoryFiles = @(& git ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) {
        throw "git ls-files failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$sensitiveFiles = [System.Collections.Generic.List[string]]::new()
$endpointFiles = [System.Collections.Generic.List[string]]::new()
foreach ($relativePath in ($repositoryFiles | Sort-Object -Unique)) {
    $fileName = [IO.Path]::GetFileName($relativePath)
    $extension = [IO.Path]::GetExtension($relativePath)
    if ($sensitiveNames.Contains($fileName) -or $sensitiveExtensions.Contains($extension)) {
        $sensitiveFiles.Add($relativePath)
        continue
    }
    if (-not $textExtensions.Contains($extension)) {
        continue
    }

    $absolutePath = Join-Path $repositoryRoot $relativePath
    # ls-files --cached can list a file deleted in the working tree but not yet
    # committed; skip those rather than failing on the missing path.
    if (-not (Test-Path -LiteralPath $absolutePath)) {
        continue
    }
    $content = [IO.File]::ReadAllText($absolutePath)
    if ($content.IndexOf($teamSpeakScheme, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
        $ipv4Pattern.IsMatch($content)) {
        $endpointFiles.Add($relativePath)
    }
}

if ($sensitiveFiles.Count -gt 0) {
    Write-Output "FAIL: files that must remain untracked were found:"
    $sensitiveFiles | Sort-Object | ForEach-Object { Write-Output "  $_" }
}
if ($endpointFiles.Count -gt 0) {
    Write-Output "FAIL: potential server endpoint literals were found in:"
    $endpointFiles | Sort-Object | ForEach-Object { Write-Output "  $_" }
}
if ($sensitiveFiles.Count -gt 0 -or $endpointFiles.Count -gt 0) {
    Write-Output "Privacy scan failed. Review the listed paths without publishing matched content."
    exit 2
}

Write-Output "Repository privacy scan passed."
