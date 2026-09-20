# Keepriva encrypted backup and restore — Security Step 6

## Goal

`.pvault` is the portable disaster-recovery format. It is separate from the readable TXT/HTML/PDF export formats.

## Backup security model

1. The unlocked app reads the logical entries and custom categories through the current vault key.
2. The user supplies a **separate backup password** (minimum 10 characters).
3. A random 32-byte backup salt is generated.
4. PBKDF2-HMAC-SHA256 derives a backup encryption key using 600,000 iterations.
5. The complete logical snapshot is encrypted with AES-256-GCM.
6. Only a small non-secret envelope remains readable: format/version, KDF name and iterations, salt, cipher name and encrypted payload.

The backup intentionally does **not** contain:

- the Android Keystore key;
- the current raw vault data key;
- the master password;
- the master-password-wrapped vault key;
- biometric configuration/secrets.

This makes the backup portable to another Android phone and independent of later master-password changes.

## Restore behavior

Restore is intentionally **replace**, not merge, in this security-hardening version.

1. User chooses a `.pvault` file through Android's system document picker.
2. User enters the backup password.
3. AES-GCM authentication verifies both password correctness and ciphertext integrity before any database write.
4. JSON structure, format version and basic category/item structure are validated.
5. The app shows a restore preview with entry/category counts and source schema version.
6. If the backup schema is newer than the installed app supports, restore is refused.
7. Only after explicit confirmation are the current entries/categories replaced.
8. Replacement occurs inside one SQLite transaction. A failed write rolls back the replacement.
9. Restored records are encrypted again using the **destination vault's current vault key**.
10. The destination phone keeps its current master password, Android Keystore key and biometric settings.

## Important user rules

- Keep the backup password separately and safely. Keepriva cannot recover it.
- Prefer a backup password different from the vault master password.
- A `.pvault` file is encrypted, but it is still sensitive and should be stored carefully.
- Test restore with non-production/sample data before depending on backup as the only recovery method.
- TXT/HTML/PDF exports are readable exports and are not substitutes for `.pvault` backups.

## Release test cases

- Create backup containing built-in and custom categories.
- Restore on the same installation.
- Restore on a fresh vault with a different master password.
- Restore after biometric unlock is enabled.
- Verify wrong backup password changes nothing.
- Modify one byte in the encrypted payload and verify restore fails before database changes.
- Cancel at file selection, password prompt and preview.
- Force a database write failure and verify transaction rollback.
- Restore an empty vault backup.
- Reject unsupported/newer format or schema versions.
- Reboot and verify restored data remains readable.
