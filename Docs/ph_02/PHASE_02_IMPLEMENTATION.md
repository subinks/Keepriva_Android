# Keepriva Phase 2 Screen-Routing Implementation

## Delivered scope

Phase 2 introduces the navigation and reusable-view foundation required by the later Keepriva UI phases while retaining the current setup, unlock, browser, category, item, transfer, and security workflows.

The implementation adds:

- one activity-owned `FrameLayout` root, with `setContentView` called once;
- an explicit `VaultScreen` destination set;
- immutable, process-memory-only `VaultNavigationState`;
- a deterministic `VaultScreenRouter` with reset, navigate, replace, Back, and clear behavior;
- semantic root identifiers for setup, unlock, loading, error, and vault-browser screens;
- safe loading and database/decryption-error states;
- complete router clearing on explicit lock, automatic lock, screen-off lock, and activity destruction;
- reusable XML toolbar, empty-state, category-row, item-row, field-row, and action-row layouts;
- reusable view factories and stable-ID category/item RecyclerView adapters;
- nine local JVM tests and six additive lifecycle instrumentation tests.

Phase 2 intentionally does not convert search, item details, editing, move, or history to their final full-screen designs. Those routes exist now so later phases can adopt them without another navigation rewrite.

## Security and compatibility invariants

| Invariant | Phase 2 result |
|---|---|
| Session key ownership | Remains in `MainActivity` only |
| Key/model storage in router | Prohibited |
| Saved-instance restoration of decrypted UI | Not used |
| Lock behavior | Clears router, query, IDs, models, and key |
| Database schema | Version 3, unchanged |
| Backup envelope | Version 1, unchanged |
| Network permission | Absent |
| Existing instrumentation inventory | All 89 tests retained |
| New tests | 9 JVM plus 6 instrumentation |

## Files installed

The installer validates and writes exactly 18 payload files:

- 1 Gradle dependency file;
- 1 modified activity;
- 1 modified lifecycle test class;
- 8 new production Java classes;
- 6 new XML layouts;
- 1 new JVM test class.

The installer refuses to run on the wrong branch, without the verified Phase 1 ancestor, with unrelated working-tree changes, against unexpected source blobs, or with an invalid payload checksum.

## Apply and verify

From the repository root on `ui_eh_ph02_screen_routing`:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\Docs\ph_02\35-implement-phase-02-screen-routing.ps1
git status --short
git diff --check
```

If a compatible global Gradle installation is available, the installer can also run local compilation and lint:

```powershell
.\Docs\ph_02\35-implement-phase-02-screen-routing.ps1 -RunLocalBuild
```

After review, commit and push the Phase 2 source, resource, test, and plan files. The existing parallel workflow must finish with all five instrumentation batches, visual verification, and the verification gate green.

## Expected test inventory

| Suite | Before | Added | Expected after |
|---|---:|---:|---:|
| Instrumentation | 89 | 6 | 95 |
| JVM unit | 0 Phase 2 tests | 9 | 9 Phase 2 tests |

The biometric instrumentation test remains diagnostic/non-blocking exactly as configured in Phase 1. The new lifecycle tests join the existing `auth-lifecycle-smoke` batch.

## Rollback

The verified rollback point is Phase 1 commit `e3e8cd7c08a95f2c8450bcd22d426f1876a0adef`. Because Phase 2 has no database or backup migration, rollback requires no data conversion.
