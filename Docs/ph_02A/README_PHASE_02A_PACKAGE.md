# Keepriva Phase 2A Wave A1 execution guide

The planning documents were installed manually and committed separately. Script 37 and the documentation payload are obsolete and must not be retained.

## Files retained in `Docs/ph_02A`

- `PHASE_02A_MAINACTIVITY_MODULARIZATION_ANALYSIS_DESIGN_AND_IMPLEMENTATION_PLAN.md`
- `README_PHASE_02A_PACKAGE.md`
- `38-validate-phase-02a-implementation-readiness.ps1`
- `39-implement-phase-02a-wave-a-architecture-seams.ps1`
- `wave_a_payload.sha256`
- `wave_a_payload/` and its six Java files

## Files removed

- `37-update-master-plan-for-phase-02a.ps1`
- `payload.sha256`
- the documentation `payload/` directory

## Execution sequence

1. Commit the retained support files on `ui_eh_ph02_screen_routing` and wait for its unchanged workflow to pass.
2. Create `ui_eh_ph02a_mainactivity_modularization` from the latest green head of `ui_eh_ph02_screen_routing`.
3. Ensure `git status --short` is empty.
4. Run `38-validate-phase-02a-implementation-readiness.ps1` from the repository root.
5. Run `39-implement-phase-02a-wave-a-architecture-seams.ps1` from the repository root.
6. Run `gradlew.bat testDebugUnitTest`.
7. Run `gradlew.bat assembleDebug assembleDebugAndroidTest`.
8. Review `git status --short`, `git diff --check`, and the source diff.
9. Commit and push Wave A1, then wait for every blocking CI job to pass before designing Wave A2.

Wave A1 changes only key ownership, root rendering, controller teardown foundations, and five JVM tests. It intentionally does not implement the complete Phase 2A refactor in one patch.

