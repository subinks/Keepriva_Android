# Keepriva v17 — Nested Categories

## Scope

This version is built directly on the v16 security-hardened base. It does **not** include the Secure Inter-App API, Manual Share, or Android Autofill variants.

## Hierarchy model

- Built-in categories remain protected root categories.
- A custom category may be:
  - top-level, or
  - a child of a built-in category, or
  - a child of another custom category.
- Items may belong to any built-in or custom category at any level.
- Category names remain globally unique, which keeps existing v16 item/category references backward-compatible.

Example:

```text
Banking
├── SBI
│   ├── Internet Banking
│   └── Credit Cards
└── ICICI
    └── Net Banking

Work
└── Company A
    ├── VPN
    └── Production Systems
```

Items may exist directly under `Banking`, `SBI`, `Internet Banking`, or any other allowed level.

## Configurable maximum depth

Open:

```text
Keepriva → Preferences
```

The **Maximum category depth** preference behaves as follows:

- Default: **3**
- Hard maximum: **5**
- Root category counts as level 1.
- The value is read-only when the Preferences dialog first opens.
- Enable **Enable editing category nesting depth** to modify it.
- Reducing the preference below the depth already used by the existing hierarchy is rejected.

Examples with maximum depth 3:

```text
Level 1: Banking
Level 2: SBI
Level 3: Credit Cards
```

A fourth level is rejected until the preference is increased.

## Create a sub-category

1. Unlock Keepriva.
2. Open **Categories**.
3. Select **+ New custom category / sub-category**.
4. Enter the category name and configured custom fields.
5. Select the parent:
   - Top level
   - Built-in category
   - Existing custom category
6. Save.

Keepriva validates the resulting depth before committing the category.

## Move a category

1. Open **Categories**.
2. Select **Edit / Move** for the custom category.
3. Change **Parent category**.
4. Save.

The move is rejected if:

- the new parent is the category itself,
- the new parent is one of its descendants,
- the move would create a cycle,
- the category or any descendant would exceed the configured maximum depth.

## Rename a category

Renaming a category updates, transactionally:

- entries directly assigned to the category,
- direct child categories whose `parentName` refers to the old category name.

This preserves the nested tree.

## Delete a nested category

### Empty category

If a custom category contains no direct entries and no direct sub-categories, Keepriva asks for confirmation and deletes only that category.

### Category containing items and/or children

Keepriva does not recursively destroy them.

The user must choose a destination category. In one SQLite transaction Keepriva:

1. Moves direct entries to the destination category.
2. Re-parents direct child categories under the destination category.
3. Leaves deeper descendant relationships intact.
4. Deletes only the selected source category.

If any database write fails, the transaction rolls back.

## Filtering

Selecting a parent category in the main category filter includes entries belonging directly to that category **and entries in descendant categories**.

For example, selecting `Banking` can show items in:

```text
Banking
Banking / SBI
Banking / SBI / Credit Cards
Banking / ICICI
```

## Item editor

The item category selector displays hierarchical paths such as:

```text
Banking / SBI / Internet Banking
```

The encrypted item payload continues to store the globally unique category name, preserving compatibility with the v16 data model.

## Backup and restore

Encrypted `.pvault` backups now preserve `parentName` for custom categories.

Older backups that do not contain `parentName` remain compatible and restore their custom categories as top-level categories.

Before restore, Keepriva validates:

- missing parents,
- cycles,
- maximum configured hierarchy depth.

If a backup hierarchy is deeper than the current preference, restore is blocked until the user increases the preference.

## JSON import

The category object now supports optional hierarchy metadata:

```json
{
  "name": "SBI",
  "parentCategory": "Banking",
  "fields": [
    {
      "key": "customerId",
      "label": "Customer ID",
      "type": "text",
      "sensitive": true,
      "multipleValues": false,
      "multiline": false,
      "target": "custom"
    }
  ],
  "entries": []
}
```

`parentCategory` may be:

- empty for a top-level custom category,
- a built-in category,
- an existing custom category,
- another custom category being created by the same import.

Import preview validates the complete resulting hierarchy before any database write is committed.

## Compatibility

This feature extends the encrypted custom-category JSON payload instead of adding plaintext hierarchy columns to SQLite.

Existing v16 custom categories do not have `parentName`; they are interpreted as top-level categories automatically.

No bulk decrypt/re-encrypt migration of vault items is required.
