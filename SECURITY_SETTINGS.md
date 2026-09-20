# Keepriva — Security Settings and Master Password Management

Version: **2.0.0-alpha10** / versionCode **15**

## Security Settings access

The main toolbar now exposes a single **Security** entry point. Opening Security Settings requires the current master password, even when the vault was unlocked biometrically. This protects changes to security-sensitive preferences from someone who only has temporary access to an unlocked session.

## Change master password

Keepriva uses an independent random AES-256 vault data key. The master password derives a Key Encryption Key (KEK), which wraps that vault key. Therefore changing the master password does not decrypt/re-encrypt every database record.

Flow:

1. Enter the current master password.
2. Enter and confirm a new master password.
3. The current password unwraps/verifies the existing vault key.
4. A fresh 32-byte salt is generated.
5. PBKDF2-HMAC-SHA256 derives a new KEK using the existing 600,000-iteration policy.
6. The unchanged vault key is wrapped with the new KEK.
7. The new salt/wrapper/verifier are committed to local preferences.

Biometric unlock remains valid because the biometric wrapper protects the same unchanged vault key.

### New-password policy

New vault creation and master-password changes require:

- minimum 12 characters; and
- at least three of lowercase, uppercase, number, and symbol.

Existing installations with an older/shorter password can still unlock; the stronger rule applies only when creating or changing a password.

## Auto-lock

Configurable choices:

- Immediately
- 30 seconds (default)
- 1 minute
- 5 minutes

The timeout is evaluated when returning after the app has been backgrounded. Keepriva tracks Android document-picker operations separately so an import/export/backup file selection can return safely before normal auto-lock processing resumes.

## Lock on screen off

Enabled by default. A dynamic, non-exported receiver listens for Android `ACTION_SCREEN_OFF`. If the vault is unlocked and this setting is enabled, the session is locked immediately.

## Consolidated controls

Security Settings also provides access to:

- biometric unlock enable/disable;
- password clipboard timeout;
- current security-state summary.

## Session cleanup

On lock or Activity destruction, Keepriva performs best-effort cleanup by:

- dropping the in-memory vault key reference;
- clearing decrypted item and custom-category collections;
- clearing pending plaintext export/template/backup byte buffers;
- clearing the last Keepriva sensitive clipboard value when applicable.

Java `String` and provider-managed `SecretKey` objects are not guaranteed to be physically zeroizable, so this is best-effort memory hygiene rather than a guarantee against forensic extraction from a compromised/runtime-instrumented device.

## Release tests

1. Create a new vault with a password below 12 characters — creation must be rejected.
2. Create with 12+ characters and three character classes — creation should succeed.
3. Unlock an upgraded vault that still has an older 8-character password — it should continue to work.
4. Open Security Settings — master-password re-authentication must be required.
5. Change the master password; lock; verify old password fails and new password unlocks.
6. If biometric unlock is enabled, change the master password and verify biometric unlock still works.
7. Test all auto-lock values by backgrounding and returning.
8. Set auto-lock to Immediately; launch export/import/backup document picker and return/cancel — result processing must not be broken by premature locking.
9. With screen-off lock enabled, turn the screen off/on — vault must require unlock.
10. Disable screen-off lock and repeat — only configured background timeout should apply.
11. Lock manually after copying a password — clipboard cleanup should still occur.
