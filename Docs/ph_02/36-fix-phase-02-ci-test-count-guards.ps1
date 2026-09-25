[CmdletBinding()]
param(
    [string]$ExpectedBranch = "ui_eh_ph02_screen_routing"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptVersion = "1.0.0"
$Phase1Commit = "e3e8cd7c08a95f2c8450bcd22d426f1876a0adef"
$ExpectedOriginalBlob = "3effd1281d423e55d967e993a3be2b7d38e6f83c"
$Target = "scripts/ci/run-instrumentation-batch.sh"

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

function Get-TestCount([string]$Root) {
    $count = 0
    Get-ChildItem -LiteralPath $Root -Filter "*.java" -Recurse | ForEach-Object {
        $text = [System.IO.File]::ReadAllText($_.FullName)
        $count += ([regex]::Matches($text, "(?m)^\s*@Test\s*$")).Count
    }
    return $count
}

Write-Host "Keepriva Phase 2 CI test-count guard fix version $ScriptVersion"

Write-Step "Validate repository and branch"
$repoRoot = (Invoke-Git -Arguments @("rev-parse", "--show-toplevel") |
        Select-Object -First 1).Trim()
Set-Location $repoRoot

$branch = (Invoke-Git -Arguments @("branch", "--show-current") |
        Select-Object -First 1).Trim()
if ($branch -ne $ExpectedBranch) {
    throw "Expected branch '$ExpectedBranch', but the current branch is '$branch'."
}

& git merge-base --is-ancestor $Phase1Commit HEAD
if ($LASTEXITCODE -ne 0) {
    throw "Current HEAD does not contain the verified Phase 1 commit $Phase1Commit."
}

$targetPath = Join-Path $repoRoot $Target
if (-not (Test-Path -LiteralPath $targetPath -PathType Leaf)) {
    throw "CI helper was not found: $Target"
}

$content = [System.IO.File]::ReadAllText($targetPath)
$oldAuth = "auth-lifecycle-smoke) readonly EXPECTED_TESTS=26 ;;"
$newAuth = "auth-lifecycle-smoke) readonly EXPECTED_TESTS=32 ;;"
$oldSerial = "serial-safety-net) readonly EXPECTED_TESTS=88 ;;"
$newSerial = "serial-safety-net) readonly EXPECTED_TESTS=94 ;;"

$alreadyApplied = $content.Contains($newAuth) -and
        $content.Contains($newSerial) -and
        -not $content.Contains($oldAuth) -and
        -not $content.Contains($oldSerial)

if (-not $alreadyApplied) {
    $targetStatus = @(& git status --porcelain=v1 -- $Target)
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect $Target." }
    if ($targetStatus.Count -ne 0) {
        throw "Target file has an overlapping local change: $($targetStatus -join '; ')"
    }

    $actualBlob = (Invoke-Git -Arguments @("rev-parse", "HEAD:$Target") |
            Select-Object -First 1).Trim()
    if ($actualBlob -ne $ExpectedOriginalBlob) {
        throw "The CI helper differs from the analyzed Phase 2 version. No file was changed."
    }

    if (-not $content.Contains($oldAuth) -or -not $content.Contains($oldSerial)) {
        throw "The expected Phase 1 test-count guards were not found. No file was changed."
    }

    Write-Step "Update Phase 2 blocking test-count guards"
    $updated = $content.Replace($oldAuth, $newAuth).Replace($oldSerial, $newSerial)
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($targetPath, $updated, $utf8WithoutBom)
    Write-Host "Updated $Target"
} else {
    Write-Step "Validate existing correction"
    Write-Host "The Phase 2 test-count guards are already correct."
}

Write-Step "Verify counts and helper syntax"
$finalContent = [System.IO.File]::ReadAllText($targetPath)
if (-not $finalContent.Contains($newAuth) -or
        -not $finalContent.Contains($newSerial) -or
        $finalContent.Contains($oldAuth) -or
        $finalContent.Contains($oldSerial)) {
    throw "The Phase 2 test-count guard correction is incomplete."
}

$instrumentationCount = Get-TestCount (Join-Path $repoRoot "app/src/androidTest/java")
if ($instrumentationCount -ne 95) {
    throw "Expected 95 Phase 2 instrumentation tests, but found $instrumentationCount."
}

$blockingTotal = 32 + 24 + 14 + 12 + 12
if ($blockingTotal -ne 94) {
    throw "Internal blocking-suite total is invalid: $blockingTotal"
}

$bash = Get-Command bash -ErrorAction SilentlyContinue
if ($null -ne $bash) {
    & bash -n $targetPath
    if ($LASTEXITCODE -ne 0) { throw "bash -n rejected $Target." }
    Write-Host "bash syntax: passed"
} else {
    Write-Host "bash is unavailable locally; GitHub Actions will run bash -n."
}

& git diff --check -- $Target
if ($LASTEXITCODE -ne 0) {
    throw "git diff --check found a patch-format problem."
}

Write-Host "Instrumentation inventory: 95 total"
Write-Host "Blocking matrix inventory: 94 total"
Write-Host "auth-lifecycle-smoke: 32"
Write-Host "serial-safety-net: 94"

Write-Step "Patch complete"
Write-Host "Only $Target was modified." -ForegroundColor Green
Write-Host "Review with: git diff -- $Target"
Write-Host "Then commit, push, and rerun the Phase 2 workflow."
