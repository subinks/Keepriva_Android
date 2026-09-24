[CmdletBinding()]
param(
    [string]$ProjectRoot = "C:\Tools\Setup\Projects\Keepriva_Android"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptVersion = "1.0.0"
$ExpectedBranch = "ui_eh_ph01_parallel_ci"
$Phase0Commit = "28855285c0a4b0d00013e11e436e03c962b3308b"
$OldHelperSha256 = "e9fb799fb749461a9ff36278e3b2aea45c54658786c633155817cf1da04989b6"
$NewHelperSha256 = "b37d568e56136139af57bce6a4b91db91456ec0535d92a6f42516df12f30f08b"
$OldWorkflowSha256 = "c7a307d9fdcf9f3432aadceae69e93ef3741452980815172df857701411968f6"
$NewWorkflowSha256 = "0c807e196ebf5fda0d8e5dc028029261ab72cf6becff242dde7d57e9b39df87a"
$HelperPayloadPath = Join-Path $PSScriptRoot "payload\scripts\ci\run-visual-verification.sh"
$HelperInstalledPath = Join-Path $ProjectRoot "scripts\ci\run-visual-verification.sh"
$WorkflowPayloadPath = Join-Path $PSScriptRoot "payload\.github\workflows\verify-keepriva-android.yml"
$WorkflowInstalledPath = Join-Path $ProjectRoot ".github\workflows\verify-keepriva-android.yml"

Write-Host "Keepriva Phase 1 visual UI-dump hardening version $ScriptVersion" -ForegroundColor Green

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

    # Preserve the leading status column emitted by git status --porcelain.
    return ($output -join "`n").TrimEnd()
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-Utf8Lf {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    $content = [System.IO.File]::ReadAllText($Source)
    $content = $content.Replace("`r`n", "`n").Replace("`r", "`n")
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Destination, $content, $encoding)
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
    throw "Expected branch '$ExpectedBranch' but found '$branch'."
}

& git -C $ProjectRoot merge-base --is-ancestor $Phase0Commit HEAD
if ($LASTEXITCODE -ne 0) {
    throw "Branch '$ExpectedBranch' does not contain Phase 0 commit $Phase0Commit."
}

$head = Invoke-Git -GitArguments @("rev-parse", "HEAD")
Write-Host "Branch: $branch"
Write-Host "HEAD:   $head"

Write-Step "Protect application and test sources"

$protectedChanges = Invoke-Git -GitArguments @(
    "status",
    "--porcelain=v1",
    "--",
    "app/src"
)
if (-not [string]::IsNullOrWhiteSpace($protectedChanges)) {
    throw "Protected Phase 1 files already have uncommitted changes:`n$protectedChanges"
}
Write-Host "No application, Android-test, or resource changes detected."

Write-Step "Validate the hardened helper payload"

if (-not (Test-Path -LiteralPath $HelperPayloadPath -PathType Leaf)) {
    throw "Hardened helper payload is missing: $HelperPayloadPath"
}
if (-not (Test-Path -LiteralPath $HelperInstalledPath -PathType Leaf)) {
    throw "Installed visual helper is missing: $HelperInstalledPath"
}

$helperPayloadHash = Get-Sha256 -Path $HelperPayloadPath
if ($helperPayloadHash -ne $NewHelperSha256) {
    throw "Hardened helper payload checksum mismatch. Expected $NewHelperSha256 but found $helperPayloadHash."
}

$helperInstalledHash = Get-Sha256 -Path $HelperInstalledPath
if ($helperInstalledHash -ne $OldHelperSha256 -and $helperInstalledHash -ne $NewHelperSha256) {
    throw "Installed helper has unexpected local changes. Found checksum $helperInstalledHash."
}

if (-not (Test-Path -LiteralPath $WorkflowPayloadPath -PathType Leaf)) {
    throw "Hardened workflow payload is missing: $WorkflowPayloadPath"
}
if (-not (Test-Path -LiteralPath $WorkflowInstalledPath -PathType Leaf)) {
    throw "Installed workflow is missing: $WorkflowInstalledPath"
}

