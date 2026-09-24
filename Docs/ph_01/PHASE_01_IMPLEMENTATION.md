# Phase 1 - Build Once and Run Emulator Tests in Parallel

## 1. Scope

This implementation applies Phase 1 from
`Docs/Keepriva_UI_Restructure_Phase_Wise_Implementation_and_Parallel_Test_Plan.md`
to branch `ui_eh_ph01_parallel_ci`.

Phase 1 changes CI infrastructure only. It does not change Java production
code, Android resources, test methods, database version, backup format, or UI
behavior.

## 2. Verified starting point

| Item | Value |
|---|---|
| Branch | `ui_eh_ph01_parallel_ci` |
| Phase 0 commit | `28855285c0a4b0d00013e11e436e03c962b3308b` |
| Phase 0 workflow | `35989863275` |
| Phase 0 result | Success |
| Existing instrumentation inventory | 89 methods in 9 classes |
| Blocking tests | 88 |
| Biometric diagnostic | 1 non-blocking test |

## 3. Files installed

```text
.github/workflows/verify-keepriva-android.yml
scripts/ci/stage-apks.sh
scripts/ci/run-instrumentation-batch.sh
scripts/ci/run-visual-verification.sh
scripts/ci/patch-ci-screenshot-protection.py
Docs/ph_01/PHASE_01_IMPLEMENTATION.md
Docs/ph_01/32-implement-phase-01-parallel-ci.ps1
```

## 4. Job topology

### `build-apks`

The application and instrumentation APKs are compiled once. This job also
runs lint and JVM unit tests, applies the existing disposable screenshot patch,
checks the offline-only manifest contract, stages stable APK names, generates
SHA-256 checksums, and uploads the immutable bundle.

### `instrumentation`

Five matrix entries boot independent API 35 emulators and run disjoint class
groups against the exact APKs from `build-apks`.

| Batch | Classes | Expected methods |
|---|---|---:|
| `auth-lifecycle-smoke` | `KeeprivaAuthHomeTest`, `KeeprivaLifecycleRobustnessTest`, `KeeprivaUiSmokeTest` | 26 |
| `categories` | `KeeprivaCategoryPreferencesTest`, `KeeprivaCategoryDeletionTest` | 24 |
| `item-core` | `KeeprivaItemCrudTest` | 14 |
| `data-transfer` | `KeeprivaDataTransferTest` | 12 |
| `security` | `KeeprivaSecuritySettingsTest` | 12 |
| **Total** | Eight normal test classes | **88** |

The matrix uses `fail-fast: false` and `max-parallel: 5`, so one failure does
not cancel diagnostics from the other batches.

### `visual-verification`

This independent emulator job captures deterministic setup, home, and unlock
states. `KeeprivaBiometricCiTest` runs only here and remains explicitly
non-blocking because CI biometric enrollment depends on the emulator.

### `serial-safety-net`

The full 88-test blocking suite runs serially only for manual dispatch and the
weekly schedule. It is not duplicated on every push or pull request.

### `verification-gate`

The final gate requires the build, every matrix entry, and visual verification
to succeed. The serial job must either succeed when selected or be skipped on a
normal push/pull request.

## 5. Failure isolation and diagnostics

Every batch has its own artifact directory containing:

- Complete raw instrumentation output.
- Expected and observed test counts.
- Duration and selected runner.
- A screenshot and UI hierarchy when a failure occurs.
- Package state, process state, device properties, and logcat on failure.

The runner fails if:

- APKs or the instrumentation runner are missing or ambiguous.
- `adb shell am instrument` returns a failure status.
- Output contains a crash or instrumentation failure marker.
- A final `OK (N tests)` result is absent.
- The observed count differs from the batch's expected count.

No passwords or decrypted vault values are intentionally printed by the helper
scripts.

## 6. Installation

Extract the Phase 1 package into the repository root, then run:

```powershell
Set-Location C:\Tools\Setup\Projects\Keepriva_Android

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\Docs\ph_01\32-implement-phase-01-parallel-ci.ps1
```

The installer validates the branch, Phase 0 ancestry, test inventory, payload,
workflow batch coverage, LF line endings, and available local syntax tools. It
does not commit or push.

## 7. Review before commit

```powershell
git status --short
git diff --check
git diff -- .github/workflows/verify-keepriva-android.yml
git diff -- scripts/ci
```

Only the workflow, `scripts/ci`, and `Docs/ph_01` should change.

## 8. Commit and push

```powershell
git add .github/workflows/verify-keepriva-android.yml
git add scripts/ci
git add Docs/ph_01

git commit -m "Implement Phase 1 parallel Android CI"
git push origin ui_eh_ph01_parallel_ci
```

## 9. Expected normal push result

```text
Build APKs once
Instrumentation - auth-lifecycle-smoke
Instrumentation - categories
Instrumentation - item-core
Instrumentation - data-transfer
Instrumentation - security
Visual verification and biometric diagnostic
Full serial safety net (skipped)
Verification gate
```

Expected blocking total: 88 tests, with all five matrix entries successful.
The biometric diagnostic remains separate from the blocking total.

## 10. Phase 1 exit criteria

- [ ] APKs are compiled exactly once in the workflow.
- [ ] Target and instrumentation APK checksums pass in every consumer job.
- [ ] All five matrix batches pass independently.
- [ ] The sum of observed matrix counts is 88.
- [ ] Visual verification captures setup, home, and unlock states.
- [ ] Biometric availability cannot fail the main build.
- [ ] Every failed batch uploads isolated diagnostics.
- [ ] The normal push verification gate passes.
- [ ] A manually dispatched serial safety-net run passes all 88 blocking tests.
- [ ] No Java production source, Android resource, or test method changed.
- [ ] The final workflow duration and per-batch durations are recorded.

Do not start Phase 2 until both the parallel push run and one manual serial
safety-net run are green.

