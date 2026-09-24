# Keepriva Phase 0 Baseline Record

## Baseline identity

| Item | Value |
|---|---|
| Repository | `subinks/Keepriva_Android` |
| Working branch reviewed | `ui_eh_ph0` |
| Branch commit at initial review | `bb70c9ade6975386bfb111bfe97775adfc0d86cf` |
| Green application commit | `6851c467896abc3d8d375cfb9f237379adce5708` |
| Protected baseline tag | `ui-enhancement-green-2026-09-24` |
| Green workflow run | [`35973848830`](https://github.com/subinks/Keepriva_Android/actions/runs/35973848830) |
| Workflow conclusion | Success |
| Workflow job duration | 18 minutes 17 seconds |
| Workflow wall-clock duration | Approximately 18 minutes 22 seconds |

The initial `ui_eh_ph0` commit is a direct child of the green application
commit. Before Phase 0 implementation files, its only intentional differences
were the UI restructure Markdown and PDF planning documents.

## Version and security baseline

| Item | Value |
|---|---|
| Version code | 16 |
| Version name | `2.1.0-alpha1` |
| Database version | 3 |
| Encrypted backup format version | 1 |
| Architecture | One `MainActivity`; programmatically created views and dialogs |
| `android.permission.INTERNET` | Absent |

## Instrumentation inventory

| Class | Test methods |
|---|---:|
| `KeeprivaAuthHomeTest` | 11 |
| `KeeprivaBiometricCiTest` | 1 |
| `KeeprivaCategoryDeletionTest` | 5 |
| `KeeprivaCategoryPreferencesTest` | 19 |
| `KeeprivaDataTransferTest` | 12 |
| `KeeprivaItemCrudTest` | 14 |
| `KeeprivaLifecycleRobustnessTest` | 5 |
| `KeeprivaSecuritySettingsTest` | 12 |
| `KeeprivaUiSmokeTest` | 10 |
| **Total** | **89** |

The green run completed with 88 normal tests passing and the single biometric
CI diagnostic skipped/handled as non-blocking.

## Preserved visual baseline

The following files are copied without modification from workflow artifact
`keepriva-android-verification` (artifact ID `10797748716`):

- `screenshots/01-fresh-install-setup.png`
- `screenshots/02-vault-home.png`
- `screenshots/03-real-unlock-screen.png`

The fourth biometric image is intentionally excluded because it is a
conditional environment diagnostic, not an approved primary-screen baseline.

## Final Phase 0 validation

After the Phase 0 files are pushed, record the final run here:

| Item | Value |
|---|---|
| Final Phase 0 commit | Pending |
| Final Phase 0 workflow run | Pending |
| Final result | Pending |

Phase 1 must not start until the final Phase 0 workflow is green.

