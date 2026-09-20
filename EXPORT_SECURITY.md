# Keepriva — Plaintext Export Security (2.0.0-alpha7)

TXT, HTML and PDF are convenience/export formats, not backups. They are readable plaintext files once created.
Use `.pvault` for encrypted backup/restore.

## Safe-by-default export

When exporting one entry, one category, or the entire vault, both sensitive options start OFF:

- **Include passwords** — OFF
- **Include sensitive custom fields** — OFF

A normal export therefore omits passwords and custom fields whose category definition marks them sensitive.
The generated TXT/HTML/PDF also states the export policy used, for example:

`Export policy: passwords omitted; sensitive custom fields omitted.`

## Sensitive export

If either sensitive option is enabled, Keepriva requires the master password again before it will create the document.
A failed or cancelled re-authentication cancels the sensitive export.

After successful re-authentication the user still chooses TXT, HTML or PDF and then selects the destination using Android's document picker.

## Custom field sensitivity

Custom category definitions now persist a list of field labels that are sensitive. The metadata is stored inside the encrypted custom-category payload.

The custom category editor has:

- Field names — one per line
- Sensitive field names — one per line

Sensitive names must match fields in the main field list.

JSON import templates that use `"sensitive": true` for a `target: "custom"` field now preserve that label as sensitive category metadata.

Encrypted `.pvault` backups also preserve this metadata. Older backups without the metadata still restore normally.

## Security properties

- Plaintext export never happens automatically.
- Passwords are excluded by default.
- Sensitive custom values are excluded by default.
- Sensitive exports require fresh master-password verification.
- Export files remain unencrypted after creation; the app warns the user accordingly.
- No Internet permission is required or added.

## Recommended testing

1. Export an entry with default options and verify the password is absent.
2. Mark a custom field sensitive and verify default export omits it.
3. Enable Include passwords; enter a wrong master password and verify no document picker opens.
4. Enable Include passwords; enter the correct master password and verify the password appears in the chosen export.
5. Enable only Include sensitive custom fields and verify password stays omitted.
6. Export TXT, HTML and PDF and verify each contains the export-policy notice.
7. Create a `.pvault` backup, restore it, and verify sensitive custom-field metadata is preserved.
