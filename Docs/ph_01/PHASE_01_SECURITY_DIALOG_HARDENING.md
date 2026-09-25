# Phase 1 Security Dialog Test Hardening

## Purpose

This patch addresses the only failure in GitHub Actions run 52:

```text
KeeprivaSecuritySettingsTest.changePasswordDialog_opens
RootViewPicker$RootViewWithoutFocusException
KeeprivaTestBase.openSecuritySettings():300
```

The application successfully opened the security settings dialog, but Espresso selected the underlying `MainActivity` root while that root did not own window focus. The security tests also left dialogs open when several tests completed, producing `WindowLeaked` messages while `ActivityScenario` closed the activity.

## Scope

The installer changes exactly two Android instrumentation-test sources:

- `KeeprivaTestBase.java`
- `KeeprivaSecuritySettingsTest.java`

It does not change application code, resources, the database, backup formats, Gradle configuration, or the Phase 1 workflow.

## Changes

1. Adds a bounded 10-second dialog wait that always uses `inRoot(isDialog())`.
2. Uses the dialog root for the reauthentication password, Continue button, security settings, and all child dialogs.
3. Waits across the reauthentication-to-security-settings and security-settings-to-child-dialog transitions.
4. Explicitly closes the final dialog in every security test.
5. Waits for `MainActivity` to regain stable focus before checking the home screen or ending a test.
6. Preserves all 12 security tests and the complete 89-test inventory.

## Installer safety

The script:

- requires branch `ui_eh_ph01_parallel_ci` by default;
- refuses to overwrite uncommitted edits in either target source;
- verifies the exact Git blob versions analyzed from failed commit `f84cc45dde2451b2b4ee7183ac69b5087912e1c7`;
- writes UTF-8 without a byte-order mark;
- validates the test inventory after installation;
- runs `git diff --check`;
- is safe to rerun after a successful installation or commit.

## Execution

From the repository root in PowerShell:

```powershell
Set-Location C:\Tools\Setup\Projects\Keepriva_Android
.\Docs\ph_01\34-stabilize-phase-01-security-dialog-tests.ps1
```

Then review and commit:

```powershell
git status --short
git diff --check
git diff -- app/src/androidTest/java/com/example/privatevault/KeeprivaTestBase.java
git diff -- app/src/androidTest/java/com/example/privatevault/KeeprivaSecuritySettingsTest.java
git add app/src/androidTest/java/com/example/privatevault/KeeprivaTestBase.java `
        app/src/androidTest/java/com/example/privatevault/KeeprivaSecuritySettingsTest.java
git commit -m "Stabilize security dialog instrumentation tests"
git push origin ui_eh_ph01_parallel_ci
```

## Expected CI result

- All five blocking instrumentation batches pass.
- The security batch reports 12 tests with no failures.
- Visual verification remains independent and blocking.
- The full serial safety net remains skipped when the parallel jobs and verification gate succeed.

