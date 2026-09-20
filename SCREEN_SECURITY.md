# Keepriva screen-capture protection

## Security goal

Keepriva treats every in-app screen as potentially sensitive. Screen-capture protection is therefore applied to the whole `MainActivity`, not only to password-detail views.

## Controls implemented

1. `WindowManager.LayoutParams.FLAG_SECURE` is applied as soon as `MainActivity` is created.
2. The protection is re-applied in `onResume()` after returning from system UI such as biometric authentication or Android's document picker.
3. The protection is re-applied whenever the activity regains window focus. This is defensive against OEM/window transitions that may recreate or adjust window flags.
4. On Android 13 / API 33 and later, `Activity.setRecentsScreenshotEnabled(false)` is also used so Android does not retain a screenshot of the activity for the Recents/Overview representation.
5. Explicitly created sensitive `AlertDialog` windows are also marked secure before display.
6. The lock screen remains protected too. This avoids accidentally exposing app state during screen transitions and keeps the behavior uniform.

## What FLAG_SECURE protects

On normal Android implementations, `FLAG_SECURE` prevents the activity surface from appearing in ordinary screenshots, non-secure displays, and typical screen recordings. Android may display a blank/blocked region instead.

## Important limitation

No application-level control can protect a secret from every possible capture method. Examples include:

- another physical camera pointed at the phone;
- a rooted/compromised device;
- malicious firmware or sufficiently privileged system software;
- accessibility or OEM behavior outside the guarantees of the Android security model.

The app therefore treats screenshot blocking as one layer in a broader defense-in-depth design rather than as encryption.

## Test checklist

Test on every representative Android version/device:

1. Unlock the vault and open a record containing a password.
2. Try the hardware screenshot gesture. Verify Android blocks the capture or produces a blank/secure result.
3. Start the built-in screen recorder and navigate through list, details, edit, export, backup and restore dialogs. Verify vault content is not captured.
4. Open Android Recents/Overview. Verify no live readable vault screenshot is shown.
5. Return from Recents and verify the app still works and remains protected.
6. Open the biometric prompt, cancel it, and verify the vault window remains protected.
7. Open Android's file picker for import/export/backup, return to the app, and repeat screenshot/recording checks.
8. Test password reveal and copy actions while protection is active.
9. Confirm the application still has no `INTERNET` permission.

## Release rule

Do not remove or conditionally disable screen protection in production builds. If a future development-only screenshot mode is introduced for UI testing, it must be isolated to debug builds and must never be enabled in release variants.
