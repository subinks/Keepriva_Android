package com.example.privatevault;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

/**
 * Local encrypted vault database.
 *
 * Migration policy:
 *  - Never use destructive fallback migrations.
 *  - Every schema upgrade is represented by an explicit sequential migration.
 *  - A missing migration path throws rather than recreating/deleting user data.
 *  - SQLiteOpenHelper executes onUpgrade() inside its database transaction, so a
 *    failed migration rolls back before the database version is advanced.
 */
public class VaultDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "private_vault.db";

    // Schema history:
    // v1: vault_items
    // v2: custom_categories
    // v3: migration_history + indexes used by encrypted record/category ordering
    private static final int DB_VERSION = 3;

    public VaultDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createVaultItemsTable(db);
        createCustomCategoriesTable(db);
        createMigrationHistoryTable(db);
        createLatestIndexes(db);
        recordMigration(db, 0, DB_VERSION, "fresh_install_v3");
        verifySchema(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1) {
            throw new IllegalStateException("Unsupported vault database version: " + oldVersion);
        }
        if (newVersion > DB_VERSION) {
            throw new IllegalStateException("Database is newer than this app supports: " + newVersion);
        }

        int current = oldVersion;
        while (current < newVersion) {
            if (current == 1) {
                migrate1To2(db);
                current = 2;
            } else if (current == 2) {
                migrate2To3(db);
                current = 3;
            } else {
                // Never guess how to upgrade a vault. Missing migration = hard failure.
                throw new IllegalStateException(
                        "No non-destructive migration registered from database version " + current);
            }
        }
        verifySchema(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // A downgrade could cause an older app to misunderstand newer encrypted data.
        // Refuse instead of using SQLiteOpenHelper's default destructive/undefined path.
        throw new IllegalStateException(
                "Vault database downgrade is not supported (" + oldVersion + " -> " + newVersion + ")");
    }

    private void migrate1To2(SQLiteDatabase db) {
        createCustomCategoriesTable(db);
        // migration_history did not exist in v2, so this step cannot be recorded yet.
    }

    private void migrate2To3(SQLiteDatabase db) {
        createMigrationHistoryTable(db);
        createLatestIndexes(db);
        recordMigration(db, 2, 3, "add_migration_history_and_indexes");
    }

    private void createVaultItemsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS vault_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "payload TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");
    }

    private void createCustomCategoriesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS custom_categories (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "payload TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");
    }

    private void createMigrationHistoryTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS migration_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "from_version INTEGER NOT NULL," +
                "to_version INTEGER NOT NULL," +
                "migration_name TEXT NOT NULL," +
                "applied_at INTEGER NOT NULL)");
    }

    private void createLatestIndexes(SQLiteDatabase db) {
        // Payload remains encrypted; indexes intentionally cover metadata only.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_items_updated_at " +
                "ON vault_items(updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_custom_categories_updated_at " +
                "ON custom_categories(updated_at DESC)");
    }

    private void recordMigration(SQLiteDatabase db, int from, int to, String name) {
        if (!tableExists(db, "migration_history")) return;
        ContentValues cv = new ContentValues();
        cv.put("from_version", from);
        cv.put("to_version", to);
        cv.put("migration_name", name);
        cv.put("applied_at", System.currentTimeMillis());
        db.insertOrThrow("migration_history", null, cv);
    }

    private void verifySchema(SQLiteDatabase db) {
        String[] requiredTables = {"vault_items", "custom_categories", "migration_history"};
        for (String table : requiredTables) {
            if (!tableExists(db, table)) {
                throw new IllegalStateException(
                        "Vault schema verification failed: missing table " + table);
            }
        }
    }

    private boolean tableExists(SQLiteDatabase db, String tableName) {
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                new String[]{tableName})) {
            return c.moveToFirst();
        }
    }

    public long save(VaultItem item, SecretKey key) throws GeneralSecurityException, JSONException {
        return save(getWritableDatabase(), item, key);
    }

    private long save(SQLiteDatabase db, VaultItem item, SecretKey key)
            throws GeneralSecurityException, JSONException {
        long now = System.currentTimeMillis();
        if (item.createdAt == 0) item.createdAt = now;
        item.updatedAt = now;
        String payload = CryptoManager.encrypt(key, toJson(item).toString());
        ContentValues cv = new ContentValues();
        cv.put("payload", payload);
        cv.put("created_at", item.createdAt);
        cv.put("updated_at", item.updatedAt);
        if (item.id == 0) item.id = db.insertOrThrow("vault_items", null, cv);
        else db.update("vault_items", cv, "id=?", new String[]{String.valueOf(item.id)});
        return item.id;
    }

    public void delete(long id) {
        getWritableDatabase().delete("vault_items", "id=?", new String[]{String.valueOf(id)});
    }

    public List<VaultItem> list(SecretKey key) throws GeneralSecurityException, JSONException {
        List<VaultItem> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("vault_items",
                new String[]{"id", "payload", "created_at", "updated_at"},
                null, null, null, null, "updated_at DESC")) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String plaintext = CryptoManager.decrypt(key, c.getString(1));
                VaultItem item = fromJson(new JSONObject(plaintext));
                item.id = id;
                item.createdAt = c.getLong(2);
                item.updatedAt = c.getLong(3);
                result.add(item);
            }
        }
        return result;
    }

    public void importBatch(List<CustomCategory> categories, List<VaultItem> items, SecretKey key)
            throws GeneralSecurityException, JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (CustomCategory category : categories) saveCustomCategory(db, category, key);
            for (VaultItem item : items) save(db, item, key);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public long saveCustomCategory(CustomCategory category, SecretKey key)
            throws GeneralSecurityException, JSONException {
        return saveCustomCategory(getWritableDatabase(), category, key);
    }

    private long saveCustomCategory(SQLiteDatabase db, CustomCategory category, SecretKey key)
            throws GeneralSecurityException, JSONException {
        long now = System.currentTimeMillis();
        if (category.createdAt == 0) category.createdAt = now;
        category.updatedAt = now;
        JSONObject json = new JSONObject();
        json.put("name", safe(category.name));
        json.put("parentName", safe(category.parentName));
        JSONArray fields = new JSONArray();
        for (String field : category.fields) fields.put(field);
        json.put("fields", fields);
        JSONArray sensitiveFields = new JSONArray();
        for (String field : category.sensitiveFields) sensitiveFields.put(field);
        json.put("sensitiveFields", sensitiveFields);
        String payload = CryptoManager.encrypt(key, json.toString());
        ContentValues cv = new ContentValues();
        cv.put("payload", payload);
        cv.put("created_at", category.createdAt);
        cv.put("updated_at", category.updatedAt);
        if (category.id == 0) category.id = db.insertOrThrow("custom_categories", null, cv);
        else db.update("custom_categories", cv, "id=?", new String[]{String.valueOf(category.id)});
        return category.id;
    }

    public List<CustomCategory> listCustomCategories(SecretKey key)
            throws GeneralSecurityException, JSONException {
        List<CustomCategory> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("custom_categories",
                new String[]{"id", "payload", "created_at", "updated_at"},
                null, null, null, null, "updated_at DESC")) {
            while (c.moveToNext()) {
                String plaintext = CryptoManager.decrypt(key, c.getString(1));
                JSONObject json = new JSONObject(plaintext);
                CustomCategory category = new CustomCategory();
                category.id = c.getLong(0);
                category.name = json.optString("name", "Custom");
                category.parentName = json.optString("parentName", "");
                JSONArray fields = json.optJSONArray("fields");
                if (fields != null) {
                    for (int i = 0; i < fields.length(); i++) {
                        category.fields.add(fields.optString(i, ""));
                    }
                }
                JSONArray sensitiveFields = json.optJSONArray("sensitiveFields");
                if (sensitiveFields != null) {
                    for (int i = 0; i < sensitiveFields.length(); i++) {
                        String label = sensitiveFields.optString(i, "");
                        if (!label.isEmpty()) category.sensitiveFields.add(label);
                    }
                }
                category.createdAt = c.getLong(2);
                category.updatedAt = c.getLong(3);
                result.add(category);
            }
        }
        return result;
    }

    public void deleteCustomCategory(long id) {
        getWritableDatabase().delete("custom_categories", "id=?", new String[]{String.valueOf(id)});
    }

    /**
     * Deletes every item and custom-category row in a resolved category subtree.
     * The caller resolves names/ids from already decrypted in-memory models; this
     * method guarantees all-or-nothing persistence with one SQLite transaction.
     */
    public void deleteCategorySubtree(List<VaultItem> items,
                                      java.util.Set<String> categoryNames,
                                      java.util.Set<Long> customCategoryIds) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (VaultItem item : items) {
                if (categoryNames.contains(safe(item.category).toLowerCase(java.util.Locale.ROOT))) {
                    db.delete("vault_items", "id=?", new String[]{String.valueOf(item.id)});
                }
            }
            for (Long id : customCategoryIds) {
                db.delete("custom_categories", "id=?", new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
    /**
     * Renames or reparents a custom category and updates all encrypted references atomically.
     * Entry/category payloads remain encrypted throughout the operation.
     */
    public void saveCustomCategoryAndUpdateReferences(CustomCategory category, String oldName,
                                                       List<CustomCategory> categories,
                                                       List<VaultItem> items, SecretKey key)
            throws GeneralSecurityException, JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            saveCustomCategory(db, category, key);
            if (oldName != null && !oldName.isEmpty() && !oldName.equals(category.name)) {
                for (VaultItem item : items) {
                    if (oldName.equals(safe(item.category))) {
                        item.category = category.name;
                        save(db, item, key);
                    }
                }
                for (CustomCategory child : categories) {
                    if (child.id != category.id && oldName.equals(safe(child.parentName))) {
                        child.parentName = category.name;
                        saveCustomCategory(db, child, key);
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Moves direct entries and direct child categories to a destination category, then deletes
     * the source category. Descendants below each direct child remain attached to that child.
     */
    public void moveContentsAndDeleteCustomCategory(List<VaultItem> items,
                                                     List<CustomCategory> categories,
                                                     String sourceCategory,
                                                     String destinationCategory,
                                                     long categoryId, SecretKey key)
            throws GeneralSecurityException, JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (VaultItem item : items) {
                if (safe(item.category).equals(sourceCategory)) {
                    item.category = destinationCategory;
                    save(db, item, key);
                }
            }
            for (CustomCategory child : categories) {
                if (child.id != categoryId && safe(child.parentName).equals(sourceCategory)) {
                    child.parentName = destinationCategory;
                    saveCustomCategory(db, child, key);
                }
            }
            db.delete("custom_categories", "id=?", new String[]{String.valueOf(categoryId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void moveEntriesAndDeleteCustomCategory(List<VaultItem> items, String sourceCategory,
                                                    String destinationCategory, long categoryId,
                                                    SecretKey key)
            throws GeneralSecurityException, JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (VaultItem item : items) {
                if (safe(item.category).equals(sourceCategory)) {
                    item.category = destinationCategory;
                    save(db, item, key);
                }
            }
            db.delete("custom_categories", "id=?", new String[]{String.valueOf(categoryId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Replaces the logical vault contents in one transaction after a backup has already
     * been decrypted and fully validated. Existing data remains intact if any write fails.
     */
    public void replaceAllFromBackup(List<CustomCategory> categories, List<VaultItem> items, SecretKey key)
            throws GeneralSecurityException, JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("vault_items", null, null);
            db.delete("custom_categories", null, null);
            for (CustomCategory c : categories) {
                c.id = 0;
                saveCustomCategory(db, c, key);
            }
            for (VaultItem i : items) {
                i.id = 0;
                save(db, i, key);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Returns the current SQLite schema version for diagnostics/tests. */
    public int currentSchemaVersion() {
        return getReadableDatabase().getVersion();
    }

    /** Returns non-sensitive migration audit information only. */
    public List<String> migrationHistory() {
        List<String> history = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "migration_history",
                new String[]{"from_version", "to_version", "migration_name", "applied_at"},
                null, null, null, null, "id ASC")) {
            while (c.moveToNext()) {
                history.add(c.getInt(0) + "->" + c.getInt(1) + ":" + c.getString(2) + "@" + c.getLong(3));
            }
        }
        return history;
    }

    private static JSONObject toJson(VaultItem i) throws JSONException {
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
            for (Map.Entry<String, String> e : i.customFields.entrySet()) {
                custom.put(e.getKey(), safe(e.getValue()));
            }
        }
        o.put("customFields", custom);
        return o;
    }

    private static VaultItem fromJson(JSONObject o) {
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
            while (keys.hasNext()) {
                String key = keys.next();
                i.customFields.put(key, custom.optString(key, ""));
            }
        }
        return i;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
