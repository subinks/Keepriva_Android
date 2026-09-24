[CmdletBinding()]
param(
    [string]$ProjectRoot = "C:\Tools\Setup\Projects\Keepriva_Android",
    [string]$ArtifactZip = "",
    [switch]$PushTag,
    [switch]$SkipScreenshots
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ExpectedBranch = "ui_eh_ph0"
$BaselineCommit = "6851c467896abc3d8d375cfb9f237379adce5708"
$BaselineTag = "ui-enhancement-green-2026-09-24"
$BaselineRunId = "35973848830"
$BaselineArtifactName = "keepriva-android-verification"
$Repository = "subinks/Keepriva_Android"
$ScriptVersion = "2.0.0"

Write-Host "Keepriva Phase 0 baseline script version $ScriptVersion" -ForegroundColor Green

function Write-Step {
    param([string]$Message)
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$GitArguments
    )

    if ($GitArguments.Count -eq 0) {
        throw "Invoke-Git requires at least one Git argument."
    }

    $output = & git -C $ProjectRoot @GitArguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "git $($GitArguments -join ' ') failed:`n$($output -join "`n")"
    }
    return ($output -join "`n").Trim()
}

function Write-Utf8NoBom {
    param(
        [string]$Path,
        [string]$Content
    )
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Get-RequiredMatch {
    param(
        [string]$Content,
        [string]$Pattern,
        [string]$Description
    )
    $match = [regex]::Match($Content, $Pattern)
    if (-not $match.Success) {
        throw "Could not determine $Description. The expected source pattern was not found."
    }
    return $match.Groups[1].Value
}

Write-Step "Validate repository and branch"

if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "Project root does not exist: $ProjectRoot"
}
if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot ".git"))) {
    throw "Project root is not a Git working tree: $ProjectRoot"
}

$branch = Invoke-Git -GitArguments @("branch", "--show-current")
if ($branch -ne $ExpectedBranch) {
    throw "Expected branch '$ExpectedBranch' but found '$branch'. Switch branches and run again."
}

& git -C $ProjectRoot merge-base --is-ancestor $BaselineCommit HEAD
if ($LASTEXITCODE -ne 0) {
    throw "The selected branch does not contain required baseline commit $BaselineCommit."
}

$headBefore = Invoke-Git -GitArguments @("rev-parse", "HEAD")
Write-Host "Branch: $branch"
Write-Host "HEAD:   $headBefore"
Write-Host "Base:   $BaselineCommit"

Write-Step "Create or validate the protected baseline tag"

$existingTag = & git -C $ProjectRoot rev-parse -q --verify "refs/tags/$BaselineTag" 2>$null
if ($LASTEXITCODE -eq 0) {
    $tagTarget = Invoke-Git -GitArguments @("rev-list", "-n", "1", $BaselineTag)
    if ($tagTarget -ne $BaselineCommit) {
        throw "Tag '$BaselineTag' points to $tagTarget, not $BaselineCommit. Refusing to move it."
    }
    Write-Host "Tag already exists and points to the correct baseline commit."
} else {
    Invoke-Git -GitArguments @(
        "tag",
        "-a",
        $BaselineTag,
        $BaselineCommit,
        "-m",
        "Keepriva green UI enhancement baseline 2026-09-24"
    ) | Out-Null
    Write-Host "Created annotated tag $BaselineTag at $BaselineCommit."
}

if ($PushTag) {
    Invoke-Git -GitArguments @("push", "origin", "refs/tags/$BaselineTag") | Out-Null
    Write-Host "Pushed tag to origin."
} else {
    Write-Host "Tag was not pushed. Use -PushTag or run: git push origin $BaselineTag" -ForegroundColor Yellow
}

Write-Step "Enable the unchanged verification workflow on ui_eh_ph0"

$workflowPath = Join-Path $ProjectRoot ".github\workflows\verify-keepriva-android.yml"
if (-not (Test-Path -LiteralPath $workflowPath -PathType Leaf)) {
    throw "Workflow file not found: $workflowPath"
}

$workflow = Get-Content -LiteralPath $workflowPath -Raw
if ($workflow -notmatch '(?m)^\s{6}- ui_eh_ph0\s*$') {
    $anchor = "      - ui_enhancement"
    if (-not $workflow.Contains($anchor)) {
        throw "Could not find the ui_enhancement workflow branch anchor. Review the workflow manually."
    }
    $workflow = $workflow.Replace($anchor, "$anchor`r`n      - ui_eh_ph0")
    Write-Utf8NoBom -Path $workflowPath -Content $workflow
    Write-Host "Added ui_eh_ph0 to the workflow push filter."
} else {
    Write-Host "Workflow already includes ui_eh_ph0."
}

