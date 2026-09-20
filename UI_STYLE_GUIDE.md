# Keepriva — Canonical Android UI Style Guide

## Status

This document defines the **canonical UI appearance for the actual Keepriva Android source**.

It is not a mockup-only palette. The v17 source now centralizes these tokens in:

- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/themes.xml`
- `app/src/main/java/com/example/privatevault/UiStyle.java`

Most Keepriva screens are created programmatically in `MainActivity.java`, so `UiStyle` is the common component-styling layer used to prevent visual drift.

---

## 1. Canonical Palette

| Token | Hex | Usage |
|---|---|---|
| Primary Dark Green | `#0B604B` | status/navigation bars, Keepriva header, strong identity surfaces |
| Primary Green | `#0D765B` | primary action buttons |
| Accent Green | `#24A36D` | Android accent/focus controls |
| Background | `#F7F9F8` | app screen background |
| Surface | `#FFFFFF` | cards, fields, dialog surfaces |
| Soft Green Surface | `#E9F6F0` | secondary buttons and low-emphasis actions |
| Primary Text | `#17201D` | labels and important content |
| Secondary Text | `#66736E` | helper text, descriptions, metadata |
| Outline | `#D7E2DD` | field/card borders |
| Danger | `#C62828` | destructive actions only |
| Warning | `#F9A825` | warning states only |

Legacy resource names such as `vault_primary` remain as aliases for compatibility, but new UI code should use the `keepriva_*` tokens.

---

## 2. Visual Principles

Keepriva should consistently look:

- secure
- calm
- private
- functional
- uncluttered
- offline-first

The app should **not** use a different palette per screen.

Green represents secure/normal actions. Red is reserved for destructive actions such as deletion. Warning amber should be used only when the user is about to expose sensitive data or perform another risky action.

---

## 3. Typography

The current implementation uses Android's system sans-serif family.

Recommended hierarchy:

- Main screen title: 22–26sp, bold
- Entry title/card title: 18sp, bold
- Field/value text: 15–16sp
- Field labels: 14sp, bold
- Supporting/help text: 15sp, secondary color

Do not introduce decorative fonts into security-critical screens.

---

## 4. Inputs

All programmatic `EditText` fields should use `UiStyle.styleInput()`.

Canonical field treatment:

- white surface
- 1dp muted-green/gray outline
- 10dp corner radius
- 48dp minimum height
- 12dp horizontal padding
- primary text color
- secondary hint color

Password fields use the same visual component; only input behavior differs.

---

## 5. Buttons

### Primary actions

Use `UiStyle.stylePrimaryButton()`.

Examples:

- Unlock
- Create encrypted vault
- major confirm actions where the user intentionally proceeds

Appearance:

- primary green fill
- white bold text
- 10dp rounded corners
- minimum 48dp height

### Secondary actions

Use `UiStyle.styleSecondaryButton()`.

Examples:

- Categories
- Import
- Export
- Backup
- Preferences
- Security
- Show/Copy actions

Appearance:

- soft green surface
- dark green text
- subtle outline

### Destructive actions

Use `UiStyle.styleDangerButton()`.

Examples:

- Delete category
- future irreversible destructive operations

Red must not be used as a decorative color.

---

## 6. Cards

Vault-entry cards use `UiStyle.styleCard()`.

Canonical card treatment:

- white background
- muted outline
- 12dp corner radius
- light elevation
- dark primary entry title
- secondary category/path metadata

Nested category paths should use readable breadcrumb text, for example:

```text
Banking > SBI > Credit Cards
```

---

## 7. Home Screen Structure

The actual home screen now uses:

1. Dark-green Keepriva identity header
2. Compact Add button
3. Horizontally scrollable action strip
4. Search field
5. Category filter
6. Vault-entry cards

The horizontally scrolling action strip is intentional. It prevents `Categories / Import / Export / Backup / Preferences / Security / Lock` from overflowing on narrow phones.

---

## 8. Dialogs

Keepriva currently uses Android `AlertDialog` for many flows.

The app theme supplies the green accent so standard dialog controls remain consistent.

Dialog content that uses Keepriva-created fields/buttons should still use `UiStyle` helpers.

Sensitive dialogs remain protected by the existing screenshot-security controls.

---

## 9. Nested Categories

Nested-category UI should keep hierarchy legible using:

- breadcrumb paths where possible
- consistent indentation/tree semantics
- primary text for category name
- secondary text for parent/path/count metadata
- green for safe navigation/move actions
- red only for delete

Configured depth behavior remains independent of visual styling:

- default maximum depth: 3
- hard maximum depth: 5

---

## 10. System Bars

Canonical system-bar colors:

- Status bar: `#0B604B`
- Navigation bar: `#0B604B`
- Light status-bar icons disabled so icons remain visible on the dark green surface

---

## 11. Screens Covered by the Canonical Theme

The shared styling affects the real programmatic UI used for:

- initial vault setup
- master-password unlock
- biometric unlock entry point
- main entries list
- search
- entry details
- add/edit entry
- custom/nested category management
- category move/delete workflows
- Preferences
- Security Settings
- import/export
- backup/restore
- password/clipboard controls

System-owned screens such as Android biometric prompts, document pickers and Android Settings retain the device's system styling by design.

---

## 12. Future Variant Rule

When rebasing optional variants onto Keepriva v17:

- Approach 1 — Secure Inter-App API
- Approach 2 — Manual Single-Value Sharing
- Approach 3 — Android Autofill

copy/retain the same:

```text
colors.xml
themes.xml
UiStyle.java
MainActivity theme-helper changes
```

Do not invent a different visual palette for an optional variant.

---

## 13. Generating Real Screenshots

AI-generated UI previews are **concept/mockups**, not real application screenshots.

A canonical real screenshot must be captured from the compiled Keepriva APK.

Recommended process:

1. Open the project in Android Studio.
2. Build `:app:assembleDebug`.
3. Create an Android emulator, preferably Pixel 7 / API 36.
4. Install/run Keepriva.
5. Create deterministic sample data.
6. Navigate to the target screen.
7. Capture using the emulator screenshot button or:

```bash
adb exec-out screencap -p > keepriva-home.png
```

8. Keep those images as the canonical real-screen references.

Because Keepriva uses `FLAG_SECURE`, normal screenshot capture may intentionally be blocked on sensitive screens. For development-only visual verification, use a dedicated debug-only screenshot/testing mechanism rather than weakening the production release. Never disable production screen security merely to make store screenshots.

---

## 14. Screenshot Baseline Recommendation

After the first successful Android build, capture at least:

1. Setup/Unlock
2. Home / Entries
3. Search
4. Entry Details
5. Add/Edit Entry
6. Categories tree
7. Add Subcategory
8. Move Category
9. Delete Category
10. Preferences / nesting depth
11. Backup & Restore
12. Security Settings
13. Import preview
14. Export options

These real screenshots should replace the generated mockups as the long-term UI reference.

---

## 15. Do Not Change Without Deliberate Design Review

The following should be treated as Keepriva identity constants once real-device review approves them:

- primary palette
- background/surface palette
- button corner radius
- field corner radius
- card styling
- typography hierarchy
- danger/warning color semantics
- home header design

Any future theme change should be applied centrally rather than screen-by-screen.
