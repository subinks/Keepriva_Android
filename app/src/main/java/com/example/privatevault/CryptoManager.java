package com.example.privatevault;

import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoManager {
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    /** Iteration count used by Private Vault v1.x. Retained only for legacy unlock migration. */
    public static final int LEGACY_KDF_ITERATIONS = 210_000;

    /**
     * Iteration count used to derive the master-password Key Encryption Key (KEK) in v2.x.
     * The KEK wraps the independent random vault data key; it does not encrypt records directly.
     */
    public static final int MASTER_KDF_ITERATIONS = 600_000;

    private static final SecureRandom RNG = new SecureRandom();

    private CryptoManager() {}

    public static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RNG.nextBytes(bytes);
        return bytes;
    }

    public static SecretKey generateVaultKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(KEY_BITS, RNG);
        return generator.generateKey();
    }

    /** New v2.x master-password KEK derivation. */
    public static SecretKey deriveMasterKey(char[] password, byte[] salt) throws GeneralSecurityException {
        return derivePbkdf2Key(password, salt, MASTER_KDF_ITERATIONS);
    }

    /** Legacy v1.x derivation used only to unlock/migrate existing installations. */
    public static SecretKey deriveLegacyKey(char[] password, byte[] salt) throws GeneralSecurityException {
        return derivePbkdf2Key(password, salt, LEGACY_KDF_ITERATIONS);
    }

    /** Kept for source compatibility; new code should use deriveMasterKey(). */
    @Deprecated
    public static SecretKey deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        return deriveMasterKey(password, salt);
    }

    public static SecretKey derivePbkdf2KeyForBackup(char[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        return derivePbkdf2Key(password, salt, iterations);
    }

    private static SecretKey derivePbkdf2Key(char[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        byte[] raw = null;
        try {
            raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(raw, "AES");
        } finally {
            spec.clearPassword();
            if (raw != null) Arrays.fill(raw, (byte) 0);
        }
    }

    public static String encrypt(SecretKey key, String plaintext) throws GeneralSecurityException {
        return Base64.encodeToString(
                encryptBytes(key, plaintext.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    public static String decrypt(SecretKey key, String encoded) throws GeneralSecurityException {
        byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
        byte[] plaintext = decryptBytes(key, packed);
        try {
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public static byte[] encryptBytes(SecretKey key, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) {
            throw new GeneralSecurityException("Unexpected AES-GCM IV length");
        }
        byte[] encrypted = cipher.doFinal(plaintext);
        ByteBuffer packed = ByteBuffer.allocate(iv.length + encrypted.length);
        packed.put(iv).put(encrypted);
        return packed.array();
    }

    public static byte[] decryptBytes(SecretKey key, byte[] packed) throws GeneralSecurityException {
        if (packed == null || packed.length <= IV_BYTES) {
            throw new GeneralSecurityException("Invalid encrypted payload");
        }
        byte[] iv = Arrays.copyOfRange(packed, 0, IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(packed, IV_BYTES, packed.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } finally {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    public static String wrapVaultKey(SecretKey wrappingKey, SecretKey vaultKey)
            throws GeneralSecurityException {
        byte[] raw = vaultKey.getEncoded();
        if (raw == null || raw.length != KEY_BITS / 8) {
            throw new GeneralSecurityException("Vault key is not exportable AES-256 material");
        }
        try {
            return Base64.encodeToString(encryptBytes(wrappingKey, raw), Base64.NO_WRAP);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    public static SecretKey unwrapVaultKey(SecretKey wrappingKey, String wrapped)
            throws GeneralSecurityException {
        byte[] packed = Base64.decode(wrapped, Base64.NO_WRAP);
        byte[] raw = decryptBytes(wrappingKey, packed);
        try {
            if (raw.length != KEY_BITS / 8) {
                throw new GeneralSecurityException("Invalid wrapped vault key length");
            }
            return new SecretKeySpec(raw, "AES");
        } finally {
            Arrays.fill(raw, (byte) 0);
            Arrays.fill(packed, (byte) 0);
        }
    }
}
