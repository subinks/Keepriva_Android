# Phase 0 - Freeze and Document the Green Baseline

## 1. Purpose

This file is the implementation checklist and evidence record for Phase 0 of
`Keepriva_UI_Restructure_Phase_Wise_Implementation_and_Parallel_Test_Plan.md`.

Phase 0 does not redesign the UI and does not change production behavior. It
establishes a recoverable, measured, reproducible baseline before Phase 1
changes the CI workflow and before later phases change the application.

## 2. Selected working branch

The requested Phase 0 working branch is:

```text
ui_eh_ph0
```

The plan originally suggested `ui_restructure_v2`. For this implementation,
`ui_eh_ph0` is the accepted Phase 0 equivalent. Later phase branches should be
created from the final green Phase 0 commit, not directly from the older
`ui_enhancement` branch.

## 3. Repository verification performed on 2026-09-24

| Check | Verified value | Status |
|---|---|---|
| Repository | `subinks/Keepriva_Android` | Confirmed |
| Working branch | `ui_eh_ph0` | Confirmed |
| Current branch commit before Phase 0 files | `bb70c9ade6975386bfb111bfe97775adfc0d86cf` | Confirmed |
| Current commit message | `Doc` | Confirmed |
| Direct parent | `6851c467896abc3d8d375cfb9f237379adce5708` | Confirmed |
| Parent workflow run | `35973848830` | Passed |
| Parent workflow job | `build-and-verify` | Passed |
| Workflow duration | 18 minutes 17 seconds job time; approximately 18 minutes 22 seconds run wall time | Confirmed |
| Instrumentation inventory | 89 test methods in 9 classes | Confirmed |
| Normal blocking tests | 88 | Confirmed |
| Biometric CI diagnostic | 1 separately handled diagnostic test | Confirmed |
| Android app version | `versionCode 16`, `versionName 2.1.0-alpha1` | Confirmed |
| Database version | 3 | Confirmed |
| Encrypted backup format | 1 | Confirmed |
| Application structure | One activity with programmatically created views and dialogs | Confirmed |
| Internet permission | Absent from `AndroidManifest.xml` | Confirmed |
| Baseline artifact | `keepriva-android-verification`, artifact ID `10797748716` | Confirmed |
| Baseline screenshots | Setup, vault home, and real unlock screenshots exist in the artifact | Confirmed |

The `ui_eh_ph0` branch is a direct child of the known green commit. Before the
Phase 0 implementation files, its only intentional differences from the green
commit were the UI restructure Markdown and PDF planning documents.

## 4. Gaps found

### 4.1 Baseline tag is missing

The required tag does not currently exist:

```text
ui-enhancement-green-2026-09-24
```

It must point to this exact commit:

```text
6851c467896abc3d8d375cfb9f237379adce5708
```

The tag must never be moved to the later documentation or Phase 0 commit.

### 4.2 The workflow does not run automatically for `ui_eh_ph0`

The current workflow push filter contains only:

```text
initial_commit
ui_enhancement
```

`ui_eh_ph0` must be added to the push filter so the unchanged full verification
suite runs after Phase 0 is pushed.

### 4.3 No workflow run exists for `ui_eh_ph0`

The last green evidence belongs to `ui_enhancement` at the baseline commit.
After applying Phase 0, the same full workflow must pass on `ui_eh_ph0`.

### 4.4 Baseline screenshots are stored only in an expiring artifact

The green run contains these required screenshots:

```text
screenshots/01-fresh-install-setup.png
screenshots/02-vault-home.png
screenshots/03-real-unlock-screen.png
```

The workflow artifact is scheduled to expire. The three images must be copied
to the repository under:

```text
docs/phase-0-baseline/screenshots/
```

The biometric screenshot is diagnostic and is not one of the three required
Phase 0 visual baselines.

### 4.5 A durable baseline record is missing

The repository needs a generated baseline record containing the exact version
constants, commit, workflow run, test inventory, and manifest security result.

## 5. Changes required for Phase 0

1. Add `ui_eh_ph0` to the workflow push branch list.
2. Create the annotated baseline tag at the exact green commit.
3. Preserve setup, home, and unlock screenshots under
   `docs/phase-0-baseline/screenshots/`.
4. Generate `docs/phase-0-baseline/baseline-metadata.json`.
5. Generate `docs/phase-0-baseline/BASELINE_RECORD.md`.
6. Push the Phase 0 commit and baseline tag.
7. Allow the unchanged workflow to run on `ui_eh_ph0`.
8. Confirm the workflow reports 89 total tests, 88 passed, 1 skipped, and 0
   failed, with the biometric condition remaining non-blocking.
9. Record the Phase 0 workflow URL and final commit in the baseline record.

No Java application source, database schema, backup format, or test behavior
needs to change in Phase 0.

## 6. Patch script

Run the repository-root script from PowerShell:

```powershell
Set-Location C:\Tools\Setup\Projects\Keepriva_Android
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\31-implement-phase-0-baseline-freeze.ps1
```

If GitHub CLI is not installed, first download the workflow artifact ZIP and
pass its path:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\31-implement-phase-0-baseline-freeze.ps1 `
  -ArtifactZip "C:\Path\To\keepriva-android-verification.zip"
```

To create and push the tag in the same run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\31-implement-phase-0-baseline-freeze.ps1 `
  -PushTag
```

The script is idempotent. It validates an existing tag rather than moving it,
does not commit or push application changes, and refuses to run from the wrong
branch or a branch that does not contain the required green commit.

## 7. Manual commands after the script

Review the generated files before committing:

```powershell
git status --short
git diff -- .github/workflows/verify-keepriva-android.yml
git diff -- docs/phase-0-baseline
```

Commit and push:

```powershell
git add .github/workflows/verify-keepriva-android.yml `
        PHASE_0_IMPLEMENTATION.md `
        31-implement-phase-0-baseline-freeze.ps1 `
        docs/phase-0-baseline
git commit -m "Complete Phase 0 baseline freeze"
git push origin ui_eh_ph0
git push origin ui-enhancement-green-2026-09-24
```

## 8. Phase 0 exit gate

Phase 0 is complete only when every item is checked:

- [ ] `ui-enhancement-green-2026-09-24` resolves to
      `6851c467896abc3d8d375cfb9f237379adce5708` locally and on GitHub.
- [ ] `ui_eh_ph0` contains the green baseline in its history.
- [ ] `ui_eh_ph0` has no unintended production-code change.
- [ ] Version code/name, database version, and backup format are recorded.
- [ ] The manifest contains no `android.permission.INTERNET` permission.
- [ ] The setup, home, and unlock baseline screenshots are committed.
- [ ] The full workflow passes on the final Phase 0 commit.
- [ ] The run reports 89 tests: 88 passed, 1 skipped, 0 failed.
- [ ] Lint and build tasks pass.
- [ ] The baseline workflow URL and final commit are recorded.
- [ ] Phase 1 branches from the final green `ui_eh_ph0` commit.

## 9. Do not include in Phase 0

The following belong to later phases and must not be mixed into this branch:

- Parallel emulator matrix implementation.
- Screen router or reusable screen components.
- Category browser redesign.
- Dedicated search results.
- New copy, move, timestamps, or history features.
- Database v4 migration.
- Backup format v2.
- Test rewrites for later UI behavior.

