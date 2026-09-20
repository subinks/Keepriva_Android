# Keepriva — Effective Remaining Work After v17

The following items remain after the v16 security-hardening base plus v17 nested-category/name work.

## Production identity / availability

- Final Android package ID.
- Google Play name availability check for **Keepriva**.
- Domain availability check.
- Trademark/conflict check.

> `Keepriva` is the selected working app name in v17. It should be treated as production-final only after the above availability checks pass.

## Google Play / signing

- Play-specific signing-certificate adjustment so the Play build validates the Google Play app-signing certificate rather than only the upload/local signing certificate.
- Final signed APK generation.
- Final signed AAB generation.

## Build and testing

- Actual Android build/compile verification using Android Studio or CI.
- Real-device regression testing across supported Android versions/manufacturers.
- Fresh-install `.pvault` backup/restore verification, including nested categories.
- Upgrade verification from v16 to v17, ensuring existing categories become top-level and all vault data remains intact.

## Google Play publication

- Store listing and final assets.
- Privacy policy publication/submission.
- Data Safety and other Play Console declarations.
- Internal/closed testing as required.
- Production submission/review.

## Optional future improvements

- Autofill save-new-credential flow.
- Merge-style `.pvault` restore with duplicate/conflict handling.

## Separate capability variants

The following variants remain separate from the v17 mainline until intentionally rebased/merged:

- v16 + Approach 1 — Secure Inter-App API
- v16 + Approach 2 — Manual single-value sharing
- v16 + Approach 3 — Android Autofill

A later merge can use **v17 as the new base** for each of those three independent variants.