$workflowPayloadHash = Get-Sha256 -Path $WorkflowPayloadPath
if ($workflowPayloadHash -ne $NewWorkflowSha256) {
    throw "Hardened workflow payload checksum mismatch. Expected $NewWorkflowSha256 but found $workflowPayloadHash."
}

$workflowInstalledHash = Get-Sha256 -Path $WorkflowInstalledPath
if ($workflowInstalledHash -ne $OldWorkflowSha256 -and $workflowInstalledHash -ne $NewWorkflowSha256) {
    throw "Installed workflow has unexpected local changes. Found checksum $workflowInstalledHash."
}

Write-Step "Install retry-hardened visual helper"

Write-Utf8Lf -Source $HelperPayloadPath -Destination $HelperInstalledPath
Write-Host "Installed scripts/ci/run-visual-verification.sh"
Write-Utf8Lf -Source $WorkflowPayloadPath -Destination $WorkflowInstalledPath
Write-Host "Installed .github/workflows/verify-keepriva-android.yml"

Write-Step "Validate retry behavior and syntax"

$helperInstalledHash = Get-Sha256 -Path $HelperInstalledPath
if ($helperInstalledHash -ne $NewHelperSha256) {
    throw "Installed helper checksum mismatch after copy."
}

$workflowInstalledHash = Get-Sha256 -Path $WorkflowInstalledPath
if ($workflowInstalledHash -ne $NewWorkflowSha256) {
    throw "Installed workflow checksum mismatch after copy."
}

$helperContent = [System.IO.File]::ReadAllText($HelperInstalledPath)
$requiredMarkers = @(
    'readonly UI_DUMP_ATTEMPTS=12',
    'uiautomator-dump-retries.log',
    'grep -Fq "$expected_text"',
    'UI hierarchy unavailable after {attempts} attempts',
    '"vault home screen"',
    '"master-password unlock screen"'
)
foreach ($marker in $requiredMarkers) {
    if (-not $helperContent.Contains($marker)) {
        throw "Hardened helper is missing required marker: $marker"
    }
}

$workflowContent = [System.IO.File]::ReadAllText($WorkflowInstalledPath)
$workflowMarkers = @(
    'keepriva-android-${{ github.workflow }}-${{ github.ref }}-${{ github.event_name }}',
    "`${{ github.event_name == 'push' || github.event_name == 'pull_request' }}"
)
foreach ($marker in $workflowMarkers) {
    if (-not $workflowContent.Contains($marker)) {
        throw "Hardened workflow is missing required marker: $marker"
    }
}

$bytes = [System.IO.File]::ReadAllBytes($HelperInstalledPath)
if ([Array]::IndexOf($bytes, [byte]13) -ge 0) {
    throw "Installed helper contains CRLF line endings; LF is required."
}
$workflowBytes = [System.IO.File]::ReadAllBytes($WorkflowInstalledPath)
if ([Array]::IndexOf($workflowBytes, [byte]13) -ge 0) {
    throw "Installed workflow contains CRLF line endings; LF is required."
}

$bash = Get-Command bash -ErrorAction SilentlyContinue
if ($null -ne $bash) {
    & bash -n $HelperInstalledPath
    if ($LASTEXITCODE -ne 0) {
        throw "bash -n rejected the hardened visual helper."
    }
    Write-Host "bash -n passed."
} else {
    Write-Host "bash is unavailable locally; GitHub Actions will run bash -n." -ForegroundColor Yellow
}

Write-Step "Final scope verification"

$changedText = Invoke-Git -GitArguments @(
    "status",
    "--porcelain=v1",
    "--untracked-files=all"
)
foreach ($line in @($changedText -split "`n")) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }

    $path = $line.Substring(3).Replace("\", "/")
    $allowed =
        $path.StartsWith("Docs/ph_01/") -or
        $path -eq "scripts/ci/run-visual-verification.sh" -or
        $path -eq ".github/workflows/verify-keepriva-android.yml"

    if (-not $allowed) {
        throw "Phase 1 hardening produced an out-of-scope change: $line"
    }
}

Write-Host "`nPhase 1 visual and concurrency hardening was installed successfully." -ForegroundColor Green
Write-Host "No Java, Android resource, database, backup, or test file was modified."
Write-Host "Review with: git status --short; git diff --check"
