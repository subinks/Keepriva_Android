package com.example.privatevault;

import android.app.Activity;
import android.hardware.biometrics.BiometricPrompt;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
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
        VaultBrowserActions,
        CategoryManagementController.Gateway,
        CategoryManagementActions,
        DataTransferController.Gateway,
        DataTransferActions,
        ItemDialogController.Gateway,
        ItemDialogActions {
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
    private CategoryManagementController categoryManagementController;
    private ItemDialogController itemDialogController;
    private DataTransferController dataTransferController;
    private final ActivityResultCoordinator activityResults = new ActivityResultCoordinator();
    private final VaultScreenRouter screenRouter = new VaultScreenRouter();
    private String selectedHomeCategory = "All";
    private List<VaultItem> allItems = new ArrayList<>();
    private List<CustomCategory> customCategories = new ArrayList<>();
    private long backgroundAt = 0L;
    private ClipboardSecurityManager clipboardSecurity;
    // PBKDF2 deliberately uses a high work factor; never derive on the UI thread.
    private final ExecutorService unlockExecutor = Executors.newSingleThreadExecutor();
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
        composeControllers();
        database = new VaultDatabase(this);
        clipboardSecurity = new ClipboardSecurityManager(this);
        registerScreenOffReceiver();
        if (isConfigured()) showUnlockScreen(); else showSetupScreen();
    }

    /** Feature owners are constructed together only after release-security verification. */
    private void composeControllers() {
        securityPreferences = new VaultSecurityPreferences(getSharedPreferences(PREFS, MODE_PRIVATE));
        setupController = controllerRegistry.register(new SetupController(viewFactory, this, this));
        unlockController = controllerRegistry.register(new UnlockController(this, viewFactory, this, this));
        securitySettingsController = controllerRegistry.register(new SecuritySettingsController(
                this, viewFactory, dialogRegistry, securityPreferences, this, this));
        browserController = controllerRegistry.register(
                new LegacyVaultBrowserController(this, viewFactory, this, this));
        categoryManagementController = controllerRegistry.register(
                new CategoryManagementController(
                        this, viewFactory, dialogRegistry, categoryHierarchy, this, this));
        itemDialogController = controllerRegistry.register(
                new ItemDialogController(this, viewFactory, dialogRegistry, this, this));
        dataTransferController = controllerRegistry.register(
                new DataTransferController(this, viewFactory, dialogRegistry, this, this));
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
        if (vaultSession.isUnlocked() && backgroundAt > 0 && !activityResults.isPickerInProgress()) {
            long timeout = getAutoLockMs();
            if (timeout == VaultSecurityPreferences.AUTO_LOCK_IMMEDIATELY
                    || System.currentTimeMillis() - backgroundAt >= timeout) {
                lockVault();
            }
        }
    }

    @Override
    protected void onDestroy() {
        clearSessionAndControllers(true);
        if (screenOffReceiverRegistered) {
            try { unregisterReceiver(screenOffReceiver); } catch (Exception ignored) { }
            screenOffReceiverRegistered = false;
        }
        unlockExecutor.shutdownNow();
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
        LinearLayout root = viewFactory.verticalContainer(24);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(viewFactory.title("Security verification failed"));
        root.addView(viewFactory.subtitle(message + "\n\nInstall an official signed build of Keepriva."));
        Button close = viewFactory.secondaryButton("Close app");
        close.setOnClickListener(v -> finishAndRemoveTask());
        root.addView(close);
        VaultNavigationState state = VaultNavigationState.root(VaultScreen.ERROR);
        screenRouter.reset(state);
        renderRootScreen(viewFactory.scroll(root), state);
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
    private SecretKey unlockLegacyForVerification(String password, SharedPreferences prefs) throws Exception {
        byte[] salt = Base64.decode(prefs.getString(PREF_LEGACY_SALT, ""), Base64.NO_WRAP);
        try {
            SecretKey key = CryptoManager.deriveLegacyKey(password.toCharArray(), salt);
            String verifier = CryptoManager.decrypt(key, prefs.getString(PREF_LEGACY_VERIFIER, ""));
            if (!LEGACY_VERIFIER_TEXT.equals(verifier)) throw new GeneralSecurityException("Wrong password");
            return key;
        } finally { java.util.Arrays.fill(salt, (byte) 0); }
    }

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

    @Override
    public int deepestCategoryDepth() {
        return categoryHierarchy.deepestDepth(customCategories);
    }

    @Override
    public String categoryPath(String categoryName) {
        return categoryHierarchy.path(categoryName, customCategories);
    }

    /** Returns null when valid, otherwise a user-facing hierarchy validation error. */
    private String validateCategoryHierarchy(List<CustomCategory> categories, int maxDepth) {
        return categoryHierarchy.validate(categories, maxDepth);
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

    @Override
    public void copyPassword(String password) {
        if (password == null || password.isEmpty()) return;
        long timeout = getClipboardTimeoutMs();
        clipboardSecurity.copySensitive("Keepriva password", password, timeout);
        toast(timeout == ClipboardSecurityManager.NEVER_CLEAR
                ? "Password copied. Clipboard auto-clear is disabled."
                : "Password copied. It will be cleared in " + (timeout / 1000L) + " seconds.");
    }

    private void lockVault() {
        if (clipboardSecurity != null) clipboardSecurity.clearSensitiveClipboardNow();
        clearSessionAndControllers(false);
        backgroundAt = 0;
        showUnlockScreen();
    }

    /** Idempotent session cleanup shared by lock, vault-load failure and destruction. */
    private void clearSessionAndControllers(boolean destroying) {
        callbackGeneration.invalidate();
        dialogRegistry.dismissAll();
        screenRouter.clear();
        vaultSession.clear();
        allItems.clear();
        customCategories.clear();
        if (browserController != null) browserController.clearSessionState();
        selectedHomeCategory = "All";
        if (dataTransferController != null) dataTransferController.clearSessionState();
        activityResults.clear();
        if (destroying) controllerRegistry.close();
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
        itemDialogController.showDetails(itemId);
    }

    @Override
    public void onAddEntryRequested(String categoryName) {
        itemDialogController.showEditor(null, safe(categoryName).isEmpty() ? "Login" : categoryName);
    }

    @Override
    public void onAddSubcategoryRequested(String parentCategoryName) {
        categoryManagementController.showEditor(null, safe(parentCategoryName));
    }

    @Override
    public void onManageCategoriesRequested() {
        categoryManagementController.showManager();
    }

    @Override
    public void onImportRequested() {
        dataTransferController.showImportDialog();
    }

    @Override
    public void onExportRequested(String categoryName) {
        selectedHomeCategory = safe(categoryName).isEmpty() ? "All" : categoryName;
        dataTransferController.exportCategory(selectedHomeCategory);
    }

    @Override
    public void onBackupRestoreRequested() {
        dataTransferController.showBackupRestoreDialog();
    }

    @Override
    public void onPreferencesRequested() {
        securitySettingsController.showPreferences();
    }

    @Override
    public void onSecurityRequested() {
        securitySettingsController.requestMasterPasswordReauth(
                "Security settings", securitySettingsController::showSecuritySettings);
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

    @Override
    public VaultItem findItem(long itemId) {
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

    @Override
    public List<CustomCategory> categoryDefinitions() {
        List<CustomCategory> copies = new ArrayList<>();
        for (CustomCategory category : customCategories) {
            copies.add(CategoryManagementController.copyCategory(category));
        }
        return copies;
    }

    @Override
    public List<String> activeBuiltInCategories() {
        Set<String> hidden = hiddenBuiltInCategories();
        List<String> active = new ArrayList<>();
        for (String name : BUILT_IN_CATEGORIES) {
            if (!hidden.contains(name.toLowerCase(Locale.ROOT))) active.add(name);
        }
        return active;
    }

    @Override
    public int maxCategoryDepth() {
        return getMaxCategoryDepth();
    }

    @Override
    public boolean isBuiltInCategory(String name) {
        for (String category : BUILT_IN_CATEGORIES) {
            if (category.equalsIgnoreCase(name)) return true;
        }
        return "All".equalsIgnoreCase(name);
    }

    @Override
    public int itemCountInSubtree(Set<String> categoryNames) {
        int count = 0;
        for (VaultItem item : allItems) {
            if (categoryNames.contains(safe(item.category).toLowerCase(Locale.ROOT))) count++;
        }
        return count;
    }

    @Override
    public int directItemCount(String categoryName) {
        int count = 0;
        for (VaultItem item : allItems) {
            if (safe(item.category).equalsIgnoreCase(categoryName)) count++;
        }
        return count;
    }

    @Override
    public void saveCategory(CustomCategory category, String oldName) throws Exception {
        database.saveCustomCategoryAndUpdateReferences(
                category, oldName, customCategories, allItems, vaultSession.requireKey());
    }

    @Override
    public void deleteCategorySubtree(
            String categoryName, Long customCategoryId, Set<String> subtreeNames) {
        Set<Long> categoryIds = new HashSet<>();
        for (CustomCategory category : customCategories) {
            if (subtreeNames.contains(safe(category.name).toLowerCase(Locale.ROOT))) {
                categoryIds.add(category.id);
            }
        }
        database.deleteCategorySubtree(allItems, subtreeNames, categoryIds);
        if (customCategoryId == null && isBuiltInCategory(categoryName)) {
            hideBuiltInCategory(categoryName);
        }
    }

    @Override
    public void moveContentsAndDeleteCategory(
            long sourceCategoryId, String sourceCategoryName, String destinationCategory)
            throws Exception {
        database.moveContentsAndDeleteCustomCategory(
                allItems, customCategories, sourceCategoryName,
                destinationCategory, sourceCategoryId, vaultSession.requireKey());
    }

    @Override
    public void onCategoriesChanged() {
        showVaultScreen();
    }

    @Override
    public void onCategoryManagementClosed() {
        // The browser remains the active screen; no navigation transition is required.
    }

    private Set<String> hiddenBuiltInCategories() {
        Set<String> result = new HashSet<>();
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_HIDDEN_BUILT_IN_CATEGORIES, "");
        for (String name : stored.split("\n")) {
            String normalized = safe(name).trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
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
        clearSessionAndControllers(false);
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

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    @Override public long saveItem(VaultItem item) throws Exception {
        return database.save(item, vaultSession.requireKey());
    }

    @Override public void deleteItem(long itemId) { database.delete(itemId); }

    @Override public void exportItem(long itemId) {
        VaultItem item = findItem(itemId);
        if (item != null) dataTransferController.exportEntry(itemId);
    }

    @Override public void copySensitiveField(String value) {
        if (value == null || value.isEmpty()) return;
        long timeout = getClipboardTimeoutMs();
        clipboardSecurity.copySensitive("Keepriva sensitive field", value, timeout);
        toast(timeout == ClipboardSecurityManager.NEVER_CLEAR
                ? "Sensitive field copied. Clipboard auto-clear is disabled."
                : "Sensitive field copied. It will be cleared in " + (timeout / 1000L) + " seconds.");
    }

    @Override public boolean isSessionActive() { return vaultSession.isUnlocked(); }

    @Override public void onItemSaved(long itemId, String categoryName) {
        selectedHomeCategory = categoryName;
        browserController.selectCategory(categoryName, true);
        loadItems();
    }

    @Override public void onItemDeleted(long itemId) { loadItems(); }

    @Override public void onItemRestored(long itemId) { loadItems(); }

    @Override public void onItemDialogClosed() { }

    @Override public List<VaultItem> itemsForExport(String category) {
        List<VaultItem> selected = new ArrayList<>();
        for (VaultItem item : allItems) {
            if ("All".equals(category)
                    || categoryHierarchy.isDescendantOf(item.category, category, customCategories)) selected.add(item);
        }
        return selected;
    }

    @Override public String[] builtInCategoryNames() { return BUILT_IN_CATEGORIES.clone(); }

    @Override public boolean verifyExportPassword(String password) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            SecretKey verified = prefs.getInt(PREF_CRYPTO_VERSION, 0) >= CRYPTO_VERSION_2
                    ? unlockV2(password, prefs)
                    : unlockLegacyForVerification(password, prefs);
            return verified != null;
        } catch (Exception error) { return false; }
    }

    @Override public byte[] createEncryptedBackup(char[] password) throws Exception {
        List<CustomCategory> categories = database.listCustomCategories(vaultSession.requireKey());
        List<VaultItem> items = database.list(vaultSession.requireKey());
        return BackupManager.createBackup(categories, items, password, database.currentSchemaVersion());
    }

    @Override public int schemaVersion() { return database.currentSchemaVersion(); }

    @Override public String validateCategoryHierarchy(List<CustomCategory> categories) {
        return validateCategoryHierarchy(categories, getMaxCategoryDepth());
    }

    @Override public void restoreBackup(BackupManager.RestoredBackup restored) throws Exception {
        database.replaceAllFromBackup(restored.categories, restored.items, vaultSession.requireKey());
    }

    @Override public void importBatch(ImportManager.ParsedImport parsed) throws Exception {
        database.importBatch(parsed.categoriesToCreate, parsed.items, vaultSession.requireKey());
    }

    @Override public void onTransferCompleted() { showVaultScreen(); }
    @Override public void onTransferClosed() { }

    @Override public void onPickerRequested(TransferOperation operation, String mimeType, String suggestedName) {
        if (!vaultSession.isUnlocked()) { dataTransferController.cancelPending(operation); return; }
        Intent intent = new Intent(operation == TransferOperation.JSON_IMPORT
                || operation == TransferOperation.BACKUP_RESTORE
                ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        if (suggestedName != null) intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
        try {
            activityResults.begin(operation, System.currentTimeMillis());
            startActivityForResult(intent, ActivityResultCoordinator.requestCode(operation));
        } catch (RuntimeException error) {
            activityResults.clear();
            dataTransferController.cancelPending(operation);
            toast("Could not open document picker.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ActivityResultCoordinator.Completed completed = activityResults.complete(
                requestCode, System.currentTimeMillis(), getAutoLockMs(), vaultSession.isUnlocked());
        if (completed == null) return;
        backgroundAt = 0L;
        if (completed.lockAfterPicker) {
            dataTransferController.cancelPending(completed.operation);
            lockVault();
            return;
        }
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        dataTransferController.handlePickerResult(completed.operation, uri);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
