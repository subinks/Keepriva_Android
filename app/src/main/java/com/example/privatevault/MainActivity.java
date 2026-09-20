package com.example.privatevault;

import android.app.Activity;
import android.app.AlertDialog;
import android.hardware.biometrics.BiometricPrompt;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {
    private static final String PREFS = "vault_config";
    // v1.x legacy configuration keys. Kept for one-time in-place migration.
    private static final String PREF_LEGACY_SALT = "salt";
    private static final String PREF_LEGACY_VERIFIER = "verifier";

    // v2.x key hierarchy: master password -> KEK -> wrapped independent vault data key.
    private static final String PREF_CRYPTO_VERSION = "crypto_version";
    private static final int CRYPTO_VERSION_2 = 2;
    private static final String PREF_MASTER_SALT = "master_salt_v2";
    private static final String PREF_WRAPPED_VAULT_KEY = "wrapped_vault_key_v2";
    private static final String PREF_VAULT_VERIFIER = "vault_verifier_v2";
    private static final String VERIFIER_TEXT = "PRIVATE_VAULT_VERIFIER_V2";
    private static final String LEGACY_VERIFIER_TEXT = "PRIVATE_VAULT_VERIFIER_V1";
    private static final String PREF_DEVICE_KEY_STATUS = "device_key_status_v1";
    private static final String DEVICE_KEY_READY = "auth_bound_key_ready";
    private static final String PREF_BIOMETRIC_WRAPPED_VAULT_KEY = "biometric_wrapped_vault_key_v1";
    private static final String PREF_BIOMETRIC_IV = "biometric_wrapped_vault_key_iv_v1";
    private static final String PREF_CLIPBOARD_TIMEOUT_MS = "clipboard_timeout_ms_v1";
    private static final long DEFAULT_CLIPBOARD_TIMEOUT_MS = ClipboardSecurityManager.THIRTY_SECONDS;
    private static final String PREF_AUTO_LOCK_MS = "auto_lock_ms_v1";
    private static final String PREF_LOCK_ON_SCREEN_OFF = "lock_on_screen_off_v1";
    private static final String PREF_MAX_CATEGORY_DEPTH = "max_category_depth_v1";
    private static final int DEFAULT_MAX_CATEGORY_DEPTH = 3;
    private static final int HARD_MAX_CATEGORY_DEPTH = 5;
    private static final long DEFAULT_AUTO_LOCK_MS = 30_000L;
    private static final long AUTO_LOCK_IMMEDIATELY = 0L;
    private static final String[] BUILT_IN_CATEGORIES = {"Login", "Website", "App", "Contact", "Banking", "Work", "Personal", "Secure Note", "Other"};
    private static final int EXPORT_REQUEST = 7001;
    private static final int TEMPLATE_EXPORT_REQUEST = 7002;
    private static final int IMPORT_REQUEST = 7003;
    private static final int BACKUP_EXPORT_REQUEST = 7004;
    private static final int BACKUP_RESTORE_REQUEST = 7005;

    /** Spinner model: keep the stable category name separate from its hierarchical label. */
    private static final class CategoryOption {
        final String name;
        final String label;
        CategoryOption(String name, String label) { this.name = name; this.label = label; }
        @Override public String toString() { return label; }
    }

    private SecretKey sessionKey;
    private VaultDatabase database;
    private LinearLayout listContainer;
    private EditText searchBox;
    private Spinner categoryFilter;
    private ScrollView homeScroll;
    private TextView homeEntriesHeading;
    private List<VaultItem> allItems = new ArrayList<>();
    private List<CustomCategory> customCategories = new ArrayList<>();
    private byte[] pendingExportBytes;
    private String pendingExportMime;
    private byte[] pendingTemplateBytes;
    private byte[] pendingBackupBytes;
    private long backgroundAt = 0L;
    private boolean explicitlyLocked = true;
    private ClipboardSecurityManager clipboardSecurity;
    // PBKDF2 deliberately uses a high work factor; never derive on the UI thread.
    private final ExecutorService unlockExecutor = Executors.newSingleThreadExecutor();
    private boolean systemPickerInProgress = false;
    private long systemPickerStartedAt = 0L;
    private boolean screenOffReceiverRegistered = false;
    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && sessionKey != null && getLockOnScreenOff()) {
                lockVault();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenSecurityManager.protect(this);
        ReleaseSecurityManager.Result releaseSecurity = ReleaseSecurityManager.verify(this);
        if (!releaseSecurity.ok) {
            showReleaseSecurityBlock(releaseSecurity.message);
            return;
        }
        database = new VaultDatabase(this);
        clipboardSecurity = new ClipboardSecurityManager(this);
        registerScreenOffReceiver();
        if (isConfigured()) showUnlockScreen(); else showSetupScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-assert protection after returning from system UI such as the
        // document picker or biometric prompt.
        ScreenSecurityManager.protect(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ScreenSecurityManager.protect(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (sessionKey != null) backgroundAt = System.currentTimeMillis();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (sessionKey != null && backgroundAt > 0 && !systemPickerInProgress) {
            long timeout = getAutoLockMs();
            if (timeout == AUTO_LOCK_IMMEDIATELY || System.currentTimeMillis() - backgroundAt >= timeout) {
                lockVault();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (screenOffReceiverRegistered) {
            try { unregisterReceiver(screenOffReceiver); } catch (Exception ignored) { }
            screenOffReceiverRegistered = false;
        }
        unlockExecutor.shutdownNow();
        clearSessionState();
        super.onDestroy();
    }

    private void registerScreenOffReceiver() {
        if (screenOffReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOffReceiver, filter);
        }
        screenOffReceiverRegistered = true;
    }

    private void showReleaseSecurityBlock(String message) {
        LinearLayout root = baseVertical(24);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title("Security verification failed"));
        root.addView(subtitle(message + "\n\nInstall an official signed build of Keepriva."));
        Button close = button("Close app");
        close.setOnClickListener(v -> finishAndRemoveTask());
        root.addView(close);
        setContentView(wrap(root));
    }

    private boolean isConfigured() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean v2 = p.getInt(PREF_CRYPTO_VERSION, 0) >= CRYPTO_VERSION_2
                && p.contains(PREF_MASTER_SALT)
                && p.contains(PREF_WRAPPED_VAULT_KEY)
                && p.contains(PREF_VAULT_VERIFIER);
        boolean legacy = p.contains(PREF_LEGACY_SALT) && p.contains(PREF_LEGACY_VERIFIER);
        return v2 || legacy;
    }

    private void showSetupScreen() {
        LinearLayout root = baseVertical(24);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title("Create Keepriva"));
        root.addView(subtitle("Your master password never leaves this device. If you forget it, the encrypted vault cannot be recovered."));

        EditText pass = passwordField("Master password (12+ characters)");
        EditText confirm = passwordField("Confirm master password");
        root.addView(pass); root.addView(confirm);

        Button create = primaryButton("Create encrypted vault");
        create.setOnClickListener(v -> {
            String p1 = pass.getText().toString();
            String p2 = confirm.getText().toString();
            String strengthError = validateNewMasterPassword(p1);
            if (strengthError != null) { toast(strengthError); return; }
            if (!p1.equals(p2)) { toast("Passwords do not match."); return; }
            try {
                initializeV2Vault(p1);
                explicitlyLocked = false;
                showVaultScreen();
            } catch (Exception e) {
                toast("Unable to initialize vault: " + e.getMessage());
            }
        });
        root.addView(create);
        setContentView(wrap(root));
    }

    private void showUnlockScreen() {
        sessionKey = null;
        explicitlyLocked = true;

        final int white = Color.WHITE;
        final int mutedWhite = Color.argb(215, 255, 255, 255);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(26), dp(34), dp(26), dp(30));
        root.setBackgroundColor(getColor(R.color.keepriva_primary_dark));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_keepriva_shield_leaf);
        logo.setContentDescription("Keepriva shield and leaf logo");
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(88), dp(88));
        logoParams.bottomMargin = dp(10);
        root.addView(logo, logoParams);

        TextView appName = new TextView(this);
        appName.setText("Keepriva");
        appName.setTextColor(white);
        appName.setTextSize(32);
        appName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        appName.setGravity(Gravity.CENTER);
        root.addView(appName, matchWidth());

        TextView tagline = new TextView(this);
        tagline.setText("Your secrets. Your control.");
        tagline.setTextColor(mutedWhite);
        tagline.setTextSize(15);
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, dp(4), 0, dp(22));
        root.addView(tagline, matchWidth());

        LinearLayout authCard = UiStyle.verticalCard(this, 18);
        authCard.addView(UiStyle.sectionTitle(this, "Unlock your vault"));
        authCard.addView(UiStyle.sectionCaption(
                this,
                isBiometricUnlockConfigured()
                        ? "Use biometrics or your master password."
                        : "Use your master password. Biometrics can be enabled later from Security."
        ));

        if (isBiometricUnlockConfigured()) {
            Button biometric = button("Unlock with fingerprint / face");
            biometric.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_keepriva_fingerprint, 0, 0, 0);
            biometric.setCompoundDrawablePadding(dp(8));
            biometric.setContentDescription("Unlock Keepriva with biometrics");
            biometric.setOnClickListener(v -> unlockWithBiometric());
            authCard.addView(biometric, matchWidth());

            TextView or = UiStyle.sectionCaption(this, "or use your master password");
            or.setGravity(Gravity.CENTER);
            or.setPadding(0, dp(12), 0, dp(6));
            authCard.addView(or, matchWidth());
        }

        EditText pass = passwordField("Master password");
        pass.setContentDescription("Master password");
        authCard.addView(pass, matchWidth());

        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = dp(10);
        authCard.addView(progress, progressParams);

        Button unlock = primaryButton("Unlock");
        unlock.setContentDescription("Unlock with master password");

        View.OnClickListener action = v -> {
            String entered = pass.getText().toString();
            if (entered.isEmpty()) {
                pass.setError("Enter your master password");
                return;
            }

            pass.setEnabled(false);
            unlock.setEnabled(false);
            unlock.setText("Unlocking…");
            progress.setVisibility(View.VISIBLE);
            unlockInBackground(entered, pass, unlock, progress);
        };

        unlock.setOnClickListener(action);
        pass.setOnEditorActionListener((v, actionId, event) -> {
            action.onClick(v);
            return true;
        });

        LinearLayout.LayoutParams unlockParams = matchWidth();
        unlockParams.topMargin = dp(10);
        authCard.addView(unlock, unlockParams);

        root.addView(authCard, matchWidth());

        TextView footer = new TextView(this);
        footer.setText("Fully offline  •  Secure  •  Private");
        footer.setTextColor(Color.argb(195, 255, 255, 255));
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(22), 0, 0);
        root.addView(footer, matchWidth());

        ScrollView screen = new ScrollView(this);
        screen.setFillViewport(true);
        screen.setBackgroundColor(getColor(R.color.keepriva_primary_dark));
        screen.addView(root);
        setContentView(screen);
    }
    private void unlock(String password) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (prefs.getInt(PREF_CRYPTO_VERSION, 0) >= CRYPTO_VERSION_2) {
                sessionKey = unlockV2(password, prefs);
            } else {
                sessionKey = unlockLegacyAndMigrate(password, prefs);
            }
            provisionAuthenticationBoundDeviceKey();
            explicitlyLocked = false;
            backgroundAt = 0;
            showVaultScreen();
        } catch (Exception e) {
            sessionKey = null;
            toast("Incorrect master password or vault configuration is damaged.");
        }
    }

    private void initializeV2Vault(String password) throws Exception {
        byte[] salt = CryptoManager.randomBytes(32);
        SecretKey masterKey = null;
        try {
            masterKey = CryptoManager.deriveMasterKey(password.toCharArray(), salt);
            SecretKey vaultKey = CryptoManager.generateVaultKey();
            String wrappedVaultKey = CryptoManager.wrapVaultKey(masterKey, vaultKey);
            String verifier = CryptoManager.encrypt(vaultKey, VERIFIER_TEXT);

            boolean saved = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(PREF_CRYPTO_VERSION, CRYPTO_VERSION_2)
                    .putString(PREF_MASTER_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(PREF_WRAPPED_VAULT_KEY, wrappedVaultKey)
                    .putString(PREF_VAULT_VERIFIER, verifier)
                    .remove(PREF_LEGACY_SALT)
                    .remove(PREF_LEGACY_VERIFIER)
                    .commit();
            if (!saved) throw new GeneralSecurityException("Could not persist vault configuration");
            sessionKey = vaultKey;
            provisionAuthenticationBoundDeviceKey();
        } finally {
            java.util.Arrays.fill(salt, (byte) 0);
        }
    }

    private void unlockInBackground(String password, EditText pass, Button unlock, ProgressBar progress) {
        unlockExecutor.execute(() -> {
            SecretKey unlocked = null;
            Exception failure = null;

            try {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                if (prefs.getInt(PREF_CRYPTO_VERSION, 0) >= CRYPTO_VERSION_2) {
                    unlocked = unlockV2(password, prefs);
                } else {
                    unlocked = unlockLegacyAndMigrate(password, prefs);
                }

                // Android Keystore initialization may also be slow on first use.
                provisionAuthenticationBoundDeviceKey();
            } catch (Exception e) {
                failure = e;
            }

            final SecretKey result = unlocked;
            final Exception error = failure;

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                if (error == null && result != null) {
                    sessionKey = result;
                    explicitlyLocked = false;
                    backgroundAt = 0;
                    showVaultScreen();
                    return;
                }

                sessionKey = null;
                progress.setVisibility(View.GONE);
                pass.setEnabled(true);
                unlock.setEnabled(true);
                unlock.setText("Unlock");
                pass.requestFocus();
                toast("Incorrect master password or vault configuration is damaged.");
            });
        });
    }
    private SecretKey unlockV2(String password, SharedPreferences prefs) throws Exception {
        byte[] salt = Base64.decode(prefs.getString(PREF_MASTER_SALT, ""), Base64.NO_WRAP);
        try {
            SecretKey masterKey = CryptoManager.deriveMasterKey(password.toCharArray(), salt);
            SecretKey vaultKey = CryptoManager.unwrapVaultKey(
                    masterKey, prefs.getString(PREF_WRAPPED_VAULT_KEY, ""));
            String verifier = CryptoManager.decrypt(
                    vaultKey, prefs.getString(PREF_VAULT_VERIFIER, ""));
            if (!VERIFIER_TEXT.equals(verifier)) {
                throw new GeneralSecurityException("Wrong password");
            }
            return vaultKey;
        } finally {
            java.util.Arrays.fill(salt, (byte) 0);
        }
    }

    /**
     * One-time migration from v1.x. The already-validated legacy record key becomes the
     * permanent vault data key and is wrapped by a new master-password KEK. No record is
     * rewritten, so an interrupted upgrade cannot leave a partially re-encrypted database.
     */
    private SecretKey unlockLegacyAndMigrate(String password, SharedPreferences prefs) throws Exception {
        byte[] legacySalt = Base64.decode(prefs.getString(PREF_LEGACY_SALT, ""), Base64.NO_WRAP);
        try {
            SecretKey legacyVaultKey = CryptoManager.deriveLegacyKey(password.toCharArray(), legacySalt);
            String legacyVerifier = CryptoManager.decrypt(
                    legacyVaultKey, prefs.getString(PREF_LEGACY_VERIFIER, ""));
            if (!LEGACY_VERIFIER_TEXT.equals(legacyVerifier)) {
                throw new GeneralSecurityException("Wrong password");
            }

            byte[] newSalt = CryptoManager.randomBytes(32);
            try {
                SecretKey masterKey = CryptoManager.deriveMasterKey(password.toCharArray(), newSalt);
                String wrappedVaultKey = CryptoManager.wrapVaultKey(masterKey, legacyVaultKey);
                String verifier = CryptoManager.encrypt(legacyVaultKey, VERIFIER_TEXT);
                boolean saved = prefs.edit()
                        .putInt(PREF_CRYPTO_VERSION, CRYPTO_VERSION_2)
                        .putString(PREF_MASTER_SALT, Base64.encodeToString(newSalt, Base64.NO_WRAP))
                        .putString(PREF_WRAPPED_VAULT_KEY, wrappedVaultKey)
                        .putString(PREF_VAULT_VERIFIER, verifier)
                        .remove(PREF_LEGACY_SALT)
                        .remove(PREF_LEGACY_VERIFIER)
                        .commit();
                if (!saved) throw new GeneralSecurityException("Could not migrate vault configuration");
                return legacyVaultKey;
            } finally {
                java.util.Arrays.fill(newSalt, (byte) 0);
            }
        } finally {
            java.util.Arrays.fill(legacySalt, (byte) 0);
        }
    }

    /**
     * Provisions the non-exportable Android Keystore key used by the next biometric
     * hardening step. Failure never locks the user out: master-password unlock stays
     * fully functional and remains the recovery path.
     */
    private void provisionAuthenticationBoundDeviceKey() {
        try {
            DeviceKeyManager.ensureAuthenticationBoundKey();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_DEVICE_KEY_STATUS, DEVICE_KEY_READY)
                    .apply();
        } catch (GeneralSecurityException e) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove(PREF_DEVICE_KEY_STATUS)
                    .apply();
            // Deliberately do not log exception details: Keystore/provider messages can
            // reveal device security state. Biometric setup will surface a safe UI message.
        }
    }

    private boolean isBiometricUnlockConfigured() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        return p.contains(PREF_BIOMETRIC_WRAPPED_VAULT_KEY)
                && p.contains(PREF_BIOMETRIC_IV);
    }

    private void showBiometricSettings() {
        if (sessionKey == null) return;
        if (isBiometricUnlockConfigured()) {
            new AlertDialog.Builder(this)
                    .setTitle("Biometric unlock")
                    .setMessage("Biometric unlock is enabled on this device. The master password remains the recovery method.")
                    .setPositiveButton("Disable", (d, w) -> disableBiometricUnlock())
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Enable biometric unlock?")
                    .setMessage("Your fingerprint or strong face authentication will authorize Android Keystore to unwrap the vault key. Your master password remains available as fallback and recovery.")
                    .setPositiveButton("Enable", (d, w) -> enableBiometricUnlock())
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private void enableBiometricUnlock() {
        if (sessionKey == null) return;
        try {
            DeviceKeyManager.ensureAuthenticationBoundKey();
            Cipher cipher = DeviceKeyManager.createEncryptionCipher();
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle("Enable biometric unlock")
                    .setSubtitle("Authenticate to protect this device's vault-key copy")
                    .setNegativeButton("Cancel", getMainExecutor(), (dialog, which) -> { })
                    .build();
            CancellationSignal cancellation = new CancellationSignal();
            prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), cancellation,
                    getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            try {
                                Cipher authorized = requireCipher(result);
                                byte[] rawVaultKey = sessionKey.getEncoded();
                                if (rawVaultKey == null || rawVaultKey.length != 32) {
                                    throw new GeneralSecurityException("Vault key is unavailable");
                                }
                                try {
                                    byte[] wrapped = authorized.doFinal(rawVaultKey);
                                    byte[] iv = authorized.getIV();
                                    boolean saved = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                            .putString(PREF_BIOMETRIC_WRAPPED_VAULT_KEY,
                                                    Base64.encodeToString(wrapped, Base64.NO_WRAP))
                                            .putString(PREF_BIOMETRIC_IV,
                                                    Base64.encodeToString(iv, Base64.NO_WRAP))
                                            .putString(PREF_DEVICE_KEY_STATUS, DEVICE_KEY_READY)
                                            .commit();
                                    java.util.Arrays.fill(wrapped, (byte) 0);
                                    if (!saved) throw new GeneralSecurityException("Could not save biometric configuration");
                                    toast("Biometric unlock enabled.");
                                    showVaultScreen();
                                } finally {
                                    java.util.Arrays.fill(rawVaultKey, (byte) 0);
                                }
                            } catch (Exception e) {
                                clearBiometricState(false);
                                toast("Could not enable biometric unlock. Master password is unchanged.");
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
                            if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                                    && errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                                toast("Biometric authentication is unavailable. Use the master password.");
                            }
                        }
                    });
        } catch (Exception e) {
            clearBiometricState(true);
            toast("Biometric authentication is not available on this device. Master password still works.");
        }
    }

    private void unlockWithBiometric() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!isBiometricUnlockConfigured()) return;
        try {
            byte[] iv = Base64.decode(p.getString(PREF_BIOMETRIC_IV, ""), Base64.NO_WRAP);
            Cipher cipher = DeviceKeyManager.createDecryptionCipher(iv);
            java.util.Arrays.fill(iv, (byte) 0);
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle("Unlock Keepriva")
                    .setSubtitle("Authenticate with fingerprint or strong face")
                    .setNegativeButton("Use master password", getMainExecutor(), (dialog, which) -> { })
                    .build();
            CancellationSignal cancellation = new CancellationSignal();
            prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), cancellation,
                    getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            byte[] rawVaultKey = null;
                            byte[] wrapped = null;
                            try {
                                Cipher authorized = requireCipher(result);
                                wrapped = Base64.decode(p.getString(PREF_BIOMETRIC_WRAPPED_VAULT_KEY, ""), Base64.NO_WRAP);
                                rawVaultKey = authorized.doFinal(wrapped);
                                if (rawVaultKey.length != 32) throw new GeneralSecurityException("Invalid vault key");
                                SecretKey candidate = new SecretKeySpec(rawVaultKey, "AES");
                                String verifier = CryptoManager.decrypt(candidate, p.getString(PREF_VAULT_VERIFIER, ""));
                                if (!VERIFIER_TEXT.equals(verifier)) throw new GeneralSecurityException("Vault verifier failed");
                                sessionKey = candidate;
                                explicitlyLocked = false;
                                backgroundAt = 0;
                                showVaultScreen();
                            } catch (Exception e) {
                                sessionKey = null;
                                clearBiometricState(true);
                                toast("Biometric key is no longer valid. Unlock with the master password to continue.");
                                showUnlockScreen();
                            } finally {
                                if (rawVaultKey != null) java.util.Arrays.fill(rawVaultKey, (byte) 0);
                                if (wrapped != null) java.util.Arrays.fill(wrapped, (byte) 0);
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
                            if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                                    && errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                                toast("Biometric unlock failed. Use the master password.");
                            }
                        }
                    });
        } catch (Exception e) {
            clearBiometricState(true);
            toast("Biometric key is unavailable or was invalidated. Use the master password.");
            showUnlockScreen();
        }
    }

    private Cipher requireCipher(BiometricPrompt.AuthenticationResult result) throws GeneralSecurityException {
        if (result == null || result.getCryptoObject() == null || result.getCryptoObject().getCipher() == null) {
            throw new GeneralSecurityException("Biometric cryptographic authorization was not returned");
        }
        return result.getCryptoObject().getCipher();
    }

    private void disableBiometricUnlock() {
        clearBiometricState(true);
        toast("Biometric unlock disabled. Use your master password to unlock.");
        if (sessionKey != null) showVaultScreen();
    }

    private void clearBiometricState(boolean deleteDeviceKey) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove(PREF_BIOMETRIC_WRAPPED_VAULT_KEY)
                .remove(PREF_BIOMETRIC_IV)
                .remove(PREF_DEVICE_KEY_STATUS)
                .apply();
        if (deleteDeviceKey) {
            try { DeviceKeyManager.deleteKey(); } catch (GeneralSecurityException ignored) { }
        }
    }

    private long getAutoLockMs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(PREF_AUTO_LOCK_MS, DEFAULT_AUTO_LOCK_MS);
    }

    private boolean getLockOnScreenOff() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_LOCK_ON_SCREEN_OFF, true);
    }

    private int getMaxCategoryDepth() {
        int configured = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(PREF_MAX_CATEGORY_DEPTH, DEFAULT_MAX_CATEGORY_DEPTH);
        return Math.max(1, Math.min(HARD_MAX_CATEGORY_DEPTH, configured));
    }

    /** Root categories have depth 1. Unknown/broken references fail conservatively at max+1. */
    private int categoryDepth(String categoryName) {
        String current = safe(categoryName).trim();
        if (current.isEmpty() || isBuiltInCategory(current)) return 1;
        Set<String> seen = new HashSet<>();
        int depth = 1;
        while (!current.isEmpty()) {
            if (!seen.add(current.toLowerCase(Locale.ROOT))) return HARD_MAX_CATEGORY_DEPTH + 1;
            CustomCategory c = findCustomCategory(current);
            if (c == null || safe(c.parentName).trim().isEmpty()) return depth;
            String parent = safe(c.parentName).trim();
            depth++;
            if (isBuiltInCategory(parent)) return depth;
            current = parent;
            if (depth > HARD_MAX_CATEGORY_DEPTH + 1) return depth;
        }
        return depth;
    }

    private int deepestCategoryDepth() {
        int deepest = 1;
        for (CustomCategory c : customCategories) deepest = Math.max(deepest, categoryDepth(c.name));
        return deepest;
    }

    private String categoryPath(String categoryName) {
        String leaf = safe(categoryName).trim();
        if (leaf.isEmpty() || isBuiltInCategory(leaf)) return leaf;
        List<String> parts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = leaf;
        while (!current.isEmpty() && seen.add(current.toLowerCase(Locale.ROOT))) {
            parts.add(current);
            CustomCategory c = findCustomCategory(current);
            if (c == null || safe(c.parentName).trim().isEmpty()) break;
            current = safe(c.parentName).trim();
            if (isBuiltInCategory(current)) { parts.add(current); break; }
        }
        Collections.reverse(parts);
        return String.join(" / ", parts);
    }

    private boolean isDescendantOf(String candidateName, String ancestorName) {
        String current = safe(candidateName).trim();
        String ancestor = safe(ancestorName).trim();
        Set<String> seen = new HashSet<>();
        while (!current.isEmpty() && seen.add(current.toLowerCase(Locale.ROOT))) {
            if (current.equals(ancestor)) return true;
            CustomCategory c = findCustomCategory(current);
            if (c == null) return false;
            current = safe(c.parentName).trim();
        }
        return false;
    }

    private int subtreeRelativeDepth(String categoryName) {
        int max = 1;
        for (CustomCategory c : customCategories) {
            if (isDescendantOf(c.name, categoryName)) {
                int relative = categoryDepth(c.name) - categoryDepth(categoryName) + 1;
                max = Math.max(max, relative);
            }
        }
        return max;
    }

    private boolean moveFitsDepth(String categoryName, String newParentName) {
        int parentDepth = safe(newParentName).trim().isEmpty() ? 0 : categoryDepth(newParentName);
        int newRootDepth = parentDepth + 1;
        return newRootDepth + subtreeRelativeDepth(categoryName) - 1 <= getMaxCategoryDepth();
    }

    /** Returns null when valid, otherwise a user-facing hierarchy validation error. */
    private String validateCategoryHierarchy(List<CustomCategory> categories, int maxDepth) {
        Map<String, String> parents = new LinkedHashMap<>();
        for (CustomCategory c : categories) {
            String name = safe(c.name).trim();
            if (name.isEmpty()) return "A custom category has no name.";
            String key = name.toLowerCase(Locale.ROOT);
            if (parents.containsKey(key)) return "Duplicate custom category: " + name;
            parents.put(key, safe(c.parentName).trim());
        }
        for (CustomCategory c : categories) {
            String current = safe(c.name).trim();
            Set<String> seen = new HashSet<>();
            int depth = 1;
            while (!current.isEmpty()) {
                String lc = current.toLowerCase(Locale.ROOT);
                if (!seen.add(lc)) return "Category cycle detected around: " + c.name;
                String parent = parents.get(lc);
                if (parent == null || parent.isEmpty()) break;
                depth++;
                if (depth > maxDepth) return "Category '" + c.name + "' exceeds the configured maximum depth of " + maxDepth + ".";
                if (isBuiltInCategory(parent)) break;
                if (!parents.containsKey(parent.toLowerCase(Locale.ROOT))) {
                    return "Category '" + c.name + "' references missing parent '" + parent + "'.";
                }
                current = parent;
            }
        }
        return null;
    }

    private void showPreferencesDialog() {
        LinearLayout root = baseVertical(8);
        root.addView(subtitle("Category nesting controls how many levels of categories and sub-categories Keepriva allows. Existing data is never flattened automatically."));

        CheckBox enableEdit = new CheckBox(this);
        enableEdit.setText("Enable editing category nesting depth");
        enableEdit.setChecked(false);
        root.addView(enableEdit);

        EditText depth = field("Maximum category depth (1-5)", String.valueOf(getMaxCategoryDepth()));
        depth.setInputType(InputType.TYPE_CLASS_NUMBER);
        depth.setEnabled(false);
        root.addView(depth);
        root.addView(subtitle("Default: 3. Hard maximum: 5. Root categories count as level 1."));

        enableEdit.setOnCheckedChangeListener((buttonView, checked) -> depth.setEnabled(checked));

        AlertDialog dialog = new AlertDialog.Builder(this)
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
            try { requested = Integer.parseInt(depth.getText().toString().trim()); }
            catch (Exception e) { depth.setError("Enter a number from 1 to 5"); return; }
            if (requested < 1 || requested > HARD_MAX_CATEGORY_DEPTH) {
                depth.setError("Allowed range is 1 to " + HARD_MAX_CATEGORY_DEPTH);
                return;
            }
            int existing = deepestCategoryDepth();
            if (requested < existing) {
                depth.setError("Current hierarchy already uses " + existing + " levels. Move categories upward before reducing this preference.");
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(PREF_MAX_CATEGORY_DEPTH, requested).apply();
            dialog.dismiss();
            toast("Maximum category depth set to " + requested + ".");
        }));
        dialog.show();
    }

    private String autoLockLabel(long timeout) {
        if (timeout == 0L) return "Immediately";
        if (timeout == 30_000L) return "30 seconds";
        if (timeout == 60_000L) return "1 minute";
        if (timeout == 300_000L) return "5 minutes";
        return (timeout / 1000L) + " seconds";
    }

    private void requestMasterPasswordReauth(String purpose, Runnable onSuccess) {
        EditText pass = passwordField("Master password");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(purpose)
                .setMessage("Enter your master password to continue.")
                .setView(pass)
                .setPositiveButton("Continue", null)
                .setNegativeButton("Cancel", null)
                .create();
        ScreenSecurityManager.protect(dialog);
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                SecretKey verified = prefs.getInt(PREF_CRYPTO_VERSION, 0) >= CRYPTO_VERSION_2
                        ? unlockV2(pass.getText().toString(), prefs)
                        : unlockLegacyAndMigrate(pass.getText().toString(), prefs);
                if (verified == null) throw new GeneralSecurityException("Verification failed");
                pass.setText("");
                dialog.dismiss();
                onSuccess.run();
            } catch (Exception e) {
                pass.setText("");
                pass.requestFocus();
                toast("Incorrect master password.");
            }
        }));
        dialog.show();
    }

    private void showSecuritySettings() {
        if (sessionKey == null) return;
        LinearLayout root = baseVertical(8);
        TextView status = subtitle("Master password: configured\nBiometric unlock: "
                + (isBiometricUnlockConfigured() ? "enabled" : "disabled")
                + "\nAuto-lock: " + autoLockLabel(getAutoLockMs())
                + "\nLock on screen off: " + (getLockOnScreenOff() ? "enabled" : "disabled")
                + "\nClipboard timeout: " + clipboardTimeoutLabel(getClipboardTimeoutMs()));
        root.addView(status);

        Button changePassword = button("Change master password");
        changePassword.setOnClickListener(v -> {
            AlertDialog parentDialog = findShowingDialogForView(v);
            if (parentDialog != null) parentDialog.dismiss();
            showChangeMasterPasswordDialog();
        });
        root.addView(changePassword);

        Button autoLock = button("Auto-lock: " + autoLockLabel(getAutoLockMs()));
        autoLock.setOnClickListener(v -> {
            AlertDialog parentDialog = findShowingDialogForView(v);
            if (parentDialog != null) parentDialog.dismiss();
            showAutoLockSettings();
        });
        root.addView(autoLock);

        CheckBox screenOff = new CheckBox(this);
        screenOff.setText("Lock when screen turns off");
        screenOff.setChecked(getLockOnScreenOff());
        screenOff.setOnCheckedChangeListener((b, checked) -> getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(PREF_LOCK_ON_SCREEN_OFF, checked).apply());
        root.addView(screenOff);

        Button biometric = button(isBiometricUnlockConfigured() ? "Biometric unlock: Enabled" : "Biometric unlock: Disabled");
        biometric.setOnClickListener(v -> {
            AlertDialog parentDialog = findShowingDialogForView(v);
            if (parentDialog != null) parentDialog.dismiss();
            showBiometricSettings();
        });
        root.addView(biometric);

        Button clipboard = button("Clipboard timeout: " + clipboardTimeoutLabel(getClipboardTimeoutMs()));
        clipboard.setOnClickListener(v -> {
            AlertDialog parentDialog = findShowingDialogForView(v);
            if (parentDialog != null) parentDialog.dismiss();
            showClipboardSettings();
        });
        root.addView(clipboard);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Security settings")
                .setView(root)
                .setPositiveButton("Done", (d, w) -> showVaultScreen())
                .create();
        root.setTag(dialog);
        ScreenSecurityManager.protect(dialog);
        dialog.show();
    }

    private void showAutoLockSettings() {
        final String[] labels = {"Immediately", "30 seconds", "1 minute", "5 minutes"};
        final long[] values = {0L, 30_000L, 60_000L, 300_000L};
        long current = getAutoLockMs();
        int selected = 1;
        for (int i = 0; i < values.length; i++) if (values[i] == current) selected = i;

        LinearLayout box = baseVertical(6);
        box.addView(subtitle("Keepriva locks after it has been left in the background for this long."));

        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        group.setOrientation(android.widget.RadioGroup.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(labels[i]);
            rb.setTag(i);
            group.addView(rb);
            if (i == selected) group.check(rb.getId());
        }
        box.addView(group);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Auto-lock")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            int checkedId = group.getCheckedRadioButtonId();
            View checked = group.findViewById(checkedId);
            if (checked == null || checked.getTag() == null) return;
            int which = (Integer) checked.getTag();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(PREF_AUTO_LOCK_MS, values[which]).apply();
            dialog.dismiss();
            toast("Auto-lock: " + labels[which]);
        }));

        ScreenSecurityManager.protect(dialog);
        dialog.show();
    }

    private String validateNewMasterPassword(String password) {
        if (password == null || password.length() < 12) return "Use at least 12 characters for a new master password.";
        int classes = 0;
        if (password.matches(".*[a-z].*")) classes++;
        if (password.matches(".*[A-Z].*")) classes++;
        if (password.matches(".*[0-9].*")) classes++;
        if (password.matches(".*[^A-Za-z0-9].*")) classes++;
        if (classes < 3) return "Use at least three of: lowercase, uppercase, number, symbol.";
        return null;
    }

    private void showChangeMasterPasswordDialog() {
        if (sessionKey == null) return;
        LinearLayout fields = baseVertical(6);
        EditText current = passwordField("Current master password");
        EditText next = passwordField("New master password (12+ characters)");
        EditText confirm = passwordField("Confirm new master password");
        fields.addView(current); fields.addView(next); fields.addView(confirm);
        AlertDialog dialog = new AlertDialog.Builder(this)
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
            String strengthError = validateNewMasterPassword(newText);
            if (strengthError != null) { toast(strengthError); return; }
            if (!newText.equals(confirmText)) { toast("New passwords do not match."); return; }
            if (newText.equals(currentText)) { toast("Choose a different master password."); return; }
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                SecretKey verifiedVaultKey = unlockV2(currentText, prefs);
                if (!java.security.MessageDigest.isEqual(verifiedVaultKey.getEncoded(), sessionKey.getEncoded())) {
                    throw new GeneralSecurityException("Current password did not unlock this session");
                }
                byte[] newSalt = CryptoManager.randomBytes(32);
                try {
                    SecretKey newMasterKey = CryptoManager.deriveMasterKey(newText.toCharArray(), newSalt);
                    String wrapped = CryptoManager.wrapVaultKey(newMasterKey, sessionKey);
                    String verifier = CryptoManager.encrypt(sessionKey, VERIFIER_TEXT);
                    boolean saved = prefs.edit()
                            .putInt(PREF_CRYPTO_VERSION, CRYPTO_VERSION_2)
                            .putString(PREF_MASTER_SALT, Base64.encodeToString(newSalt, Base64.NO_WRAP))
                            .putString(PREF_WRAPPED_VAULT_KEY, wrapped)
                            .putString(PREF_VAULT_VERIFIER, verifier)
                            .commit();
                    if (!saved) throw new GeneralSecurityException("Could not save new master password configuration");
                } finally {
                    java.util.Arrays.fill(newSalt, (byte) 0);
                }
                current.setText(""); next.setText(""); confirm.setText("");
                dialog.dismiss();
                toast("Master password changed. Biometric unlock remains available on this device.");
                showVaultScreen();
            } catch (Exception e) {
                current.setText("");
                toast("Current master password is incorrect or the password change could not be saved.");
            }
        }));
        dialog.show();
    }

    private long getClipboardTimeoutMs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(PREF_CLIPBOARD_TIMEOUT_MS, DEFAULT_CLIPBOARD_TIMEOUT_MS);
    }

    private String clipboardTimeoutLabel(long timeout) {
        if (timeout == ClipboardSecurityManager.NEVER_CLEAR) return "Never";
        return (timeout / 1000L) + "s";
    }

    private void showClipboardSettings() {
        final String[] labels = {"15 seconds", "30 seconds (recommended)", "60 seconds", "Never auto-clear"};
        final long[] values = {
                ClipboardSecurityManager.FIFTEEN_SECONDS,
                ClipboardSecurityManager.THIRTY_SECONDS,
                ClipboardSecurityManager.SIXTY_SECONDS,
                ClipboardSecurityManager.NEVER_CLEAR
        };
        long current = getClipboardTimeoutMs();
        int selected = 1;
        for (int i = 0; i < values.length; i++) if (values[i] == current) selected = i;

        LinearLayout box = baseVertical(6);
        box.addView(subtitle("Copied passwords are marked sensitive. Keepriva can also clear a copied password after a short delay. 'Never' is less secure."));

        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        group.setOrientation(android.widget.RadioGroup.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(labels[i]);
            rb.setTag(i);
            group.addView(rb);
            if (i == selected) group.check(rb.getId());
        }
        box.addView(group);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Password clipboard timeout")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            int checkedId = group.getCheckedRadioButtonId();
            View checked = group.findViewById(checkedId);
            if (checked == null || checked.getTag() == null) return;
            int which = (Integer) checked.getTag();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(PREF_CLIPBOARD_TIMEOUT_MS, values[which]).apply();
            dialog.dismiss();
            toast("Clipboard timeout: " + labels[which]);
        }));

        ScreenSecurityManager.protect(dialog);
        dialog.show();
    }

    private void copyPasswordToClipboard(String password) {
        if (password == null || password.isEmpty()) return;
        long timeout = getClipboardTimeoutMs();
        clipboardSecurity.copySensitive("Keepriva password", password, timeout);
        toast(timeout == ClipboardSecurityManager.NEVER_CLEAR
                ? "Password copied. Clipboard auto-clear is disabled."
                : "Password copied. It will be cleared in " + (timeout / 1000L) + " seconds.");
    }

    private void lockVault() {
        if (clipboardSecurity != null) clipboardSecurity.clearSensitiveClipboardNow();
        clearSessionState();
        explicitlyLocked = true;
        backgroundAt = 0;
        showUnlockScreen();
    }
    private void clearSessionState() {
        sessionKey = null;
        allItems.clear();
        customCategories.clear();
        listContainer = null;
        searchBox = null;
        categoryFilter = null;
        homeScroll = null;
        homeEntriesHeading = null;
        clearPendingExportData();
        clearPendingTemplateData();
        clearPendingBackupData();
        systemPickerInProgress = false;
        systemPickerStartedAt = 0L;
    }
    private int directChildCount(String parentName) {
        int count = 0;
        for (CustomCategory category : customCategories) {
            if (safe(category.parentName).equals(parentName)) count++;
        }
        return count;
    }
    private CategoryOption[] parentOptionsFor(CustomCategory existing) {
        List<CategoryOption> options = new ArrayList<>();
        options.add(new CategoryOption("", "Top level"));

        int maxDepth = getMaxCategoryDepth();

        for (String builtIn : BUILT_IN_CATEGORIES) {
            if (1 < maxDepth) {
                options.add(new CategoryOption(builtIn, builtIn));
            }
        }

        List<CustomCategory> sorted = new ArrayList<>(customCategories);
        sorted.sort((a, b) ->
                categoryPath(a.name).compareToIgnoreCase(categoryPath(b.name)));

        for (CustomCategory candidate : sorted) {
            if (existing != null) {
                if (candidate.id == existing.id) continue;
                if (isDescendantOf(candidate.name, existing.name)) continue;
                if (!moveFitsDepth(existing.name, candidate.name)) continue;
            } else if (categoryDepth(candidate.name) + 1 > maxDepth) {
                continue;
            }

            options.add(new CategoryOption(
                    candidate.name,
                    categoryPath(candidate.name)));
        }

        return options.toArray(new CategoryOption[0]);
    }
    private void showVaultScreen() {
        if (sessionKey == null) { showUnlockScreen(); return; }

        try {
            customCategories = database.listCustomCategories(sessionKey);
            allItems = database.list(sessionKey);
        } catch (Exception e) {
            toast("Could not decrypt vault. Locking for safety.");
            lockVault();
            return;
        }

        LinearLayout outer = baseVertical(14);
        outer.setPadding(dp(14), dp(14), dp(14), dp(28));

        // Compact identity header.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(14), dp(14), dp(14));
        header.setBackground(UiStyle.rounded(this, R.color.keepriva_primary_dark, 16));

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);

        TextView appTitle = new TextView(this);
        appTitle.setText("Keepriva");
        appTitle.setTextColor(getColor(android.R.color.white));
        appTitle.setTextSize(24);
        appTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        identity.addView(appTitle);

        TextView appSubtitle = new TextView(this);
        appSubtitle.setText("Private vault • Offline by design");
        appSubtitle.setTextColor(Color.argb(205, 255, 255, 255));
        appSubtitle.setTextSize(13);
        identity.addView(appSubtitle);

        header.addView(identity, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button lock = button("Lock");
        lock.setContentDescription("Lock vault");
        UiStyle.styleCompactButton(lock);
        lock.setOnClickListener(v -> lockVault());
        header.addView(lock);

        outer.addView(header, matchWidth());

        // Search sits above the category tree.
        searchBox = field("Search title, username, phone, website or notes", "");
        searchBox.setSingleLine(true);
        LinearLayout.LayoutParams searchParams = matchWidth();
        searchParams.topMargin = dp(12);
        outer.addView(searchBox, searchParams);

        // Category tree: this is now the primary navigation instead of a row/grid
        // of top-level buttons. Parent and child categories are shown one below
        // another with indentation and entry counts.
        LinearLayout categoryCard = UiStyle.verticalCard(this, 10);

        LinearLayout categoryHeader = new LinearLayout(this);
        categoryHeader.setOrientation(LinearLayout.HORIZONTAL);
        categoryHeader.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout categoryHeaderText = new LinearLayout(this);
        categoryHeaderText.setOrientation(LinearLayout.VERTICAL);
        categoryHeaderText.addView(UiStyle.sectionTitle(this, "Categories"));
        categoryHeaderText.addView(UiStyle.sectionCaption(
                this, "Browse your vault by category and subcategory."));

        categoryHeader.addView(categoryHeaderText, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button manageCategories = button("Manage");
        manageCategories.setContentDescription("Manage categories");
        UiStyle.styleCompactButton(manageCategories);
        manageCategories.setOnClickListener(v -> showCustomCategoriesDialog());
        categoryHeader.addView(manageCategories);

        categoryCard.addView(categoryHeader, matchWidth());

        LinearLayout categoryTree = new LinearLayout(this);
        categoryTree.setOrientation(LinearLayout.VERTICAL);

        addHomeCategoryTreeRow(categoryTree, "All", "All categories", 0, countItemsForCategory("All"), true);

        Set<String> rendered = new HashSet<>();

        for (String builtIn : BUILT_IN_CATEGORIES) {
            addHomeCategoryTreeRow(
                    categoryTree,
                    builtIn,
                    builtIn,
                    0,
                    countItemsForCategory(builtIn),
                    false
            );
            addHomeCustomCategoryChildren(categoryTree, builtIn, 1, rendered);
        }

        List<CustomCategory> roots = new ArrayList<>();
        for (CustomCategory category : customCategories) {
            if (safe(category.parentName).trim().isEmpty()) roots.add(category);
        }
        roots.sort((a, b) -> safe(a.name).compareToIgnoreCase(safe(b.name)));

        for (CustomCategory root : roots) {
            String key = safe(root.name).toLowerCase(Locale.ROOT);
            if (!rendered.add(key)) continue;
            addHomeCategoryTreeRow(
                    categoryTree,
                    root.name,
                    root.name,
                    0,
                    countItemsForCategory(root.name),
                    false
            );
            addHomeCustomCategoryChildren(categoryTree, root.name, 1, rendered);
        }

        categoryCard.addView(categoryTree, matchWidth());

        LinearLayout.LayoutParams categoryParams = matchWidth();
        categoryParams.topMargin = dp(12);
        outer.addView(categoryCard, categoryParams);

        // Primary vault content belongs directly below category navigation.
        // Previously entries were rendered after the entire Vault tools section,
        // which made category selection and search appear to do nothing.
        Button addItem = primaryButton("+  Add item");
        addItem.setContentDescription("Add item");
        addItem.setOnClickListener(v -> showEditDialog(null));
        LinearLayout.LayoutParams addParams = matchWidth();
        addParams.topMargin = dp(12);
        outer.addView(addItem, addParams);

        homeEntriesHeading = UiStyle.sectionTitle(this, "Entries — All categories");
        homeEntriesHeading.setPadding(dp(2), dp(18), 0, dp(6));
        outer.addView(homeEntriesHeading, matchWidth());

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(listContainer, matchWidth());

        // Secondary management actions come after the user's actual vault content.
        LinearLayout toolsCard = UiStyle.verticalCard(this, 10);
        toolsCard.addView(UiStyle.sectionTitle(this, "Vault tools"));
        toolsCard.addView(UiStyle.sectionCaption(
                this, "Transfer, back up, configure and secure Keepriva."));

        toolsCard.addView(homeToolRow(
                R.drawable.ic_keepriva_import,
                "Import",
                "Bulk import credentials from Keepriva JSON",
                v -> showImportDialog()), matchWidth());

        toolsCard.addView(homeToolRow(
                R.drawable.ic_keepriva_export,
                "Export",
                "Export the currently selected category",
                v -> exportSelectedCategory()), matchWidth());

        toolsCard.addView(homeToolRow(
                R.drawable.ic_keepriva_backup,
                "Backup & Restore",
                "Encrypted .pvault backup and recovery",
                v -> showBackupRestoreDialog()), matchWidth());

        toolsCard.addView(homeToolRow(
                R.drawable.ic_keepriva_settings,
                "Preferences",
                "Category nesting and app preferences",
                v -> showPreferencesDialog()), matchWidth());

        toolsCard.addView(homeToolRow(
                R.drawable.ic_keepriva_security,
                "Security",
                "Master password, biometrics and lock settings",
                v -> requestMasterPasswordReauth(
                        "Security settings",
                        this::showSecuritySettings)), matchWidth());

        LinearLayout.LayoutParams toolsParams = matchWidth();
        toolsParams.topMargin = dp(16);
        outer.addView(toolsCard, toolsParams);

        // Hidden logical spinner retained only as state-holder for existing filtering
        // and export code. Users navigate through the visible tree rows above.
        categoryFilter = new Spinner(this);
        categoryFilter.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                getFilterCategories()));
        categoryFilter.setVisibility(View.GONE);
        outer.addView(categoryFilter, new LinearLayout.LayoutParams(1, 1));

        searchBox.addTextChangedListener(new SimpleTextWatcher(() -> {
            applyFilter();
            if (!searchBox.getText().toString().trim().isEmpty()) {
                scrollHomeToEntries();
            }
        }));
        categoryFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { applyFilter(); }
            public void onNothingSelected(AdapterView<?> p) { }
        });

        homeScroll = wrap(outer);
        setContentView(homeScroll);
        applyFilter();
    }

    private View homeToolRow(
            int iconRes,
            String title,
            String description,
            View.OnClickListener action) {

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(11), dp(8), dp(11));
        row.setBackground(UiStyle.outlined(
                this, R.color.keepriva_surface, R.color.keepriva_outline, 12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(action);
        row.setContentDescription(title);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        icon.setBackground(UiStyle.rounded(
                this, R.color.keepriva_surface_soft, 20));

        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dp(42), dp(42));
        iconParams.rightMargin = dp(12);
        row.addView(icon, iconParams);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setTextColor(getColor(R.color.keepriva_text_primary));
        text.addView(titleView);

        TextView descriptionView = new TextView(this);
        descriptionView.setText(description);
        descriptionView.setTextSize(13);
        descriptionView.setTextColor(getColor(R.color.keepriva_text_secondary));
        text.addView(descriptionView);

        row.addView(text, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(28);
        arrow.setTextColor(getColor(R.color.keepriva_text_secondary));
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(40)));

        LinearLayout.LayoutParams lp = matchWidth();
        lp.setMargins(0, dp(5), 0, dp(5));
        row.setLayoutParams(lp);

        return row;
    }
    private void addHomeCustomCategoryChildren(
            LinearLayout container,
            String parentName,
            int depth,
            Set<String> rendered) {

        List<CustomCategory> children = new ArrayList<>();
        for (CustomCategory category : customCategories) {
            if (safe(category.parentName).equalsIgnoreCase(safe(parentName))) {
                children.add(category);
            }
        }

        children.sort((a, b) -> safe(a.name).compareToIgnoreCase(safe(b.name)));

        for (CustomCategory child : children) {
            String key = safe(child.name).toLowerCase(Locale.ROOT);
            if (!rendered.add(key)) continue;

            addHomeCategoryTreeRow(
                    container,
                    child.name,
                    child.name,
                    depth,
                    countItemsForCategory(child.name),
                    false
            );

            addHomeCustomCategoryChildren(container, child.name, depth + 1, rendered);
        }
    }

    private void addHomeCategoryTreeRow(
            LinearLayout container,
            String categoryName,
            String label,
            int depth,
            int itemCount,
            boolean allCategories) {

        LinearLayout indent = new LinearLayout(this);
        indent.setOrientation(LinearLayout.VERTICAL);
        indent.setPadding(dp(Math.min(depth, HARD_MAX_CATEGORY_DEPTH) * 20), 0, 0, 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(10), dp(6), dp(10));
        row.setBackground(UiStyle.outlined(
                this, R.color.keepriva_surface, R.color.keepriva_outline, 12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription("Open category " + label);

        ImageView icon = new ImageView(this);
        icon.setImageResource(categoryIconFor(categoryName, allCategories));
        icon.setPadding(dp(7), dp(7), dp(7), dp(7));
        icon.setBackground(UiStyle.rounded(
                this, R.color.keepriva_surface_soft, 20));

        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dp(42), dp(42));
        iconParams.rightMargin = dp(12);
        row.addView(icon, iconParams);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView nameView = new TextView(this);
        nameView.setText(label);
        nameView.setTextSize(depth == 0 ? 16 : 15);
        nameView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        nameView.setTextColor(getColor(R.color.keepriva_text_primary));
        text.addView(nameView);

        TextView countView = new TextView(this);
        countView.setText((depth == 0 ? "Category" : "Subcategory")
                + " • " + itemCount + (itemCount == 1 ? " entry" : " entries"));
        countView.setTextSize(13);
        countView.setTextColor(getColor(R.color.keepriva_text_secondary));
        text.addView(countView);

        row.addView(text, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(28);
        arrow.setTextColor(getColor(R.color.keepriva_text_secondary));
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(40)));

        row.setOnClickListener(v -> {
            String selected = allCategories ? "All" : categoryName;
            selectHomeCategoryByName(selected);

            if (homeEntriesHeading != null) {
                homeEntriesHeading.setText(allCategories
                        ? "Entries — All categories"
                        : "Entries — " + label);
            }

            applyFilter();
            scrollHomeToEntries();
        });

        indent.addView(row, matchWidth());

        LinearLayout.LayoutParams lp = matchWidth();
        lp.setMargins(0, dp(4), 0, dp(4));
        container.addView(indent, lp);
    }

    private void scrollHomeToEntries() {
        if (homeScroll == null || homeEntriesHeading == null) return;

        homeEntriesHeading.post(() ->
                homeScroll.smoothScrollTo(0, homeEntriesHeading.getTop()));
    }
    private int categoryIconFor(String categoryName, boolean allCategories) {
        if (allCategories) return R.drawable.ic_keepriva_folder;

        String category = safe(categoryName).trim().toLowerCase(Locale.ROOT);

        if ("login".equals(category)) return R.drawable.ic_keepriva_key;
        if ("banking".equals(category)) return R.drawable.ic_keepriva_card;
        if ("secure note".equals(category)) return R.drawable.ic_keepriva_note;
        if ("contact".equals(category)) return R.drawable.ic_keepriva_contact;

        return R.drawable.ic_keepriva_folder;
    }
    private int countItemsForCategory(String categoryName) {
        if ("All".equals(categoryName)) return allItems == null ? 0 : allItems.size();

        int count = 0;
        if (allItems != null) {
            for (VaultItem item : allItems) {
                if (isDescendantOf(item.category, categoryName)) count++;
            }
        }
        return count;
    }

    private void selectHomeCategoryByName(String categoryName) {
        if (categoryFilter == null || categoryFilter.getAdapter() == null) return;

        for (int i = 0; i < categoryFilter.getAdapter().getCount(); i++) {
            Object option = categoryFilter.getAdapter().getItem(i);
            if (option instanceof CategoryOption
                    && safe(((CategoryOption) option).name).equalsIgnoreCase(safe(categoryName))) {
                categoryFilter.setSelection(i);
                return;
            }
        }
    }
    private void loadItems() {
        try {
            allItems = database.list(sessionKey);
            applyFilter();
        } catch (Exception e) {
            toast("Could not decrypt vault. Locking for safety.");
            lockVault();
        }
    }

    private void applyFilter() {
        if (listContainer == null) return;
        String query = searchBox == null ? "" : searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        String category = selectedCategoryName(categoryFilter, "All");
        listContainer.removeAllViews();
        int shown = 0;
        for (VaultItem item : allItems) {
            if (!"All".equals(category) && !isDescendantOf(item.category, category)) continue;
            if (!query.isEmpty() && !searchText(item).contains(query)) continue;
            listContainer.addView(itemCard(item));
            shown++;
        }
        if (shown == 0) {
            TextView empty = subtitle(allItems.isEmpty() ? "No items yet. Tap Add item to create your first credential or note." : "No matching items.");
            empty.setPadding(dp(8), dp(24), dp(8), dp(24));
            listContainer.addView(empty);
        }
    }

    private String searchText(VaultItem i) {
        StringBuilder text = new StringBuilder(safe(i.title)).append(' ').append(safe(i.category)).append(' ')
                .append(safe(i.username)).append(' ').append(safe(i.phone1)).append(' ').append(safe(i.phone2)).append(' ')
                .append(safe(i.phone3)).append(' ').append(safe(i.website)).append(' ').append(safe(i.websiteUrl)).append(' ').append(safe(i.notes));
        if (i.customFields != null) for (Map.Entry<String, String> e : i.customFields.entrySet())
            text.append(' ').append(e.getKey()).append(' ').append(safe(e.getValue()));
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private View itemCard(VaultItem item) {
        LinearLayout card = baseVertical(6);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView title = new TextView(this);
        title.setText(item.title.isEmpty() ? "Untitled" : item.title);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(getColor(R.color.keepriva_text_primary));
        card.addView(title);
        TextView cat = subtitle(categoryPath(item.category) + (item.username.isEmpty() ? "" : "  •  " + item.username));
        card.addView(cat);
        UiStyle.styleCard(card);
        card.setOnClickListener(v -> showDetails(item));
        LinearLayout.LayoutParams lp = matchWidth();
        lp.setMargins(0, dp(5), 0, dp(5));
        card.setLayoutParams(lp);
        return card;
    }

    private void showDetails(VaultItem item) {
        LinearLayout body = baseVertical(8);
        addLabelValue(body, "Category", categoryPath(item.category));

        String category = safe(item.category);
        if ("Contact".equals(category)) {
            addNonEmptyLabelValue(body, "Phone 1", item.phone1);
            addNonEmptyLabelValue(body, "Phone 2", item.phone2);
            addNonEmptyLabelValue(body, "Phone 3", item.phone3);
            addNonEmptyLabelValue(body, "Email", item.username);
            addNonEmptyLabelValue(body, "Website", item.website);
            addNonEmptyLabelValue(body, "Website URL", item.websiteUrl);
        } else if ("Secure Note".equals(category)) {
            // Notes are shown below. Other fields are shown only when they actually contain data.
            addNonEmptyLabelValue(body, "Username / Email", item.username);
            addNonEmptyLabelValue(body, "Phone 1", item.phone1);
            addNonEmptyLabelValue(body, "Phone 2", item.phone2);
            addNonEmptyLabelValue(body, "Phone 3", item.phone3);
            addNonEmptyLabelValue(body, "Website / App", item.website);
            addNonEmptyLabelValue(body, "Website URL", item.websiteUrl);
        } else {
            String userLabel = "Banking".equals(category) ? "Customer ID / Username" : "Username / Email";
            addNonEmptyLabelValue(body, userLabel, item.username);
            addNonEmptyLabelValue(body, "Phone 1", item.phone1);
            addNonEmptyLabelValue(body, "Phone 2", item.phone2);
            addNonEmptyLabelValue(body, "Phone 3", item.phone3);
            addNonEmptyLabelValue(body, websiteLabelFor(category), item.website);
            addNonEmptyLabelValue(body, "Website URL", item.websiteUrl);
        }

        if (item.customFields != null) {
            CustomCategory itemCustomCategory = findCustomCategory(item.category);
            java.util.Set<String> sensitiveNames = itemCustomCategory == null
                    ? java.util.Collections.emptySet()
                    : new java.util.HashSet<>(itemCustomCategory.sensitiveFields);

            for (Map.Entry<String, String> e : item.customFields.entrySet()) {
                if (sensitiveNames.contains(e.getKey())) {
                    addSensitiveCustomField(body, e.getKey(), e.getValue());
                } else {
                    addNonEmptyLabelValue(body, e.getKey(), e.getValue());
                }
            }
        }

        Button exportEntry = button("Export this entry");
        exportEntry.setOnClickListener(v -> showExportFormatChooser(Collections.singletonList(item), item.title));
        body.addView(exportEntry);

        if (!safe(item.password).isEmpty()) {
            TextView label = boldLabel("Password");
            body.addView(label);
            LinearLayout pwRow = new LinearLayout(this);
            pwRow.setOrientation(LinearLayout.HORIZONTAL);
            TextView pw = new TextView(this);
            pw.setText("••••••••••••");
            pw.setTextSize(17);
            pw.setPadding(0, dp(4), dp(8), dp(8));
            pwRow.addView(pw, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button show = button("Show");
            final boolean[] visible = {false};
            show.setOnClickListener(v -> {
                visible[0] = !visible[0];
                pw.setText(visible[0] ? item.password : "••••••••••••");
                show.setText(visible[0] ? "Hide" : "Show");
            });
            pwRow.addView(show);
            Button copy = button("Copy");
            copy.setContentDescription("Copy password securely");
            copy.setOnClickListener(v -> copyPasswordToClipboard(item.password));
            pwRow.addView(copy);
            body.addView(pwRow);
        }
        addNonEmptyLabelValue(body, "Notes", item.notes);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(item.title.isEmpty() ? "Vault item" : item.title)
                .setView(wrap(body))
                .setPositiveButton("Edit", null)
                .setNeutralButton("Delete", null)
                .setNegativeButton("Close", null)
                .create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { d.dismiss(); showEditDialog(item); });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> confirmDelete(item, d));
        });
        ScreenSecurityManager.protect(d);
        d.show();
    }

    private void confirmDelete(VaultItem item, AlertDialog parent) {
        final VaultItem deletedSnapshot = copyVaultItem(item);
        new AlertDialog.Builder(this)
                .setTitle("Delete item?")
                .setMessage("Delete \"" + safe(item.title) + "\" from the vault? You can undo this deletion immediately afterward.")
                .setPositiveButton("Delete", (d, w) -> {
                    database.delete(item.id);
                    parent.dismiss();
                    loadItems();
                    showUndoDeletedItem(deletedSnapshot);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private VaultItem copyVaultItem(VaultItem source) {
        VaultItem copy = new VaultItem();
        copy.title = source.title;
        copy.category = source.category;
        copy.username = source.username;
        copy.password = source.password;
        copy.website = source.website;
        copy.websiteUrl = source.websiteUrl;
        copy.phone1 = source.phone1;
        copy.phone2 = source.phone2;
        copy.phone3 = source.phone3;
        copy.notes = source.notes;
        copy.customFields = new LinkedHashMap<>();
        if (source.customFields != null) copy.customFields.putAll(source.customFields);
        // Restore as a new row if Undo is chosen; the deleted row id no longer exists.
        copy.id = 0;
        copy.createdAt = source.createdAt;
        return copy;
    }

    private void showUndoDeletedItem(VaultItem deletedSnapshot) {
        new AlertDialog.Builder(this)
                .setTitle("Item deleted")
                .setMessage("\"" + safe(deletedSnapshot.title) + "\" was deleted.")
                .setPositiveButton("Undo", (d, w) -> {
                    try {
                        database.save(deletedSnapshot, sessionKey);
                        loadItems();
                        toast("Item restored.");
                    } catch (Exception e) {
                        toast("Could not restore the deleted item.");
                    }
                })
                .setNegativeButton("Dismiss", null)
                .show();
    }

    private void showEditDialog(VaultItem existing) {
        VaultItem item = existing == null ? new VaultItem() : existing;
        LinearLayout form = baseVertical(6);
        EditText title = field("Title", item.title);
        Spinner category = new Spinner(this);
        CategoryOption[] editableCats = getEditableCategories();
        category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, editableCats));
        int idx = 0;
        for (int i = 0; i < editableCats.length; i++) if (editableCats[i].name.equals(item.category)) idx = i;
        category.setSelection(idx);

        TextView formHelp = subtitle("");
        EditText username = field("Username / email (optional)", item.username);
        EditText password = passwordField("Password (optional)");
        password.setText(item.password);
        EditText phone1 = phoneField("Phone 1 (optional)", item.phone1);
        EditText phone2 = phoneField("Phone 2 (optional)", item.phone2);
        EditText phone3 = phoneField("Phone 3 (optional)", item.phone3);
        EditText website = field("Website or app name (optional)", item.website);
        EditText websiteUrl = field("Website URL (optional)", item.websiteUrl);
        EditText notes = field("Notes (optional)", item.notes);
        notes.setSingleLine(false);
        notes.setMinLines(4);
        notes.setGravity(Gravity.TOP);

        TextView loginSection = boldLabel("Login details");
        TextView phoneSection = boldLabel("Phone numbers");
        TextView webSection = boldLabel("Website / App details");
        Button optionalToggle = button("Show all optional fields");
        final boolean[] showAll = {false};
        TextView customSection = boldLabel("Custom fields");
        LinearLayout customContainer = baseVertical(2);
        Map<String, String> customDraft = new LinkedHashMap<>();
        if (item.customFields != null) customDraft.putAll(item.customFields);
        Map<String, EditText> customEditors = new LinkedHashMap<>();

        form.addView(title);
        form.addView(category);
        form.addView(formHelp);
        form.addView(optionalToggle);
        form.addView(loginSection);
        form.addView(username);
        form.addView(password);
        form.addView(phoneSection);
        form.addView(phone1);
        form.addView(phone2);
        form.addView(phone3);
        form.addView(webSection);
        form.addView(website);
        form.addView(websiteUrl);
        form.addView(customSection);
        form.addView(customContainer);
        form.addView(boldLabel("Notes"));
        form.addView(notes);

        Runnable refresh = () -> {
            captureCustomValues(customEditors, customDraft);
            String selected = selectedCategoryName(category, "Other");
            applyCategoryFormLayout(selected, showAll[0], formHelp,
                    loginSection, username, password,
                    phoneSection, phone1, phone2, phone3,
                    webSection, website, websiteUrl, notes, optionalToggle);
            rebuildCustomFieldEditors(selected, customSection, customContainer, customEditors, customDraft);
        };

        optionalToggle.setOnClickListener(v -> {
            showAll[0] = !showAll[0];
            refresh.run();
        });
        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refresh.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        refresh.run();

        // Keep the editor actions inside the custom dialog content instead of
        // relying on AlertDialog's platform footer. On some API/theme combinations
        // the very tall ScrollView consumes the dialog measurement and the standard
        // positive/negative buttons are not exposed in the final view hierarchy.
        // Explicit action buttons are both more reliable for users and testable by
        // accessibility/Espresso.
        LinearLayout editorRoot = baseVertical(8);

        ScrollView editorScroll = wrap(form);

        // AlertDialog measures its custom content with a WRAP_CONTENT-style pass.
        // A child using height=0 + weight=1 can therefore receive no usable space,
        // which also prevents the action row below it from being attached/layouted
        // consistently on some API/theme combinations.
        //
        // Give the editor a bounded real height instead. The fields remain scrollable,
        // while Save/Cancel stay permanently present below the scrolling region.
        int screenHeightPx = getResources().getDisplayMetrics().heightPixels;
        int preferredEditorHeightPx = (int) (screenHeightPx * 0.55f);
        int editorHeightPx = Math.max(
                dp(280),
                Math.min(dp(520), preferredEditorHeightPx)
        );

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                editorHeightPx
        );
        editorRoot.addView(editorScroll, scrollParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);

        Button cancelEditor = button("Cancel");
        cancelEditor.setContentDescription("Cancel vault item");

        Button saveEditor = primaryButton("Save");
        saveEditor.setContentDescription("Save vault item");

        actions.addView(cancelEditor);

        LinearLayout.LayoutParams saveParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.leftMargin = dp(8);
        actions.addView(saveEditor, saveParams);

        editorRoot.addView(actions, matchWidth());

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add vault item" : "Edit vault item")
                .setView(editorRoot)
                .create();

        cancelEditor.setOnClickListener(v -> d.dismiss());

        saveEditor.setOnClickListener(v -> {
            if (title.getText().toString().trim().isEmpty()) {
                title.setError("Title is required");
                title.requestFocus();
                return;
            }

            item.title = title.getText().toString().trim();
            item.category = selectedCategoryName(category, "Other");
            item.username = username.getText().toString();
            item.password = password.getText().toString();
            item.phone1 = phone1.getText().toString();
            item.phone2 = phone2.getText().toString();
            item.phone3 = phone3.getText().toString();
            item.website = website.getText().toString();
            item.websiteUrl = websiteUrl.getText().toString();
            item.notes = notes.getText().toString();

            captureCustomValues(customEditors, customDraft);
            item.customFields = new LinkedHashMap<>(customDraft);

            try {
                database.save(item, sessionKey);
                d.dismiss();
                loadItems();
            } catch (Exception e) {
                toast("Could not save encrypted item.");
            }
        });

        ScreenSecurityManager.protect(d);
        d.show();
    }

    private void applyCategoryFormLayout(
            String category, boolean showAll, TextView help,
            TextView loginSection, EditText username, EditText password,
            TextView phoneSection, EditText phone1, EditText phone2, EditText phone3,
            TextView webSection, EditText website, EditText websiteUrl,
            EditText notes, Button toggle) {

        boolean login = showAll;
        boolean phones = showAll;
        boolean web = showAll;

        if (isCustomCategory(category)) {
            help.setText("Custom category. Its configured fields are shown below. Use Show all optional fields to also use standard credential/contact fields.");
            setVisible(loginSection, showAll); setVisible(username, showAll); setVisible(password, showAll);
            setVisible(phoneSection, showAll); setVisible(phone1, showAll); setVisible(phone2, showAll); setVisible(phone3, showAll);
            setVisible(webSection, showAll); setVisible(website, showAll); setVisible(websiteUrl, showAll);
            notes.setVisibility(View.VISIBLE);
            toggle.setVisibility(View.VISIBLE);
            toggle.setText(showAll ? "Use custom fields only" : "Show all optional fields");
            return;
        }

        switch (category) {
            case "Login":
                login = true; web = true;
                help.setText("For general credentials. Login and website fields are shown first.");
                username.setHint("Username / email (optional)");
                website.setHint("Website or app name (optional)");
                break;
            case "Website":
                login = true; web = true;
                help.setText("For website credentials. URL, username and password are emphasized.");
                username.setHint("Username / email (optional)");
                website.setHint("Website name (optional)");
                break;
            case "App":
                login = true; web = true;
                help.setText("For mobile or desktop app credentials.");
                username.setHint("Username / email (optional)");
                website.setHint("App name (optional)");
                break;
            case "Contact":
                phones = true;
                help.setText("For private contact information. Phone numbers are emphasized.");
                username.setHint("Email (optional)");
                website.setHint("Website / organization (optional)");
                break;
            case "Banking":
                login = true; phones = true; web = true;
                help.setText("For banking records. Use Customer ID / Username for the bank login or customer identifier.");
                username.setHint("Customer ID / username (optional)");
                website.setHint("Bank / service name (optional)");
                break;
            case "Work":
                login = true; phones = true; web = true;
                help.setText("For work accounts, internal systems, contacts and related notes.");
                username.setHint("Work username / email (optional)");
                website.setHint("System / website / app (optional)");
                break;
            case "Personal":
                phones = true; web = true;
                help.setText("For personal information, contacts, websites and notes.");
                username.setHint("Username / email (optional)");
                website.setHint("Website / app / organization (optional)");
                break;
            case "Secure Note":
                help.setText("For encrypted free-form notes. Use Show all optional fields if this note also needs phones, a URL or credentials.");
                username.setHint("Username / email (optional)");
                website.setHint("Website / app name (optional)");
                break;
            default:
                login = true; phones = true; web = true;
                help.setText("Flexible record. All common fields are available.");
                username.setHint("Username / email (optional)");
                website.setHint("Website or app name (optional)");
                break;
        }

        setVisible(loginSection, login);
        setVisible(username, login);
        setVisible(password, login);
        setVisible(phoneSection, phones);
        setVisible(phone1, phones);
        setVisible(phone2, phones);
        setVisible(phone3, phones);
        setVisible(webSection, web);
        setVisible(website, web);
        setVisible(websiteUrl, web);
        notes.setVisibility(View.VISIBLE);

        boolean hasHidden = !(login && phones && web);
        toggle.setVisibility(hasHidden || showAll ? View.VISIBLE : View.GONE);
        toggle.setText(showAll ? "Use category-specific fields" : "Show all optional fields");
    }

    private void setVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private String websiteLabelFor(String category) {
        if ("App".equals(category)) return "App";
        if ("Website".equals(category)) return "Website";
        if ("Banking".equals(category)) return "Bank / Service";
        if ("Work".equals(category)) return "System / Website / App";
        return "Website / App";
    }

    private CategoryOption[] getEditableCategories() {
        List<CategoryOption> options = new ArrayList<>();
        for (String builtIn : BUILT_IN_CATEGORIES) options.add(new CategoryOption(builtIn, builtIn));
        List<CustomCategory> sorted = new ArrayList<>(customCategories);
        sorted.sort((a,b) -> categoryPath(a.name).compareToIgnoreCase(categoryPath(b.name)));
        for (CustomCategory c : sorted) {
            if (!safe(c.name).trim().isEmpty()) options.add(new CategoryOption(c.name.trim(), categoryPath(c.name)));
        }
        return options.toArray(new CategoryOption[0]);
    }

    private CategoryOption[] getFilterCategories() {
        List<CategoryOption> options = new ArrayList<>();
        options.add(new CategoryOption("All", "All categories"));
        for (String builtIn : BUILT_IN_CATEGORIES) options.add(new CategoryOption(builtIn, builtIn));
        List<CustomCategory> sorted = new ArrayList<>(customCategories);
        sorted.sort((a,b) -> categoryPath(a.name).compareToIgnoreCase(categoryPath(b.name)));
        for (CustomCategory c : sorted) {
            if (!safe(c.name).trim().isEmpty()) options.add(new CategoryOption(c.name.trim(), categoryPath(c.name)));
        }
        return options.toArray(new CategoryOption[0]);
    }

    private String selectedCategoryName(Spinner spinner, String fallback) {
        if (spinner == null || spinner.getSelectedItem() == null) return fallback;
        Object selected = spinner.getSelectedItem();
        return selected instanceof CategoryOption ? ((CategoryOption) selected).name : String.valueOf(selected);
    }

    private boolean isCustomCategory(String name) { return findCustomCategory(name) != null; }

    private CustomCategory findCustomCategory(String name) {
        for (CustomCategory c : customCategories) if (safe(c.name).equals(name)) return c;
        return null;
    }

    private void captureCustomValues(Map<String, EditText> editors, Map<String, String> draft) {
        for (Map.Entry<String, EditText> e : editors.entrySet()) draft.put(e.getKey(), e.getValue().getText().toString());
    }

    private void rebuildCustomFieldEditors(String category, TextView section, LinearLayout container,
                                           Map<String, EditText> editors, Map<String, String> draft) {
        container.removeAllViews();
        editors.clear();
        CustomCategory custom = findCustomCategory(category);
        boolean visible = custom != null && !custom.fields.isEmpty();
        section.setVisibility(visible ? View.VISIBLE : View.GONE);
        container.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        for (String fieldName : custom.fields) {
            String name = safe(fieldName).trim();
            if (name.isEmpty()) continue;
            EditText editor = field(name, draft.get(name));
            container.addView(boldLabel(name));
            container.addView(editor);
            editors.put(name, editor);
        }
    }

    private void showCustomCategoriesDialog() {
        LinearLayout body = baseVertical(10);
        body.setPadding(dp(12), dp(12), dp(12), dp(18));

        body.addView(UiStyle.sectionCaption(
                this,
                "Categories are shown as a hierarchy. Subcategories appear directly below their parent. "
                        + "Maximum depth: " + getMaxCategoryDepth()
                        + " of " + HARD_MAX_CATEGORY_DEPTH + "."
        ));

        body.addView(UiStyle.sectionTitle(this, "Category hierarchy"));

        Set<String> rendered = new HashSet<>();

        for (String builtIn : BUILT_IN_CATEGORIES) {
            addBuiltInCategoryTreeRow(body, builtIn);
            addCustomCategoryChildren(body, builtIn, 1, rendered);
        }

        List<CustomCategory> topLevel = new ArrayList<>();
        for (CustomCategory category : customCategories) {
            if (safe(category.parentName).trim().isEmpty()) topLevel.add(category);
        }
        topLevel.sort((a,b) -> safe(a.name).compareToIgnoreCase(safe(b.name)));

        for (CustomCategory category : topLevel) {
            String key = safe(category.name).toLowerCase(Locale.ROOT);
            if (!rendered.add(key)) continue;
            addCustomCategoryTreeRow(body, category, 0);
            addCustomCategoryChildren(body, category.name, 1, rendered);
        }

        List<CustomCategory> leftovers = new ArrayList<>();
        for (CustomCategory category : customCategories) {
            if (!rendered.contains(safe(category.name).toLowerCase(Locale.ROOT))) {
                leftovers.add(category);
            }
        }
        leftovers.sort((a,b) -> categoryPath(a.name).compareToIgnoreCase(categoryPath(b.name)));
        for (CustomCategory category : leftovers) {
            rendered.add(safe(category.name).toLowerCase(Locale.ROOT));
            addCustomCategoryTreeRow(
                    body, category, Math.max(0, categoryDepth(category.name) - 1));
        }

        if (customCategories.isEmpty()) {
            TextView empty = subtitle("No custom categories yet. Create a category or subcategory below.");
            empty.setPadding(dp(4), dp(12), dp(4), dp(12));
            body.addView(empty);
        }

        Button add = primaryButton("+  New category / subcategory");
        add.setContentDescription("Add category or subcategory");
        add.setOnClickListener(v -> {
            AlertDialog parentDialog = findShowingDialogForView(v);
            if (parentDialog != null) parentDialog.dismiss();
            showCustomCategoryEditor(null);
        });

        LinearLayout.LayoutParams addParams = matchWidth();
        addParams.topMargin = dp(14);
        body.addView(add, addParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Categories")
                .setView(wrap(body))
                .setNegativeButton("Close", null)
                .create();

        body.setTag(dialog);
        ScreenSecurityManager.protect(dialog);
        dialog.show();
    }

    private void addBuiltInCategoryTreeRow(LinearLayout body, String name) {
        LinearLayout card = UiStyle.verticalCard(this, 12);

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(getColor(R.color.keepriva_text_primary));
        card.addView(title);

        int count = directChildCount(name);
        card.addView(UiStyle.sectionCaption(
                this,
                count == 0
                        ? "Built-in category"
                        : "Built-in category • " + count
                                + (count == 1 ? " subcategory" : " subcategories")
        ));

        LinearLayout.LayoutParams lp = matchWidth();
        lp.setMargins(0, dp(8), 0, 0);
        body.addView(card, lp);
    }

    private void addCustomCategoryChildren(
            LinearLayout body, String parentName, int depth, Set<String> rendered) {

        List<CustomCategory> children = new ArrayList<>();
        for (CustomCategory category : customCategories) {
            if (safe(category.parentName).equalsIgnoreCase(safe(parentName))) {
                children.add(category);
            }
        }
        children.sort((a,b) -> safe(a.name).compareToIgnoreCase(safe(b.name)));

        for (CustomCategory child : children) {
            String key = safe(child.name).toLowerCase(Locale.ROOT);
            if (!rendered.add(key)) continue;
            addCustomCategoryTreeRow(body, child, depth);
            addCustomCategoryChildren(body, child.name, depth + 1, rendered);
        }
    }

    private void addCustomCategoryTreeRow(
            LinearLayout body, CustomCategory category, int depth) {

        LinearLayout indent = new LinearLayout(this);
        indent.setOrientation(LinearLayout.VERTICAL);
        indent.setPadding(dp(Math.min(depth, HARD_MAX_CATEGORY_DEPTH) * 18), 0, 0, 0);

        LinearLayout card = UiStyle.verticalCard(this, 12);

        TextView title = new TextView(this);
        title.setText(category.name + "  •  " + category.fields.size() + " fields");
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(getColor(R.color.keepriva_text_primary));
        card.addView(title);

        String parentName = safe(category.parentName).trim();
        card.addView(UiStyle.sectionCaption(
                this,
                parentName.isEmpty()
                        ? "Top-level custom category"
                        : "Subcategory of " + parentName
        ));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        Button edit = button("Edit / Move");
        UiStyle.styleCompactButton(edit);
        edit.setOnClickListener(v -> {
            AlertDialog parentDialog = findShowingDialogForView(v);
            if (parentDialog != null) parentDialog.dismiss();
            showCustomCategoryEditor(category);
        });

        Button del = button("Delete");
        UiStyle.styleDangerButton(del);
        del.setOnClickListener(v -> {
            AlertDialog parentDialog = findShowingDialogForView(v);
            if (parentDialog != null) parentDialog.dismiss();
            deleteCustomCategory(category);
        });

        actions.addView(edit);
        LinearLayout.LayoutParams delParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        delParams.leftMargin = dp(8);
        actions.addView(del, delParams);

        card.addView(actions, matchWidth());
        indent.addView(card, matchWidth());

        LinearLayout.LayoutParams lp = matchWidth();
        lp.setMargins(0, dp(8), 0, 0);
        body.addView(indent, lp);
    }
    private void showCustomCategoryEditor(CustomCategory existing) {
        CustomCategory model = existing == null ? new CustomCategory() : existing;
        LinearLayout form = baseVertical(6);
        EditText name = field("Category name", model.name);

        Spinner parent = new Spinner(this);
        CategoryOption[] parentOptions = parentOptionsFor(existing);
        parent.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, parentOptions));
        int parentIndex = 0;
        for (int i = 0; i < parentOptions.length; i++) {
            if (parentOptions[i].name.equals(safe(model.parentName))) { parentIndex = i; break; }
        }
        parent.setSelection(parentIndex);

        EditText fields = field("Field names - one per line (optional for folder categories)", String.join("\n", model.fields));
        fields.setSingleLine(false); fields.setMinLines(7); fields.setGravity(Gravity.TOP);
        EditText sensitiveFields = field("Sensitive field names - one per line (optional)", String.join("\n", model.sensitiveFields));
        sensitiveFields.setSingleLine(false); sensitiveFields.setMinLines(4); sensitiveFields.setGravity(Gravity.TOP);
        form.addView(name);
        form.addView(boldLabel("Parent category / folder"));
        form.addView(parent);
        form.addView(subtitle("Choose where this category belongs. A subcategory appears directly below its parent. Cycles and moves beyond the configured maximum depth are blocked."));
        form.addView(subtitle("Example fields: Account Number, Recovery Email, Security Question, Membership ID"));
        form.addView(fields);
        form.addView(subtitle("Sensitive fields are omitted from normal TXT/HTML/PDF exports unless you explicitly include them and re-authenticate. Names must match the field list above."));
        form.addView(sensitiveFields);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(existing == null ? "New category" : "Edit / move category")
                .setView(wrap(form)).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String categoryName = name.getText().toString().trim();
            String newParent = selectedCategoryName(parent, "");
            if (categoryName.isEmpty()) { name.setError("Category name is required"); return; }
            if (isBuiltInCategory(categoryName)) { name.setError("This is a built-in category name"); return; }
            for (CustomCategory c : customCategories) if (c.id != model.id && c.name.equalsIgnoreCase(categoryName)) {
                name.setError("A category with this name already exists"); return;
            }
            if (existing != null && !newParent.isEmpty() && isDescendantOf(newParent, existing.name)) {
                toast("A category cannot be moved under itself or one of its descendants.");
                return;
            }
            int proposedDepth = newParent.isEmpty() ? 1 : categoryDepth(newParent) + 1;
            int subtree = existing == null ? 1 : subtreeRelativeDepth(existing.name);
            if (proposedDepth + subtree - 1 > getMaxCategoryDepth()) {
                toast("This move would exceed the configured maximum category depth of " + getMaxCategoryDepth() + ".");
                return;
            }
            List<String> parsed = new ArrayList<>();
            for (String line : fields.getText().toString().split("\\r?\\n")) {
                String f = line.trim();
                if (!f.isEmpty() && !parsed.contains(f)) parsed.add(f);
            }
            // A category may be used purely as an organizational parent/folder.
            // Therefore zero custom fields is valid.
            List<String> parsedSensitive = new ArrayList<>();
            for (String line : sensitiveFields.getText().toString().split("\\r?\\n")) {
                String f = line.trim();
                if (!f.isEmpty() && parsed.contains(f) && !parsedSensitive.contains(f)) parsedSensitive.add(f);
            }
            String oldName = safe(model.name);
            model.name = categoryName;
            model.parentName = newParent;
            model.fields = parsed;
            model.sensitiveFields = parsedSensitive;
            try {
                database.saveCustomCategoryAndUpdateReferences(model, oldName, customCategories, allItems, sessionKey);
                d.dismiss(); showVaultScreen();
                toast("Category saved.");
            } catch (Exception e) { toast("Could not save category."); }
        }));
        ScreenSecurityManager.protect(d);
        d.show();
    }

    private boolean isBuiltInCategory(String name) {
        for (String c : BUILT_IN_CATEGORIES) if (c.equalsIgnoreCase(name)) return true;
        return "All".equalsIgnoreCase(name);
    }

    private void deleteCustomCategory(CustomCategory category) {
        int usedCount = 0;
        for (VaultItem item : allItems) if (safe(item.category).equals(category.name)) usedCount++;
        int childCount = directChildCount(category.name);
        final int finalUsedCount = usedCount;
        final int finalChildCount = childCount;

        if (usedCount == 0 && childCount == 0) {
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Delete category?")
                    .setMessage("Delete \"" + categoryPath(category.name) + "\"? It contains no direct items or sub-categories.")
                    .setPositiveButton("Delete", (d, w) -> {
                        database.deleteCustomCategory(category.id);
                        showVaultScreen();
                        toast("Category deleted.");
                    })
                    .setNegativeButton("Cancel", null).create();
            ScreenSecurityManager.protect(dialog);
            dialog.show();
            return;
        }

        String message = "\"" + categoryPath(category.name) + "\" contains "
                + usedCount + (usedCount == 1 ? " direct item" : " direct items") + " and "
                + childCount + (childCount == 1 ? " direct sub-category." : " direct sub-categories.")
                + "\n\nChoose another category. Direct items will move there and direct sub-categories will be re-parented there. Descendant trees and item contents are preserved.";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Move contents before deleting")
                .setMessage(message)
                .setPositiveButton("Choose destination", (d, w) -> showMoveCategoryContentsDialog(category, finalUsedCount, finalChildCount))
                .setNegativeButton("Cancel", null).create();
        ScreenSecurityManager.protect(dialog);
        dialog.show();
    }

    private void showMoveCategoryContentsDialog(CustomCategory sourceCategory, int entryCount, int childCount) {
        List<CategoryOption> destinations = new ArrayList<>();
        for (String builtIn : BUILT_IN_CATEGORIES) {
            if (!isDescendantOf(builtIn, sourceCategory.name)) destinations.add(new CategoryOption(builtIn, builtIn));
        }
        for (CustomCategory custom : customCategories) {
            if (custom.id == sourceCategory.id) continue;
            if (isDescendantOf(custom.name, sourceCategory.name)) continue;
            // Direct children are re-parented under destination; ensure deepest moved child remains valid.
            int deepestDirectChildSubtree = 0;
            for (CustomCategory child : customCategories) {
                if (safe(child.parentName).equals(sourceCategory.name)) {
                    deepestDirectChildSubtree = Math.max(deepestDirectChildSubtree, subtreeRelativeDepth(child.name));
                }
            }
            int destinationDepth = categoryDepth(custom.name);
            if (childCount > 0 && destinationDepth + deepestDirectChildSubtree > getMaxCategoryDepth()) continue;
            destinations.add(new CategoryOption(custom.name, categoryPath(custom.name)));
        }
        destinations.sort((a,b) -> a.label.compareToIgnoreCase(b.label));
        if (destinations.isEmpty()) {
            toast("No safe destination is available within the configured nesting depth.");
            return;
        }

        final CategoryOption[] choices = destinations.toArray(new CategoryOption[0]);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Choose destination")
                .setItems(java.util.Arrays.stream(choices).map(c -> c.label).toArray(String[]::new),
                        (dlg, which) -> confirmMoveContentsAndDelete(sourceCategory, choices[which].name, entryCount, childCount))
                .setNegativeButton("Cancel", null).create();
        ScreenSecurityManager.protect(dialog);
        dialog.show();
    }

    private void confirmMoveContentsAndDelete(CustomCategory sourceCategory, String destinationCategory,
                                              int entryCount, int childCount) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Confirm move and delete")
                .setMessage("Move " + entryCount + (entryCount == 1 ? " direct item" : " direct items")
                        + " and re-parent " + childCount + (childCount == 1 ? " direct sub-category" : " direct sub-categories")
                        + " from \"" + categoryPath(sourceCategory.name) + "\" to \"" + categoryPath(destinationCategory)
                        + "\", then delete only the selected category?")
                .setPositiveButton("Move & delete", (d, w) -> {
                    try {
                        database.moveContentsAndDeleteCustomCategory(
                                allItems, customCategories, sourceCategory.name, destinationCategory,
                                sourceCategory.id, sessionKey);
                        showVaultScreen();
                        toast("Contents moved and category deleted.");
                    } catch (Exception e) {
                        showVaultScreen();
                        toast("Could not move category contents. No partial deletion was committed.");
                    }
                })
                .setNegativeButton("Cancel", null).create();
        ScreenSecurityManager.protect(dialog);
        dialog.show();
    }

    private void exportSelectedCategory() {
        String category = selectedCategoryName(categoryFilter, "All");
        List<VaultItem> selected = new ArrayList<>();
        for (VaultItem item : allItems) if ("All".equals(category) || isDescendantOf(item.category, category)) selected.add(item);
        if (selected.isEmpty()) { toast("There are no entries to export in this category."); return; }
        showExportFormatChooser(selected, "All".equals(category) ? "Keepriva" : category);
    }

    private void showExportFormatChooser(List<VaultItem> items, String suggestedName) {
        LinearLayout box = baseVertical(8);
        TextView warning = subtitle("TXT, HTML, PDF and JSON exports are readable plaintext files. JSON is structured and can be imported back into Keepriva. By default Keepriva omits passwords and custom fields marked sensitive.");
        CheckBox includePasswords = new CheckBox(this);
        includePasswords.setText("Include passwords");
        includePasswords.setChecked(false);
        CheckBox includeSensitive = new CheckBox(this);
        includeSensitive.setText("Include sensitive custom fields");
        includeSensitive.setChecked(false);
        box.addView(warning); box.addView(includePasswords); box.addView(includeSensitive);

        new AlertDialog.Builder(this)
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
                .show();
    }

    private Map<String, java.util.Set<String>> sensitiveCustomFieldsByCategory() {
        Map<String, java.util.Set<String>> result = new LinkedHashMap<>();
        for (CustomCategory c : customCategories) {
            result.put(safe(c.name), new java.util.HashSet<>(c.sensitiveFields));
        }
        return result;
    }

    private void requireMasterPasswordForSensitiveExport(List<VaultItem> items, String suggestedName,
                                                         ExportManager.Options options) {
        EditText password = passwordField("Master password");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Re-authentication required")
                .setMessage("This export can contain passwords or fields you marked sensitive. Enter the master password again before creating the plaintext file.")
                .setView(password)
                .setPositiveButton("Authenticate", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            char[] chars = password.getText().toString().toCharArray();
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                SecretKey verified = prefs.getInt(PREF_CRYPTO_VERSION, 0) >= CRYPTO_VERSION_2
                        ? unlockV2(new String(chars), prefs)
                        : unlockLegacyForVerification(new String(chars), prefs);
                if (verified == null) throw new GeneralSecurityException("Authentication failed");
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
        dialog.show();
    }

    private SecretKey unlockLegacyForVerification(String password, SharedPreferences prefs) throws Exception {
        byte[] salt = Base64.decode(prefs.getString(PREF_LEGACY_SALT, ""), Base64.NO_WRAP);
        try {
            SecretKey key = CryptoManager.deriveLegacyKey(password.toCharArray(), salt);
            String verifier = CryptoManager.decrypt(key, prefs.getString(PREF_LEGACY_VERIFIER, ""));
            if (!LEGACY_VERIFIER_TEXT.equals(verifier)) throw new GeneralSecurityException("Wrong password");
            return key;
        } finally { java.util.Arrays.fill(salt, (byte) 0); }
    }

    private void chooseExportFormat(List<VaultItem> items, String suggestedName, ExportManager.Options options) {
        LinearLayout box = baseVertical(8);

        TextView warning = subtitle(
                options.includePasswords || options.includeSensitiveCustomFields
                        ? "Sensitive values are enabled for this plaintext export. Store the file securely and delete it when no longer needed."
                        : "Safe export: passwords and sensitive custom fields will be omitted.");
        box.addView(warning);

        Button json = primaryButton("Keepriva JSON (.json) — re-importable");
        Button txt = button("Formatted text (.txt)");
        Button html = button("HTML page (.html)");
        Button pdf = button("PDF document (.pdf)");

        box.addView(json, matchWidth());
        box.addView(txt, matchWidth());
        box.addView(html, matchWidth());
        box.addView(pdf, matchWidth());

        AlertDialog dialog = new AlertDialog.Builder(this)
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
        dialog.show();
    }

    private void prepareExport(List<VaultItem> items, String suggestedName, int format, ExportManager.Options options) {
        try {
            String base = sanitizeFileName(suggestedName);
            String filename;

            if (format == 0) {
                pendingExportBytes = ExportManager.toText(items, options).getBytes(StandardCharsets.UTF_8);
                pendingExportMime = "text/plain";
                filename = base + ".txt";
            } else if (format == 1) {
                pendingExportBytes = ExportManager.toHtml(items, options).getBytes(StandardCharsets.UTF_8);
                pendingExportMime = "text/html";
                filename = base + ".html";
            } else if (format == 2) {
                pendingExportBytes = ExportManager.toPdf(items, options);
                pendingExportMime = "application/pdf";
                filename = base + ".pdf";
            } else {
                pendingExportBytes = ExportManager
                        .toImportCompatibleJson(items, options, customCategories)
                        .getBytes(StandardCharsets.UTF_8);
                pendingExportMime = "application/json";
                filename = base + ".json";
            }

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(pendingExportMime);
            intent.putExtra(Intent.EXTRA_TITLE, filename);
            beginSystemPicker();
            startActivityForResult(intent, EXPORT_REQUEST);
        } catch (Exception e) {
            toast("Could not prepare export: " + e.getMessage());
        }
    }

    private void beginSystemPicker() {
        systemPickerInProgress = true;
        systemPickerStartedAt = System.currentTimeMillis();
    }

    private void clearPendingExportData() {
        if (pendingExportBytes != null) java.util.Arrays.fill(pendingExportBytes, (byte) 0);
        pendingExportBytes = null;
        pendingExportMime = null;
    }

    private void clearPendingTemplateData() {
        if (pendingTemplateBytes != null) java.util.Arrays.fill(pendingTemplateBytes, (byte) 0);
        pendingTemplateBytes = null;
    }

    private void clearPendingBackupData() {
        if (pendingBackupBytes != null) java.util.Arrays.fill(pendingBackupBytes, (byte) 0);
        pendingBackupBytes = null;
    }

    private boolean shouldLockAfterPicker() {
        if (sessionKey == null || systemPickerStartedAt <= 0L) return false;
        long timeout = getAutoLockMs();
        long elapsed = System.currentTimeMillis() - systemPickerStartedAt;
        return timeout == AUTO_LOCK_IMMEDIATELY || elapsed >= timeout;
    }
    private void showBackupRestoreDialog() {
        LinearLayout box = baseVertical(8);
        box.addView(subtitle("Backups use a separate password and are portable to another phone. Keep the backup password safe: it is not recoverable by the app."));

        Button create = button("Create encrypted .pvault backup");
        Button restore = button("Restore encrypted .pvault backup");
        box.addView(create, matchWidth());
        box.addView(restore, matchWidth());

        AlertDialog dialog = new AlertDialog.Builder(this)
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
        dialog.show();
    }

    private void promptCreateBackupPassword() {
        LinearLayout box = baseVertical(8);
        box.addView(subtitle("Use a password different from your phone unlock. The .pvault file contains an authenticated encrypted snapshot of entries and custom categories."));
        EditText pass = passwordField("Backup password (10+ characters)");
        EditText confirm = passwordField("Confirm backup password");
        box.addView(pass);
        box.addView(confirm);

        AlertDialog dialog = new AlertDialog.Builder(this)
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
        dialog.show();
    }

    private void createEncryptedBackup(char[] backupPassword) {
        try {
            // Reload from the database so the backup is a complete authoritative snapshot.
            List<CustomCategory> categories = database.listCustomCategories(sessionKey);
            List<VaultItem> items = database.list(sessionKey);
            pendingBackupBytes = BackupManager.createBackup(
                    categories, items, backupPassword, database.currentSchemaVersion());
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, "Keepriva-backup.pvault");
            beginSystemPicker();
            startActivityForResult(intent, BACKUP_EXPORT_REQUEST);
        } catch (Exception e) {
            pendingBackupBytes = null;
            toast("Could not create backup: " + e.getMessage());
        }
    }

    private void chooseBackupForRestore() {
        new AlertDialog.Builder(this)
                .setTitle("Restore encrypted backup")
                .setMessage("Restore replaces the current vault entries and custom categories only after the selected backup has been decrypted and validated. Your current master password and biometric settings are kept.")
                .setPositiveButton("Choose .pvault file", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    systemPickerInProgress = true;
                    startActivityForResult(intent, BACKUP_RESTORE_REQUEST);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptRestorePassword(byte[] backupBytes) {
        EditText pass = passwordField("Backup password");
        AlertDialog dialog = new AlertDialog.Builder(this)
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
        dialog.setOnCancelListener(d -> java.util.Arrays.fill(backupBytes, (byte) 0));

        ScreenSecurityManager.protect(dialog);
        dialog.show();
    }

    private void showRestorePreview(BackupManager.RestoredBackup restored) {
        if (restored.sourceSchemaVersion > database.currentSchemaVersion()) {
            new AlertDialog.Builder(this)
                    .setTitle("Backup is newer than this app")
                    .setMessage("This backup was created from vault schema " + restored.sourceSchemaVersion
                            + ", but this app supports schema " + database.currentSchemaVersion()
                            + ". Update Keepriva before restoring it.")
                    .setPositiveButton("Close", null).show();
            return;
        }
        String hierarchyError = validateCategoryHierarchy(restored.categories, getMaxCategoryDepth());
        if (hierarchyError != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Backup category hierarchy cannot be restored")
                    .setMessage(hierarchyError + "\n\nOpen Preferences and increase the category nesting depth if appropriate, then retry restore.")
                    .setPositiveButton("Close", null).show();
            return;
        }
        String message = "Backup entries: " + restored.items.size()
                + "\nCustom categories: " + restored.categories.size()
                + "\nSource schema version: " + restored.sourceSchemaVersion
                + "\n\nRestoring will REPLACE the current entries and custom categories."
                + "\n\nThe operation is transactional: if a write fails, the existing vault remains intact.";
        new AlertDialog.Builder(this)
                .setTitle("Restore preview")
                .setMessage(message)
                .setPositiveButton("Replace current vault", (d,w) -> {
                    try {
                        database.replaceAllFromBackup(restored.categories, restored.items, sessionKey);
                        showVaultScreen();
                        toast("Encrypted backup restored successfully.");
                    } catch (Exception e) {
                        toast("Restore failed; current vault was not partially replaced: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showImportDialog() {
        LinearLayout box = baseVertical(10);

        box.addView(UiStyle.sectionTitle(this, "Bulk import from JSON"));
        box.addView(UiStyle.sectionCaption(
                this,
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
                this, R.color.keepriva_surface_soft, R.color.keepriva_outline, 12));
        box.addView(steps, matchWidth());

        Button download = button("Step 1 — Save JSON import template");
        Button importFile = primaryButton("Step 2 — Import completed JSON template");

        LinearLayout.LayoutParams first = matchWidth();
        first.topMargin = dp(10);
        box.addView(download, first);

        LinearLayout.LayoutParams second = matchWidth();
        second.topMargin = dp(8);
        box.addView(importFile, second);

        AlertDialog dialog = new AlertDialog.Builder(this)
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
        dialog.show();
    }

    private void downloadImportTemplate() {
        try {
            pendingTemplateBytes = ImportManager.buildTemplate().getBytes(StandardCharsets.UTF_8);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "Keepriva-import-template.json");
            beginSystemPicker();
            startActivityForResult(intent, TEMPLATE_EXPORT_REQUEST);
        } catch (Exception e) { toast("Could not create import template: " + e.getMessage()); }
    }

    private void chooseImportTemplate() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        systemPickerInProgress = true;
        startActivityForResult(intent, IMPORT_REQUEST);
    }

    private byte[] readBytes(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
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
        ImportManager.ParsedImport parsed = ImportManager.parse(json, customCategories, BUILT_IN_CATEGORIES);
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
        List<CustomCategory> hierarchyPreview = new ArrayList<>(customCategories);
        hierarchyPreview.addAll(parsed.categoriesToCreate);
        String hierarchyError = validateCategoryHierarchy(hierarchyPreview, getMaxCategoryDepth());
        if (hierarchyError != null) parsed.errors.add(hierarchyError);

        if (!parsed.errors.isEmpty()) {
            message.append("\n\nErrors:");
            int limit = Math.min(parsed.errors.size(), 8);
            for (int i = 0; i < limit; i++) message.append("\n• ").append(parsed.errors.get(i));
            if (parsed.errors.size() > limit) message.append("\n• … and ").append(parsed.errors.size() - limit).append(" more");
            new AlertDialog.Builder(this).setTitle("Import validation failed")
                    .setMessage(message.toString()).setPositiveButton("Close", null).show();
            return;
        }
        if (parsed.items.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Nothing to import")
                    .setMessage(message.toString()).setPositiveButton("Close", null).show();
            return;
        }
        message.append("\n\nOn import, all entry fields—including fields marked sensitive—are encrypted before being written to the local vault database.");
        new AlertDialog.Builder(this).setTitle("Import preview")
                .setMessage(message.toString())
                .setPositiveButton("Import", (d, w) -> commitImport(parsed))
                .setNegativeButton("Cancel", null).show();
    }

    private void commitImport(ImportManager.ParsedImport parsed) {
        try {
            database.importBatch(parsed.categoriesToCreate, parsed.items, sessionKey);
            showVaultScreen();
            toast("Imported " + parsed.items.size() + (parsed.items.size() == 1 ? " entry." : " entries."));
        } catch (Exception e) {
            toast("Import failed: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        boolean lockAfterPicker = shouldLockAfterPicker();
        systemPickerInProgress = false;
        systemPickerStartedAt = 0L;
        backgroundAt = 0L;

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == EXPORT_REQUEST) clearPendingExportData();
            else if (requestCode == TEMPLATE_EXPORT_REQUEST) clearPendingTemplateData();
            else if (requestCode == BACKUP_EXPORT_REQUEST) clearPendingBackupData();

            if (lockAfterPicker) lockVault();
            return;
        }

        Uri uri = data.getData();

        if (requestCode == EXPORT_REQUEST) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Cannot open selected file");
                if (pendingExportBytes == null) throw new IllegalStateException("Export data is unavailable");
                out.write(pendingExportBytes);
                out.flush();
                toast("Export saved.");
            } catch (Exception e) {
                toast("Export failed: " + e.getMessage());
            } finally {
                clearPendingExportData();
            }
        } else if (requestCode == TEMPLATE_EXPORT_REQUEST) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Cannot open selected file");
                if (pendingTemplateBytes == null) throw new IllegalStateException("Template data is unavailable");
                out.write(pendingTemplateBytes);
                out.flush();
                toast("Import template saved.");
            } catch (Exception e) {
                toast("Template save failed: " + e.getMessage());
            } finally {
                clearPendingTemplateData();
            }
        } else if (requestCode == IMPORT_REQUEST) {
            try {
                previewImport(readUtf8(uri));
            } catch (Exception e) {
                toast("Could not read import file: " + e.getMessage());
            }
        } else if (requestCode == BACKUP_EXPORT_REQUEST) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Cannot open selected file");
                if (pendingBackupBytes == null) throw new IllegalStateException("Backup data is unavailable");
                out.write(pendingBackupBytes);
                out.flush();
                toast("Encrypted .pvault backup saved.");
            } catch (Exception e) {
                toast("Backup save failed: " + e.getMessage());
            } finally {
                clearPendingBackupData();
            }
        } else if (requestCode == BACKUP_RESTORE_REQUEST) {
            try {
                promptRestorePassword(readBytes(uri));
            } catch (Exception e) {
                toast("Could not read backup file: " + e.getMessage());
            }
        }

        if (lockAfterPicker && requestCode != BACKUP_RESTORE_REQUEST) {
            lockVault();
        }
    }
    private String sanitizeFileName(String value) {
        String s = safe(value).trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return s.isEmpty() ? "Keepriva-export" : s;
    }

    private EditText phoneField(String hint, String value) {
        EditText e = field(hint, value);
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        return e;
    }

    private LinearLayout baseVertical(int gapDp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_NONE);
        l.setBackgroundColor(getColor(R.color.keepriva_background));
        return l;
    }

    private ScrollView wrap(View child) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setBackgroundColor(getColor(R.color.keepriva_background));
        s.addView(child);
        return s;
    }

    private TextView title(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(26);
        v.setPadding(0, dp(8), 0, dp(12));
        UiStyle.styleTitle(v);
        return v;
    }

    private TextView subtitle(String text) {
        TextView v = new TextView(this);
        v.setText(text == null || text.isEmpty() ? "—" : text);
        v.setTextSize(15);
        v.setPadding(0, dp(2), 0, dp(10));
        UiStyle.styleBodyText(v);
        return v;
    }

    private TextView boldLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(14);
        v.setPadding(0, dp(8), 0, 0);
        UiStyle.styleLabel(v);
        return v;
    }

    private void addLabelValue(LinearLayout body, String label, String value) {
        body.addView(boldLabel(label)); body.addView(subtitle(value));
    }

    private void addNonEmptyLabelValue(LinearLayout body, String label, String value) {
        if (value != null && !value.trim().isEmpty()) addLabelValue(body, label, value);
    }
    private void addSensitiveCustomField(LinearLayout body, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;

        body.addView(boldLabel(label + " (sensitive)"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView display = new TextView(this);
        display.setText("••••••••••••");
        display.setTextSize(17);
        display.setPadding(0, dp(4), dp(8), dp(8));
        UiStyle.styleBodyText(display);
        row.addView(display, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final boolean[] visible = {false};
        Button show = button("Show");
        show.setOnClickListener(v -> {
            visible[0] = !visible[0];
            display.setText(visible[0] ? value : "••••••••••••");
            show.setText(visible[0] ? "Hide" : "Show");
        });
        row.addView(show);

        Button copy = button("Copy");
        copy.setContentDescription("Copy sensitive field securely");
        copy.setOnClickListener(v -> copySensitiveCustomFieldToClipboard(value));
        row.addView(copy);

        body.addView(row);
    }

    private void copySensitiveCustomFieldToClipboard(String value) {
        if (value == null || value.isEmpty()) return;
        long timeout = getClipboardTimeoutMs();
        clipboardSecurity.copySensitive("Keepriva sensitive field", value, timeout);
        toast(timeout == ClipboardSecurityManager.NEVER_CLEAR
                ? "Sensitive field copied. Clipboard auto-clear is disabled."
                : "Sensitive field copied. It will be cleared in " + (timeout / 1000L) + " seconds.");
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextSize(16);
        UiStyle.styleInput(e);
        LinearLayout.LayoutParams lp = matchWidth();
        lp.setMargins(0, dp(4), 0, dp(8));
        e.setLayoutParams(lp);
        return e;
    }

    private EditText passwordField(String hint) {
        EditText e = field(hint, "");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        UiStyle.styleSecondaryButton(b);
        return b;
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        UiStyle.stylePrimaryButton(b);
        return b;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private AlertDialog findShowingDialogForView(View view) {
        View current = view;
        while (current != null) {
            Object tag = current.getTag();
            if (tag instanceof AlertDialog) {
                AlertDialog dialog = (AlertDialog) tag;
                if (dialog.isShowing()) return dialog;
            }
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private static String safe(String s) { return s == null ? "" : s; }
}







