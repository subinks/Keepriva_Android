# Keepriva Phase 2 Implementation Plan

## 1. Phase identity

| Item | Value |
|---|---|
| Phase | 2 - Screen router and reusable UI foundation |
| Implementation branch | `ui_eh_ph02_screen_routing` |
| Required parent | `ui_eh_ph01_parallel_ci` |
| Verified starting commit | `e3e8cd7c08a95f2c8450bcd22d426f1876a0adef` |
| Starting CI run | GitHub Actions run `36093848533` |
| Starting test inventory | 89 instrumentation tests: 88 blocking and 1 biometric diagnostic |
| Database version | 3 - unchanged in Phase 2 |
| Backup format | 1 - unchanged in Phase 2 |
| Network permission | Remains absent |

## 2. Objective

Introduce a small, deterministic in-memory navigation layer and reusable view foundation while keeping Keepriva's current user-visible vault behavior available. Phase 2 is infrastructure for later full-screen browser, search, details, move, and history work; it is not the browser redesign itself.

The implementation must satisfy these boundaries:

1. `MainActivity` remains the sole owner of the in-memory `SecretKey`, auto-lock behavior, screenshot protection, clipboard cleanup, and Android lifecycle integration.
2. No key, password, decrypted field value, `VaultItem`, or custom-field map enters an `Intent`, `Bundle`, saved-instance state, static field, router state, or persistent navigation record.
3. The database schema, encryption hierarchy, backup format, import/export formats, and manifest permissions do not change.
4. Existing setup, unlock, home, category, item, transfer, and security behavior remains reachable with the same semantic accessibility identifiers.
5. Full-screen search, details, move, and history interfaces remain scheduled for their documented later phases.

## 3. Current-source analysis

### 3.1 Activity and content ownership

`MainActivity` currently contains approximately 3,600 lines and calls `setContentView` independently for the release-security block, setup screen, unlock screen, and vault home. There is no persistent activity-owned root container.

Phase 2 will add one `FrameLayout` root during `onCreate`. Every screen renderer will replace only that container's child. This gives the future router one stable rendering target and prevents later screens from repeatedly replacing the activity window.

### 3.2 Security lifecycle

The existing activity correctly owns:

- the in-memory `SecretKey`;
- explicit, background-timeout, and screen-off locking;
- clipboard clearing;
- Android Keystore and biometric operations;
- pending system-picker buffers;
- screenshot protection.

These responsibilities remain in `MainActivity`. `clearSessionState()` will additionally clear the router and all navigation state.

### 3.3 Existing UI model

The vault browser is programmatic and uses a long `ScrollView`/`LinearLayout` hierarchy. Item details and editing remain dialogs. Phase 2 will not replace these workflows. It will establish reusable XML-backed toolbar, empty-state, category-row, item-row, field-row, and action-row components, plus stable-ID `RecyclerView` adapters, for gradual adoption beginning in Phase 3.

### 3.4 Existing test architecture

The Phase 1 workflow builds APKs once and executes five blocking instrumentation batches in parallel. Phase 2 routing instrumentation belongs to `auth-lifecycle-smoke`; pure router tests run in `build-apks` through `testDebugUnitTest`. No new emulator job is required.

## 4. Navigation model

### 4.1 Explicit screens

`VaultScreen` defines the complete intended flow without forcing all screens to be rendered in Phase 2:

- `SETUP`
- `UNLOCK`
- `LOADING`
- `ERROR`
- `VAULT_BROWSER`
- `SEARCH_RESULTS`
- `ITEM_DETAILS`
- `ITEM_EDITOR`
- `MOVE_ITEM`
- `ITEM_HISTORY`
- `ITEM_VERSION_DETAILS`

### 4.2 Navigation state

`VaultNavigationState` is immutable and contains only minimal navigation metadata:

- screen;
- current category name;
- current search query;
- selected item database ID;
- selected history-version ID;
- list scroll position.

It deliberately contains no Android `Bundle`, no `Parcelable`, no `Serializable`, no key, and no vault model object. Query/category strings are kept only in memory and are erased by router clearing.

### 4.3 Router behavior

`VaultScreenRouter` owns:

- one current state;
- an in-memory back stack;
- root reset;
- forward navigation;
- current-state replacement;
- deterministic back navigation;
- complete clearing on lock/destruction.

Resetting to setup, unlock, loading, or the browser clears navigation history. Forward navigation snapshots the previous immutable state. Going back restores the exact previous category, query, selected IDs, and scroll position.

## 5. Root rendering and screen states

`MainActivity` will:

1. Create a single activity-owned root content container in `onCreate`.
2. Render all existing full-screen content through `renderRootScreen`.
3. Assign a stable screen content description such as `Screen vault browser` or `Screen unlock`.
4. Render a reusable loading state while opening the encrypted vault.
5. On a vault-load failure, clear the session/key/router before showing a safe error state that can return to unlock.
6. Reset the router to setup, unlock, or vault browser at the corresponding security boundary.
7. Synchronize the browser's category, search query, and vertical scroll position into the current in-memory navigation state.

