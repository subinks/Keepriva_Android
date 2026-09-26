[CmdletBinding()]
param(
    [string]$ExpectedBranch = "ui_eh_ph02_screen_routing",
    [switch]$RunLocalBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptVersion = "1.0.0"
$Phase1Commit = "e3e8cd7c08a95f2c8450bcd22d426f1876a0adef"
$ScriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$PayloadDirectory = Join-Path $ScriptDirectory "payload"
$ManifestPath = Join-Path $ScriptDirectory "payload.sha256"

$ExpectedBlobs = @{
    "app/build.gradle.kts" = "4da81a812c6e2fb9323953a7d2e5f706bee34c28"
    "app/src/main/java/com/example/privatevault/MainActivity.java" = "326bff49fa698c02b3220037b9afb510683f4636"
    "app/src/androidTest/java/com/example/privatevault/KeeprivaLifecycleRobustnessTest.java" = "2004ab67d1b75eab7d29aae073f10c82136b1279"
}

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Invoke-Git([string[]]$Arguments) {
    $output = & git @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed:`n$($output -join [Environment]::NewLine)"
    }
    return @($output)
}

function Read-Utf8([string]$Path) {
    return [System.IO.File]::ReadAllText($Path)
}

function Get-TestCount([string]$Root) {
    $count = 0
    if (-not (Test-Path -LiteralPath $Root)) { return 0 }
    Get-ChildItem -LiteralPath $Root -Filter "*.java" -Recurse | ForEach-Object {
        $count += ([regex]::Matches((Read-Utf8 $_.FullName), "(?m)^\s*@Test\s*$")).Count
    }
    return $count
}

function Read-PayloadManifest {
    if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
        throw "Payload manifest was not found: $ManifestPath"
    }

    $entries = @()
    foreach ($line in Get-Content -LiteralPath $ManifestPath) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -notmatch '^([0-9a-f]{64})\s+payload/(.+)$') {
            throw "Invalid payload manifest line: $line"
        }
        $entries += [PSCustomObject]@{
            Hash = $Matches[1]
            RelativePath = $Matches[2].Replace('/', [System.IO.Path]::DirectorySeparatorChar)
        }
    }
    if ($entries.Count -ne 18) {
        throw "Expected 18 Phase 2 payload files, but found $($entries.Count)."
    }
    return $entries
}

Write-Host "Keepriva Phase 2 screen-routing installer version $ScriptVersion"

Write-Step "Validate repository, branch, and Phase 1 ancestry"
$repoRoot = (Invoke-Git -Arguments @("rev-parse", "--show-toplevel") | Select-Object -First 1).Trim()
if ([string]::IsNullOrWhiteSpace($repoRoot)) {
    throw "Could not determine the Git repository root."
}
Set-Location $repoRoot

$branch = (Invoke-Git -Arguments @("branch", "--show-current") | Select-Object -First 1).Trim()
if ($branch -ne $ExpectedBranch) {
    throw "Expected branch '$ExpectedBranch', but the current branch is '$branch'."
}

& git merge-base --is-ancestor $Phase1Commit HEAD
if ($LASTEXITCODE -ne 0) {
    throw "Current HEAD does not contain the verified Phase 1 commit $Phase1Commit."
}

$head = (Invoke-Git -Arguments @("rev-parse", "HEAD") | Select-Object -First 1).Trim()
Write-Host "Branch: $branch"
Write-Host "HEAD:   $head"
Write-Host "Phase1: $Phase1Commit"

Write-Step "Validate the Phase 2 payload"
$payloadEntries = @(Read-PayloadManifest)
foreach ($entry in $payloadEntries) {
    $payloadPath = Join-Path $PayloadDirectory $entry.RelativePath
    if (-not (Test-Path -LiteralPath $payloadPath -PathType Leaf)) {
        throw "Payload file is missing: $payloadPath"
    }
    $actualHash = (Get-FileHash -LiteralPath $payloadPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $entry.Hash) {
        throw "Payload checksum mismatch: $($entry.RelativePath)"
    }
}
Write-Host "Verified $($payloadEntries.Count) payload files."

$allInstalled = $true
foreach ($entry in $payloadEntries) {
    $payloadPath = Join-Path $PayloadDirectory $entry.RelativePath
    $targetPath = Join-Path $repoRoot $entry.RelativePath
    if (-not (Test-Path -LiteralPath $targetPath -PathType Leaf)) {
        $allInstalled = $false
        break
    }
    $payloadHash = (Get-FileHash -LiteralPath $payloadPath -Algorithm SHA256).Hash
    $targetHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash
    if ($payloadHash -ne $targetHash) {
        $allInstalled = $false
        break
    }
}

if (-not $allInstalled) {
    Write-Step "Protect the working tree and analyzed source versions"
    $statusLines = @(& git status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect the working tree." }
    foreach ($line in $statusLines) {
        $path = if ($line.Length -gt 3) { $line.Substring(3).Replace('\', '/') } else { "" }
        if (-not $path.StartsWith("Docs/ph_02/")) {
            throw "Unrelated working-tree change detected: $line"
        }
    }

    foreach ($relativePath in $ExpectedBlobs.Keys) {
        $actualBlob = (Invoke-Git -Arguments @("rev-parse", "HEAD:$relativePath") |
                Select-Object -First 1).Trim()
        if ($actualBlob -ne $ExpectedBlobs[$relativePath]) {
            throw "Source '$relativePath' differs from the analyzed Phase 1 version. No application files were written."
        }
    }

    $baseText = Read-Utf8 (Join-Path $repoRoot "app/src/androidTest/java/com/example/privatevault/KeeprivaTestBase.java")
    $securityText = Read-Utf8 (Join-Path $repoRoot "app/src/androidTest/java/com/example/privatevault/KeeprivaSecuritySettingsTest.java")
    if (-not $baseText.Contains("Phase 1 security-dialog hardening") -or
            -not $securityText.Contains("Phase 1 security-dialog hardening")) {
        throw "Patch 34 is not present in the checked-out Phase 2 source."
    }

    $preInstrumentationCount = Get-TestCount (Join-Path $repoRoot "app/src/androidTest/java")
    if ($preInstrumentationCount -ne 89) {
        throw "Expected 89 pre-Phase-2 instrumentation tests, but found $preInstrumentationCount."
    }
    Write-Host "Verified pre-patch inventory: $preInstrumentationCount instrumentation tests."

    Write-Step "Install router, reusable UI foundation, and Phase 2 tests"
    foreach ($entry in $payloadEntries) {
        $source = Join-Path $PayloadDirectory $entry.RelativePath
        $destination = Join-Path $repoRoot $entry.RelativePath
        $parent = Split-Path -Parent $destination
        if (-not (Test-Path -LiteralPath $parent)) {
            [System.IO.Directory]::CreateDirectory($parent) | Out-Null
        }
        [System.IO.File]::Copy($source, $destination, $true)
        Write-Host "Installed $($entry.RelativePath.Replace('\', '/'))"
    }
} else {
    Write-Step "Validate the existing Phase 2 installation"
    Write-Host "All payload files are already installed with matching checksums."
}

Write-Step "Verify architecture, security invariants, and test inventory"
$mainPath = Join-Path $repoRoot "app/src/main/java/com/example/privatevault/MainActivity.java"
$mainText = Read-Utf8 $mainPath
$setContentViewCount = ([regex]::Matches($mainText, "\bsetContentView\s*\(")).Count
if ($setContentViewCount -ne 1) {
    throw "MainActivity must call setContentView exactly once; found $setContentViewCount calls."
}
foreach ($required in @(
    "private FrameLayout rootContent",
    "private final VaultScreenRouter screenRouter",
    "screenRouter.clear()",
    "Screen vault browser",
    "showVaultLoadError()"
)) {
    $allSource = $mainText + (Read-Utf8 (Join-Path $repoRoot "app/src/main/java/com/example/privatevault/VaultScreen.java"))
    if (-not $allSource.Contains($required)) {
        throw "Phase 2 source validation failed; missing marker: $required"
    }
}

$databaseText = Read-Utf8 (Join-Path $repoRoot "app/src/main/java/com/example/privatevault/VaultDatabase.java")
if ($databaseText -notmatch 'DB_VERSION\s*=\s*3\s*;') {
    throw "Phase 2 must retain database version 3."
}
$backupText = Read-Utf8 (Join-Path $repoRoot "app/src/main/java/com/example/privatevault/BackupManager.java")
if ($backupText -notmatch 'FORMAT_VERSION\s*=\s*1\s*;') {
    throw "Phase 2 must retain backup format version 1."
}
$manifestText = Read-Utf8 (Join-Path $repoRoot "app/src/main/AndroidManifest.xml")
if ($manifestText.Contains("android.permission.INTERNET")) {
    throw "INTERNET permission must remain absent."
}

$stateText = Read-Utf8 (Join-Path $repoRoot "app/src/main/java/com/example/privatevault/VaultNavigationState.java")
foreach ($forbidden in @("SecretKey", "VaultItem", "Parcelable", "implements Serializable")) {
    if ($stateText -match "(?m)^\s*(import|private|public|protected).*\b$([regex]::Escape($forbidden))\b") {
        throw "Forbidden persisted/sensitive type in VaultNavigationState: $forbidden"
    }
}

$instrumentationCount = Get-TestCount (Join-Path $repoRoot "app/src/androidTest/java")
$unitCount = Get-TestCount (Join-Path $repoRoot "app/src/test/java")
if ($instrumentationCount -ne 95) {
    throw "Expected 95 instrumentation tests after Phase 2, but found $instrumentationCount."
}
if ($unitCount -ne 9) {
    throw "Expected 9 Phase 2 JVM tests, but found $unitCount."
}
Write-Host "Instrumentation tests: $instrumentationCount"
Write-Host "JVM router/model tests: $unitCount"
Write-Host "Database version: 3"
Write-Host "Backup format version: 1"
Write-Host "Manifest INTERNET permission: absent"

foreach ($entry in $payloadEntries) {
    $targetPath = Join-Path $repoRoot $entry.RelativePath
    $payloadPath = Join-Path $PayloadDirectory $entry.RelativePath
    if ((Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash -ne
            (Get-FileHash -LiteralPath $payloadPath -Algorithm SHA256).Hash) {
        throw "Installed file differs from payload: $($entry.RelativePath)"
    }
}

Get-ChildItem -Path (Join-Path $repoRoot "app/src/main/res/layout") -Filter "*vault*.xml" | ForEach-Object {
    try { [xml](Get-Content -LiteralPath $_.FullName -Raw) | Out-Null }
    catch { throw "Invalid XML resource '$($_.FullName)': $($_.Exception.Message)" }
}

& git diff --check
if ($LASTEXITCODE -ne 0) {
    throw "git diff --check found a whitespace or patch-format problem."
}

if ($RunLocalBuild) {
    Write-Step "Run optional local Gradle verification"
    $gradleCommand = Get-Command gradle -ErrorAction SilentlyContinue
    if ($null -eq $gradleCommand) {
        throw "-RunLocalBuild was requested, but gradle is unavailable on PATH."
    }
    & gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
    if ($LASTEXITCODE -ne 0) { throw "Local Gradle verification failed." }
}

Write-Step "Final scope report"
$allowedTargets = @{}
foreach ($entry in $payloadEntries) {
    $allowedTargets[$entry.RelativePath.Replace('\', '/')] = $true
}
$statusAfter = @(& git status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0) { throw "Could not inspect final working-tree scope." }
foreach ($line in $statusAfter) {
    $path = if ($line.Length -gt 3) { $line.Substring(3).Replace('\', '/') } else { "" }
    if (-not $allowedTargets.ContainsKey($path) -and -not $path.StartsWith("Docs/ph_02/")) {
        throw "Phase 2 produced an out-of-scope change: $line"
    }
}

Write-Host ""
Write-Host "Phase 2 files were installed successfully." -ForegroundColor Green
Write-Host "The current 89 instrumentation tests were preserved and 6 lifecycle tests were added."
Write-Host "Nine JVM router/model tests were added."
Write-Host "Review with: git status --short; git diff --check"
Write-Host "Commit the Phase 2 plan and app changes, but not the installer payload unless desired."

