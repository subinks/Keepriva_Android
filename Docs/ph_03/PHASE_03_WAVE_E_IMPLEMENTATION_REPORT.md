# Phase 03 Wave E — Implementation Report

## Baseline and purpose

Wave E is based on the green Wave D commit
`e49c9ab0a8182756c7f9e6eda8c3906978b466c7` on
`ui_eh_ph_03_redesign`. It closes Phase 03 by removing the temporary recursive browser
implementation and by enforcing the final category-first architecture.

## Implemented changes

- Removed `LegacyVaultBrowserController`, `VaultBrowserModel`, and
  `VaultBrowserModelBuilder`, together with the obsolete model-builder test.
- Removed the inline compatibility search and the visible `Vault tools` section. Search and
  management operations remain available through the typed toolbar and overflow routes.
- Reused `VaultCategoryIconResolver` directly from category management; no replacement legacy
  model or duplicate icon logic was introduced.
- Kept navigation snapshots process-memory-only. The removed inline query now contributes an
  empty query while category and scroll restoration remain intact.
- Hid the internal category heading on the root screen and show `Subcategories` only when a
  nested category actually has children.
- Compacted the toolbar vertically while retaining 48 dp search, lock, and overflow touch
  targets and their existing content descriptions.
- Moved `Export entry` into the item-details action strip beside Edit, Delete, and Close without
  changing its content description or export callback.
- Added source-level architecture guards for legacy-file absence, compatibility-wiring absence,
  the 1,200-line `MainActivity` ceiling, accessible toolbar sizing, and action-strip placement.

## Preserved boundaries

- Database schema version remains 3.
- Backup format remains 1.
- No Internet permission is added.
- Decrypted items and the session `SecretKey` remain owned by `MainActivity`/`VaultSession`, not
  by presentation controllers or navigation state.
- Category navigation remains direct-child/direct-item rendering; it never expands the complete
  vault tree on one screen.

## Verification gate

The Wave E installer performs baseline, payload, post-apply, deletion, and rollback checks. The
local acceptance run completed successfully with Gradle 9.1.0, JDK 17, and Android SDK 36:

```text
:app:lintDebug
:app:testDebugUnitTest
:app:assembleDebug
:app:assembleDebugAndroidTest
BUILD SUCCESSFUL
```

After commit and push, the normal parallel workflow and the marker-triggered serial safety net
(`[serial-safety-net]` in the commit message) are the final remote Phase 03 gate.
