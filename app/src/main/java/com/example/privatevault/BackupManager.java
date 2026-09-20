package com.example.privatevault;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

/**
 * Portable encrypted .pvault backup format.
 *
 * The backup never stores the Android Keystore key, master-password wrapped vault key,
 * or current raw vault key. A logical snapshot is encrypted independently with a key
 * derived from the user-supplied backup password.
 */
public final class BackupManager {
    public static final String FORMAT = "private-vault-backup";
    public static final int FORMAT_VERSION = 1;
    public static final int BACKUP_KDF_ITERATIONS = 600_000;
    private static final int SALT_BYTES = 32;

    private BackupManager() {}

    public static byte[] createBackup(List<CustomCategory> categories, List<VaultItem> items,
                                      char[] backupPassword, int schemaVersion)
            throws GeneralSecurityException, JSONException {
        requirePassword(backupPassword);
        byte[] salt = CryptoManager.randomBytes(SALT_BYTES);
        byte[] plaintext = null;
        try {
            JSONObject payload = new JSONObject();
            payload.put("schemaVersion", schemaVersion);
            payload.put("createdAt", System.currentTimeMillis());
            payload.put("categories", categoriesToJson(categories));
            payload.put("items", itemsToJson(items));
            plaintext = payload.toString().getBytes(StandardCharsets.UTF_8);

            SecretKey key = CryptoManager.derivePbkdf2KeyForBackup(
                    backupPassword, salt, BACKUP_KDF_ITERATIONS);
            byte[] encrypted = CryptoManager.encryptBytes(key, plaintext);
            try {
                JSONObject envelope = new JSONObject();
                envelope.put("format", FORMAT);
                envelope.put("formatVersion", FORMAT_VERSION);
                envelope.put("kdf", "PBKDF2WithHmacSHA256");
                envelope.put("iterations", BACKUP_KDF_ITERATIONS);
                envelope.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
                envelope.put("cipher", "AES-256-GCM");
                envelope.put("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP));
                return envelope.toString(2).getBytes(StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(encrypted, (byte) 0);
            }
        } finally {
            Arrays.fill(salt, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    public static RestoredBackup decryptAndValidate(byte[] fileBytes, char[] backupPassword)
            throws GeneralSecurityException, JSONException {
        requirePassword(backupPassword);
        if (fileBytes == null || fileBytes.length == 0) {
            throw new GeneralSecurityException("Backup file is empty");
        }
        JSONObject envelope = new JSONObject(new String(fileBytes, StandardCharsets.UTF_8));
        if (!FORMAT.equals(envelope.optString("format"))) {
            throw new GeneralSecurityException("Not a Keepriva-compatible backup");
        }
        if (envelope.optInt("formatVersion", -1) != FORMAT_VERSION) {
            throw new GeneralSecurityException("Unsupported backup format version");
        }
        int iterations = envelope.optInt("iterations", 0);
        if (iterations < 100_000 || iterations > 5_000_000) {
            throw new GeneralSecurityException("Invalid backup KDF parameters");
        }
        byte[] salt = Base64.decode(envelope.getString("salt"), Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(envelope.getString("payload"), Base64.NO_WRAP);
        byte[] plaintext = null;
        try {
            if (salt.length < 16) throw new GeneralSecurityException("Invalid backup salt");
            SecretKey key = CryptoManager.derivePbkdf2KeyForBackup(backupPassword, salt, iterations);
            plaintext = CryptoManager.decryptBytes(key, encrypted); // GCM tag validates integrity/password.
            JSONObject payload = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            int schemaVersion = payload.optInt("schemaVersion", -1);
            if (schemaVersion < 1) throw new GeneralSecurityException("Invalid backup schema version");
            List<CustomCategory> categories = parseCategories(payload.optJSONArray("categories"));
            List<VaultItem> items = parseItems(payload.optJSONArray("items"));
            validateReferences(categories, items);
            return new RestoredBackup(schemaVersion, payload.optLong("createdAt", 0L), categories, items);
        } catch (GeneralSecurityException | JSONException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("Backup validation failed", e);
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static void requirePassword(char[] password) throws GeneralSecurityException {
        if (password == null || password.length < 10) {
            throw new GeneralSecurityException("Backup password must be at least 10 characters");
        }
    }

    private static JSONArray categoriesToJson(List<CustomCategory> categories) throws JSONException {
        JSONArray a = new JSONArray();
        for (CustomCategory c : categories) {
            JSONObject o = new JSONObject();
            o.put("name", safe(c.name));
            o.put("parentName", safe(c.parentName));
            JSONArray f = new JSONArray();
            for (String field : c.fields) f.put(field);
            o.put("fields", f);
            JSONArray sf = new JSONArray();
            for (String field : c.sensitiveFields) sf.put(field);
            o.put("sensitiveFields", sf);
            o.put("createdAt", c.createdAt);
            o.put("updatedAt", c.updatedAt);
            a.put(o);
        }
        return a;
    }

    private static JSONArray itemsToJson(List<VaultItem> items) throws JSONException {
        JSONArray a = new JSONArray();
        for (VaultItem i : items) {
            JSONObject o = new JSONObject();
            o.put("title", safe(i.title));
            o.put("category", safe(i.category));
            o.put("username", safe(i.username));
            o.put("password", safe(i.password));
            o.put("website", safe(i.website));
            o.put("websiteUrl", safe(i.websiteUrl));
            o.put("phone1", safe(i.phone1));
            o.put("phone2", safe(i.phone2));
            o.put("phone3", safe(i.phone3));
            o.put("notes", safe(i.notes));
            JSONObject custom = new JSONObject();
            if (i.customFields != null) {
                for (Map.Entry<String,String> e : i.customFields.entrySet()) custom.put(e.getKey(), safe(e.getValue()));
            }
            o.put("customFields", custom);
            o.put("createdAt", i.createdAt);
            o.put("updatedAt", i.updatedAt);
            a.put(o);
        }
        return a;
    }

    private static List<CustomCategory> parseCategories(JSONArray a) throws JSONException {
        List<CustomCategory> out = new ArrayList<>();
        if (a == null) return out;
        for (int x=0; x<a.length(); x++) {
            JSONObject o = a.getJSONObject(x);
            CustomCategory c = new CustomCategory();
            c.name = o.optString("name", "").trim();
            c.parentName = o.optString("parentName", "").trim();
            if (c.name.isEmpty()) throw new JSONException("Custom category name is empty");
            JSONArray f = o.optJSONArray("fields");
            if (f != null) for (int j=0;j<f.length();j++) c.fields.add(f.optString(j, ""));
            JSONArray sf = o.optJSONArray("sensitiveFields");
            if (sf != null) for (int j=0;j<sf.length();j++) {
                String label = sf.optString(j, "");
                if (!label.isEmpty() && c.fields.contains(label)) c.sensitiveFields.add(label);
            }
            c.createdAt = o.optLong("createdAt", 0L);
            c.updatedAt = o.optLong("updatedAt", 0L);
            out.add(c);
        }
        return out;
    }

    private static List<VaultItem> parseItems(JSONArray a) throws JSONException {
        List<VaultItem> out = new ArrayList<>();
        if (a == null) return out;
        for (int x=0; x<a.length(); x++) {
            JSONObject o = a.getJSONObject(x);
            VaultItem i = new VaultItem();
            i.title = o.optString("title", "");
            i.category = o.optString("category", "Other");
            i.username = o.optString("username", "");
            i.password = o.optString("password", "");
            i.website = o.optString("website", "");
            i.websiteUrl = o.optString("websiteUrl", "");
            i.phone1 = o.optString("phone1", "");
            i.phone2 = o.optString("phone2", "");
            i.phone3 = o.optString("phone3", "");
            i.notes = o.optString("notes", "");
            i.customFields = new LinkedHashMap<>();
            JSONObject custom = o.optJSONObject("customFields");
            if (custom != null) {
                Iterator<String> keys = custom.keys();
                while (keys.hasNext()) { String k = keys.next(); i.customFields.put(k, custom.optString(k, "")); }
            }
            i.createdAt = o.optLong("createdAt", 0L);
            i.updatedAt = o.optLong("updatedAt", 0L);
            out.add(i);
        }
        return out;
    }

    private static void validateReferences(List<CustomCategory> categories, List<VaultItem> items)
            throws GeneralSecurityException {
        java.util.HashSet<String> customNames = new java.util.HashSet<>();
        for (CustomCategory c : categories) {
            if (!customNames.add(c.name)) throw new GeneralSecurityException("Duplicate custom category: " + c.name);
        }
        for (VaultItem i : items) {
            if (i.category == null || i.category.trim().isEmpty()) i.category = "Other";
            if (i.title == null) i.title = "";
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    public static final class RestoredBackup {
        public final int sourceSchemaVersion;
        public final long createdAt;
        public final List<CustomCategory> categories;
        public final List<VaultItem> items;
        RestoredBackup(int sourceSchemaVersion, long createdAt, List<CustomCategory> categories, List<VaultItem> items) {
            this.sourceSchemaVersion = sourceSchemaVersion;
            this.createdAt = createdAt;
            this.categories = categories;
            this.items = items;
        }
    }
}
