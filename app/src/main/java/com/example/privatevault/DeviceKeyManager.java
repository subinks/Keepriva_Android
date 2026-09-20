package com.example.privatevault;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Owns the device-bound Android Keystore key that will protect a secondary copy of
 * the vault data key for biometric unlock.
 *
 * Security properties:
 * - key material is generated inside AndroidKeyStore and is non-exportable;
 * - every cryptographic use requires strong biometric authentication;
 * - biometric enrollment changes invalidate the key where Android supports it;
 * - this key is never used as the sole recovery path: the master-password wrapped
 *   vault key remains authoritative.
 *
 * The key is used only through BiometricPrompt CryptoObject authorization. A secondary
 * wrapped copy of the vault key may be stored for biometric unlock, while the
 * master-password-wrapped copy remains the permanent recovery path.
 */
public final class DeviceKeyManager {
    public static final String KEY_ALIAS = "private_vault_biometric_wrap_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private DeviceKeyManager() {}

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P; // app minSdk is 28
    }

    public static boolean containsKey() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            return keyStore.containsAlias(KEY_ALIAS);
        } catch (Exception e) {
            throw asSecurityException("Unable to inspect Android Keystore", e);
        }
    }

    /**
     * Creates a per-use-authentication AES-256 key if it does not already exist.
     * No plaintext key bytes ever leave Android Keystore.
     */
    public static void ensureAuthenticationBoundKey() throws GeneralSecurityException {
        if (!isSupported() || containsKey()) return;
        try {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // duration=0 means each cryptographic operation must be authorized by
                // a BIOMETRIC_STRONG authentication through BiometricPrompt.
                builder.setUserAuthenticationParameters(
                        0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            } else {
                // API 28-29 equivalent: authentication is required for every use.
                builder.setUserAuthenticationValidityDurationSeconds(-1);
            }

            generator.init(builder.build());
            generator.generateKey();
        } catch (Exception e) {
            throw asSecurityException("Unable to create authentication-bound Keystore key", e);
        }
    }

    public static void deleteKey() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception e) {
            throw asSecurityException("Unable to remove Android Keystore key", e);
        }
    }

    /** Pass this Cipher to BiometricPrompt.CryptoObject when enabling biometric unlock. */
    public static Cipher createEncryptionCipher() throws GeneralSecurityException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, loadSecretKey());
            return cipher;
        } catch (Exception e) {
            throw asSecurityException("Unable to initialize Keystore encryption", e);
        }
    }

    /** Pass this Cipher to BiometricPrompt.CryptoObject when unlocking with biometrics. */
    public static Cipher createDecryptionCipher(byte[] iv) throws GeneralSecurityException {
        if (iv == null || iv.length != 12) {
            throw new GeneralSecurityException("Invalid AES-GCM IV");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, loadSecretKey(), new GCMParameterSpec(128, iv));
            return cipher;
        } catch (Exception e) {
            throw asSecurityException("Unable to initialize Keystore decryption", e);
        }
    }

    private static SecretKey loadSecretKey() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
            if (!(key instanceof SecretKey)) {
                throw new GeneralSecurityException("Keystore key is unavailable");
            }
            return (SecretKey) key;
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw asSecurityException("Unable to load Android Keystore key", e);
        }
    }

    private static GeneralSecurityException asSecurityException(String message, Exception cause) {
        GeneralSecurityException out = new GeneralSecurityException(message);
        out.initCause(cause);
        return out;
    }
}
