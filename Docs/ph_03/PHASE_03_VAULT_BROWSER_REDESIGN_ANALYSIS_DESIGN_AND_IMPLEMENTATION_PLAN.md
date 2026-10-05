# Phase 03: Vault Browser Redesign — Analysis, Design, and Implementation Plan

## 1. Document status

| Item | Value |
|---|---|
| Phase | Phase 03 — Redesign the vault browser |
| Working branch | `ui_eh_ph_03_redesign` |
| Required baseline commit | `0043ebda60ff0af82e51a816d9c390a2ccb0fcaa` |
| Baseline CI | GitHub Actions run `36259175292`, successful |
| Database version | 3 — must remain unchanged |
| Backup format | 1 — must remain unchanged |
| Network policy | No `android.permission.INTERNET` |
| Current `MainActivity` size | 1,097 lines |
| Current JVM inventory | 52 tests before Phase 03 Wave A |
| Current instrumentation inventory | 96 tests: 95 blocking plus one biometric diagnostic |

This document specializes the Phase 03 section of
`Docs/Keepriva_UI_Restructure_Phase_Wise_Implementation_and_Parallel_Test_Plan.md`.
The master plan remains authoritative when this document is silent.

## 2. Phase 02 and Phase 02A entry-gate decision

Phase 03 may start because the Phase 02/02A exit gate is satisfied at the required baseline:

- Phase 02 routing and reusable row foundations are present.
- Phase 02A Waves A–E are present in history and its final implementation report maps the
  extracted responsibilities to their owners.
- `MainActivity` is below the 1,200-line target and remains the sensitive session owner.
- Database version 3, backup format 1, and the offline manifest are unchanged.
- GitHub Actions run `36259175292` passed the build, all five isolated instrumentation
  batches, visual verification, the complete serial safety net, and the verification gate.

The checked-in Phase 02A implementation plan defines Waves A–E. It does not define a Wave F
scope. Therefore, an undefined Phase 02A Wave F is not a Phase 03 blocker. The GitHub Actions
Node.js 20 deprecation warning is intentionally parked as maintenance work and is not part of
this functional phase.

## 3. Objective

Replace the legacy long expandable category tree with a category-first vault browser:

1. The root shows direct root categories.
2. Selecting a category opens that category rather than expanding the complete tree.
3. A category screen shows only its direct subcategories and direct items.
4. A breadcrumb communicates the complete path.
5. Search, lock, add, and management actions remain reachable from the browser chrome.
6. Returning from item details preserves the selected category and list position.

The redesign changes presentation and navigation only. It must not migrate, delete, move,
re-encrypt, or reinterpret stored vault data.

## 4. Existing implementation analysis

### 4.1 Reusable Phase 02/02A foundations

The branch already contains the foundations Phase 03 should reuse:

- `VaultScreenRouter` and immutable `VaultNavigationState`.
- `VaultRootRenderer` and `VaultViewFactory`.
- Stable-ID `CategoryRowAdapter` and `ItemRowAdapter`.
- XML toolbar, category-row, item-row, and empty-state components.
- `CategoryHierarchyService` for depth, path, cycle, descendant, and move validation.
- Typed `VaultBrowserActions` callbacks.
- Phase 02A controllers for item dialogs, category management, data transfer, preferences,
  security, unlock, and setup.
- `ControllerRegistry`, `DialogRegistry`, and callback-generation lifecycle invalidation.

### 4.2 Temporary implementation to replace

`LegacyVaultBrowserController` currently owns a programmatic `ScrollView` containing:

- an inline search field;
- a recursively expandable category tree;
- inline item rows;
- a large vault-tools card.

`VaultBrowserModel` and `VaultBrowserModelBuilder` produce the recursive presentation tree.
They are temporary Phase 02A compatibility code. They may coexist during Phase 03 Waves A–D,
but must be removed in Wave E.

### 4.3 Security observations

