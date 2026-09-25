[CmdletBinding()]
param(
    [string]$ExpectedBranch = "ui_eh_ph02a_mainactivity_modularization",
    [string]$Phase2CodeBaseline = "2212df03ad713e5693bfd8d6b46b7d5ae926b363"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ScriptVersion = "2.0.0"

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

function Count-JUnitTests([string]$Root) {
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        return 0
    }
    return @(
        Get-ChildItem -LiteralPath $Root -Recurse -Filter "*.java" |
            Select-String -Pattern '^\s*@Test(?:\s*\([^)]*\))?\s*$'
    ).Count
}

Write-Host "Keepriva Phase 2A implementation-readiness validator version $ScriptVersion"

Write-Step "Validate repository and implementation branch"
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

$WorkingChanges = @(& git status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect the Git working tree."
}
if ($WorkingChanges.Count -ne 0) {
    throw "The working tree must be clean before Wave A1:`n$($WorkingChanges -join [Environment]::NewLine)"
}

Write-Host "Branch: $Branch"
Write-Host "HEAD:   $(Invoke-Git @('rev-parse', 'HEAD'))"

Write-Step "Validate committed Phase 2A documentation"
$MasterPlanPath = Join-Path $RepoRoot "Docs/Keepriva_UI_Restructure_Phase_Wise_Implementation_and_Parallel_Test_Plan.md"
$Phase2APlanPath = Join-Path $RepoRoot "Docs/ph_02A/PHASE_02A_MAINACTIVITY_MODULARIZATION_ANALYSIS_DESIGN_AND_IMPLEMENTATION_PLAN.md"

foreach ($Path in @($MasterPlanPath, $Phase2APlanPath)) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required plan is missing: $Path"
    }
}

$MasterPlanText = Get-Content -LiteralPath $MasterPlanPath -Raw
$Phase2APlanText = Get-Content -LiteralPath $Phase2APlanPath -Raw
if (-not $MasterPlanText.Contains("Phase 2A - Modularize MainActivity without changing behavior")) {
    throw "The master plan does not contain the Phase 2A architecture gate."
}
if (-not $MasterPlanText.Contains("VaultSessionCoordinator") -or
        -not $MasterPlanText.Contains("sole in-memory owner of the active")) {
    throw "The master plan does not contain the corrected vault-key ownership rule."
}
if (-not $Phase2APlanText.Contains("Patch A - Contracts and common ownership")) {
    throw "The standalone Phase 2A implementation plan is incomplete."
}

Write-Step "Validate analyzed source and security invariants"
$MainActivityRelativePath = "app/src/main/java/com/example/privatevault/MainActivity.java"
$MainActivityPath = Join-Path $RepoRoot $MainActivityRelativePath
$MainActivityLines = @(Get-Content -LiteralPath $MainActivityPath).Count
if ($MainActivityLines -ne 3684) {
    throw "Expected the analyzed 3,684-line MainActivity, but found $MainActivityLines lines."
}

$MainActivityBlob = Invoke-Git @("rev-parse", "HEAD:$MainActivityRelativePath")
if ($MainActivityBlob -ne "d2107d224459ae0e9585cb2871a64a38e1317c4e") {
    throw "MainActivity differs from the analyzed Phase 2 source. Found blob $MainActivityBlob."
}

$ManifestText = Get-Content -LiteralPath (Join-Path $RepoRoot "app/src/main/AndroidManifest.xml") -Raw
if ($ManifestText -match 'android.permission.INTERNET') {
    throw "Security invariant failed: INTERNET permission is present."
}

$JavaText = (
    Get-ChildItem -LiteralPath (Join-Path $RepoRoot "app/src/main/java") -Recurse -Filter "*.java" |
        ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }
) -join "`n"
if ($JavaText -notmatch 'DB_VERSION\s*=\s*3') {
    throw "Database version 3 invariant was not found."
}
if ($JavaText -notmatch 'FORMAT_VERSION\s*=\s*1') {
    throw "Backup format version 1 invariant was not found."
}

Write-Step "Validate test inventory and CI count guards"
$InstrumentationTests = Count-JUnitTests (Join-Path $RepoRoot "app/src/androidTest")
$JvmTests = Count-JUnitTests (Join-Path $RepoRoot "app/src/test")
if ($InstrumentationTests -ne 95 -or $JvmTests -ne 9) {
    throw "Expected 95 instrumentation and 9 JVM tests; found $InstrumentationTests and $JvmTests."
}

$BatchHelperPath = Join-Path $RepoRoot "scripts/ci/run-instrumentation-batch.sh"
if (-not (Test-Path -LiteralPath $BatchHelperPath -PathType Leaf)) {
    throw "CI batch helper is missing: $BatchHelperPath"
}
$BatchHelper = Get-Content -LiteralPath $BatchHelperPath -Raw
$RequiredGuards = @(
    'auth-lifecycle-smoke\)\s+readonly\s+EXPECTED_TESTS=32',
    'categories\)\s+readonly\s+EXPECTED_TESTS=24',
    'item-core\)\s+readonly\s+EXPECTED_TESTS=14',
    'data-transfer\)\s+readonly\s+EXPECTED_TESTS=12',
    'security\)\s+readonly\s+EXPECTED_TESTS=12',
    'serial-safety-net\)\s+readonly\s+EXPECTED_TESTS=94'
)
foreach ($Guard in $RequiredGuards) {
    if ($BatchHelper -notmatch $Guard) {
        throw "Expected CI test-count guard was not found: $Guard"
    }
}

Write-Step "Readiness result"
Write-Host "Phase 2A Wave A1 prerequisites are satisfied."
Write-Host "Inventory: 95 instrumentation tests and 9 JVM tests."
Write-Host "Database version 3, backup format 1, and offline manifest are unchanged."
Write-Host "You may now run 39-implement-phase-02a-wave-a-architecture-seams.ps1."
