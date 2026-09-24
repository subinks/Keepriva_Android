[CmdletBinding()]
param(
    [string]$ProjectRoot = "C:\Tools\Setup\Projects\Keepriva_Android"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptVersion = "1.0.1"
$ExpectedBranch = "ui_eh_ph01_parallel_ci"
$Phase0Commit = "28855285c0a4b0d00013e11e436e03c962b3308b"
$PayloadRoot = Join-Path $PSScriptRoot "payload"

Write-Host "Keepriva Phase 1 parallel CI installer version $ScriptVersion" -ForegroundColor Green

function Write-Step {
    param([string]$Message)
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$GitArguments
    )

    $output = & git -C $ProjectRoot @GitArguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "git $($GitArguments -join ' ') failed:`n$($output -join "`n")"
    }
    return ($output -join "`n").TrimEnd()
}

function Write-Utf8Lf {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    $content = [System.IO.File]::ReadAllText($Source)
    $content = $content.Replace("`r`n", "`n").Replace("`r", "`n")
    $parent = Split-Path -Parent $Destination
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Destination, $content, $encoding)
}

Write-Step "Validate repository, branch, and Phase 0 ancestry"

if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "Project root does not exist: $ProjectRoot"
}
if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot ".git"))) {
    throw "Project root is not a Git working tree: $ProjectRoot"
}

$branch = Invoke-Git -GitArguments @("branch", "--show-current")
if ($branch -ne $ExpectedBranch) {
    throw "Expected branch '$ExpectedBranch' but found '$branch'."
}

& git -C $ProjectRoot merge-base --is-ancestor $Phase0Commit HEAD
if ($LASTEXITCODE -ne 0) {
    throw "Branch '$ExpectedBranch' does not contain Phase 0 commit $Phase0Commit."
}

$head = Invoke-Git -GitArguments @("rev-parse", "HEAD")
Write-Host "Branch: $branch"
Write-Host "HEAD:   $head"
Write-Host "Phase0: $Phase0Commit"

Write-Step "Protect application and test sources from accidental Phase 1 edits"

$statusText = Invoke-Git -GitArguments @("status", "--porcelain=v1", "--untracked-files=all")
$statusLines = @($statusText -split "`n")
foreach ($line in $statusLines) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }

    $path = $line.Substring(3).Replace("\", "/")
    $allowed =
        $path.StartsWith("Docs/ph_01/") -or
        $path -eq ".github/workflows/verify-keepriva-android.yml" -or
        $path.StartsWith("scripts/ci/")

    if (-not $allowed) {
        throw "Unrelated working-tree change detected: $line"
    }
}

$testRoot = Join-Path $ProjectRoot "app\src\androidTest\java\com\example\privatevault"
$testFiles = Get-ChildItem -LiteralPath $testRoot -Filter "*Test.java" -File
$totalTests = 0
foreach ($testFile in $testFiles) {
    $content = Get-Content -LiteralPath $testFile.FullName -Raw
    $totalTests += [regex]::Matches($content, '(?m)^\s*@Test\b').Count
}
if ($testFiles.Count -ne 9 -or $totalTests -ne 89) {
    throw "Expected 9 instrumentation classes and 89 tests; found $($testFiles.Count) classes and $totalTests tests."
}
Write-Host "Verified unchanged inventory: 9 classes and 89 tests."

Write-Step "Validate Phase 1 payload"

$payloadFiles = @(
    ".github/workflows/verify-keepriva-android.yml",
    "scripts/ci/stage-apks.sh",
    "scripts/ci/run-instrumentation-batch.sh",
    "scripts/ci/run-visual-verification.sh",
    "scripts/ci/patch-ci-screenshot-protection.py"
)

foreach ($relativePath in $payloadFiles) {
    $source = Join-Path $PayloadRoot $relativePath
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Phase 1 payload file is missing: $source"
    }
}

Write-Step "Install workflow and CI helper scripts"

foreach ($relativePath in $payloadFiles) {
    $source = Join-Path $PayloadRoot $relativePath
    $destination = Join-Path $ProjectRoot $relativePath
    Write-Utf8Lf -Source $source -Destination $destination
    Write-Host "Installed $relativePath"
}

Write-Step "Validate workflow topology and complete test coverage"

