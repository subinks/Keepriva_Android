# Keepriva Phase 2A MainActivity Modularization

## 1. Document control

| Item | Value |
|---|---|
| Phase | 2A - behavior-preserving `MainActivity` modularization |
| Planning branch | `ui_eh_ph02_screen_routing` |
| Implementation branch | `ui_eh_ph02a_mainactivity_modularization` |
| Required parent commit | `2212df03ad713e5693bfd8d6b46b7d5ae926b363` |
| Required green workflow | `36100647077` |
| Current instrumentation inventory | 95 total: 94 blocking plus 1 diagnostic |
| Current JVM router/model tests | 9 |
| Current database version | 3 |
| Current backup format | 1 |
| Network permission | Absent and must remain absent |

## 2. Outcome

Phase 2A converts `MainActivity` from a 3,684-line feature monolith into a small lifecycle and composition shell. It does not intentionally change user-visible behavior. It creates explicit ownership boundaries that Phases 3-10 will extend instead of placing more feature code in the activity.

The target is not multiple activities, fragments, Compose, or a new dependency-injection framework. Keepriva retains one activity because it is the established security/session boundary. The improvement comes from composition, typed event contracts, immutable view state, repositories, and deterministic teardown.

## 3. Source analysis

### 3.1 Current responsibility inventory

The green Phase 2 activity currently owns all of the following:

| Area | Representative responsibilities |
|---|---|
| Android lifecycle | Create/start/stop/resume/destroy, receiver registration, Activity results |
| Security/session | `SecretKey`, setup, unlock, legacy migration, biometric unlock, lock and auto-lock |
| Root/navigation | Root container, screen rendering, router synchronization |
| Preferences/security UI | Category depth, auto-lock, screen-off lock, clipboard timeout, password change |
| Browser | Search box, category tree, item list, row construction, selected category/scroll |
| Categories | Hierarchy validation, editor, tombstones, delete, force-delete, move contents |
| Items | Details, editor, custom fields, delete/Undo, password reveal/copy |
| Transfer | Import, export, template, backup, restore, system picker, pending buffers |
| Generic UI | Buttons, text fields, labels, scroll wrappers, dialog sequencing |
| Test diagnostics | Current screen, router size, root children, key-presence checks |

This concentration creates three risks:

1. Later UI phases would add more fields, callbacks, routes, and dialog logic to the same class.
2. Lock/destruction cleanup is difficult to audit because many feature-owned references and buffers share one object.
3. A change in one feature can accidentally affect activity-wide focus, dialog, scroll, and session behavior.

### 3.2 Constraints established by the green baseline

- `MainActivity` must remain the sole owner of the active `SecretKey`.
- Activity recreation must not restore decrypted UI without a new unlock.
- Explicit lock, auto-lock, screen-off lock, and destruction must clear router and sensitive state.
- The root container and semantic screen descriptions introduced in Phase 2 must remain stable.
- Existing Espresso selectors and dialog-root hardening remain valid.
- Database schema 3, backup format 1, encryption hierarchy, and offline manifest remain unchanged.
- The current 95 instrumentation tests are regression requirements, not optional migration targets.

## 4. Design principles

### 4.1 Thin activity, explicit composition

`MainActivity` is responsible for Android lifecycle and composition. It creates controllers, injects narrow actions/gateways, selects the current route, and destroys session-scoped objects. It does not construct feature screens or dialogs.

### 4.2 Typed actions instead of activity calls

Controllers emit typed intent through interfaces such as:

```java
interface VaultBrowserActions {
    void onLockRequested();
    void onCategorySelected(String categoryName);
    void onItemSelected(long itemId);
    void onAddEntryRequested(String categoryName);
    void onAddSubcategoryRequested(String parentCategoryName);
}
```

The implementation performs routing or sensitive work. Controllers do not know which other controller will handle the next screen.

### 4.3 Immutable view state

Controllers receive immutable, presentation-oriented state. Item-list state contains item ID, title, category label, and approved secondary text only. It does not retain passwords, notes, complete `VaultItem` objects, or keys.

### 4.4 Session-scoped sensitive gateways

Operations that require the vault key are exposed as synchronous or callback-based gateways owned by the activity/session coordinator. The key is supplied only during the call and is never stored by the controller.

### 4.5 Deterministic teardown

Every UI-owning controller implements an idempotent teardown method. Teardown:

- dismisses registered dialogs;
- removes listeners and delayed callbacks;
- clears view references;
- clears pending plaintext/byte arrays where applicable;
- marks asynchronous callbacks invalid;
- can safely be called more than once.

## 5. Target architecture

```text
MainActivity
  |-- VaultSessionCoordinator
  |     |-- owns SecretKey through MainActivity boundary
  |     |-- setup/unlock/lock
  |     `-- clears all session-scoped modules
  |-- VaultRootRenderer
  |-- VaultScreenRouter
  |-- ActivityResultCoordinator
  |-- ControllerRegistry
  |     |-- SetupController
  |     |-- UnlockController
  |     |-- SecuritySettingsController
  |     |-- LegacyVaultBrowserController
  |     |-- CategoryManagementController
  |     |-- ItemDialogController
  |     `-- DataTransferController
  `-- Repositories and gateways
        |-- CategoryHierarchyService
        |-- ItemRepository
        |-- CategoryRepository
        `-- TransferGateway
```