Write-Step "Verify baseline constants and test inventory"

$buildFile = Get-Content -LiteralPath (Join-Path $ProjectRoot "app\build.gradle.kts") -Raw
$databaseFile = Get-Content -LiteralPath (Join-Path $ProjectRoot "app\src\main\java\com\example\privatevault\VaultDatabase.java") -Raw
$backupFile = Get-Content -LiteralPath (Join-Path $ProjectRoot "app\src\main\java\com\example\privatevault\BackupManager.java") -Raw
$manifestFile = Get-Content -LiteralPath (Join-Path $ProjectRoot "app\src\main\AndroidManifest.xml") -Raw

$versionCode = Get-RequiredMatch $buildFile '(?m)^\s*versionCode\s*=\s*(\d+)\s*$' "versionCode"
$versionName = Get-RequiredMatch $buildFile '(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$' "versionName"
$databaseVersion = Get-RequiredMatch $databaseFile 'DB_VERSION\s*=\s*(\d+)\s*;' "database version"
$backupFormatVersion = Get-RequiredMatch $backupFile 'FORMAT_VERSION\s*=\s*(\d+)\s*;' "backup format version"

if ($manifestFile -match 'android\.permission\.INTERNET') {
    throw "The manifest contains android.permission.INTERNET. Phase 0 requires offline-only behavior."
}

$testRoot = Join-Path $ProjectRoot "app\src\androidTest\java\com\example\privatevault"
$testFiles = Get-ChildItem -LiteralPath $testRoot -Filter "*Test.java" -File | Sort-Object Name
$testInventory = @()
$totalTests = 0
foreach ($testFile in $testFiles) {
    $content = Get-Content -LiteralPath $testFile.FullName -Raw
    $count = [regex]::Matches($content, '(?m)^\s*@Test\b').Count
    $totalTests += $count
    $testInventory += [ordered]@{
        class = $testFile.BaseName
        methods = $count
    }
}

$biometricTests = ($testInventory | Where-Object { $_.class -eq "KeeprivaBiometricCiTest" }).methods
$blockingTests = $totalTests - $biometricTests
if ($testFiles.Count -ne 9 -or $totalTests -ne 89 -or $blockingTests -ne 88 -or $biometricTests -ne 1) {
    throw "Unexpected test baseline. Expected 9 classes / 89 total / 88 blocking / 1 biometric; found $($testFiles.Count) / $totalTests / $blockingTests / $biometricTests."
}

Write-Host "Application: versionCode $versionCode, versionName $versionName"
Write-Host "Database:    version $databaseVersion"
Write-Host "Backup:      format version $backupFormatVersion"
Write-Host "Tests:       $totalTests total, $blockingTests blocking, $biometricTests biometric diagnostic"
Write-Host "Manifest:    INTERNET permission absent"

Write-Step "Preserve required visual baselines"

$baselineRoot = Join-Path $ProjectRoot "docs\phase-0-baseline"
$screenshotRoot = Join-Path $baselineRoot "screenshots"
New-Item -ItemType Directory -Force -Path $screenshotRoot | Out-Null

if (-not $SkipScreenshots) {
    $requiredScreenshots = @(
        "01-fresh-install-setup.png",
        "02-vault-home.png",
        "03-real-unlock-screen.png"
    )
    $screenshotsAlreadyPresent = $true
    foreach ($name in $requiredScreenshots) {
        $existingPath = Join-Path $screenshotRoot $name
        if (-not (Test-Path -LiteralPath $existingPath -PathType Leaf) -or (Get-Item -LiteralPath $existingPath).Length -eq 0) {
            $screenshotsAlreadyPresent = $false
        }
    }

    if ($screenshotsAlreadyPresent) {
        Write-Host "All three baseline screenshots are already present; download is not required."
    } else {
    $tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("keepriva-phase0-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    try {
        if (-not [string]::IsNullOrWhiteSpace($ArtifactZip)) {
            if (-not (Test-Path -LiteralPath $ArtifactZip -PathType Leaf)) {
                throw "Artifact ZIP does not exist: $ArtifactZip"
            }
            Expand-Archive -LiteralPath $ArtifactZip -DestinationPath $tempRoot -Force
        } else {
            $gh = Get-Command gh -ErrorAction SilentlyContinue
            if ($null -eq $gh) {
                throw "GitHub CLI was not found. Install/authenticate gh, pass -ArtifactZip, or use -SkipScreenshots temporarily."
            }
            & gh run download $BaselineRunId --repo $Repository --name $BaselineArtifactName --dir $tempRoot
            if ($LASTEXITCODE -ne 0) {
                throw "GitHub CLI could not download workflow run $BaselineRunId. Check 'gh auth status'."
            }
        }

        foreach ($name in $requiredScreenshots) {
            $source = Get-ChildItem -LiteralPath $tempRoot -Recurse -File -Filter $name | Select-Object -First 1
            if ($null -eq $source) {
                throw "Required screenshot '$name' was not found in the baseline artifact."
            }
            Copy-Item -LiteralPath $source.FullName -Destination (Join-Path $screenshotRoot $name) -Force
        }
        Write-Host "Copied setup, home, and unlock screenshots."
    } finally {
        if (Test-Path -LiteralPath $tempRoot) {
            Remove-Item -LiteralPath $tempRoot -Recurse -Force
        }
    }
    }
} else {
    Write-Host "Screenshot copy skipped. Phase 0 cannot be closed until all three screenshots are preserved." -ForegroundColor Yellow
}