$workflowPath = Join-Path $ProjectRoot ".github\workflows\verify-keepriva-android.yml"
$workflow = Get-Content -LiteralPath $workflowPath -Raw

$requiredJobs = @(
    "build-apks:",
    "instrumentation:",
    "visual-verification:",
    "serial-safety-net:",
    "verification-gate:"
)
foreach ($job in $requiredJobs) {
    if (-not $workflow.Contains($job)) {
        throw "Workflow is missing required job marker: $job"
    }
}

$expectedClasses = @(
    "KeeprivaAuthHomeTest",
    "KeeprivaLifecycleRobustnessTest",
    "KeeprivaUiSmokeTest",
    "KeeprivaCategoryPreferencesTest",
    "KeeprivaCategoryDeletionTest",
    "KeeprivaItemCrudTest",
    "KeeprivaDataTransferTest",
    "KeeprivaSecuritySettingsTest"
)
foreach ($className in $expectedClasses) {
    if (-not $workflow.Contains("com.example.privatevault.$className")) {
        throw "Workflow is missing blocking test class $className."
    }
}

if ($workflow.Contains("com.example.privatevault.KeeprivaBiometricCiTest")) {
    throw "Biometric test must not be present in the blocking workflow matrix."
}
if (-not $workflow.Contains("max-parallel: 5")) {
    throw "Workflow does not enforce max-parallel: 5."
}
if (-not $workflow.Contains("fail-fast: false")) {
    throw "Workflow does not disable matrix fail-fast."
}

$installedTextFiles = @(
    $workflowPath,
    (Join-Path $ProjectRoot "scripts\ci\stage-apks.sh"),
    (Join-Path $ProjectRoot "scripts\ci\run-instrumentation-batch.sh"),
    (Join-Path $ProjectRoot "scripts\ci\run-visual-verification.sh"),
    (Join-Path $ProjectRoot "scripts\ci\patch-ci-screenshot-protection.py")
)
foreach ($path in $installedTextFiles) {
    if ([System.IO.File]::ReadAllText($path).Contains("`r")) {
        throw "Installed file contains CRLF/CR line endings: $path"
    }
}

Write-Step "Run available local syntax checks"

$bash = Get-Command bash -ErrorAction SilentlyContinue
if ($null -ne $bash) {
    Push-Location $ProjectRoot
    try {
        foreach ($script in @(
            "scripts/ci/stage-apks.sh",
            "scripts/ci/run-instrumentation-batch.sh",
            "scripts/ci/run-visual-verification.sh"
        )) {
            & bash -n $script
            if ($LASTEXITCODE -ne 0) {
                throw "bash -n failed for $script"
            }
        }
        Write-Host "bash -n passed for all shell helpers."

        $shellcheck = Get-Command shellcheck -ErrorAction SilentlyContinue
        if ($null -ne $shellcheck) {
            foreach ($script in @(
                "scripts/ci/stage-apks.sh",
                "scripts/ci/run-instrumentation-batch.sh",
                "scripts/ci/run-visual-verification.sh"
            )) {
                & shellcheck $script
                if ($LASTEXITCODE -ne 0) {
                    throw "shellcheck reported an error in $script."
                }
            }
            Write-Host "shellcheck passed."
        } else {
            Write-Host "shellcheck is unavailable locally; CI will run it when available." -ForegroundColor Yellow
        }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "bash is unavailable locally; GitHub Actions will run bash -n." -ForegroundColor Yellow
}

Write-Step "Final scope verification"

$changedText = Invoke-Git -GitArguments @(
    "status",
    "--porcelain=v1",
    "--untracked-files=all"
)
$changedPaths = @($changedText -split "`n")
foreach ($line in $changedPaths) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }
    $path = $line.Substring(3).Replace("\", "/")
    $allowed =
        $path.StartsWith("Docs/ph_01/") -or
        $path -eq ".github/workflows/verify-keepriva-android.yml" -or
        $path.StartsWith("scripts/ci/")
    if (-not $allowed) {
        throw "Phase 1 produced an out-of-scope change: $line"
    }
}

Write-Host "`nPhase 1 parallel CI files were installed successfully." -ForegroundColor Green
Write-Host "No Java source, Android resource, database, backup, or test file was modified."
Write-Host "Review with: git status --short; git diff --check"
