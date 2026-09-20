# Keepriva — Release and Tamper Hardening

Version: **2.0.0-alpha9**  
Security step: **9 — release/tamper hardening**

## Goal

A release APK/AAB must be materially different from a development build. This project now enables Android/R8 release protections, keeps signing secrets out of source control, and can optionally pin the expected release signing certificate for an offline self-check.

## Release build protections

`app/build.gradle.kts` configures the release variant with:

- `debuggable = false`
- JNI debugging disabled
- R8 code shrinking/optimization enabled
- Android resource shrinking enabled
- `proguard-android-optimize.txt`
- Keepriva R8 rules
- no debug application-id suffix on release
- optional release signing from environment/Gradle secrets
- optional compile-time SHA-256 pin of the signing certificate

The debug variant uses the application ID:

```text
com.example.privatevault.debug
```

while release continues to use:

```text
com.example.privatevault
```

This allows a debug build and the real release build to coexist on the same phone without confusing one for the other.

## R8 / release logging

The release R8 configuration removes `Log.v`, `Log.d`, and `Log.i` calls if any are added later. The current source has no Android `Log.*`, `System.out`, or `printStackTrace()` calls containing vault data.

Do not rely on R8 to make logging safe. Never construct a log message containing passwords, master passwords, vault keys, backup passwords, decrypted JSON, notes, phone numbers, or custom sensitive values.

## Signing secrets

Never commit a keystore or signing password. `.gitignore` excludes common signing files and generated APK/AAB artifacts.

For automated signing, provide these values as environment variables or Gradle properties:

```text
PV_KEYSTORE_FILE
PV_KEYSTORE_PASSWORD
PV_KEY_ALIAS
PV_KEY_PASSWORD
```

Example in Windows PowerShell:

```powershell
$env:PV_KEYSTORE_FILE="C:\Secure\PrivateVault-release.jks"
$env:PV_KEYSTORE_PASSWORD="your-keystore-password"
$env:PV_KEY_ALIAS="privatevault"
$env:PV_KEY_PASSWORD="your-key-password"

.\gradlew.bat verifyReleaseHardening
.\gradlew.bat assembleRelease
.\gradlew.bat bundleRelease
```

Do not save those commands with real passwords in a repository or shared shell script.

## Signing-certificate self-check

When the Gradle build can read the configured release keystore, it calculates the release certificate SHA-256 digest at build time and embeds only that **public certificate digest** into the release BuildConfig.

On application startup, `ReleaseSecurityManager` verifies:

1. a hardened release is not marked debuggable; and
2. if a signing-certificate digest was embedded, the installed APK is signed by that expected certificate.

If the check fails, Keepriva does not open the vault UI.

If a release is signed using Android Studio's signing wizard rather than the `PV_*` configuration, you may supply:

```text
PV_EXPECTED_SIGNING_CERT_SHA256
```

as a 64-character SHA-256 certificate digest during the build to activate signature pinning.

### Important limitation

This is an **offline client-side tamper check**. A sophisticated attacker who can modify and resign the APK can attempt to patch the check as well. It should be treated as defense-in-depth, not as the main protection for stored credentials. Encryption, authentication, Android Keystore use, biometric-gated key access, and device security remain the primary controls.

## Verify release hardening

Run:

```powershell
.\gradlew.bat verifyReleaseHardening
```

It checks that release minification/resource shrinking are enabled and that release is non-debuggable. If signing variables are present, it also checks that a signing-certificate digest can be derived.

## Inspect a finished APK

From the Android SDK Build Tools directory, run:

```powershell
apksigner verify --verbose --print-certs app-release.apk
```

Check that verification succeeds and record the certificate SHA-256 digest for your release records.

You can also inspect package/debuggable state with Android SDK tools or Android Studio's APK Analyzer. A production build must not be debuggable.

## Release signing key rules

- Use a dedicated release keystore, not the Android debug keystore.
- Keep at least two secure offline backups of the release keystore.
- Do not email the keystore to yourself.
- Do not put it into Git, a source ZIP, Google Drive folder shared broadly, or app assets/resources.
- Protect CI values using GitHub/Codemagic/Bitrise secret stores.
- Use the same release signing identity for direct APK upgrades.
- If later publishing through Google Play, configure Play App Signing appropriately and retain the upload key securely.

## Dependency review

This project currently uses Android/Java platform APIs and has no third-party runtime library dependencies. That reduces dependency and supply-chain surface. Re-review this statement whenever adding a library.

Before every release:

1. review dependency changes;
2. remove unused dependencies;
3. check for known vulnerable versions;
4. confirm no library introduces an Internet permission unexpectedly;
5. inspect the merged manifest for permissions/components.

## Manifest security review

The current manifest intentionally keeps:

- `android:allowBackup="false"`
- `android:fullBackupContent="false"`
- `android:usesCleartextTraffic="false"`
- no `INTERNET` permission
- only the launcher Activity exported

The biometric permission is required for the local biometric-unlock feature.

## Pre-release checklist

- [ ] Build from a clean source checkout.
- [ ] Confirm `versionCode` increased.
- [ ] Run database migration tests.
- [ ] Test master-password unlock.
- [ ] Test biometric unlock/fallback.
- [ ] Test clipboard timeout and lock cleanup.
- [ ] Test encrypted `.pvault` backup and restore.
- [ ] Test safe and sensitive TXT/HTML/PDF export.
- [ ] Verify screenshots/recording/Recents protections on real devices.
- [ ] Run `verifyReleaseHardening`.
- [ ] Build signed APK and AAB.
- [ ] Run `apksigner verify --verbose --print-certs` on the APK.
- [ ] Confirm release APK is not debuggable.
- [ ] Inspect merged manifest and confirm no `INTERNET` permission.
- [ ] Confirm no keystore/password/secret was included in the source ZIP or Git history.
- [ ] Install the signed APK on a clean test device.
- [ ] Upgrade over the previous signed release and verify all encrypted data remains accessible.