- `MainActivity` owns decrypted `VaultItem` objects and `SecretKey` access for the unlocked
  session. That ownership must not move into the browser controller.
- The legacy builder searches password-adjacent source fields and can put a username in an
  item row. The Phase 03 browser read-model will expose only item ID, display title, and full
  category path. Passwords, notes, usernames, phone numbers, URLs, and custom-field values do
  not belong in `VaultBrowserViewState`.
- Browser view state is process-memory-only and must never implement `Parcelable` or
  `Serializable`.

## 5. Target architecture

### 5.1 Ownership boundary

| Owner | Responsibilities |
|---|---|
| `MainActivity` / `VaultSessionCoordinator` | Key ownership, decrypted item lifetime, database reads, lock |
| `VaultBrowserViewStateBuilder` | Convert short-lived activity-owned data into non-secret direct rows |
| `VaultBrowserController` | Inflate/bind browser UI, adapter state, toolbar/FAB/overflow events |
| `CategoryHierarchyService` | Parent, direct-child, breadcrumb, depth and validation rules |
| `VaultScreenRouter` / `VaultNavigationState` | Category and list-position navigation metadata |
| Existing Phase 02A controllers | Item/category/data-transfer/preferences/security workflows |

The browser controller may retain immutable presentation rows and identifiers. It must not
retain a `SecretKey`, `VaultDatabase`, decrypted `VaultItem`, password, notes, or custom-field
map.

### 5.2 Immutable browser state

`VaultBrowserViewState` contains:

- current category name;
- toolbar title;
- complete breadcrumb text;
- parent category name;
- immutable direct-subcategory `CategoryRowModel` list;
- immutable direct-item `ItemRowModel` list;
- one explicit empty-state classification.

The virtual category `All` is the browser root. Its direct categories are active built-in
categories and custom categories whose parent is empty. A non-root category contains only
custom categories whose `parentName` equals the current category and items whose category
equals the current category. Comparisons are case-insensitive; emitted names preserve their
stored spelling.

### 5.3 Category counts and icons

Each category row shows the count of items in its complete descendant subtree, preserving the
existing count meaning. Built-in categories keep their semantic icon family. A custom category
inherits the first built-in semantic family found while walking its ancestors; an unrelated
custom root uses `Custom`.

### 5.4 Item row privacy

Browser item rows show:

- the item title, using `Untitled` when empty; and
- the complete category path.

They do not show or retain password, notes, username, phone, URL, or custom-field values.
Phase 04 will implement the dedicated search-results route; Phase 03 must not silently turn the
category browser into a global secret-field search surface.

### 5.5 Empty states

| State | Condition | Required presentation |
|---|---|---|
| `EMPTY_VAULT` | Root selected and the vault has no items | Explain that the vault has no entries; add remains available |
| `EMPTY_CATEGORY` | Selected category has no direct children and no direct items | Explain that the category is empty |
| `NO_DIRECT_ITEMS` | Selected category has direct children but no direct items | Keep subcategories visible and explain that there are no direct entries |
| `NONE` | None of the above | No empty-state panel |

### 5.6 Navigation rules

- Category tap: replace the current browser state with the selected category and position 0.
- System back from a nested category: navigate to its parent category.
- System back at `All`: use the existing root/lock behavior defined by the router.
- Item tap: push item details while retaining the browser state beneath it.
- Return from details/editor: restore the category and adapter position from
  `VaultNavigationState`.
- Lock, timeout, vault-load failure, and destruction: clear browser/controller state through
  the existing registries and session cleanup.

## 6. User-interface specification

### 6.1 Toolbar

The browser toolbar contains:

- current vault/category title;
- Search action;
- Lock action;
- Overflow action.

The Search action routes to the reserved `SEARCH_RESULTS` destination. The final dedicated
search UI is Phase 04; until then the Phase 03 test contract is reachability and safe routing,
not a new global inline search implementation.

### 6.2 Content

The content order is:

