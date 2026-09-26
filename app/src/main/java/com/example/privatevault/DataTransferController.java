package com.example.privatevault;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Session-scoped owner of legacy import/export and encrypted-backup dialogs. */
final class DataTransferController implements VaultController {
    interface Gateway {
        boolean isSessionActive();
        List<VaultItem> itemsForExport(String category);
        VaultItem findItem(long id);
        List<CustomCategory> categoryDefinitions();
        String[] builtInCategoryNames();
        boolean verifyExportPassword(String password);
        byte[] createEncryptedBackup(char[] password) throws Exception;
        int schemaVersion();
        String validateCategoryHierarchy(List<CustomCategory> categories);
        void restoreBackup(BackupManager.RestoredBackup restored) throws Exception;
        void importBatch(ImportManager.ParsedImport parsed) throws Exception;
        void showMessage(String message);
    }

    private final Activity activity;
    private final VaultViewFactory views;
    private final DialogRegistry dialogs;
    private final Gateway gateway;
    private final DataTransferActions actions;
    private final TransferPayloadStore payloads = new TransferPayloadStore();
    private boolean closed;

    DataTransferController(Activity activity, VaultViewFactory views, DialogRegistry dialogs,
                           Gateway gateway, DataTransferActions actions) {
        this.activity = activity;
        this.views = views;
        this.dialogs = dialogs;
        this.gateway = gateway;
        this.actions = actions;
    }

    private boolean active() { return !closed && gateway.isSessionActive(); }
    void clearSessionState() { payloads.clearAll(); }
    void cancelPending(TransferOperation operation) { payloads.clear(operation); }
    @Override public void close() { if (closed) return; closed = true; payloads.close(); }

    void exportCategory(String category) {
        if (!active()) return;
        List<VaultItem> selected = gateway.itemsForExport(category);
        if (selected.isEmpty()) { gateway.showMessage("There are no entries to export in this category."); return; }
        showExportFormatChooser(selected, "All".equals(category) ? "Keepriva" : category);
    }

    void exportEntry(long itemId) {
        if (!active()) return;
        VaultItem item = gateway.findItem(itemId);
        if (item != null) showExportFormatChooser(Collections.singletonList(item), item.title);
    }

    private static String safe(String text) { return text == null ? "" : text; }
    private int dp(int value) { return views.dp(value); }
    private LinearLayout baseVertical(int gap) { return views.verticalContainer(gap); }
    private TextView subtitle(String text) { return views.subtitle(text); }
    private EditText passwordField(String hint) { return views.passwordField(hint); }
    private Button button(String title) { return views.secondaryButton(title); }
    private Button primaryButton(String title) { return views.primaryButton(title); }
    private LinearLayout.LayoutParams matchWidth() { return views.matchWidth(); }
    private AlertDialog showDialog(AlertDialog dialog) { return dialogs.show(dialog); }

    private void showExportFormatChooser(List<VaultItem> items, String suggestedName) {
        if (!active()) return;
        LinearLayout box = baseVertical(8);
        TextView warning = subtitle("TXT, HTML, PDF and JSON exports are readable plaintext files. JSON is structured and can be imported back into Keepriva. By default Keepriva omits passwords and custom fields marked sensitive.");
        CheckBox includePasswords = new CheckBox(activity);
        includePasswords.setText("Include passwords");
        includePasswords.setChecked(false);
        CheckBox includeSensitive = new CheckBox(activity);
        includeSensitive.setText("Include sensitive custom fields");
        includeSensitive.setChecked(false);
        box.addView(warning); box.addView(includePasswords); box.addView(includeSensitive);

        showDialog(new AlertDialog.Builder(activity)
                .setTitle("Export security")
                .setView(box)
                .setPositiveButton("Continue", (d, w) -> {
                    boolean sensitiveExport = includePasswords.isChecked() || includeSensitive.isChecked();
                    ExportManager.Options options = new ExportManager.Options(
                            includePasswords.isChecked(), includeSensitive.isChecked(), sensitiveCustomFieldsByCategory());
                    if (sensitiveExport) {
                        requireMasterPasswordForSensitiveExport(items, suggestedName, options);
                    } else {
                        chooseExportFormat(items, suggestedName, options);
                    }
                })
                .setNegativeButton("Cancel", null)
                .create());
    }

    private Map<String, java.util.Set<String>> sensitiveCustomFieldsByCategory() {
        Map<String, java.util.Set<String>> result = new LinkedHashMap<>();
        for (CustomCategory c : gateway.categoryDefinitions()) {
            result.put(safe(c.name), new java.util.HashSet<>(c.sensitiveFields));
        }
        return result;
    }