No navigation state is restored from `savedInstanceState`. Activity recreation therefore returns to setup/unlock according to persistent vault configuration and never restores a decrypted screen.

## 6. Reusable UI foundation

### 6.1 XML-backed components

Add layouts for:

- toolbar;
- empty state;
- category row;
- item row;
- field row;
- action row.

`VaultUiComponents` inflates and binds these layouts, applies semantic content descriptions, and centralizes row configuration. Existing Phase 2 screens may continue using their current programmatic views; the components become the supported foundation for Phase 3 onward.

### 6.2 Stable-ID adapters

Add:

- `CategoryRowModel` and `CategoryRowAdapter`;
- `ItemRowModel` and `ItemRowAdapter`.

Both adapters call `setHasStableIds(true)`. Category IDs are deterministic hashes of normalized category identity, and item IDs use the database row ID. Adapters accept immutable row models rather than decrypted `VaultItem` instances, limiting accidental sensitive-data retention in view holders.

## 7. Planned source changes

| Path | Change |
|---|---|
| `app/build.gradle.kts` | Add RecyclerView and JVM JUnit dependencies |
| `MainActivity.java` | Root container, router integration, loading/error rendering, state clearing and test-visible navigation diagnostics |
| `VaultScreen.java` | Explicit screen enum and accessibility labels |
| `VaultNavigationState.java` | Immutable non-persisted navigation metadata |
| `VaultScreenRouter.java` | Stack/reset/back/clear implementation |
| `VaultUiComponents.java` | XML inflation and reusable component binding |
| `CategoryRowModel.java` | Non-sensitive category presentation model |
| `ItemRowModel.java` | Minimal item-row presentation model |
| `CategoryRowAdapter.java` | Stable-ID category adapter |
| `ItemRowAdapter.java` | Stable-ID item adapter |
| `res/layout/view_vault_toolbar.xml` | Reusable toolbar |
| `res/layout/view_vault_empty_state.xml` | Loading, empty and error state shell |
| `res/layout/row_vault_category.xml` | Category row |
| `res/layout/row_vault_item.xml` | Item row |
| `res/layout/row_vault_field.xml` | Label/value/copy row foundation |
| `res/layout/row_vault_action.xml` | Icon/title/description action row |
| `VaultScreenRouterTest.java` | Pure JVM router-state tests |
| `KeeprivaLifecycleRobustnessTest.java` | Root-screen, lock clearing and recreation tests |

## 8. Test design

### 8.1 Pure JVM tests

Verify:

1. An empty router contains no current screen or history.
2. Reset to vault browser creates a root without back history.
3. Forward navigation and Back restore category, query and scroll position.
4. Replacing current state does not add history.
5. Clear removes current state, selected IDs, query and history.
6. Router state fields are limited to approved non-model types.
7. Navigation state is neither `Parcelable` nor `Serializable`.

### 8.2 Instrumentation tests

Extend `KeeprivaLifecycleRobustnessTest` to verify:

1. Successful vault creation starts on `Screen vault browser`.
2. Explicit lock renders `Screen unlock` and leaves router history empty.
3. Activity recreation after unlock never restores the vault-browser screen without an in-memory key.
4. The activity-owned root container remains the rendering parent across setup, browser, lock and unlock transitions.
5. Immediate background auto-lock clears the router history and in-memory key.
6. Screen-off lock clears the router history and in-memory key.

Existing lock/unlock, dialog cancellation, and all other tests remain unchanged unless semantic root matching is required.

### 8.3 CI placement

- Router JVM tests: `build-apks` job.
- Lifecycle instrumentation additions: existing `auth-lifecycle-smoke` matrix batch.
- No matrix restructuring is needed for Phase 2.

## 9. Patch sequence

1. Validate the exact branch and green Phase 1 ancestry.
2. Refuse to overwrite dirty target files.
3. Install the plan and Phase 2 source/resource/test payload.
4. Apply guarded edits to `app/build.gradle.kts`, `MainActivity.java`, and `KeeprivaLifecycleRobustnessTest.java`.
5. Verify database/backup/manifest invariants.
6. Verify existing 89 tests remain present and the planned lifecycle tests are additive.
7. Run Java/XML/Gradle structural checks and `git diff --check` locally.
8. Push and let the Phase 1 parallel workflow perform Android compilation, lint, JVM tests, all instrumentation batches, and visual verification.

## 10. Exit criteria

Phase 2 is complete only when:

- the activity owns one stable root content container;
- router and immutable navigation state exist;
- setup, unlock, loading, error and vault-browser roots have semantic screen identifiers;
- locking and destruction clear router history and sensitive navigation strings;
- recreation does not restore decrypted UI state;
- reusable XML components and stable-ID adapters compile;
- the database remains version 3;
- backup remains version 1;
- the manifest remains offline;
- all pre-existing tests and new Phase 2 tests pass;
- all five instrumentation batches, visual verification and the verification gate are green.

## 11. Rollback

The rollback point is Phase 1 commit:

```text
e3e8cd7c08a95f2c8450bcd22d426f1876a0adef
```

Phase 2 introduces no persisted-data migration. Rolling back the Phase 2 commit therefore requires no database or backup conversion.
