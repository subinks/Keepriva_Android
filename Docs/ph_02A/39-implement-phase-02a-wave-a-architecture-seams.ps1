[CmdletBinding()]
param(
    [string]$ExpectedBranch = "ui_eh_ph02a_mainactivity_modularization",
    [string]$Phase2CodeBaseline = "2212df03ad713e5693bfd8d6b46b7d5ae926b363"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ScriptVersion = "2.0.0"
$ExpectedMainActivityBlob = "d2107d224459ae0e9585cb2871a64a38e1317c4e"
$PayloadRoot = Join-Path $PSScriptRoot "wave_a_payload"
$ManifestPath = Join-Path $PSScriptRoot "wave_a_payload.sha256"

$InstallFiles = @(
    "app/src/main/java/com/example/privatevault/MainActivity.java",
    "app/src/main/java/com/example/privatevault/VaultController.java",
    "app/src/main/java/com/example/privatevault/ControllerRegistry.java",
    "app/src/main/java/com/example/privatevault/VaultSessionCoordinator.java",
    "app/src/main/java/com/example/privatevault/VaultRootRenderer.java",
    "app/src/test/java/com/example/privatevault/Phase2AArchitectureTest.java"
)

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Invoke-Git([string[]]$Arguments) {
    $output = @(& git @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed:`n$($output -join [Environment]::NewLine)"
    }
    return ($output -join "`n").TrimEnd()
}

function File-Sha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Count-JUnitTests([string]$Root) {
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        return 0
    }
    return @(
        Get-ChildItem -LiteralPath $Root -Recurse -Filter "*.java" |
            Select-String -Pattern '^\s*@Test(?:\s*\([^)]*\))?\s*$'
    ).Count
}

Write-Host "Keepriva Phase 2A Wave A1 installer version $ScriptVersion"

Write-Step "Validate repository, branch, and clean starting point"
$RepoRoot = (Invoke-Git @("rev-parse", "--show-toplevel")).Replace("\", "/")
$CurrentDirectory = (Get-Location).Path.Replace("\", "/")
if ($RepoRoot.TrimEnd("/") -ne $CurrentDirectory.TrimEnd("/")) {
    throw "Run this script from the repository root: $RepoRoot"
}

$Branch = Invoke-Git @("branch", "--show-current")
if ($Branch -ne $ExpectedBranch) {
    throw "Expected branch '$ExpectedBranch', but found '$Branch'."
}

& git merge-base --is-ancestor $Phase2CodeBaseline HEAD
if ($LASTEXITCODE -ne 0) {
    throw "HEAD does not descend from Phase 2 code baseline $Phase2CodeBaseline."
}

$StartingChanges = @(& git status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect the Git working tree."
}
if ($StartingChanges.Count -ne 0) {
    throw "The working tree must be clean before installing Wave A1:`n$($StartingChanges -join [Environment]::NewLine)"
}

$MainActivityRelativePath = $InstallFiles[0]
$MainActivityBlob = Invoke-Git @("rev-parse", "HEAD:$MainActivityRelativePath")
if ($MainActivityBlob -ne $ExpectedMainActivityBlob) {
    throw "MainActivity differs from the analyzed Phase 2 source. Expected $ExpectedMainActivityBlob, found $MainActivityBlob."
}

Write-Step "Validate the complete Wave A1 payload"
if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
    throw "Payload checksum manifest is missing: $ManifestPath"
}

$ExpectedHashes = @{}
foreach ($Line in Get-Content -LiteralPath $ManifestPath) {
    if ($Line -notmatch '^([0-9a-fA-F]{64})\s{2}(.+)$') {
        throw "Invalid checksum-manifest line: $Line"
    }
    $ExpectedHashes[$Matches[2].Replace("\", "/")] = $Matches[1].ToLowerInvariant()
}

foreach ($RelativePath in $InstallFiles) {
    $NormalizedPath = $RelativePath.Replace("\", "/")
    $SourcePath = Join-Path $PayloadRoot $RelativePath
    if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
        throw "Wave A1 payload file is missing: $SourcePath"
    }
    if (-not $ExpectedHashes.ContainsKey($NormalizedPath)) {
        throw "Checksum manifest does not contain: $NormalizedPath"
    }
    $ActualHash = File-Sha256 $SourcePath
    if ($ActualHash -ne $ExpectedHashes[$NormalizedPath]) {
        throw "Checksum mismatch for $NormalizedPath."
    }
}

$InstrumentationBefore = Count-JUnitTests (Join-Path $RepoRoot "app/src/androidTest")
$JvmBefore = Count-JUnitTests (Join-Path $RepoRoot "app/src/test")
if ($InstrumentationBefore -ne 95 -or $JvmBefore -ne 9) {
    throw "Expected 95 instrumentation and 9 JVM tests before Wave A1; found $InstrumentationBefore and $JvmBefore."
}

Write-Step "Install Wave A1 source and tests"
foreach ($RelativePath in $InstallFiles) {
    $SourcePath = Join-Path $PayloadRoot $RelativePath
    $TargetPath = Join-Path $RepoRoot $RelativePath
    $TargetDirectory = Split-Path -Parent $TargetPath
    if (-not (Test-Path -LiteralPath $TargetDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $TargetDirectory -Force | Out-Null
    }
    Copy-Item -LiteralPath $SourcePath -Destination $TargetPath -Force
    Write-Host "Installed $RelativePath"
}

Write-Step "Validate installed scope and architecture markers"
$AllowedPaths = @{}
foreach ($RelativePath in $InstallFiles) {
    $AllowedPaths[$RelativePath.Replace("\", "/")] = $true
}

$ResultingChanges = @(& git status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect the resulting Git changes."
}
foreach ($Line in $ResultingChanges) {
    if ($Line.Length -lt 4) {
        throw "Unexpected Git status record: $Line"
    }
    $ChangedPath = $Line.Substring(3).Replace("\", "/")
    if (-not $AllowedPaths.ContainsKey($ChangedPath)) {
        throw "Wave A1 produced an out-of-scope change: $Line"
    }
}

foreach ($RelativePath in $InstallFiles) {
    $SourcePath = Join-Path $PayloadRoot $RelativePath
    $TargetPath = Join-Path $RepoRoot $RelativePath
    if ((File-Sha256 $SourcePath) -ne (File-Sha256 $TargetPath)) {
        throw "Installed target differs from its payload: $RelativePath"
    }
}

$MainActivityText = Get-Content -LiteralPath (Join-Path $RepoRoot $MainActivityRelativePath) -Raw
if ($MainActivityText -match '\bprivate\s+SecretKey\s+sessionKey\b' -or
        $MainActivityText -match '\bsessionKey\s*=') {
    throw "MainActivity still directly owns or assigns sessionKey."
}
foreach ($Marker in @(
    "VaultSessionCoordinator vaultSession",
    "VaultRootRenderer rootRenderer",
    "vaultSession.requireKey()",
    "rootRenderer.render(screen, state)"
)) {
    if (-not $MainActivityText.Contains($Marker)) {
        throw "MainActivity is missing required Wave A1 marker: $Marker"
    }
}

$InstrumentationAfter = Count-JUnitTests (Join-Path $RepoRoot "app/src/androidTest")
$JvmAfter = Count-JUnitTests (Join-Path $RepoRoot "app/src/test")
if ($InstrumentationAfter -ne 95 -or $JvmAfter -ne 14) {
    throw "Expected 95 instrumentation and 14 JVM tests after Wave A1; found $InstrumentationAfter and $JvmAfter."
}

$ManifestText = Get-Content -LiteralPath (Join-Path $RepoRoot "app/src/main/AndroidManifest.xml") -Raw
if ($ManifestText -match 'android.permission.INTERNET') {
    throw "Security invariant failed: INTERNET permission is present."
}

& git diff --check
if ($LASTEXITCODE -ne 0) {
    throw "git diff --check reported whitespace errors."
}

Write-Step "Wave A1 result"
Write-Host "Wave A1 was installed successfully."
Write-Host "Instrumentation tests remain 95; JVM tests increased from 9 to 14."
Write-Host "No database, backup-format, manifest, resource, or CI-selector file was changed."
Write-Host "Run: .\gradlew.bat testDebugUnitTest"
Write-Host "Then run: .\gradlew.bat assembleDebug assembleDebugAndroidTest"
Write-Host "Commit and push only after both commands pass."