1. breadcrumb when below the root;
2. `Subcategories` section when direct child categories exist;
3. `Items` section when direct items exist;
4. applicable empty-state panel.

No recursive descendants are inflated into the current screen.

### 6.3 Add action

A floating `+` action offers:

- `Entry`, routed to `ItemDialogController` with the current category; and
- `Subcategory`, routed to `CategoryManagementController` with the current category as parent.

At `All`, the existing safe defaults are retained: entry creation selects an allowed category,
and subcategory creation creates a root custom category.

### 6.4 Overflow actions

Overflow routes typed intents to the existing Phase 02A owners for:

- category management;
- import;
- export;
- backup and restore;
- preferences;
- security settings.

The browser controller does not implement those workflows.

## 7. Sub-phases and green checkpoints

Phase 03 is divided into five ordered waves. Every wave starts from the preceding green commit.
No later-wave payload may be applied early.

### Wave A — Direct-row contracts and hierarchy read-model

Scope:

- add immutable `VaultBrowserViewState`;
- add `VaultBrowserViewStateBuilder` for root/direct-child/direct-item state;
- extend `CategoryHierarchyService` with sorted direct-child and parent lookup;
- add semantic icon-family data to `CategoryRowModel` without breaking the legacy constructor;
- add focused JVM privacy, hierarchy, count, breadcrumb, and empty-state tests;
- install this Phase 03 plan.

Wave A deliberately does not change the rendered UI or instrumentation selectors. Expected
inventory after Wave A is 62 JVM tests and 96 instrumentation tests.

Exit gate:

- Wave A JVM tests and the full JVM suite pass;
- debug compile, lint, and APK assembly pass;
- all 96 instrumentation tests remain unchanged and green in CI;
- DB/backup/offline invariants remain unchanged.

### Wave B — Category-first controller and direct-list rendering

Scope:

- add `VaultBrowserController` using the Wave A state;
- add/inflate the category-first screen layout;
- bind stable-ID category and item adapters;
- implement root and nested direct-list rendering;
- bind breadcrumb and the three empty states;
- switch `MainActivity` composition from the legacy controller to the new controller while
  keeping existing action routing.

Test focus:

- built-ins and custom roots appear;
- only direct children/direct items appear after navigation;
- breadcrumb is complete;
- item title/path and semantic category icons render;
- empty-vault, empty-category, and no-direct-items states render.

### Wave C — Browser chrome, actions, and navigation restoration

Scope:

- complete toolbar Search, Lock, and Overflow actions;
- add the floating Entry/Subcategory menu;
- route overflow actions to existing Phase 02A controllers;
- implement parent-category back navigation;
- retain selected category and adapter position across item details/editor return;
- preserve lifecycle invalidation on lock, timeout, failure, and destruction.

Test focus:

- toolbar lock and action content descriptions;
- add choices and current-category routing;
- overflow reachability;
- parent back behavior;
- return-from-details category/position restoration;
- lock clears state and late callbacks cannot redraw the vault.

### Wave D — Instrumentation migration and visual stabilization

Scope:

- update legacy inline-expansion expectations in `KeeprivaAuthHomeTest`,
  `KeeprivaCategoryPreferencesTest`, `KeeprivaItemCrudTest`, and `KeeprivaUiSmokeTest`;
- add only the instrumentation methods required by the master Phase 03 acceptance matrix;
- update per-batch and serial expected counts only when tests are actually added;
- update visual capture for root, nested, and empty category screens;
- preserve isolated emulator batches and immutable APK reuse.

Test focus:

- `categories`, `item-core`, and `auth-lifecycle-smoke` batches;
- all unaffected data-transfer and security tests;
- deterministic root/nested/empty screenshots.

### Wave E — Legacy removal and final Phase 03 verification

Scope:

- remove `LegacyVaultBrowserController`, `VaultBrowserModel`, and
  `VaultBrowserModelBuilder`;
