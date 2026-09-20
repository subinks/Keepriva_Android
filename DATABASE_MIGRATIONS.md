# Keepriva Database Migration Policy

Keepriva stores encrypted user records in SQLite. Database upgrades are therefore treated as security-sensitive, non-destructive operations.

## Current schema version

`3`

## Schema history

| Version | Schema |
|---|---|
| 1 | `vault_items` table containing encrypted item payloads |
| 2 | Adds `custom_categories` containing encrypted category definitions |
| 3 | Adds non-sensitive `migration_history` plus indexes on `updated_at` metadata |

The indexes do **not** expose title, username, password, phone number, URL, notes, category name, or custom-field values. Those continue to live inside encrypted payloads.

## Upgrade paths

The app registers sequential migrations:

- `1 -> 2`: create `custom_categories`
- `2 -> 3`: create `migration_history` and metadata-only indexes

An installation upgrading directly from v1 to v3 executes both migrations in order.

## Safety properties

1. **No destructive fallback.** The app does not drop/recreate the vault when a migration fails.
2. **Missing path = failure.** If a future developer bumps the schema version without implementing the matching migration, opening the database throws instead of guessing.
3. **Transactional upgrade.** `SQLiteOpenHelper` runs schema upgrades inside its database transaction. A failed upgrade does not advance the database version.
4. **Schema verification.** After creation or upgrade, expected tables are checked before the vault proceeds.
5. **Downgrades rejected.** Installing an older APK over a newer database is not allowed to reinterpret newer vault data.
6. **Migration history contains no secrets.** Only version numbers, migration name, and timestamp are stored there.
7. **Encrypted payload format remains compatible.** Schema v3 does not decrypt/rewrite existing vault records.

## Release testing checklist for every future schema change

Before shipping a schema-changing APK:

- Install/create data using each supported old schema version.
- Upgrade the APK without uninstalling the old version.
- Unlock with master password.
- Verify every existing vault item.
- Verify custom categories and custom fields.
- Verify biometric configuration/fallback where applicable.
- Add/edit/delete a record after migration.
- Restart the process and unlock again.
- Reboot the device and unlock again.
- Confirm the SQLite schema version is the expected latest version.
- Test a deliberately failing migration in a development build and verify the original database remains intact.
- Back up test data before destructive manual experiments.

## Future rule

For a new schema version `N`, add an explicit `migrate(N-1)ToN()` implementation **before** increasing `DB_VERSION`.

Never add code such as:

```java
db.execSQL("DROP TABLE ...");
onCreate(db);
```

as a fallback for a user's password vault.
