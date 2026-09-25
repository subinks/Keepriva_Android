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
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
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

public class MainActivity extends Activity implements
        SetupController.Gateway,
        SetupActions,
        UnlockController.Gateway,
        UnlockActions,
        SecuritySettingsController.Gateway,
        SecuritySettingsActions,
        LegacyVaultBrowserController.DataSource,
        VaultBrowserActions {
    private static final String PREFS = "vault_config";
    private static final String PREF_HIDDEN_BUILT_IN_CATEGORIES = "hidden_built_in_categories";
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

    private final VaultSessionCoordinator vaultSession = new VaultSessionCoordinator();
    private final CategoryHierarchyService categoryHierarchy =
            new CategoryHierarchyService(BUILT_IN_CATEGORIES);
    private final CallbackGeneration callbackGeneration = new CallbackGeneration();
    private final ControllerRegistry controllerRegistry = new ControllerRegistry();
    private final DialogRegistry dialogRegistry = controllerRegistry.register(new DialogRegistry());
    private VaultDatabase database;
    private VaultRootRenderer rootRenderer;
    private VaultViewFactory viewFactory;
    private VaultSecurityPreferences securityPreferences;
    private SetupController setupController;
    private UnlockController unlockController;
    private SecuritySettingsController securitySettingsController;
    private LegacyVaultBrowserController browserController;
    private final VaultScreenRouter screenRouter = new VaultScreenRouter();
    private String selectedHomeCategory = "All";
    private String pendingNewItemCategory = null;
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
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) handleScreenOffLock();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenSecurityManager.protect(this);
        viewFactory = new VaultViewFactory(this);
        FrameLayout rootContent = new FrameLayout(this);
        rootContent.setContentDescription("Keepriva root content");
        setContentView(rootContent);
        rootRenderer = new VaultRootRenderer(rootContent);
        ReleaseSecurityManager.Result releaseSecurity = ReleaseSecurityManager.verify(this);
        if (!releaseSecurity.ok) {
            showReleaseSecurityBlock(releaseSecurity.message);
            return;
        }
        securityPreferences = new VaultSecurityPreferences(getSharedPreferences(PREFS, MODE_PRIVATE));
        setupController = controllerRegistry.register(new SetupController(viewFactory, this, this));
        unlockController = controllerRegistry.register(new UnlockController(this, viewFactory, this, this));
        securitySettingsController = controllerRegistry.register(new SecuritySettingsController(
                this, viewFactory, dialogRegistry, securityPreferences, this, this));
        browserController = controllerRegistry.register(
                new LegacyVaultBrowserController(this, viewFactory, this, this));
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
        if (vaultSession.isUnlocked()) backgroundAt = System.currentTimeMillis();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (vaultSession.isUnlocked() && backgroundAt > 0 && !systemPickerInProgress) {
            long timeout = getAutoLockMs();
            if (timeout == VaultSecurityPreferences.AUTO_LOCK_IMMEDIATELY
                    || System.currentTimeMillis() - backgroundAt >= timeout) {
                lockVault();
            }
        }
    }

    @Override
    protected void onDestroy() {
        callbackGeneration.invalidate();
        controllerRegistry.close();
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
        VaultNavigationState state = VaultNavigationState.root(VaultScreen.ERROR);
        screenRouter.reset(state);
        renderRootScreen(wrap(root), state);
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
        VaultNavigationState state = VaultNavigationState.root(VaultScreen.SETUP);
        screenRouter.reset(state);
        renderRootScreen(setupController.createView(), state);
    }

    private void showUnlockScreen() {
        vaultSession.clear();
        explicitlyLocked = true;
        VaultNavigationState state = VaultNavigationState.root(VaultScreen.UNLOCK);
        screenRouter.reset(state);
        renderRootScreen(unlockController.createView(isBiometricUnlockConfigured()), state);
    }
    @Override
    public void initializeVault(String password) throws Exception {
        initializeV2Vault(password);
    }

    @Override
    public void onSetupCompleted() {
        explicitlyLocked = false;
        showVaultScreen();
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
            vaultSession.unlock(vaultKey);
            provisionAuthenticationBoundDeviceKey();
        } finally {
            java.util.Arrays.fill(salt, (byte) 0);
        }
    }

    @Override
    public void requestPasswordUnlock(String password, UnlockController.Attempt attempt) {
        unlockInBackground(password, attempt);
    }

    @Override
    public void requestBiometricUnlock() {
        unlockWithBiometric();
    }

    private void unlockInBackground(String password, UnlockController.Attempt attempt) {
        CallbackGeneration.Token callbackToken = callbackGeneration.capture();
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
                if (!callbackGeneration.isCurrent(callbackToken) || isFinishing() || isDestroyed()) return;

                if (error == null && result != null) {
                    vaultSession.unlock(result);
                    unlockController.completeSuccess(attempt);
                    return;
                }

                vaultSession.clear();
                unlockController.completeFailure(attempt);
            });
        });
    }

    @Override
    public void onUnlockCompleted() {
        explicitlyLocked = false;
        backgroundAt = 0;
        showVaultScreen();
    }

    @Override
    public void onUnlockCancelled() {
        // The current UI stays on the unlock screen when authentication is cancelled.
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

    @Override
    public boolean isBiometricConfigured() {
        return isBiometricUnlockConfigured();
    }

    @Override
    public void enableBiometricUnlock() {
        if (!vaultSession.isUnlocked()) return;
        CallbackGeneration.Token callbackToken = callbackGeneration.capture();
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
                            if (!callbackGeneration.isCurrent(callbackToken)
                                    || isFinishing() || isDestroyed()) return;
                            try {
                                Cipher authorized = requireCipher(result);
                                byte[] rawVaultKey = vaultSession.requireKey().getEncoded();
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
                            if (!callbackGeneration.isCurrent(callbackToken)
                                    || isFinishing() || isDestroyed()) return;
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
        CallbackGeneration.Token callbackToken = callbackGeneration.capture();
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
                            if (!callbackGeneration.isCurrent(callbackToken)
                                    || isFinishing() || isDestroyed()) return;
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
                                vaultSession.unlock(candidate);
                                unlockController.completeBiometricSuccess();
                            } catch (Exception e) {
                                vaultSession.clear();
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
                            if (!callbackGeneration.isCurrent(callbackToken)
                                    || isFinishing() || isDestroyed()) return;
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

    @Override
    public void disableBiometricUnlock() {
        clearBiometricState(true);
        toast("Biometric unlock disabled. Use your master password to unlock.");
        if (vaultSession.isUnlocked()) showVaultScreen();
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
        return securityPreferences.autoLockMs();
    }

    private boolean getLockOnScreenOff() {
        return securityPreferences.lockOnScreenOff();
    }

    private int getMaxCategoryDepth() {
        return securityPreferences.maxCategoryDepth();
    }

    /** Root categories have depth 1. Unknown/broken references fail conservatively at max+1. */
    private int categoryDepth(String categoryName) {
        return categoryHierarchy.depth(categoryName, customCategories);
    }

    @Override
    public int deepestCategoryDepth() {
        return categoryHierarchy.deepestDepth(customCategories);
    }

    private String categoryPath(String categoryName) {
        return categoryHierarchy.path(categoryName, customCategories);
    }

    private boolean isDescendantOf(String candidateName, String ancestorName) {
        return categoryHierarchy.isDescendantOf(candidateName, ancestorName, customCategories);
    }

    private boolean moveFitsDepth(String categoryName, String newParentName) {
        return categoryHierarchy.moveFitsDepth(
                categoryName, newParentName, getMaxCategoryDepth(), customCategories);
    }

    private int subtreeRelativeDepth(String categoryName) {
        return categoryHierarchy.subtreeRelativeDepth(categoryName, customCategories);
    }

    /** Returns null when valid, otherwise a user-facing hierarchy validation error. */
    private String validateCategoryHierarchy(List<CustomCategory> categories, int maxDepth) {
        return categoryHierarchy.validate(categories, maxDepth);
    }

    private void showPreferencesDialog() {
        securitySettingsController.showPreferences();
    }

    private void requestMasterPasswordReauth(String purpose, Runnable onSuccess) {
        securitySettingsController.requestMasterPasswordReauth(purpose, onSuccess);
    }

    private void showSecuritySettings() {
        securitySettingsController.showSecuritySettings();
    }

    private long getClipboardTimeoutMs() {
        return securityPreferences.clipboardTimeoutMs();
    }

    @Override
    public boolean isSessionUnlocked() {
        return vaultSession.isUnlocked();
    }

    @Override
    public boolean verifyMasterPassword(String password) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            SecretKey verified = prefs.getInt(PREF_CRYPTO_VERSION, 0) >= CRYPTO_VERSION_2
                    ? unlockV2(password, prefs)
                    : unlockLegacyAndMigrate(password, prefs);
            return verified != null;
        } catch (Exception error) {
            return false;
        }
    }

    @Override
    public boolean changeMasterPassword(String currentPassword, String newPassword) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            SecretKey verifiedVaultKey = unlockV2(currentPassword, prefs);
            if (!java.security.MessageDigest.isEqual(
                    verifiedVaultKey.getEncoded(), vaultSession.requireKey().getEncoded())) {
                throw new GeneralSecurityException("Current password did not unlock this session");
            }
            byte[] newSalt = CryptoManager.randomBytes(32);
            try {
                SecretKey newMasterKey = CryptoManager.deriveMasterKey(newPassword.toCharArray(), newSalt);
                String wrapped = CryptoManager.wrapVaultKey(newMasterKey, vaultSession.requireKey());
                String verifier = CryptoManager.encrypt(vaultSession.requireKey(), VERIFIER_TEXT);
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
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    @Override
    public void showMessage(String message) {
        toast(message);
    }

    @Override
    public void onSecuritySettingsClosed() {
        if (vaultSession.isUnlocked()) showVaultScreen();
    }

    @Override
    public void onLockRequested() {
        lockVault();
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
        callbackGeneration.invalidate();
        dialogRegistry.dismissAll();
        screenRouter.clear();
        vaultSession.clear();
        allItems.clear();
        customCategories.clear();
        if (browserController != null) browserController.clearSessionState();
        selectedHomeCategory = "All";
        pendingNewItemCategory = null;
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

        for (String builtIn : activeBuiltInCategories()) {
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
        if (!vaultSession.isUnlocked()) {
            showUnlockScreen();
            return;
        }

        showVaultLoadingState();
        try {
            customCategories = database.listCustomCategories(vaultSession.requireKey());
            allItems = database.list(vaultSession.requireKey());
        } catch (Exception error) {
            showVaultLoadError();
            return;
        }

        VaultNavigationState browserState = VaultNavigationState.vaultBrowser(
                selectedHomeCategory, "", 0);
        screenRouter.reset(browserState);
        renderRootScreen(browserController.createView(), browserState);
    }

    @Override
    public VaultBrowserModel browserModel(String query) {
        return VaultBrowserModelBuilder.build(
                query, allItems, customCategories, activeBuiltInCategories());
    }

    @Override
    public void onCategorySelected(String categoryName) {
        selectedHomeCategory = safe(categoryName).isEmpty() ? "All" : categoryName;
    }

    @Override
    public void onItemSelected(long itemId) {
        VaultItem item = findItemById(itemId);
        if (item != null) showDetails(item);
    }

    @Override
    public void onAddEntryRequested(String categoryName) {
        pendingNewItemCategory = safe(categoryName).isEmpty() ? "Login" : categoryName;
        showEditDialog(null);
    }

    @Override
    public void onAddSubcategoryRequested(String parentCategoryName) {
        showCustomCategoryEditor(null, safe(parentCategoryName));
    }

    @Override
    public void onManageCategoriesRequested() {
        showCustomCategoriesDialog();
    }

    @Override
    public void onImportRequested() {
        showImportDialog();
    }

    @Override
    public void onExportRequested(String categoryName) {
        selectedHomeCategory = safe(categoryName).isEmpty() ? "All" : categoryName;
        exportSelectedCategory();
    }

    @Override
    public void onBackupRestoreRequested() {
        showBackupRestoreDialog();
    }

    @Override
    public void onPreferencesRequested() {
        showPreferencesDialog();
    }

    @Override
    public void onSecurityRequested() {
        requestMasterPasswordReauth("Security settings", this::showSecuritySettings);
    }

    @Override
    public void onBrowserNavigationChanged(
            String categoryName, String query, int scrollPosition) {
        selectedHomeCategory = safe(categoryName).isEmpty() ? "All" : categoryName;
        VaultNavigationState current = screenRouter.currentState();
        if (current == null || current.screen() != VaultScreen.VAULT_BROWSER) return;
        screenRouter.replaceCurrent(VaultNavigationState.vaultBrowser(
                selectedHomeCategory, safe(query), Math.max(0, scrollPosition)));
    }

    private VaultItem findItemById(long itemId) {
        for (VaultItem item : allItems) {
            if (item.id == itemId) return item;
        }
        return null;
    }

    private void loadItems() {
        try {
            allItems = database.list(vaultSession.requireKey());
            browserController.refresh();
        } catch (Exception error) {
            toast("Could not decrypt vault. Locking for safety.");
            lockVault();
        }
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

        ImageButton exportEntry = smallIconButton(R.drawable.ic_keepriva_export,
                "Export entry", false);
        exportEntry.setOnClickListener(v -> showExportFormatChooser(Collections.singletonList(item), item.title));
        body.addView(exportEntry);

        if (!safe(item.password).isEmpty()) {
            TextView label = boldLabel("Password");
            body.addView(label);
            LinearLayout pwRow = new LinearLayout(this);
            pwRow.setOrientation(LinearLayout.HORIZONTAL);
            TextView pw = new TextView(this);
            pw.setText("••••••••••••");
            pw.setContentDescription("Masked password");
            pw.setTextSize(17);
            pw.setPadding(0, dp(4), dp(8), dp(8));
            pwRow.addView(pw, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            ImageButton show = smallIconButton(R.drawable.ic_keepriva_visibility,
                    "Show password", false);
            final boolean[] visible = {false};
            show.setOnClickListener(v -> {
                visible[0] = !visible[0];
                pw.setText(visible[0] ? item.password : "••••••••••••");
                pw.setContentDescription(visible[0] ? "Visible password" : "Masked password");
                show.setImageResource(visible[0] ? R.drawable.ic_keepriva_visibility_off : R.drawable.ic_keepriva_visibility);
                show.setContentDescription(visible[0] ? "Hide password" : "Show password");
            });
            pwRow.addView(show);
            ImageButton copy = smallIconButton(R.drawable.ic_keepriva_copy,
                    "Copy password securely", false);
            copy.setOnClickListener(v -> copyPasswordToClipboard(item.password));
            pwRow.addView(copy);
            body.addView(pwRow);
        }
        addNonEmptyLabelValue(body, "Notes", item.notes);

        LinearLayout detailActions = new LinearLayout(this);
        detailActions.setOrientation(LinearLayout.HORIZONTAL);
        detailActions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        detailActions.setPadding(0, dp(10), 0, 0);

        ImageButton editItem = smallIconButton(R.drawable.ic_keepriva_edit, "Edit item", false);
        ImageButton deleteItem = smallIconButton(R.drawable.ic_keepriva_delete, "Delete item", true);
        ImageButton closeDetails = smallIconButton(R.drawable.ic_keepriva_close, "Close item details", false);
        detailActions.addView(editItem);
        LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(dp(40), dp(40)); dp1.leftMargin = dp(8); detailActions.addView(deleteItem, dp1);
        LinearLayout.LayoutParams cp1 = new LinearLayout.LayoutParams(dp(40), dp(40)); cp1.leftMargin = dp(8); detailActions.addView(closeDetails, cp1);
        body.addView(detailActions, matchWidth());

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(item.title.isEmpty() ? "Vault item" : item.title)
                .setView(wrap(body))
                .create();
        editItem.setOnClickListener(v -> { d.dismiss(); showEditDialog(item); });
        deleteItem.setOnClickListener(v -> confirmDelete(item, d));
        closeDetails.setOnClickListener(v -> d.dismiss());
        ScreenSecurityManager.protect(d);
        showDialog(d);
    }

    private void confirmDelete(VaultItem item, AlertDialog parent) {
        final VaultItem deletedSnapshot = copyVaultItem(item);
        showDialog(new AlertDialog.Builder(this)
                .setTitle("Delete item?")
                .setMessage("Delete \"" + safe(item.title) + "\" from the vault? You can undo this deletion immediately afterward.")
                .setPositiveButton("Delete", (d, w) -> {
                    database.delete(item.id);
                    parent.dismiss();
                    loadItems();
                    showUndoDeletedItem(deletedSnapshot);
                })
                .setNegativeButton("Cancel", null).create());
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
        showDialog(new AlertDialog.Builder(this)
                .setTitle("Item deleted")
                .setMessage("\"" + safe(deletedSnapshot.title) + "\" was deleted.")
                .setPositiveButton("Undo", (d, w) -> {
                    try {
                        database.save(deletedSnapshot, vaultSession.requireKey());
                        loadItems();
                        toast("Item restored.");
                    } catch (Exception e) {
                        toast("Could not restore the deleted item.");
                    }
                })
                .setNegativeButton("Dismiss", null)
                .create());
    }

    private void showEditDialog(VaultItem existing) {
        VaultItem item = existing == null ? new VaultItem() : existing;
        LinearLayout form = baseVertical(6);
        EditText title = field("Title", item.title);
        title.setContentDescription("Item title");
        Spinner category = new Spinner(this);
        CategoryOption[] editableCats = getEditableCategories();
        category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, editableCats));
        int idx = 0;
        String requestedCategory = existing == null && pendingNewItemCategory != null
                ? pendingNewItemCategory
                : item.category;
        for (int i = 0; i < editableCats.length; i++) {
            if (editableCats[i].name.equals(requestedCategory)) idx = i;
        }
        pendingNewItemCategory = null;
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
                database.save(item, vaultSession.requireKey());
                d.dismiss();
                selectedHomeCategory = item.category;
                browserController.selectCategory(item.category, true);
                loadItems();
            } catch (Exception e) {
                toast("Could not save encrypted item.");
            }
        });

        ScreenSecurityManager.protect(d);
        showDialog(d);
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
        for (String builtIn : activeBuiltInCategories()) options.add(new CategoryOption(builtIn, builtIn));
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
        for (String builtIn : activeBuiltInCategories()) options.add(new CategoryOption(builtIn, builtIn));
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
        LinearLayout root = baseVertical(4);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));

        final AlertDialog[] holder = new AlertDialog[1];

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = UiStyle.sectionTitle(this, "Manage Categories");
        top.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageButton close = smallIconButton(
                R.drawable.ic_keepriva_close, "Close category manager", false);
        close.setTooltipText("Close category manager");
        top.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        root.addView(top, matchWidth());

        LinearLayout hierarchyHeader = new LinearLayout(this);
        hierarchyHeader.setOrientation(LinearLayout.HORIZONTAL);
        hierarchyHeader.setGravity(Gravity.CENTER_VERTICAL);
        hierarchyHeader.setPadding(0, dp(4), 0, dp(4));

        ImageButton add = smallIconButton(
                R.drawable.ic_keepriva_add, "Add category", false);
        add.setTooltipText("Add category or subcategory");
        hierarchyHeader.addView(add, new LinearLayout.LayoutParams(dp(36), dp(36)));

        TextView heading = boldLabel("Category Hierarchy");
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        headingParams.leftMargin = dp(8);
        hierarchyHeader.addView(heading, headingParams);
        root.addView(hierarchyHeader, matchWidth());

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (String builtIn : activeBuiltInCategories()) {
            addBuiltInCategoryTreeRow(rows, builtIn);
        }

        List<CustomCategory> sorted = new ArrayList<>(customCategories);
        sorted.sort((a, b) -> categoryPath(a.name).compareToIgnoreCase(categoryPath(b.name)));
        for (CustomCategory category : sorted) {
            addCustomCategoryTreeRow(rows, category);
        }
        if (rows.getChildCount() == 0) {
            TextView empty = subtitle("No categories available. Use + to create one.");
            empty.setContentDescription("Empty category hierarchy");
            rows.addView(empty);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(root)
                .create();
        holder[0] = dialog;
        root.setTag(dialog);
        close.setOnClickListener(v -> dialog.dismiss());
        add.setOnClickListener(v -> {
            dialog.dismiss();
            showCustomCategoryEditor(null);
        });
        ScreenSecurityManager.protect(dialog);
        dialog.setOnShowListener(ignored -> {
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.88f));
            }
        });
        showDialog(dialog);
    }

    private LinearLayout compactCategoryRow(String name, int depth) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6 + Math.max(0, depth - 1) * 14), dp(3), dp(4), dp(3));
        row.setMinimumHeight(dp(46));
        row.setBackground(UiStyle.outlined(
                this, R.color.keepriva_surface, R.color.keepriva_outline, 10));
        row.setContentDescription("Category row " + name);
        return row;
    }

    private void addCategoryRowIcon(LinearLayout row, String name) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(categoryIconFor(name, false));
        icon.setContentDescription("Category icon " + categoryIconFamily(name, false));
        icon.setPadding(dp(5), dp(5), dp(5), dp(5));
        icon.setBackground(UiStyle.rounded(this, R.color.keepriva_surface_soft, 16));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        iconParams.rightMargin = dp(8);
        row.addView(icon, iconParams);
    }

    private String categoryIconFamily(String categoryName, boolean allCategories) {
        return allCategories
                ? "All"
                : VaultBrowserModelBuilder.iconFamily(categoryName, customCategories);
    }

    private int categoryIconFor(String categoryName, boolean allCategories) {
        switch (categoryIconFamily(categoryName, allCategories)) {
            case "All": return R.drawable.ic_keepriva_folder;
            case "Login": return R.drawable.ic_keepriva_key;
            case "Website": return R.drawable.ic_keepriva_website;
            case "App": return R.drawable.ic_keepriva_app;
            case "Contact": return R.drawable.ic_keepriva_contact;
            case "Banking": return R.drawable.ic_keepriva_card;
            case "Work": return R.drawable.ic_keepriva_work;
            case "Personal": return R.drawable.ic_keepriva_personal;
            case "Secure Note": return R.drawable.ic_keepriva_note;
            case "Other": return R.drawable.ic_keepriva_other;
            default: return R.drawable.ic_keepriva_custom;
        }
    }

    private void addBuiltInCategoryTreeRow(LinearLayout body, String name) {
        LinearLayout row = compactCategoryRow(name, 1);
        addCategoryRowIcon(row, name);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setSingleLine(true);
        label.setTextColor(getColor(R.color.keepriva_text_primary));
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageButton delete = smallIconButton(
                R.drawable.ic_keepriva_delete, "Delete category " + name, true);
        delete.setTooltipText("Delete " + name);
        delete.setOnClickListener(v ->
                dismissDialogThen(
                        findShowingDialogForView(v),
                        () -> deleteCategory(name, null)));
        row.addView(delete, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout.LayoutParams rowParams = matchWidth();
        rowParams.bottomMargin = dp(3);
        body.addView(row, rowParams);
    }

    private void addCustomCategoryTreeRow(LinearLayout body, CustomCategory category) {
        LinearLayout row = compactCategoryRow(category.name, categoryDepth(category.name));
        addCategoryRowIcon(row, category.name);

        TextView label = new TextView(this);
        label.setText(category.name);
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTextColor(getColor(R.color.keepriva_text_primary));
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageButton edit = smallIconButton(
                R.drawable.ic_keepriva_edit, "Edit category " + category.name, false);
        edit.setTooltipText("Edit " + category.name);
        edit.setOnClickListener(v -> {
            AlertDialog parent = findShowingDialogForView(v);
            if (parent != null) parent.dismiss();
            showCustomCategoryEditor(category);
        });
        row.addView(edit, new LinearLayout.LayoutParams(dp(36), dp(36)));

        ImageButton delete = smallIconButton(
                R.drawable.ic_keepriva_delete, "Delete category " + category.name, true);
        delete.setTooltipText("Delete " + category.name);
        delete.setOnClickListener(v ->
                dismissDialogThen(
                        findShowingDialogForView(v),
                        () -> deleteCategory(category.name, category)));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        deleteParams.leftMargin = dp(3);
        row.addView(delete, deleteParams);

        LinearLayout.LayoutParams rowParams = matchWidth();
        rowParams.bottomMargin = dp(3);
        body.addView(row, rowParams);
    }
    private void showCustomCategoryEditor(CustomCategory existing) {
        showCustomCategoryEditor(existing, null);
    }

    private void showCustomCategoryEditor(CustomCategory existing, String initialParentName) {
        CustomCategory model = existing == null ? new CustomCategory() : existing;
        LinearLayout form = baseVertical(6);
        EditText name = field("Category name", model.name);

        Spinner parent = new Spinner(this);
        CategoryOption[] parentOptions = parentOptionsFor(existing);
        parent.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, parentOptions));
        int parentIndex = 0;
        String requestedParent = existing == null && initialParentName != null
                ? initialParentName
                : safe(model.parentName);
        for (int i = 0; i < parentOptions.length; i++) {
            if (parentOptions[i].name.equals(requestedParent)) { parentIndex = i; break; }
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
                database.saveCustomCategoryAndUpdateReferences(
                        model, oldName, customCategories, allItems, vaultSession.requireKey());
                d.dismiss(); showVaultScreen();
                toast("Category saved.");
            } catch (Exception e) { toast("Could not save category."); }
        }));
        ScreenSecurityManager.protect(d);
        showDialog(d);
    }

    private Set<String> hiddenBuiltInCategories() {
        Set<String> result = new HashSet<>();
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_HIDDEN_BUILT_IN_CATEGORIES, "");
        for (String name : stored.split("\\n")) {
            String normalized = safe(name).trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }

    private List<String> activeBuiltInCategories() {
        Set<String> hidden = hiddenBuiltInCategories();
        List<String> active = new ArrayList<>();
        for (String name : BUILT_IN_CATEGORIES) {
            if (!hidden.contains(name.toLowerCase(Locale.ROOT))) active.add(name);
        }
        return active;
    }

    private void hideBuiltInCategory(String categoryName) {
        Set<String> hidden = hiddenBuiltInCategories();
        hidden.add(safe(categoryName).trim().toLowerCase(Locale.ROOT));
        List<String> sorted = new ArrayList<>(hidden);
        Collections.sort(sorted);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_HIDDEN_BUILT_IN_CATEGORIES, String.join("\n", sorted))
                .apply();
    }

    private boolean isHiddenBuiltInCategory(String categoryName) {
        return hiddenBuiltInCategories().contains(
                safe(categoryName).trim().toLowerCase(Locale.ROOT));
    }
    private boolean isBuiltInCategory(String name) {
        for (String c : BUILT_IN_CATEGORIES) if (c.equalsIgnoreCase(name)) return true;
        return "All".equalsIgnoreCase(name);
    }

    private void deleteCustomCategory(CustomCategory category) {
        deleteCategory(category.name, category);
    }

    private void deleteCategory(String categoryName, CustomCategory customCategory) {
        if (customCategory == null
                && isBuiltInCategory(categoryName)
                && activeBuiltInCategories().size() == 1
                && customCategories.isEmpty()) {
            toast("Create another category before deleting the final available category.");
            return;
        }

        Set<String> subtreeNames = categorySubtreeNames(categoryName);
        int itemCount = 0;
        for (VaultItem item : allItems) {
            if (subtreeNames.contains(safe(item.category).toLowerCase(Locale.ROOT))) itemCount++;
        }
        int descendantCount = Math.max(0, subtreeNames.size() - 1);
        final int totalItemCount = itemCount;

        if (itemCount == 0 && descendantCount == 0) {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Delete category?")
                    .setMessage("Delete \"" + categoryPath(categoryName) + "\"?")
                    .setPositiveButton("Delete category", null)
                    .setNegativeButton("Cancel", null)
                    .create();
            dialog.setOnShowListener(ignored -> {
                Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                confirm.setContentDescription("Confirm delete category " + categoryName);
                confirm.setTextColor(getColor(R.color.keepriva_danger));
                confirm.setOnClickListener(v ->
                        dismissDialogThen(
                                dialog,
                                () -> performCategorySubtreeDelete(
                                        categoryName, customCategory, subtreeNames)));
            });
            ScreenSecurityManager.protect(dialog);
            showDialog(dialog);
            return;
        }

        String summary = "\"" + categoryPath(categoryName) + "\" contains "
                + itemCount + (itemCount == 1 ? " item" : " items") + " and "
                + descendantCount + (descendantCount == 1 ? " subcategory" : " subcategories")
                + " in its complete subtree.";

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Delete category")
                .setMessage(summary + "\n\nChoose the safe move flow, or explicitly delete the entire subtree.")
                .setPositiveButton("Move contents", null)
                .setNeutralButton("Delete all contents", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button move = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            move.setEnabled(customCategory != null);
            move.setOnClickListener(v -> {
                if (customCategory == null) {
                    toast("Built-in category contents must be deleted together or moved manually first.");
                    return;
                }
                dialog.dismiss();
                showMoveCategoryContentsDialog(customCategory,
                        directItemCount(categoryName), directChildCount(categoryName));
            });

            Button destructive = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            destructive.setTextColor(getColor(R.color.keepriva_danger));
            destructive.setContentDescription("Delete category and all contents");
            destructive.setOnClickListener(v ->
                    dismissDialogThen(
                            dialog,
                            () -> confirmForceDeleteCategory(
                                    categoryName,
                                    customCategory,
                                    subtreeNames,
                                    totalItemCount,
                                    descendantCount)));
        });
        ScreenSecurityManager.protect(dialog);
        showDialog(dialog);
    }

    private int directItemCount(String categoryName) {
        int count = 0;
        for (VaultItem item : allItems) {
            if (safe(item.category).equalsIgnoreCase(categoryName)) count++;
        }
        return count;
    }

    private Set<String> categorySubtreeNames(String rootName) {
        Set<String> names = new HashSet<>();
        names.add(safe(rootName).toLowerCase(Locale.ROOT));
        boolean changed;
        do {
            changed = false;
            for (CustomCategory category : customCategories) {
                String parent = safe(category.parentName).toLowerCase(Locale.ROOT);
                String child = safe(category.name).toLowerCase(Locale.ROOT);
                if (names.contains(parent) && names.add(child)) changed = true;
            }
        } while (changed);
        return names;
    }

    private void confirmForceDeleteCategory(String categoryName,
                                            CustomCategory customCategory,
                                            Set<String> subtreeNames,
                                            int itemCount,
                                            int descendantCount) {
        AlertDialog confirm = new AlertDialog.Builder(this)
                .setTitle("Permanently delete subtree?")
                .setMessage("This permanently deletes " + itemCount
                        + (itemCount == 1 ? " item and " : " items and ")
                        + descendantCount
                        + (descendantCount == 1 ? " subcategory." : " subcategories.")
                        + " This action cannot be undone.")
                .setPositiveButton("Delete permanently", null)
                .setNegativeButton("Cancel", null)
                .create();
        confirm.setOnShowListener(ignored -> {
            Button delete = confirm.getButton(AlertDialog.BUTTON_POSITIVE);
            delete.setTextColor(getColor(R.color.keepriva_danger));
            delete.setContentDescription("Confirm permanent category deletion");
            delete.setOnClickListener(v ->
                    dismissDialogThen(
                            confirm,
                            () -> performCategorySubtreeDelete(
                                    categoryName, customCategory, subtreeNames)));

            Button cancel = confirm.getButton(AlertDialog.BUTTON_NEGATIVE);
            cancel.setContentDescription("Cancel permanent category deletion");
        });
        ScreenSecurityManager.protect(confirm);
        showDialog(confirm);
    }

    private void performCategorySubtreeDelete(String categoryName,
                                              CustomCategory customCategory,
                                              Set<String> subtreeNames) {
        Set<Long> categoryIds = new HashSet<>();
        for (CustomCategory category : customCategories) {
            if (subtreeNames.contains(safe(category.name).toLowerCase(Locale.ROOT))) {
                categoryIds.add(category.id);
            }
        }
        try {
            database.deleteCategorySubtree(allItems, subtreeNames, categoryIds);
            if (customCategory == null && isBuiltInCategory(categoryName)) {
                hideBuiltInCategory(categoryName);
            }
            showVaultScreen();
            toast("Category subtree deleted.");
        } catch (Exception e) {
            showVaultScreen();
            toast("Could not delete category. No partial deletion was committed.");
        }
    }
    private void showMoveCategoryContentsDialog(CustomCategory sourceCategory, int entryCount, int childCount) {
        List<CategoryOption> destinations = new ArrayList<>();
        for (String builtIn : activeBuiltInCategories()) {
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
        showDialog(dialog);
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
                                sourceCategory.id, vaultSession.requireKey());
                        showVaultScreen();
                        toast("Contents moved and category deleted.");
                    } catch (Exception e) {
                        showVaultScreen();
                        toast("Could not move category contents. No partial deletion was committed.");
                    }
                })
                .setNegativeButton("Cancel", null).create();
        ScreenSecurityManager.protect(dialog);
        showDialog(dialog);
    }

    private void exportSelectedCategory() {
        String category = safe(selectedHomeCategory).isEmpty() ? "All" : selectedHomeCategory;
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

        showDialog(new AlertDialog.Builder(this)
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
        showDialog(dialog);
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
        json.setContentDescription("Export Keepriva JSON");
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
        showDialog(dialog);
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
        if (!vaultSession.isUnlocked() || systemPickerStartedAt <= 0L) return false;
        long timeout = getAutoLockMs();
        long elapsed = System.currentTimeMillis() - systemPickerStartedAt;
        return timeout == VaultSecurityPreferences.AUTO_LOCK_IMMEDIATELY || elapsed >= timeout;
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
        showDialog(dialog);
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
        showDialog(dialog);
    }

    private void createEncryptedBackup(char[] backupPassword) {
        try {
            // Reload from the database so the backup is a complete authoritative snapshot.
            List<CustomCategory> categories = database.listCustomCategories(vaultSession.requireKey());
            List<VaultItem> items = database.list(vaultSession.requireKey());
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
        showDialog(new AlertDialog.Builder(this)
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
                .create());
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
        showDialog(dialog);
    }

    private void showRestorePreview(BackupManager.RestoredBackup restored) {
        if (restored.sourceSchemaVersion > database.currentSchemaVersion()) {
            showDialog(new AlertDialog.Builder(this)
                    .setTitle("Backup is newer than this app")
                    .setMessage("This backup was created from vault schema " + restored.sourceSchemaVersion
                            + ", but this app supports schema " + database.currentSchemaVersion()
                            + ". Update Keepriva before restoring it.")
                    .setPositiveButton("Close", null).create());
            return;
        }
        String hierarchyError = validateCategoryHierarchy(restored.categories, getMaxCategoryDepth());
        if (hierarchyError != null) {
            showDialog(new AlertDialog.Builder(this)
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
        showDialog(new AlertDialog.Builder(this)
                .setTitle("Restore preview")
                .setMessage(message)
                .setPositiveButton("Replace current vault", (d,w) -> {
                    try {
                        database.replaceAllFromBackup(
                                restored.categories, restored.items, vaultSession.requireKey());
                        showVaultScreen();
                        toast("Encrypted backup restored successfully.");
                    } catch (Exception e) {
                        toast("Restore failed; current vault was not partially replaced: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null)
                .create());
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

        download.setContentDescription("Download JSON import template");
        importFile.setContentDescription("Import completed JSON template");

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
        showDialog(dialog);
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
            showDialog(new AlertDialog.Builder(this).setTitle("Import validation failed")
                    .setMessage(message.toString()).setPositiveButton("Close", null).create());
            return;
        }
        if (parsed.items.isEmpty()) {
            showDialog(new AlertDialog.Builder(this).setTitle("Nothing to import")
                    .setMessage(message.toString()).setPositiveButton("Close", null).create());
            return;
        }
        message.append("\n\nOn import, all entry fields—including fields marked sensitive—are encrypted before being written to the local vault database.");
        showDialog(new AlertDialog.Builder(this).setTitle("Import preview")
                .setMessage(message.toString())
                .setPositiveButton("Import", (d, w) -> commitImport(parsed))
                .setNegativeButton("Cancel", null).create());
    }

    private void commitImport(ImportManager.ParsedImport parsed) {
        try {
            database.importBatch(
                    parsed.categoriesToCreate, parsed.items, vaultSession.requireKey());
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
        return viewFactory.phoneField(hint, value);
    }

    private LinearLayout baseVertical(int gapDp) {
        return viewFactory.verticalContainer(gapDp);
    }

    private ScrollView wrap(View child) {
        return viewFactory.scroll(child);
    }

    private TextView title(String text) {
        return viewFactory.title(text);
    }

    private TextView subtitle(String text) {
        return viewFactory.subtitle(text);
    }

    private TextView boldLabel(String text) {
        return viewFactory.boldLabel(text);
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
        return viewFactory.field(hint, value);
    }

    private EditText passwordField(String hint) {
        return viewFactory.passwordField(hint);
    }

    private Button button(String text) {
        return viewFactory.secondaryButton(text);
    }

    private Button primaryButton(String text) {
        return viewFactory.primaryButton(text);
    }

    private ImageButton smallIconButton(int iconRes, String description, boolean danger) {
        return viewFactory.smallIconButton(iconRes, description, danger);
    }

    private LinearLayout.LayoutParams matchWidth() {
        return viewFactory.matchWidth();
    }

    private void dismissDialogThen(AlertDialog dialog, Runnable continuation) {
        if (continuation == null) return;
        if (dialog == null || !dialog.isShowing()) {
            continuation.run();
            return;
        }
        dialog.setOnDismissListener(ignored -> continuation.run());
        dialog.dismiss();
    }

    private AlertDialog showDialog(AlertDialog dialog) {
        return dialogRegistry.show(dialog);
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

    /** Replaces only the child of the activity-owned root; setContentView is called once. */
    private void renderRootScreen(View screen, VaultNavigationState state) {
        if (rootRenderer == null) {
            throw new IllegalStateException("Root content container is unavailable");
        }
        rootRenderer.render(screen, state);
    }

    private void showVaultLoadingState() {
        VaultNavigationState state = VaultNavigationState.root(VaultScreen.LOADING);
        screenRouter.reset(state);
        View loading = VaultUiComponents.emptyState(
                this,
                "Opening vault",
                "Decrypting your offline vault on this device.",
                true,
                "",
                null);
        renderRootScreen(loading, state);
    }

    private void showVaultLoadError() {
        // Never retain a key or decrypted models after a database/decryption failure.
        clearSessionState();
        explicitlyLocked = true;
        backgroundAt = 0L;

        VaultNavigationState state = VaultNavigationState.root(VaultScreen.ERROR);
        screenRouter.reset(state);
        View error = VaultUiComponents.emptyState(
                this,
                "Vault could not be opened",
                "Keepriva cleared the active session. Return to unlock and try again.",
                false,
                "Return to unlock",
                v -> showUnlockScreen());
        error.setContentDescription("Vault loading error");
        renderRootScreen(error, state);
    }

    // Package-private diagnostics used by same-package instrumentation tests.
    VaultScreen currentScreenForTesting() {
        VaultNavigationState current = screenRouter.currentState();
        return current == null ? null : current.screen();
    }

    VaultNavigationState navigationStateForTesting() {
        return screenRouter.currentState();
    }

    int navigationBackStackSizeForTesting() {
        return screenRouter.backStackSize();
    }

    int rootChildCountForTesting() {
        return rootRenderer == null ? 0 : rootRenderer.childCountForTesting();
    }

    boolean hasSessionKeyForTesting() {
        return vaultSession.isUnlocked();
    }

    void triggerScreenOffForTesting() {
        handleScreenOffLock();
    }

    private void handleScreenOffLock() {
        if (vaultSession.isUnlocked() && getLockOnScreenOff()) lockVault();
    }

    private int dp(int v) { return viewFactory.dp(v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private static String safe(String s) { return s == null ? "" : s; }
}