### 5.1 Ownership table

| Component | Owns | Must not own |
|---|---|---|
| `MainActivity` | Lifecycle, root, active key, router, composition | Feature layouts/dialog implementations |
| `VaultSessionCoordinator` | Session transitions and callback generation | Persisted key or Android screen state |
| `VaultRootRenderer` | Replace the single root child and semantic screen marker | Feature state or database access |
| `ControllerRegistry` | Current session controller instances and teardown order | Key, models, database transactions |
| Screen controller | Current screen/view/dialog and typed event wiring | Static Activity/view, key, persistence |
| Repository/gateway | Focused data operation | Activity/view/dialog |
| View-state builder | Model-to-presentation transformation | Android lifecycle or mutable global state |

## 6. Proposed package structure

```text
com.example.privatevault
  MainActivity.java

  navigation/
    VaultScreen.java
    VaultNavigationState.java
    VaultScreenRouter.java
    VaultRootRenderer.java

  session/
    VaultSessionCoordinator.java
    VaultSessionGateway.java
    VaultLockReason.java

  ui/common/
    VaultController.java
    ControllerRegistry.java
    DialogRegistry.java
    VaultViewFactory.java
    VaultUiComponents.java

  ui/setup/
    SetupController.java
    SetupActions.java

  ui/unlock/
    UnlockController.java
    UnlockActions.java

  ui/security/
    SecuritySettingsController.java
    SecuritySettingsActions.java

  ui/browser/
    LegacyVaultBrowserController.java
    VaultBrowserActions.java
    VaultBrowserViewState.java

  ui/category/
    CategoryManagementController.java
    CategoryManagementActions.java
    CategoryHierarchyService.java

  ui/item/
    ItemDialogController.java
    ItemDialogActions.java

  ui/transfer/
    DataTransferController.java
    DataTransferActions.java
    ActivityResultCoordinator.java
```

Packages can remain under `com.example.privatevault` initially if moving existing package names creates unnecessary churn. The dependency rules matter more than directory aesthetics. Package moves should be mechanical and separate from behavior changes.

## 7. Security rules

The following rules are blocking:

1. Only `MainActivity`/its activity-owned session boundary has a `SecretKey` field.
2. Controller fields cannot be `SecretKey`, `VaultItem`, `Bundle`, `Parcelable`, `Serializable`, raw backup plaintext, or a complete decrypted item collection.
3. No controller, adapter, repository, or callback holds a static `Activity`, `Context`, `View`, or `Dialog`.
4. Navigation contains IDs and non-secret query/category metadata only.
5. Asynchronous callbacks check a generation/token before touching current UI.
6. Lock first invalidates callbacks, then dismisses UI, clears pending buffers/models, clears router, clears clipboard, and finally clears the key.
7. Logging and assertion messages never contain passwords, custom-field values, backup passwords, decrypted payloads, or clipboard content.
8. Controllers do not use saved-instance state to restore decrypted content.

## 8. Implementation waves and patch boundaries

### Patch A - Contracts and common ownership

Split this patch into two independently green commits. Patch A1 adds key ownership, the root renderer, the controller teardown contract/registry, and focused JVM tests. Patch A2 adds typed feature-action contracts, dialog registry, common view factory, and the remaining architecture tests. Wire both without moving feature behavior.

### Patch B - Setup, unlock, and security extraction

Move setup/unlock rendering and security-settings dialogs. Keep key derivation, migration, and key assignment inside the session boundary. Preserve exact semantic identifiers and PBKDF2 background behavior.

### Patch C - Browser, category, and item-dialog extraction

Move the legacy browser unchanged, extract hierarchy rules, category-management workflows, and existing item details/editor/delete/Undo dialogs. This is the highest-risk patch and should be split into browser/category/item commits if diff size becomes difficult to review.

### Patch D - Transfer and Activity-result extraction

Move import/export/template/backup/restore workflows, pending buffers, and request-code dispatch. Validate every cancellation and failure exit path.

### Patch E - Composition cleanup

Remove forwarding methods and unused fields, centralize lock teardown, enforce size/ownership checks, and record the final architecture inventory.

Each patch must compile and pass its focused tests before the next patch is applied. Patch scripts must check the expected parent/source hashes and refuse overlapping working-tree changes.

## 9. Test plan

### 9.1 Existing regression suite

All 95 Phase 2 instrumentation tests must remain enabled. Expected blocking starting counts are:

| Batch | Count |
|---|---:|
| `auth-lifecycle-smoke` | 32 |
| `categories` | 24 |
| `item-core` | 14 |
| `data-transfer` | 12 |
| `security` | 12 |
| **Blocking total** | **94** |

The one biometric diagnostic remains non-blocking.

### 9.2 New JVM tests

