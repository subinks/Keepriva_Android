# Keepriva Phase 2A package - execution order

This package deliberately separates documentation approval from production-source extraction. Do not run all scripts on the existing Phase 2 branch.

## Part 1 - update and commit the plan on the green Phase 2 branch

1. Check out `ui_eh_ph02_screen_routing`.
2. Confirm the working tree contains no unrelated changes.
3. Extract this package into the repository root, preserving the `Docs` folder.
4. Run:

   ```powershell
   .\Docs\ph_02A\37-update-master-plan-for-phase-02a.ps1
   ```

5. Review `git status --short` and `git diff --check`.
6. Commit and push the revised master Markdown, revised master PDF, standalone Phase 2A plan, scripts, and payloads.
7. Let the unchanged Phase 2 workflow finish green.

## Part 2 - create the implementation branch

Create `ui_eh_ph02a_mainactivity_modularization` from the green documentation commit, check it out, and run:

```powershell
.\Docs\ph_02A\38-validate-phase-02a-implementation-readiness.ps1
```

The validator is read-only. It confirms the exact analyzed `MainActivity`, the 95 instrumentation plus nine JVM baseline, database version 3, backup format 1, offline manifest, and CI batch guards.

## Part 3 - apply only Wave A1

On the clean Phase 2A implementation branch, run:

```powershell
.\Docs\ph_02A\39-implement-phase-02a-wave-a-architecture-seams.ps1
```

Wave A1 makes these behavior-preserving changes:

- transfers the active-key reference from `MainActivity` to `VaultSessionCoordinator`;
- transfers root-child replacement to `VaultRootRenderer`;
- introduces the `VaultController` teardown contract and reverse-order `ControllerRegistry`;
- adds five JVM tests for locked-key access, unlock/clear behavior, reverse teardown, idempotence, and teardown after a controller failure;
- leaves all 95 instrumentation tests and their CI batch counts unchanged.

Run `gradlew.bat testDebugUnitTest`, commit Wave A1 separately, push it, and wait for the full parallel workflow. Wave A2 must be generated from that green commit before any feature controller is extracted. This is intentional: a 3,684-line security-sensitive Activity should be extracted behind proven seams, not replaced in one unreviewable patch.

## What is not included yet

Wave A2 and Waves B-E are designed in the standalone Phase 2A plan but are not bundled as speculative source replacements. Each later installer must use the green result of the previous wave as its exact source precondition. This prevents a later payload from silently overwriting CI corrections made during an earlier wave.