- remove obsolete recursive-tree state, inline-search wiring, resources, and imports;
- apply the approved end-of-phase UI corrections: place entry export with the item-detail
  action strip, compact the vault toolbar without reducing 48 dp touch targets, remove the
  visible compatibility tools section, and avoid the duplicate root category heading;
- enforce architecture guards preventing browser rendering in `MainActivity`;
- write the Phase 03 implementation report;
- run the complete parallel workflow and marker-triggered serial safety net.

Exit gate:

- every master Phase 03 exit criterion is satisfied;
- every existing category CRUD/deletion workflow remains green;
- no item is lost, moved, or mutated by navigation;
- the complete tree is never expanded on one screen;
- management tools remain reachable without dominating vault content;
- `LegacyVaultBrowserController` and recursive browser models are absent;
- `MainActivity` contains no browser-specific rendering;
- all JVM, all blocking instrumentation tests, the biometric diagnostic, visual verification,
  and the final CI gate are green.

## 8. Test and CI strategy

### 8.1 JVM tests

Wave A establishes deterministic pure-Java tests for:

- direct-child selection and ordering;
- parent and breadcrumb resolution;
- direct-item filtering;
- subtree counts and inherited semantic icons;
- empty-state classification;
- immutable state copies;
- absence of secret-bearing fields from browser state.

Later waves add controller/action and navigation-state tests before instrumentation is changed.

### 8.2 Instrumentation batches

| Batch | Phase 03 responsibility |
|---|---|
| `auth-lifecycle-smoke` | toolbar, lock, root reachability, lifecycle cleanup |
| `categories` | root/nested hierarchy, breadcrumb, add subcategory, category management |
| `item-core` | direct items, item details/editor return, add entry |
| `data-transfer` | overflow routes continue to reach import/export/backup |
| `security` | overflow routes continue to reach preferences/security |

The one biometric test remains a non-blocking visual-job diagnostic. The serial safety net runs
the 95 blocking baseline tests plus any new blocking tests added during Phase 03. A milestone
commit message containing `[serial-safety-net]` is required for the final Wave E push.

### 8.3 Required commands

At every wave:

```text
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
```

CI then runs the five isolated instrumentation batches and visual verification against the
single immutable APK bundle. Wave E also requires the complete serial safety net.

## 9. Invariants and non-goals

Every Phase 03 wave must preserve:

- database version 3;
- backup format 1;
- no Internet permission;
- the encrypted storage and key hierarchy;
- category maximum-depth rules;
- built-in-category tombstones;
- category/item IDs and stored parent/category names;
- Phase 02A sensitive session ownership and lifecycle cleanup.

Phase 03 does not implement:

- the final dedicated search-results screen (Phase 04);
- full-screen item details/editor (Phase 05);
- move-item UI (Phase 06);
- encrypted item history or database v4 (Phase 07);
- biometric, quick-lock, idle-lock, or visual-polish phases.

## 10. Rollback

The Phase 03 rollback point is commit
`0043ebda60ff0af82e51a816d9c390a2ccb0fcaa` on
`ui_eh_ph_03_redesign`. Phase 03 has no persisted-data migration, so rolling back a Wave A–E
working commit to the preceding green checkpoint requires no database or backup downgrade.

## 11. Delivery rule

Each installer must:

1. require branch `ui_eh_ph_03_redesign`;
2. require the exact preceding green commit;
3. accept clean LF or CRLF Windows checkouts by hashing canonical UTF-8 text;
4. reject tracked, staged, or untracked changes before writing;
5. validate every payload hash and every baseline hash before writing;
6. restrict changes to the manifest allow-list;
7. roll back every installed file if post-install verification fails;
8. be executed from its extracted folder, independent of the current PowerShell directory;
9. be tested from the final ZIP on clean LF and CRLF clones before delivery.

Wave A is the only application-source payload delivered with this plan. Waves B–E must be
generated from their actual preceding green commits so that no package can overwrite build or
test corrections made between checkpoints.