- Controller field-type security test.
- Controller registry teardown order/idempotence.
- Dialog registry dismissal/idempotence.
- Category hierarchy path/depth/descendant/move validation.
- Activity-result request-code routing.
- Immutable view-state mapping.
- Late callback generation invalidation.

### 9.3 New instrumentation tests

- Lock while each extracted controller owns its screen/dialog.
- Activity destruction dismisses dialogs and ignores late callbacks.
- Root child count remains one through controller transitions.
- Setup/unlock/browser semantic screen identifiers remain unchanged.
- Import/export/backup picker returns are dispatched to the correct transfer operation.
- Recreation requires unlock and creates fresh controller instances.

### 9.4 Test-count guard rule

Every patch adding/removing an instrumentation method updates `scripts/ci/run-instrumentation-batch.sh` in the same commit. The patch validator calculates the complete instrumentation count and the sum of blocking guards.

## 10. Downstream phase impact analysis

| Phase | Required revision after Phase 2A |
|---|---|
| 3 - Browser | Replace `LegacyVaultBrowserController` with final `VaultBrowserController`; use typed actions/view state and Phase 2A category service |
| 4 - Search | Add `SearchResultsController` and pure `VaultSearchService`; route only IDs through the Activity/router |
| 5 - Details/copy | Replace the details path in `ItemDialogController`; use session-owned clipboard gateway |
| 6 - Move | Add `ItemMoveController`; reuse hierarchy service and repository transaction |
| 7 - Version storage | Extend repositories; keep schema/migration/snapshot SQL outside Activity/controllers |
| 8 - History UI | Add history/version controllers; release historical decrypted view state on every exit |
| 9 - Backup | Extend `DataTransferController`; never return pending buffers to `MainActivity` |
| 10 - Integration | Remove legacy controllers, enforce activity/controller architecture tests and final ownership boundaries |

## 11. Files expected to change during implementation

The documentation installer changes only documentation. Wave A is bundled separately and modifies only the first architecture seams. The complete Phase 2A implementation patch series is expected to modify:

- `MainActivity.java`;
- current router/root UI classes where package/ownership contracts require it;
- `KeeprivaLifecycleRobustnessTest.java`;
- test-count guards when tests are added;
- new controller/action/view-state/registry/service/repository files;
- new JVM and focused instrumentation test classes.

Phase 2A must not modify:

- `VaultDatabase.DB_VERSION`;
- `BackupManager.FORMAT_VERSION`;
- encryption algorithms or stored key hierarchy;
- manifest permissions;
- export/backup file formats;
- visible feature requirements.

## 12. Review strategy

For every extraction wave, reviewers should compare behavior rather than only moved lines:

1. Identify original methods/fields moved.
2. Confirm all original entry and cancellation paths remain reachable.
3. Confirm lock/destruction teardown covers the new owner.
4. Confirm no sensitive value moved into a longer-lived object.
5. Confirm all semantic identifiers are unchanged.
6. Run focused tests and then the full matrix.
7. Record line counts and remaining activity responsibilities.

## 13. Exit criteria

Phase 2A is complete only when:

- `MainActivity` is at most 1,200 lines and contains only approved responsibilities;
- no feature-specific dialog or row construction remains in the activity;
- all controllers have explicit action contracts and teardown;
- architecture/security tests pass;
- all 95 existing instrumentation tests plus added tests pass;
- all existing nine JVM router/model tests plus added JVM tests pass;
- the serial safety net passes;
- visual output is behaviorally equivalent to green Phase 2;
- database version 3, backup format 1, and offline manifest are unchanged;
- the final implementation report maps every removed activity responsibility to its new owner.

## 14. Rollback

Rollback to `2212df03ad713e5693bfd8d6b46b7d5ae926b363`. Phase 2A has no persisted-data changes, so no database or backup downgrade is required.

## 15. Recommended execution order

1. Apply and commit the documentation update on `ui_eh_ph02_screen_routing`.
2. Confirm the documentation-only workflow remains green.
3. Create `ui_eh_ph02a_mainactivity_modularization` from the updated green branch.
4. Produce and apply Patch A only.
5. Build and run the full workflow.
6. Continue one extraction wave at a time.
7. Run the manual serial safety net after Patch E.
8. Start Phase 3 only after Phase 2A is green and reviewed.

## 16. Bundled scripts and current implementation scope

| Script | Purpose | Mutates application source? |
|---|---|---:|
| `37-update-master-plan-for-phase-02a.ps1` | Installs the revised master Markdown/PDF and this Phase 2A plan on the green Phase 2 branch | No |
| `38-validate-phase-02a-implementation-readiness.ps1` | Read-only gate for the new Phase 2A implementation branch | No |
| `39-implement-phase-02a-wave-a-architecture-seams.ps1` | Applies Wave A1 key ownership, root rendering, controller teardown contracts, and five JVM tests | Yes |

Wave A1 is intentionally the only production-source payload in this bundle. Wave A2 and Waves B-E must each be generated from the preceding green commit so an older all-in-one payload cannot overwrite build corrections. After Wave A1, the expected inventory is 95 instrumentation tests and 14 JVM tests. Instrumentation selectors and batch-count guards are unchanged because Wave A1 adds no instrumentation method.