    private void requireMasterPasswordForSensitiveExport(List<VaultItem> items, String suggestedName,
                                                         ExportManager.Options options) {
        EditText password = passwordField("Master password");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Re-authentication required")
                .setMessage("This export can contain passwords or fields you marked sensitive. Enter the master password again before creating the plaintext file.")
                .setView(password)
                .setPositiveButton("Authenticate", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            char[] chars = password.getText().toString().toCharArray();
            try {
                if (!active() || !gateway.verifyExportPassword(new String(chars)))
                    throw new IllegalArgumentException("Authentication failed");
                dialog.dismiss();
                chooseExportFormat(items, suggestedName, options);
            } catch (Exception e) {
                password.setError("Incorrect master password");
                password.requestFocus();
            } finally {
                java.util.Arrays.fill(chars, '\0');
                password.setText("");
            }
        }));

        ScreenSecurityManager.protect(dialog);
        showDialog(dialog);
    }

    private void chooseExportFormat(List<VaultItem> items, String suggestedName, ExportManager.Options options) {
        if (!active()) return;
        LinearLayout box = baseVertical(8);

        TextView warning = subtitle(
                options.includePasswords || options.includeSensitiveCustomFields
                        ? "Sensitive values are enabled for this plaintext export. Store the file securely and delete it when no longer needed."
                        : "Safe export: passwords and sensitive custom fields will be omitted.");
        box.addView(warning);

        Button json = primaryButton("Keepriva JSON (.json) — re-importable");
        json.setContentDescription("Export Keepriva JSON");
        Button txt = button("Formatted text (.txt)");
        Button html = button("HTML page (.html)");
        Button pdf = button("PDF document (.pdf)");

        box.addView(json, matchWidth());
        box.addView(txt, matchWidth());
        box.addView(html, matchWidth());
        box.addView(pdf, matchWidth());

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Choose export format")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .create();

        json.setOnClickListener(v -> {
            dialog.dismiss();
            prepareExport(items, suggestedName, 3, options);
        });
        txt.setOnClickListener(v -> {
            dialog.dismiss();
            prepareExport(items, suggestedName, 0, options);
        });
        html.setOnClickListener(v -> {
            dialog.dismiss();
            prepareExport(items, suggestedName, 1, options);
        });
        pdf.setOnClickListener(v -> {
            dialog.dismiss();
            prepareExport(items, suggestedName, 2, options);
        });

        ScreenSecurityManager.protect(dialog);
        showDialog(dialog);
    }

    private void prepareExport(List<VaultItem> items, String suggestedName, int format, ExportManager.Options options) {
        if (!active()) return;
        try {
            String base = sanitizeFileName(suggestedName);
            String filename;
            String mimeType;

            if (format == 0) {
                payloads.put(TransferOperation.ENTRY_EXPORT, ExportManager.toText(items, options).getBytes(StandardCharsets.UTF_8));
                mimeType = "text/plain";
                filename = base + ".txt";
            } else if (format == 1) {
                payloads.put(TransferOperation.ENTRY_EXPORT, ExportManager.toHtml(items, options).getBytes(StandardCharsets.UTF_8));
                mimeType = "text/html";
                filename = base + ".html";
            } else if (format == 2) {
                payloads.put(TransferOperation.ENTRY_EXPORT, ExportManager.toPdf(items, options));
                mimeType = "application/pdf";
                filename = base + ".pdf";
            } else {
                payloads.put(TransferOperation.ENTRY_EXPORT, ExportManager
                        .toImportCompatibleJson(items, options, gateway.categoryDefinitions())
                        .getBytes(StandardCharsets.UTF_8));
                mimeType = "application/json";
                filename = base + ".json";
            }

            if (!active()) { payloads.clear(TransferOperation.ENTRY_EXPORT); return; }
            actions.onPickerRequested(TransferOperation.ENTRY_EXPORT, mimeType, filename);
        } catch (Exception e) {
            payloads.clear(TransferOperation.ENTRY_EXPORT);
            gateway.showMessage("Could not prepare export: " + e.getMessage());
        }
    }

    void showBackupRestoreDialog() {
        if (!active()) return;
        LinearLayout box = baseVertical(8);
        box.addView(subtitle("Backups use a separate password and are portable to another phone. Keep the backup password safe: it is not recoverable by the app."));

        Button create = button("Create encrypted .pvault backup");
        Button restore = button("Restore encrypted .pvault backup");
        box.addView(create, matchWidth());
        box.addView(restore, matchWidth());

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Encrypted backup & restore")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .create();

        create.setOnClickListener(v -> {
            dialog.dismiss();
            promptCreateBackupPassword();
        });
        restore.setOnClickListener(v -> {
            dialog.dismiss();
            chooseBackupForRestore();
        });

        ScreenSecurityManager.protect(dialog);
        showDialog(dialog);
    }

    private void promptCreateBackupPassword() {
        LinearLayout box = baseVertical(8);
        box.addView(subtitle("Use a password different from your phone unlock. The .pvault file contains an authenticated encrypted snapshot of entries and custom categories."));
        EditText pass = passwordField("Backup password (10+ characters)");
        EditText confirm = passwordField("Confirm backup password");
        box.addView(pass);
        box.addView(confirm);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Create encrypted backup")
                .setView(box)
                .setPositiveButton("Continue", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            char[] p1 = pass.getText().toString().toCharArray();
            char[] p2 = confirm.getText().toString().toCharArray();
            try {
                if (p1.length < 10) {
                    pass.setError("Use at least 10 characters");
                    pass.requestFocus();
                    return;
                }
                if (!java.util.Arrays.equals(p1, p2)) {
                    confirm.setError("Passwords do not match");
                    confirm.requestFocus();
                    return;
                }
                dialog.dismiss();
                createEncryptedBackup(p1);
            } finally {
                java.util.Arrays.fill(p1, '\0');
                java.util.Arrays.fill(p2, '\0');
                pass.setText("");
                confirm.setText("");
            }
        }));

        ScreenSecurityManager.protect(dialog);
        showDialog(dialog);
    }

    private void createEncryptedBackup(char[] backupPassword) {
        try {
            if (!active()) return;
            // The gateway reloads the authoritative snapshot using the session key.
            payloads.put(TransferOperation.BACKUP_EXPORT, gateway.createEncryptedBackup(backupPassword));
            if (!active()) { payloads.clear(TransferOperation.BACKUP_EXPORT); return; }
            actions.onPickerRequested(TransferOperation.BACKUP_EXPORT,
                    "application/octet-stream", "Keepriva-backup.pvault");
        } catch (Exception e) {
            payloads.clear(TransferOperation.BACKUP_EXPORT);
            gateway.showMessage("Could not create backup: " + e.getMessage());
        }
    }

    private void chooseBackupForRestore() {
        showDialog(new AlertDialog.Builder(activity)
                .setTitle("Restore encrypted backup")
                .setMessage("Restore replaces the current vault entries and custom categories only after the selected backup has been decrypted and validated. Your current master password and biometric settings are kept.")
                .setPositiveButton("Choose .pvault file", (d, w) -> {
                    if (active()) actions.onPickerRequested(TransferOperation.BACKUP_RESTORE, "*/*", null);
                })
                .setNegativeButton("Cancel", null)
                .create());
    }

    private void promptRestorePassword(byte[] backupBytes) {
        EditText pass = passwordField("Backup password");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Unlock backup")
                .setMessage("The backup is authenticated before any vault data is changed.")
                .setView(pass)
                .setPositiveButton("Validate", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
                char[] password = pass.getText().toString().toCharArray();
                try {
                    if (!active()) return;
                    BackupManager.RestoredBackup restored = BackupManager.decryptAndValidate(backupBytes, password);
                    dialog.dismiss();
                    java.util.Arrays.fill(backupBytes, (byte) 0);
                    showRestorePreview(restored);
                } catch (Exception e) {
                    pass.setError("Incorrect password or damaged/unsupported backup");
                    pass.requestFocus();
                } finally {
                    java.util.Arrays.fill(password, '\0');
                    pass.setText("");
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(btn -> {
                java.util.Arrays.fill(backupBytes, (byte) 0);
                dialog.dismiss();
            });
        });
        dialog.setOnCancelListener(d -> Arrays.fill(backupBytes, (byte) 0));
        dialog.setOnDismissListener(d -> Arrays.fill(backupBytes, (byte) 0));

        ScreenSecurityManager.protect(dialog);
        showDialog(dialog);
    }

    private void showRestorePreview(BackupManager.RestoredBackup restored) {
        if (!active()) return;
        if (restored.sourceSchemaVersion > gateway.schemaVersion()) {
            showDialog(new AlertDialog.Builder(activity)
                    .setTitle("Backup is newer than this app")
                    .setMessage("This backup was created from vault schema " + restored.sourceSchemaVersion
                            + ", but this app supports schema " + gateway.schemaVersion()
                            + ". Update Keepriva before restoring it.")
                    .setPositiveButton("Close", null).create());
            return;
        }
        String hierarchyError = gateway.validateCategoryHierarchy(restored.categories);
        if (hierarchyError != null) {
            showDialog(new AlertDialog.Builder(activity)
                    .setTitle("Backup category hierarchy cannot be restored")
                    .setMessage(hierarchyError + "\n\nOpen Preferences and increase the category nesting depth if appropriate, then retry restore.")
                    .setPositiveButton("Close", null).create());
            return;
        }
        String message = "Backup entries: " + restored.items.size()
                + "\nCustom categories: " + restored.categories.size()
                + "\nSource schema version: " + restored.sourceSchemaVersion
                + "\n\nRestoring will REPLACE the current entries and custom categories."
                + "\n\nThe operation is transactional: if a write fails, the existing vault remains intact.";
        showDialog(new AlertDialog.Builder(activity)
                .setTitle("Restore preview")
                .setMessage(message)
                .setPositiveButton("Replace current vault", (d,w) -> {
                    try {
                        if (!active()) return;
                        gateway.restoreBackup(restored);
                        actions.onTransferCompleted();
                        gateway.showMessage("Encrypted backup restored successfully.");
                    } catch (Exception e) {
                        gateway.showMessage("Restore failed; current vault was not partially replaced: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null)
                .create());
    }

    void showImportDialog() {
        if (!active()) return;
        LinearLayout box = baseVertical(10);

        box.addView(UiStyle.sectionTitle(activity, "Bulk import from JSON"));
        box.addView(UiStyle.sectionCaption(
                activity,
                "Import is for adding many credentials at once. Keepriva first creates a blank JSON "
                        + "template that describes categories, fields and entries. Fill it on your computer, "
                        + "then import the completed JSON back into Keepriva."
        ));

        TextView steps = subtitle(
                "1. Save the blank JSON template.\n"
                        + "2. Fill its entry values in a text editor.\n"
                        + "3. Import the completed JSON.\n\n"
                        + "The JSON file is plaintext and NOT encrypted. Keepriva validates it, previews "
                        + "the changes and encrypts each imported record before local database storage."
        );
        steps.setPadding(dp(12), dp(10), dp(12), dp(12));
        steps.setBackground(UiStyle.outlined(
                activity, R.color.keepriva_surface_soft, R.color.keepriva_outline, 12));
        box.addView(steps, matchWidth());

        Button download = button("Step 1 — Save JSON import template");
        Button importFile = primaryButton("Step 2 — Import completed JSON template");

        download.setContentDescription("Download JSON import template");
        importFile.setContentDescription("Import completed JSON template");

        LinearLayout.LayoutParams first = matchWidth();
        first.topMargin = dp(10);
        box.addView(download, first);

        LinearLayout.LayoutParams second = matchWidth();
        second.topMargin = dp(8);
        box.addView(importFile, second);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Import data")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .create();

        download.setOnClickListener(v -> {
            dialog.dismiss();
            downloadImportTemplate();
        });
        importFile.setOnClickListener(v -> {
            dialog.dismiss();
            chooseImportTemplate();
        });

        ScreenSecurityManager.protect(dialog);
        showDialog(dialog);
    }

    private void downloadImportTemplate() {
        try {
            payloads.put(TransferOperation.TEMPLATE_EXPORT, ImportManager.buildTemplate().getBytes(StandardCharsets.UTF_8));
            if (!active()) { payloads.clear(TransferOperation.TEMPLATE_EXPORT); return; }
            actions.onPickerRequested(TransferOperation.TEMPLATE_EXPORT, "application/json", "Keepriva-import-template.json");
        } catch (Exception e) { gateway.showMessage("Could not create import template: " + e.getMessage()); }
    }

    private void chooseImportTemplate() {
        if (active()) actions.onPickerRequested(TransferOperation.JSON_IMPORT, "application/json", null);
    }

    private byte[] readBytes(Uri uri) throws Exception {
        try (InputStream in = activity.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("Cannot open selected file");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private String readUtf8(Uri uri) throws Exception {
        byte[] bytes = readBytes(uri);
        try { return new String(bytes, StandardCharsets.UTF_8); }
        finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }

    private void previewImport(String json) {
        if (!active()) return;
        ImportManager.ParsedImport parsed = ImportManager.parse(json, gateway.categoryDefinitions(), gateway.builtInCategoryNames());
        StringBuilder message = new StringBuilder();
        message.append("Entries ready: ").append(parsed.items.size())
                .append("\nNew custom categories: ").append(parsed.categoriesToCreate.size())
                .append("\nSensitive field definitions: ").append(parsed.sensitiveFieldCount)
                .append("\nMultiple-value field definitions: ").append(parsed.multipleValueFieldCount);
        if (!parsed.warnings.isEmpty()) {
            message.append("\n\nWarnings:");
            int limit = Math.min(parsed.warnings.size(), 8);
            for (int i = 0; i < limit; i++) message.append("\n• ").append(parsed.warnings.get(i));
            if (parsed.warnings.size() > limit) message.append("\n• … and ").append(parsed.warnings.size() - limit).append(" more");
        }
        List<CustomCategory> hierarchyPreview = new ArrayList<>(gateway.categoryDefinitions());
        hierarchyPreview.addAll(parsed.categoriesToCreate);
        String hierarchyError = gateway.validateCategoryHierarchy(hierarchyPreview);
        if (hierarchyError != null) parsed.errors.add(hierarchyError);

        if (!parsed.errors.isEmpty()) {
            message.append("\n\nErrors:");
            int limit = Math.min(parsed.errors.size(), 8);
            for (int i = 0; i < limit; i++) message.append("\n• ").append(parsed.errors.get(i));
            if (parsed.errors.size() > limit) message.append("\n• … and ").append(parsed.errors.size() - limit).append(" more");
            showDialog(new AlertDialog.Builder(activity).setTitle("Import validation failed")
                    .setMessage(message.toString()).setPositiveButton("Close", null).create());
            return;
        }
        if (parsed.items.isEmpty()) {
            showDialog(new AlertDialog.Builder(activity).setTitle("Nothing to import")
                    .setMessage(message.toString()).setPositiveButton("Close", null).create());
            return;
        }
        message.append("\n\nOn import, all entry fields—including fields marked sensitive—are encrypted before being written to the local vault database.");
        showDialog(new AlertDialog.Builder(activity).setTitle("Import preview")
                .setMessage(message.toString())
                .setPositiveButton("Import", (d, w) -> commitImport(parsed))
                .setNegativeButton("Cancel", null).create());
    }

    private void commitImport(ImportManager.ParsedImport parsed) {
        try {
            if (!active()) return;
            gateway.importBatch(parsed);
            actions.onTransferCompleted();
            gateway.showMessage("Imported " + parsed.items.size() + (parsed.items.size() == 1 ? " entry." : " entries."));
        } catch (Exception e) {
            gateway.showMessage("Import failed: " + e.getMessage());
        }
    }

    void handlePickerResult(TransferOperation operation, Uri uri) {
        if (operation == null) return;
        if (!active() || uri == null) { payloads.clear(operation); return; }
        try {
            switch (operation) {
                case ENTRY_EXPORT:
                    writePending(uri, operation, "Export saved.", "Export failed: ");
                    break;
                case TEMPLATE_EXPORT:
                    writePending(uri, operation, "Import template saved.", "Template save failed: ");
                    break;
                case BACKUP_EXPORT:
                    writePending(uri, operation, "Encrypted .pvault backup saved.", "Backup save failed: ");
                    break;
                case JSON_IMPORT:
                    try { previewImport(readUtf8(uri)); }
                    catch (Exception error) { gateway.showMessage("Could not read import file: " + error.getMessage()); }
                    break;
                case BACKUP_RESTORE:
                    try { promptRestorePassword(readBytes(uri)); }
                    catch (Exception error) { gateway.showMessage("Could not read backup file: " + error.getMessage()); }
                    break;
            }
        } finally {
            payloads.clear(operation);
        }
    }

    private void writePending(Uri uri, TransferOperation operation, String success, String failure) {
        try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("Cannot open selected file");
            byte[] bytes = payloads.get(operation);
            if (bytes == null) throw new IllegalStateException("Transfer data is unavailable");
            out.write(bytes);
            out.flush();
            gateway.showMessage(success);
        } catch (Exception error) {
            gateway.showMessage(failure + error.getMessage());
        } finally {
            payloads.clear(operation);
        }
    }

    private String sanitizeFileName(String value) {
        String s = safe(value).trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return s.isEmpty() ? "Keepriva-export" : s;
    }

}