Write-Step "Generate durable baseline metadata"

New-Item -ItemType Directory -Force -Path $baselineRoot | Out-Null
$metadata = [ordered]@{
    capturedOnUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    repository = $Repository
    workingBranch = $ExpectedBranch
    headAtCapture = $headBefore
    greenCommit = $BaselineCommit
    baselineTag = $BaselineTag
    workflowRunId = [long]$BaselineRunId
    workflowRunUrl = "https://github.com/subinks/Keepriva_Android/actions/runs/$BaselineRunId"
    workflowConclusion = "success"
    workflowJobDurationSeconds = 1097
    workflowWallClockSeconds = 1102
    application = [ordered]@{
        versionCode = [int]$versionCode
        versionName = $versionName
        databaseVersion = [int]$databaseVersion
        backupFormatVersion = [int]$backupFormatVersion
        internetPermissionPresent = $false
    }
    instrumentation = [ordered]@{
        classCount = $testFiles.Count
        totalMethods = $totalTests
        blockingMethods = $blockingTests
        biometricDiagnosticMethods = $biometricTests
        classes = $testInventory
    }
}

$metadataPath = Join-Path $baselineRoot "baseline-metadata.json"
Write-Utf8NoBom -Path $metadataPath -Content ($metadata | ConvertTo-Json -Depth 8)

$inventoryLines = $testInventory | ForEach-Object { "| ``$($_.class)`` | $($_.methods) |" }
$record = @"
# Keepriva Phase 0 Baseline Record

Generated by ``31-implement-phase-0-baseline-freeze.ps1``.

## Baseline

| Item | Value |
|---|---|
| Repository | ``$Repository`` |
| Working branch | ``$ExpectedBranch`` |
| Head at capture | ``$headBefore`` |
| Green commit | ``$BaselineCommit`` |
| Protected tag | ``$BaselineTag`` |
| Green workflow run | [``$BaselineRunId``](https://github.com/subinks/Keepriva_Android/actions/runs/$BaselineRunId) |
| App version | ``$versionCode`` / ``$versionName`` |
| Database version | ``$databaseVersion`` |
| Backup format version | ``$backupFormatVersion`` |
| INTERNET permission | Absent |
| Instrumentation tests | $totalTests methods across $($testFiles.Count) classes |
| Blocking tests | $blockingTests |
| Biometric diagnostic | $biometricTests |

## Test inventory

| Class | Test methods |
|---|---:|
$($inventoryLines -join "`r`n")

## Required Phase 0 screenshots

- ``screenshots/01-fresh-install-setup.png``
- ``screenshots/02-vault-home.png``
- ``screenshots/03-real-unlock-screen.png``

## Final Phase 0 run

After committing and pushing this baseline package, add the final ``ui_eh_ph0``
workflow URL and commit SHA here before closing Phase 0.
"@

$recordPath = Join-Path $baselineRoot "BASELINE_RECORD.md"
Write-Utf8NoBom -Path $recordPath -Content $record

Write-Step "Final validation"

$tagTarget = Invoke-Git -GitArguments @("rev-list", "-n", "1", $BaselineTag)
if ($tagTarget -ne $BaselineCommit) {
    throw "Final tag validation failed."
}
if ((Get-Content -LiteralPath $workflowPath -Raw) -notmatch '(?m)^\s{6}- ui_eh_ph0\s*$') {
    throw "Final workflow branch validation failed."
}
if (-not $SkipScreenshots) {
    foreach ($name in @("01-fresh-install-setup.png", "02-vault-home.png", "03-real-unlock-screen.png")) {
        $path = Join-Path $screenshotRoot $name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -eq 0) {
            throw "Final screenshot validation failed for $name."
        }
    }
}

Write-Host "`nPhase 0 files are prepared successfully." -ForegroundColor Green
Write-Host "Review with: git status --short"
Write-Host "Then commit and push ui_eh_ph0 so the unchanged 89-test workflow can run."
