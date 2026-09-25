package com.example.privatevault;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.view.ViewParent;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.Objects;

/** Owns security/preferences dialogs while cryptographic operations remain in the host. */
final class SecuritySettingsController implements VaultController {
    private final Activity activity;
    private final VaultViewFactory views;
    private final DialogRegistry dialogs;
    private final VaultSecurityPreferences preferences;
    private final Gateway gateway;
    private final SecuritySettingsActions actions;
    private boolean closed;

    SecuritySettingsController(
            Activity activity,
            VaultViewFactory views,
            DialogRegistry dialogs,
            VaultSecurityPreferences preferences,
            Gateway gateway,
            SecuritySettingsActions actions) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.views = Objects.requireNonNull(views, "views");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    void showPreferences() {
        if (closed) return;
        LinearLayout root = views.verticalContainer(8);
        root.addView(views.subtitle("Category nesting controls how many levels of categories and sub-categories Keepriva allows. Existing data is never flattened automatically."));

        CheckBox enableEdit = new CheckBox(activity);
        enableEdit.setText("Enable editing category nesting depth");
        enableEdit.setChecked(false);
        root.addView(enableEdit);

        EditText depth = views.field("Maximum category depth (1-5)",
                String.valueOf(preferences.maxCategoryDepth()));
        depth.setInputType(InputType.TYPE_CLASS_NUMBER);
        depth.setEnabled(false);
        root.addView(depth);
        root.addView(views.subtitle("Default: 3. Hard maximum: 5. Root categories count as level 1."));
        enableEdit.setOnCheckedChangeListener((buttonView, checked) -> depth.setEnabled(checked));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Preferences")
                .setView(root)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        ScreenSecurityManager.protect(dialog);
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            if (!enableEdit.isChecked()) {
                dialog.dismiss();
                return;
            }
            int requested;
            try {
                requested = Integer.parseInt(depth.getText().toString().trim());
            } catch (Exception error) {
                depth.setError("Enter a number from 1 to 5");
                return;
            }
            if (requested < 1 || requested > VaultSecurityPreferences.HARD_MAX_CATEGORY_DEPTH) {
                depth.setError("Allowed range is 1 to " + VaultSecurityPreferences.HARD_MAX_CATEGORY_DEPTH);
                return;
            }
            int existing = gateway.deepestCategoryDepth();
            if (requested < existing) {
                depth.setError("Current hierarchy already uses " + existing
                        + " levels. Move categories upward before reducing this preference.");
                return;
            }
            preferences.setMaxCategoryDepth(requested);
            dialog.dismiss();
            gateway.showMessage("Maximum category depth set to " + requested + ".");
        }));
        dialogs.show(dialog);
    }

    void requestMasterPasswordReauth(String purpose, Runnable onSuccess) {
        if (closed) return;
        EditText pass = views.passwordField("Master password");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(purpose)
                .setMessage("Enter your master password to continue.")
                .setView(pass)
                .setPositiveButton("Continue", null)
                .setNegativeButton("Cancel", null)
                .create();
        ScreenSecurityManager.protect(dialog);
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            String password = pass.getText().toString();
            boolean verified = gateway.verifyMasterPassword(password);
            pass.setText("");
            if (verified) {
                dialog.dismiss();
                if (!closed) onSuccess.run();
            } else {
                pass.requestFocus();
                gateway.showMessage("Incorrect master password.");
            }
        }));
        dialogs.show(dialog);
    }

    void showSecuritySettings() {
        if (closed || !gateway.isSessionUnlocked()) return;
        LinearLayout root = views.verticalContainer(8);
        TextView status = views.subtitle("Master password: configured\nBiometric unlock: "
                + (gateway.isBiometricConfigured() ? "enabled" : "disabled")
                + "\nAuto-lock: " + autoLockLabel(preferences.autoLockMs())
                + "\nLock on screen off: " + (preferences.lockOnScreenOff() ? "enabled" : "disabled")
                + "\nClipboard timeout: " + clipboardTimeoutLabel(preferences.clipboardTimeoutMs()));
        root.addView(status);

        android.widget.Button changePassword = views.secondaryButton("Change master password");
        changePassword.setOnClickListener(v -> {
            dismissParentDialog(v);
            showChangeMasterPasswordDialog();
        });
        root.addView(changePassword);

        android.widget.Button autoLock = views.secondaryButton(
                "Auto-lock: " + autoLockLabel(preferences.autoLockMs()));
        autoLock.setOnClickListener(v -> {
            dismissParentDialog(v);
            showAutoLockSettings();
        });
        root.addView(autoLock);

        CheckBox screenOff = new CheckBox(activity);
        screenOff.setText("Lock when screen turns off");
        screenOff.setChecked(preferences.lockOnScreenOff());
        screenOff.setOnCheckedChangeListener((button, checked) -> preferences.setLockOnScreenOff(checked));
        root.addView(screenOff);

        android.widget.Button biometric = views.secondaryButton(
                gateway.isBiometricConfigured()
                        ? "Biometric unlock: Enabled"
                        : "Biometric unlock: Disabled");
        biometric.setOnClickListener(v -> {
            dismissParentDialog(v);
            showBiometricSettings();
        });
        root.addView(biometric);

        android.widget.Button clipboard = views.secondaryButton(
                "Clipboard timeout: " + clipboardTimeoutLabel(preferences.clipboardTimeoutMs()));
        clipboard.setOnClickListener(v -> {
            dismissParentDialog(v);
            showClipboardSettings();
        });
        root.addView(clipboard);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Security settings")
                .setView(root)
                .setPositiveButton("Done", (d, w) -> actions.onSecuritySettingsClosed())
                .create();
        root.setTag(dialog);
        ScreenSecurityManager.protect(dialog);
        dialogs.show(dialog);
    }

    private void showBiometricSettings() {
        if (closed || !gateway.isSessionUnlocked()) return;
        if (gateway.isBiometricConfigured()) {
            dialogs.show(new AlertDialog.Builder(activity)
                    .setTitle("Biometric unlock")
                    .setMessage("Biometric unlock is enabled on this device. The master password remains the recovery method.")
                    .setPositiveButton("Disable", (d, w) -> gateway.disableBiometricUnlock())
                    .setNegativeButton("Cancel", null)
                    .create());
        } else {
            dialogs.show(new AlertDialog.Builder(activity)
                    .setTitle("Enable biometric unlock?")
                    .setMessage("Your fingerprint or strong face authentication will authorize Android Keystore to unwrap the vault key. Your master password remains available as fallback and recovery.")
                    .setPositiveButton("Enable", (d, w) -> gateway.enableBiometricUnlock())
                    .setNegativeButton("Cancel", null)
                    .create());
        }
    }

    private void showAutoLockSettings() {
        final String[] labels = {"Immediately", "30 seconds", "1 minute", "5 minutes"};
        final long[] values = {0L, 30_000L, 60_000L, 300_000L};
        int selected = selectedIndex(values, preferences.autoLockMs(), 1);
        LinearLayout box = views.verticalContainer(6);
        box.addView(views.subtitle("Keepriva locks after it has been left in the background for this long."));
        RadioGroup group = optionGroup(labels, selected);
        box.addView(group);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Auto-lock")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            int which = selectedTag(group);
            if (which < 0) return;
            preferences.setAutoLockMs(values[which]);
            dialog.dismiss();
            gateway.showMessage("Auto-lock: " + labels[which]);
        }));
        ScreenSecurityManager.protect(dialog);
        dialogs.show(dialog);
    }

    private void showChangeMasterPasswordDialog() {
        if (closed || !gateway.isSessionUnlocked()) return;
        LinearLayout fields = views.verticalContainer(6);
        EditText current = views.passwordField("Current master password");
        EditText next = views.passwordField("New master password (12+ characters)");
        EditText confirm = views.passwordField("Confirm new master password");
        fields.addView(current);
        fields.addView(next);
        fields.addView(confirm);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Change master password")
                .setMessage("Only the vault key wrapper changes. Your encrypted entries do not need to be rewritten.")
                .setView(fields)
                .setPositiveButton("Change", null)
                .setNegativeButton("Cancel", null)
                .create();
        ScreenSecurityManager.protect(dialog);
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            String currentText = current.getText().toString();
            String newText = next.getText().toString();
            String confirmText = confirm.getText().toString();
            String strengthError = MasterPasswordPolicy.validate(newText);
            if (strengthError != null) {
                gateway.showMessage(strengthError);
                return;
            }
            if (!newText.equals(confirmText)) {
                gateway.showMessage("New passwords do not match.");
                return;
            }
            if (newText.equals(currentText)) {
                gateway.showMessage("Choose a different master password.");
                return;
            }
            boolean changed = gateway.changeMasterPassword(currentText, newText);
            if (changed) {
                current.setText("");
                next.setText("");
                confirm.setText("");
                dialog.dismiss();
                gateway.showMessage("Master password changed. Biometric unlock remains available on this device.");
                actions.onSecuritySettingsClosed();
            } else {
                current.setText("");
                gateway.showMessage("Current master password is incorrect or the password change could not be saved.");
            }
        }));
        dialogs.show(dialog);
    }

    private void showClipboardSettings() {
        final String[] labels = {"15 seconds", "30 seconds (recommended)", "60 seconds", "Never auto-clear"};
        final long[] values = {
                ClipboardSecurityManager.FIFTEEN_SECONDS,
                ClipboardSecurityManager.THIRTY_SECONDS,
                ClipboardSecurityManager.SIXTY_SECONDS,
                ClipboardSecurityManager.NEVER_CLEAR
        };
        int selected = selectedIndex(values, preferences.clipboardTimeoutMs(), 1);
        LinearLayout box = views.verticalContainer(6);
        box.addView(views.subtitle("Copied passwords are marked sensitive. Keepriva can also clear a copied password after a short delay. 'Never' is less secure."));
        RadioGroup group = optionGroup(labels, selected);
        box.addView(group);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Password clipboard timeout")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            int which = selectedTag(group);
            if (which < 0) return;
            preferences.setClipboardTimeoutMs(values[which]);
            dialog.dismiss();
            gateway.showMessage("Clipboard timeout: " + labels[which]);
        }));
        ScreenSecurityManager.protect(dialog);
        dialogs.show(dialog);
    }

    private RadioGroup optionGroup(String[] labels, int selected) {
        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(RadioGroup.VERTICAL);
        for (int index = 0; index < labels.length; index++) {
            RadioButton button = new RadioButton(activity);
            button.setId(View.generateViewId());
            button.setText(labels[index]);
            button.setTag(index);
            group.addView(button);
            if (index == selected) group.check(button.getId());
        }
        return group;
    }

    private int selectedTag(RadioGroup group) {
        View checked = group.findViewById(group.getCheckedRadioButtonId());
        if (checked == null || checked.getTag() == null) return -1;
        return (Integer) checked.getTag();
    }

    private int selectedIndex(long[] values, long current, int fallback) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == current) return index;
        }
        return fallback;
    }

    private String autoLockLabel(long timeout) {
        if (timeout == 0L) return "Immediately";
        if (timeout == 30_000L) return "30 seconds";
        if (timeout == 60_000L) return "1 minute";
        if (timeout == 300_000L) return "5 minutes";
        return (timeout / 1000L) + " seconds";
    }

    private String clipboardTimeoutLabel(long timeout) {
        if (timeout == ClipboardSecurityManager.NEVER_CLEAR) return "Never";
        return (timeout / 1000L) + "s";
    }

    private void dismissParentDialog(View view) {
        AlertDialog dialog = findShowingDialogForView(view);
        if (dialog != null) dialog.dismiss();
    }

    private AlertDialog findShowingDialogForView(View view) {
        View current = view;
        while (current != null) {
            Object tag = current.getTag();
            if (tag instanceof AlertDialog && ((AlertDialog) tag).isShowing()) {
                return (AlertDialog) tag;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    @Override
    public void close() {
        closed = true;
    }

    interface Gateway {
        boolean isSessionUnlocked();
        boolean isBiometricConfigured();
        boolean verifyMasterPassword(String password);
        boolean changeMasterPassword(String currentPassword, String newPassword);
        void enableBiometricUnlock();
        void disableBiometricUnlock();
        int deepestCategoryDepth();
        void showMessage(String message);
    }
}
