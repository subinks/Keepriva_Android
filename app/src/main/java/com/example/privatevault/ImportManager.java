package com.example.privatevault;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JSON import/template support. Values are plaintext only while the user-selected
 * import document is being parsed in memory. VaultDatabase encrypts the complete
 * VaultItem payload before SQLite persistence.
 */
public final class ImportManager {
    private ImportManager() {}

    public static final int TEMPLATE_VERSION = 1;
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
            "text", "textarea", "password", "email", "phone", "url", "number", "date"));
    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList(
            "title", "username", "password", "website", "websiteUrl", "phone1", "phone2", "phone3", "notes", "custom"));

    public static class ParsedImport {
        public final List<VaultItem> items = new ArrayList<>();
        public final List<CustomCategory> categoriesToCreate = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public int sensitiveFieldCount;
        public int multipleValueFieldCount;
    }

    public static String buildTemplate() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("templateVersion", TEMPLATE_VERSION);
        root.put("description", "Keepriva offline import template. Fill values, then import this JSON file in the app.");
        root.put("securityNotice", "This template file is NOT encrypted. Delete it securely after import if it contains secrets. All imported entry data is encrypted by Keepriva before local database storage.");

        JSONArray categories = new JSONArray();
        JSONObject category = new JSONObject();
        category.put("name", "Example Custom Category");
        category.put("parentCategory", "");

        JSONArray fields = new JSONArray();
        fields.put(field("accountName", "Account name", "text", false, false, false, "title"));
        fields.put(field("login", "Username / Email", "email", true, false, false, "username"));
        fields.put(field("secret", "Password", "password", true, false, false, "password"));
        fields.put(field("site", "Website URL", "url", false, false, false, "websiteUrl"));
        fields.put(field("recoveryPhones", "Recovery phone numbers", "phone", true, true, false, "custom"));
        fields.put(field("recoveryCodes", "Recovery codes", "text", true, true, true, "custom"));
        fields.put(field("details", "Notes", "textarea", true, false, true, "notes"));
        category.put("fields", fields);

        JSONArray entries = new JSONArray();
        JSONObject example = new JSONObject();
        JSONObject values = new JSONObject();
        values.put("accountName", "Example account");
        values.put("login", "user@example.com");
        values.put("secret", "replace-with-password");
        values.put("site", "https://example.com");
        values.put("recoveryPhones", new JSONArray().put("+91 9000000001").put("+91 9000000002"));
        values.put("recoveryCodes", new JSONArray().put("CODE-ONE").put("CODE-TWO"));
        values.put("details", "Multiline notes are supported.\nSecond line.");
        example.put("values", values);
        entries.put(example);
        category.put("entries", entries);
        categories.put(category);
        root.put("categories", categories);
        return root.toString(2);
    }

    private static JSONObject field(String key, String label, String type, boolean sensitive,
                                    boolean multipleValues, boolean multiline, String target) throws JSONException {
        JSONObject f = new JSONObject();
        f.put("key", key);
        f.put("label", label);
        f.put("type", type);
        f.put("sensitive", sensitive);
        f.put("multipleValues", multipleValues);
        f.put("multiline", multiline);
        f.put("target", target);
        return f;
    }

    public static ParsedImport parse(String json, List<CustomCategory> existingCustomCategories,
                                     String[] builtInCategories) {
        ParsedImport result = new ParsedImport();
        try {
            JSONObject root = new JSONObject(json);
            int version = root.optInt("templateVersion", -1);
            if (version != TEMPLATE_VERSION) {
                result.errors.add("Unsupported templateVersion " + version + ". Expected " + TEMPLATE_VERSION + ".");
                return result;
            }
            JSONArray categories = root.optJSONArray("categories");
            if (categories == null || categories.length() == 0) {
                result.errors.add("No categories were found in the template.");
                return result;
            }

            Set<String> knownBuiltIns = new HashSet<>();
            for (String s : builtInCategories) knownBuiltIns.add(s.toLowerCase(Locale.ROOT));
            Set<String> knownCustom = new HashSet<>();
            for (CustomCategory c : existingCustomCategories) knownCustom.add(safe(c.name).toLowerCase(Locale.ROOT));
            Set<String> pendingCustom = new HashSet<>();

            for (int ci = 0; ci < categories.length(); ci++) {
                JSONObject cat = categories.optJSONObject(ci);
                if (cat == null) { result.errors.add("Category #" + (ci + 1) + " is not an object."); continue; }
                String categoryName = cat.optString("name", "").trim();
                String parentCategory = cat.optString("parentCategory", "").trim();
                if (categoryName.isEmpty()) { result.errors.add("Category #" + (ci + 1) + " has no name."); continue; }

                JSONArray fieldsArray = cat.optJSONArray("fields");
                if (fieldsArray == null || fieldsArray.length() == 0) {
                    result.errors.add("Category '" + categoryName + "' has no field definitions."); continue;
                }

                Map<String, FieldDef> defs = new LinkedHashMap<>();
                List<String> customFieldLabels = new ArrayList<>();
                List<String> sensitiveCustomFieldLabels = new ArrayList<>();
                for (int fi = 0; fi < fieldsArray.length(); fi++) {
                    JSONObject f = fieldsArray.optJSONObject(fi);
                    if (f == null) { result.errors.add(categoryName + ": field #" + (fi + 1) + " is invalid."); continue; }
                    FieldDef d = new FieldDef();
                    d.key = f.optString("key", "").trim();
                    d.label = f.optString("label", d.key).trim();
                    d.type = f.optString("type", "text").trim().toLowerCase(Locale.ROOT);
                    d.target = f.optString("target", "custom").trim();
                    d.sensitive = f.optBoolean("sensitive", false);
                    d.multiple = f.optBoolean("multipleValues", false);
                    d.multiline = f.optBoolean("multiline", "textarea".equals(d.type));
                    if (d.key.isEmpty()) { result.errors.add(categoryName + ": a field has an empty key."); continue; }
                    if (defs.containsKey(d.key)) { result.errors.add(categoryName + ": duplicate field key '" + d.key + "'."); continue; }
                    if (!TYPES.contains(d.type)) result.errors.add(categoryName + ": field '" + d.key + "' has unsupported type '" + d.type + "'.");
                    if (!TARGETS.contains(d.target)) result.errors.add(categoryName + ": field '" + d.key + "' has unsupported target '" + d.target + "'.");
                    defs.put(d.key, d);
                    if (d.sensitive) result.sensitiveFieldCount++;
                    if (d.multiple) result.multipleValueFieldCount++;
                    if ("custom".equals(d.target)) {
                        String customLabel = d.label.isEmpty() ? d.key : d.label;
                        customFieldLabels.add(customLabel);
                        if (d.sensitive) sensitiveCustomFieldLabels.add(customLabel);
                    }
                }

                String lc = categoryName.toLowerCase(Locale.ROOT);
                if (!knownBuiltIns.contains(lc) && !knownCustom.contains(lc) && pendingCustom.add(lc)) {
                    CustomCategory cc = new CustomCategory();
                    cc.name = categoryName;
                    cc.parentName = parentCategory;
                    cc.fields.addAll(customFieldLabels);
                    cc.sensitiveFields.addAll(sensitiveCustomFieldLabels);
                    result.categoriesToCreate.add(cc);
                }

                JSONArray entries = cat.optJSONArray("entries");
                if (entries == null) { result.warnings.add("Category '" + categoryName + "' has no entries array."); continue; }
                for (int ei = 0; ei < entries.length(); ei++) {
                    JSONObject entry = entries.optJSONObject(ei);
                    if (entry == null) { result.errors.add(categoryName + ": entry #" + (ei + 1) + " is invalid."); continue; }
                    JSONObject values = entry.optJSONObject("values");
                    if (values == null) { result.errors.add(categoryName + ": entry #" + (ei + 1) + " has no values object."); continue; }
                    VaultItem item = new VaultItem();
                    item.category = categoryName;
                    for (FieldDef d : defs.values()) {
                        Object raw = values.opt(d.key);
                        if (raw == null || raw == JSONObject.NULL) continue;
                        String normalized = normalizeValue(raw, d, result, categoryName, ei + 1);
                        if (normalized == null) continue;
                        assign(item, d, normalized);
                    }
                    if (safe(item.title).trim().isEmpty()) {
                        item.title = firstNonBlank(item.website, item.username, categoryName + " entry " + (ei + 1));
                        result.warnings.add(categoryName + ": entry #" + (ei + 1) + " had no title; generated '" + item.title + "'.");
                    }
                    result.items.add(item);
                }
            }
        } catch (JSONException e) {
            result.errors.add("Invalid JSON: " + e.getMessage());
        }
        return result;
    }

    private static String normalizeValue(Object raw, FieldDef d, ParsedImport result, String category, int entryNo) {
        if (d.multiple) {
            if (raw instanceof JSONArray) {
                JSONArray a = (JSONArray) raw;
                List<String> values = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    String v = a.optString(i, "").trim();
                    if (!v.isEmpty()) values.add(v);
                }
                return joinLines(values);
            }
            result.warnings.add(category + ": entry #" + entryNo + ", field '" + d.key + "' is marked multipleValues but is not an array; imported as one value.");
        } else if (raw instanceof JSONArray) {
            result.warnings.add(category + ": entry #" + entryNo + ", field '" + d.key + "' is not marked multipleValues; array values were joined by new lines.");
            JSONArray a = (JSONArray) raw;
            List<String> values = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) values.add(a.optString(i, ""));
            return joinLines(values);
        }
        return String.valueOf(raw);
    }

    private static void assign(VaultItem i, FieldDef d, String value) {
        switch (d.target) {
            case "title": i.title = value; break;
            case "username": i.username = value; break;
            case "password": i.password = value; break;
            case "website": i.website = value; break;
            case "websiteUrl": i.websiteUrl = value; break;
            case "phone1": i.phone1 = value; break;
            case "phone2": i.phone2 = value; break;
            case "phone3": i.phone3 = value; break;
            case "notes": i.notes = value; break;
            default: i.customFields.put(d.label.isEmpty() ? d.key : d.label, value); break;
        }
    }

    private static String joinLines(List<String> values) {
        StringBuilder b = new StringBuilder();
        for (String v : values) {
            if (b.length() > 0) b.append('\n');
            b.append(v);
        }
        return b.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (!safe(v).trim().isEmpty()) return v.trim();
        return "Imported entry";
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static class FieldDef {
        String key, label, type, target;
        boolean sensitive, multiple, multiline;
    }
}
