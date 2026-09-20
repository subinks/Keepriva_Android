# Keepriva Import Template Guide

Keepriva 1.4.0 supports offline bulk import from a JSON template.

## Downloading the template

Unlock the app, tap **Import**, then choose **Download JSON import template**. Android asks where to save `PrivateVault-import-template.json`.

The file is ordinary JSON and can be edited in VS Code, Notepad++, IntelliJ, or another text editor.

> The template file is **not encrypted**. If you fill it with real passwords or other secrets, protect it and securely delete it after a successful import. Keepriva encrypts each imported record before it is written to the app's local SQLite database.

## Structure

```json
{
  "templateVersion": 1,
  "categories": [
    {
      "name": "Insurance",
      "fields": [
        {
          "key": "policyNumber",
          "label": "Policy Number",
          "type": "text",
          "sensitive": true,
          "multipleValues": false,
          "multiline": false,
          "target": "custom"
        },
        {
          "key": "phoneNumbers",
          "label": "Phone Numbers",
          "type": "phone",
          "sensitive": false,
          "multipleValues": true,
          "multiline": false,
          "target": "custom"
        },
        {
          "key": "notes",
          "label": "Notes",
          "type": "textarea",
          "sensitive": true,
          "multipleValues": false,
          "multiline": true,
          "target": "notes"
        }
      ],
      "entries": [
        {
          "values": {
            "policyNumber": "POL-12345",
            "phoneNumbers": ["+91 9000000001", "+91 9000000002"],
            "notes": "First line\nSecond line"
          }
        }
      ]
    }
  ]
}
```

## Field properties

- `key`: unique machine-readable key within that category.
- `label`: label shown/stored for a custom field.
- `type`: one of `text`, `textarea`, `password`, `email`, `phone`, `url`, `number`, `date`.
- `sensitive`: marks the field as sensitive in the template metadata. All imported vault values are encrypted regardless of this flag, so even fields marked `false` receive encryption at rest.
- `multipleValues`: when `true`, provide a JSON array. The app preserves the values as newline-separated values inside the encrypted entry.
- `multiline`: indicates that the value is naturally multiline. `textarea` implies multiline by default.
- `target`: maps the imported field into a standard Keepriva field or a custom field.

Supported `target` values are:

- `title`
- `username`
- `password`
- `website`
- `websiteUrl`
- `phone1`
- `phone2`
- `phone3`
- `notes`
- `custom`

Use `custom` for user-defined fields. For best editability, use a custom category when importing custom fields.

## Import process

1. Unlock Keepriva.
2. Tap **Import**.
3. Choose **Import filled JSON template**.
4. Select the JSON file using Android's document picker.
5. Keepriva validates the template and shows a preview with entry count, new category count, sensitive fields, multiple-value fields, warnings, and errors.
6. If validation succeeds, tap **Import**.
7. Categories and entries are committed in one SQLite transaction. If an import write fails, the transaction is rolled back.
8. Every entry payload is encrypted using the currently unlocked vault key before storage.

Duplicate titles are allowed intentionally because multiple accounts can belong to the same website or service.


## Nested categories (v17)

Each category object may optionally contain:

```json
"parentCategory": "Banking"
```

Use an empty string for a top-level custom category. The parent may be a built-in category, an existing custom category, or another custom category in the same import. Import preview rejects missing parents, cycles, and hierarchy depth beyond the configured preference.
