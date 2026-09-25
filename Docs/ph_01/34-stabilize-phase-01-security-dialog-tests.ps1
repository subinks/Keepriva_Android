[CmdletBinding()]
param(
    [string]$ExpectedBranch = "ui_eh_ph01_parallel_ci"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptVersion = "1.0.0"
$BaseRelativePath = "app/src/androidTest/java/com/example/privatevault/KeeprivaTestBase.java"
$SecurityRelativePath = "app/src/androidTest/java/com/example/privatevault/KeeprivaSecuritySettingsTest.java"
$Marker = "Phase 1 security-dialog hardening"
$ExpectedBaseBlob = "b91c98a6f3a7f5c58f4d6f8879bbda2e9f435986"
$ExpectedSecurityBlob = "bb0476eedfbe1363c114aceceee0b272c9d12653"

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

function Write-Utf8NoBom([string]$Path, [string]$Content) {
    $encoding = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

Write-Host "Keepriva Phase 1 security-dialog test hardening version $ScriptVersion"

Write-Step "Validate repository and branch"
$repoRoot = (Invoke-Git -Arguments @("rev-parse", "--show-toplevel") | Select-Object -First 1).Trim()
if ([string]::IsNullOrWhiteSpace($repoRoot)) {
    throw "Could not determine the Git repository root."
}
Set-Location $repoRoot

$branch = (Invoke-Git -Arguments @("branch", "--show-current") | Select-Object -First 1).Trim()
if ($branch -ne $ExpectedBranch) {
    throw "Expected branch '$ExpectedBranch', but the current branch is '$branch'."
}

$head = (Invoke-Git -Arguments @("rev-parse", "HEAD") | Select-Object -First 1).Trim()
Write-Host "Branch: $branch"
Write-Host "HEAD:   $head"

$basePath = Join-Path $repoRoot $BaseRelativePath
$securityPath = Join-Path $repoRoot $SecurityRelativePath
foreach ($path in @($basePath, $securityPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required test source was not found: $path"
    }
}

Write-Step "Protect target files from overlapping local edits"
$targetChanges = @(& git status --porcelain -- $BaseRelativePath $SecurityRelativePath)
if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect the target test files."
}

$baseBefore = Read-Utf8 $basePath
$securityBefore = Read-Utf8 $securityPath
$baseBeforeNormalized = $baseBefore.Replace("`r`n", "`n")
$securityBeforeNormalized = $securityBefore.Replace("`r`n", "`n")
$alreadyApplied = $baseBefore.Contains($Marker) -and $securityBefore.Contains($Marker)

if ($targetChanges.Count -gt 0 -and -not $alreadyApplied) {
    throw "One or both target test files already have local changes. Commit or stash them before running this patch."
}

if (-not $alreadyApplied) {
    $baseBlob = (Invoke-Git -Arguments @("rev-parse", "HEAD:$BaseRelativePath") | Select-Object -First 1).Trim()
    $securityBlob = (Invoke-Git -Arguments @("rev-parse", "HEAD:$SecurityRelativePath") | Select-Object -First 1).Trim()
    if ($baseBlob -ne $ExpectedBaseBlob -or $securityBlob -ne $ExpectedSecurityBlob) {
        throw "The target test sources do not match the analyzed failing build. No files were written."
    }
}

$testCountBefore = 0
Get-ChildItem -Path (Join-Path $repoRoot "app/src/androidTest/java") -Filter "*Test.java" -Recurse | ForEach-Object {
    $testCountBefore += ([regex]::Matches((Read-Utf8 $_.FullName), "(?m)^\s*@Test\s*$")).Count
}
if ($testCountBefore -ne 89) {
    throw "Expected the Phase 0/1 inventory of 89 tests, but found $testCountBefore."
}
Write-Host "Verified pre-patch inventory: $testCountBefore tests."

if ($alreadyApplied) {
    Write-Step "Validate the existing installation"
} else {
    Write-Step "Add bounded dialog-root synchronization to the shared test base"

    $oldBaseBlock = @'
    protected void openSecuritySettings() {
        onView(withContentDescription("Security")).perform(scrollTo(), click());

        onView(withHint("Master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withText("Continue")).perform(click());

        onView(withText("Change master password")).check(matches(isDisplayed()));
    }
'@

    $newBaseBlock = @'
    /** Phase 1 security-dialog hardening: wait only on the focused dialog root. */
    protected void waitForDialogText(String text) {
        waitForDialogView(withText(text), "dialog text '" + text + "'");
    }

    protected void waitForDialogHint(String hint) {
        waitForDialogView(withHint(hint), "dialog hint '" + hint + "'");
    }

    private void waitForDialogView(Matcher<View> matcher, String description) {
        final long deadline = SystemClock.uptimeMillis() + 10000L;
        Throwable lastFailure = null;

        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(matcher)
                        .inRoot(isDialog())
                        .check(matches(isDisplayed()));
                return;
            } catch (RuntimeException | AssertionError failure) {
                lastFailure = failure;
                SystemClock.sleep(75L);
            }
        }

        throw new AssertionError("Timed out waiting for " + description, lastFailure);
    }

    protected void openSecuritySettings() {
        onView(withContentDescription("Security")).perform(scrollTo(), click());

        waitForDialogHint("Master password");
        onView(withHint("Master password"))
                .inRoot(isDialog())
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withText("Continue"))
                .inRoot(isDialog())
                .perform(click());

        // Reauthentication dismisses one dialog and synchronously opens another.
        // A bounded retry prevents Espresso from selecting the unfocused Activity root.
        waitForDialogText("Change master password");
    }
'@

    if (-not $baseBeforeNormalized.Contains($oldBaseBlock)) {
        throw "The expected openSecuritySettings block was not found. The branch source shape has changed; no files were written."
    }

    $securityAfter = @'
package com.example.privatevault;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class KeeprivaSecuritySettingsTest extends KeeprivaTestBase {

    // Phase 1 security-dialog hardening: every modal interaction names its root,
    // and every test dismisses its final dialog before ActivityScenario teardown.

    @Test
    public void securityRequiresReauthentication() {
        createTestVault();

        onView(withContentDescription("Security")).perform(scrollTo(), click());

        waitForDialogHint("Master password");
        onView(withHint("Master password"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
        onView(withText("Continue"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        closeCurrentDialog("Cancel");
    }

    @Test
    public void wrongSecurityPassword_keepsDialogOpen() {
        createTestVault();

        onView(withContentDescription("Security")).perform(scrollTo(), click());
        waitForDialogHint("Master password");
        onView(withHint("Master password"))
                .inRoot(isDialog())
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());
        onView(withText("Continue"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogText("Continue");
        closeCurrentDialog("Cancel");
    }

    @Test
    public void correctSecurityPassword_opensSettings() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Change master password"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        closeCurrentDialog("Done");
    }

    @Test
    public void securityStatusShowsDefaults() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Lock when screen turns off"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
        onView(withText("Auto-lock: 30 seconds"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
        onView(withText("Clipboard timeout: 30s"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        closeCurrentDialog("Done");
    }

    @Test
    public void autoLockDialog_listsAllOptions() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Auto-lock: 30 seconds"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogText("Immediately");
        assertDialogTextDisplayed("Immediately");
        assertDialogTextDisplayed("30 seconds");
        assertDialogTextDisplayed("1 minute");
        assertDialogTextDisplayed("5 minutes");

        closeCurrentDialog("Cancel");
    }

    @Test
    public void autoLockCanBeChangedToImmediate() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Auto-lock: 30 seconds"))
                .inRoot(isDialog())
                .perform(click());
        waitForDialogText("Immediately");
        onView(withText("Immediately"))
                .inRoot(isDialog())
                .perform(click());
        onView(withText("Save"))
                .inRoot(isDialog())
                .perform(click());

        waitForActivityWindowFocus();
        assertHomeDisplayed();
    }

    @Test
    public void clipboardDialog_listsAllOptions() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Clipboard timeout: 30s"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogText("15 seconds");
        assertDialogTextDisplayed("15 seconds");
        assertDialogTextDisplayed("30 seconds (recommended)");
        assertDialogTextDisplayed("60 seconds");
        assertDialogTextDisplayed("Never auto-clear");

        closeCurrentDialog("Cancel");
    }

    @Test
    public void clipboardCanBeChangedTo15Seconds() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Clipboard timeout: 30s"))
                .inRoot(isDialog())
                .perform(click());
        waitForDialogText("15 seconds");
        onView(withText("15 seconds"))
                .inRoot(isDialog())
                .perform(click());
        onView(withText("Save"))
                .inRoot(isDialog())
                .perform(click());

        waitForActivityWindowFocus();
        assertHomeDisplayed();
    }

    @Test
    public void changePasswordDialog_opens() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Change master password"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogHint("Current master password");
        assertDialogHintDisplayed("Current master password");
        assertDialogHintDisplayed("New master password (12+ characters)");
        assertDialogHintDisplayed("Confirm new master password");

        closeCurrentDialog("Cancel");
    }

    @Test
    public void wrongCurrentPassword_doesNotChangePassword() {
        createTestVault();
        openSecuritySettings();

        openChangePasswordDialog();

        onView(withHint("Current master password"))
                .inRoot(isDialog())
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());
        onView(withHint("New master password (12+ characters)"))
                .inRoot(isDialog())
                .perform(replaceText("NewKeepriva123!"), closeSoftKeyboard());
        onView(withHint("Confirm new master password"))
                .inRoot(isDialog())
                .perform(replaceText("NewKeepriva123!"), closeSoftKeyboard());

        onView(withText("Change"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogText("Change");
        closeCurrentDialog("Cancel");
    }

    @Test
    public void mismatchedNewPasswords_areRejected() {
        createTestVault();
        openSecuritySettings();

        openChangePasswordDialog();

        onView(withHint("Current master password"))
                .inRoot(isDialog())
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());
        onView(withHint("New master password (12+ characters)"))
                .inRoot(isDialog())
                .perform(replaceText("NewKeepriva123!"), closeSoftKeyboard());
        onView(withHint("Confirm new master password"))
                .inRoot(isDialog())
                .perform(replaceText("DifferentNew123!"), closeSoftKeyboard());

        onView(withText("Change"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogText("Change");
        closeCurrentDialog("Cancel");
    }

    @Test
    public void biometricSettings_isReachable() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Biometric unlock: Disabled"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogText("Enable biometric unlock?");
        assertDialogTextDisplayed("Enable biometric unlock?");

        closeCurrentDialog("Cancel");
    }

    private void openChangePasswordDialog() {
        onView(withText("Change master password"))
                .inRoot(isDialog())
                .perform(click());
        waitForDialogHint("Current master password");
    }

    private void assertDialogTextDisplayed(String text) {
        onView(withText(text))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
    }

    private void assertDialogHintDisplayed(String hint) {
        onView(withHint(hint))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
    }

    private void closeCurrentDialog(String buttonText) {
        onView(withText(buttonText))
                .inRoot(isDialog())
                .perform(click());
        waitForActivityWindowFocus();
    }

    private void assertHomeDisplayed() {
        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }
}
'@

    if (([regex]::Matches($securityBeforeNormalized, "(?m)^\s*@Test\s*$")).Count -ne 12) {
        throw "Expected 12 security tests before replacement; no files were written."
    }
    if (-not $securityBeforeNormalized.Contains("public class KeeprivaSecuritySettingsTest extends KeeprivaTestBase")) {
        throw "The expected security test class declaration was not found."
    }

    $baseAfter = $baseBeforeNormalized.Replace($oldBaseBlock, $newBaseBlock)
    Write-Utf8NoBom $basePath $baseAfter

    Write-Step "Install explicit roots and deterministic cleanup in the security tests"
    Write-Utf8NoBom $securityPath ($securityAfter + "`n")
}

Write-Step "Validate installed source and unchanged test inventory"
$baseInstalled = Read-Utf8 $basePath
$securityInstalled = Read-Utf8 $securityPath

foreach ($required in @(
    $Marker,
    "waitForDialogText",
    ".inRoot(isDialog())",
    "waitForActivityWindowFocus()"
)) {
    if (-not ($baseInstalled.Contains($required) -or $securityInstalled.Contains($required))) {
        throw "Post-install validation failed; missing marker: $required"
    }
}

$securityTestCount = ([regex]::Matches($securityInstalled, "(?m)^\s*@Test\s*$")).Count
if ($securityTestCount -ne 12) {
    throw "Security test inventory changed unexpectedly: expected 12, found $securityTestCount."
}

$testCountAfter = 0
Get-ChildItem -Path (Join-Path $repoRoot "app/src/androidTest/java") -Filter "*Test.java" -Recurse | ForEach-Object {
    $testCountAfter += ([regex]::Matches((Read-Utf8 $_.FullName), "(?m)^\s*@Test\s*$")).Count
}
if ($testCountAfter -ne $testCountBefore) {
    throw "Total test inventory changed from $testCountBefore to $testCountAfter."
}

& git diff --check -- $BaseRelativePath $SecurityRelativePath
if ($LASTEXITCODE -ne 0) {
    throw "git diff --check found a whitespace or patch-format problem."
}

$changedTargets = @(& git diff --name-only -- $BaseRelativePath $SecurityRelativePath)
if ($LASTEXITCODE -ne 0) {
    throw "Could not validate the installed diff."
}
if (-not $alreadyApplied -and $changedTargets.Count -ne 2) {
    throw "Expected exactly two modified test source files, but found $($changedTargets.Count)."
}
if ($alreadyApplied -and $changedTargets.Count -notin @(0, 2)) {
    throw "The existing installation has an unexpected partial diff affecting $($changedTargets.Count) target file(s)."
}

Write-Host "Verified post-patch inventory: $testCountAfter tests ($securityTestCount security tests)."
if ($changedTargets.Count -eq 0) {
    Write-Host "The patch was already committed; no new working-tree changes were required."
} else {
    Write-Host "Modified only:"
    $changedTargets | ForEach-Object { Write-Host "  $_" }
}

Write-Step "Patch complete"
Write-Host "Review with:"
Write-Host "  git diff -- app/src/androidTest/java/com/example/privatevault/KeeprivaTestBase.java"
Write-Host "  git diff -- app/src/androidTest/java/com/example/privatevault/KeeprivaSecuritySettingsTest.java"
Write-Host "Then commit and push the branch to run the five parallel instrumentation batches."
