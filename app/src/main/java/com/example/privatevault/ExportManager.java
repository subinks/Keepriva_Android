package com.example.privatevault;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ExportManager {
    private ExportManager() {}

    public static final class Options {
        public final boolean includePasswords;
        public final boolean includeSensitiveCustomFields;
        private final Map<String, Set<String>> sensitiveByCategory;

        public Options(boolean includePasswords, boolean includeSensitiveCustomFields,
                       Map<String, Set<String>> sensitiveByCategory) {
            this.includePasswords = includePasswords;
            this.includeSensitiveCustomFields = includeSensitiveCustomFields;
            this.sensitiveByCategory = sensitiveByCategory == null
                    ? Collections.emptyMap() : sensitiveByCategory;
        }

        boolean isSensitiveCustom(String category, String label) {
            Set<String> labels = sensitiveByCategory.get(safe(category));
            return labels != null && labels.contains(safe(label));
        }
    }

    /**
     * Plaintext structured export using the same JSON contract consumed by ImportManager.
     * It is re-importable, but it is NOT an encrypted backup; .pvault remains the secure
     * backup/recovery format.
     */
    public static String toImportCompatibleJson(
            List<VaultItem> items,
            Options options,
            List<CustomCategory> customCategories) throws Exception {

        JSONObject root = new JSONObject();
        root.put("templateVersion", ImportManager.TEMPLATE_VERSION);
        root.put("description",
                "Keepriva structured JSON export. Import it using Import data.");
        root.put("securityNotice",
                "This JSON export is NOT encrypted. Use .pvault for encrypted backup/restore.");
        root.put("exportPolicy", policyText(options));

        Map<String, CustomCategory> metadataByName = new LinkedHashMap<>();
        if (customCategories != null) {
            for (CustomCategory c : customCategories) metadataByName.put(safe(c.name), c);
        }

        Map<String, List<VaultItem>> grouped = new LinkedHashMap<>();
        for (VaultItem item : items) {
            String category = safe(item.category).trim();
            if (category.isEmpty()) category = "Other";
            grouped.computeIfAbsent(category, k -> new ArrayList<>()).add(item);
        }

        JSONArray categories = new JSONArray();

        for (Map.Entry<String, List<VaultItem>> group : grouped.entrySet()) {
            String categoryName = group.getKey();
            List<VaultItem> categoryItems = group.getValue();

            JSONObject category = new JSONObject();
            category.put("name", categoryName);

            CustomCategory metadata = metadataByName.get(categoryName);
            category.put("parentCategory",
                    metadata == null ? "" : safe(metadata.parentName));

            JSONArray fields = new JSONArray();
            fields.put(jsonField("title", "Title", "text", false, false, false, "title"));
            fields.put(jsonField("username", "Username / Email", "text", false, false, false, "username"));

            if (options != null && options.includePasswords) {
                fields.put(jsonField("password", "Password", "password", true, false, false, "password"));
            }

            fields.put(jsonField("phone1", "Phone 1", "phone", false, false, false, "phone1"));
            fields.put(jsonField("phone2", "Phone 2", "phone", false, false, false, "phone2"));
            fields.put(jsonField("phone3", "Phone 3", "phone", false, false, false, "phone3"));
            fields.put(jsonField("website", "Website / App", "text", false, false, false, "website"));
            fields.put(jsonField("websiteUrl", "Website URL", "url", false, false, false, "websiteUrl"));
            fields.put(jsonField("notes", "Notes", "textarea", false, false, true, "notes"));

            LinkedHashSet<String> customLabels = new LinkedHashSet<>();
            if (metadata != null && metadata.fields != null) {
                customLabels.addAll(metadata.fields);
            }
            for (VaultItem item : categoryItems) {
                if (item.customFields != null) customLabels.addAll(item.customFields.keySet());
            }

            Map<String, String> customKeys = new LinkedHashMap<>();
            int customIndex = 1;
            for (String label : customLabels) {
                boolean sensitive = options != null && options.isSensitiveCustom(categoryName, label);
                if (sensitive && !options.includeSensitiveCustomFields) continue;

                String key = "custom_" + customIndex++;
                customKeys.put(label, key);
                fields.put(jsonField(key, label, "text", sensitive, false, false, "custom"));
            }

            category.put("fields", fields);

            JSONArray entries = new JSONArray();
            for (VaultItem item : categoryItems) {
                JSONObject values = new JSONObject();

                putIfNotBlank(values, "title", item.title);
                putIfNotBlank(values, "username", item.username);
                if (options != null && options.includePasswords) {
                    putIfNotBlank(values, "password", item.password);
                }
                putIfNotBlank(values, "phone1", item.phone1);
                putIfNotBlank(values, "phone2", item.phone2);
                putIfNotBlank(values, "phone3", item.phone3);
                putIfNotBlank(values, "website", item.website);
                putIfNotBlank(values, "websiteUrl", item.websiteUrl);
                putIfNotBlank(values, "notes", item.notes);

                if (item.customFields != null) {
                    for (Map.Entry<String, String> custom : item.customFields.entrySet()) {
                        String key = customKeys.get(custom.getKey());
                        if (key != null) putIfNotBlank(values, key, custom.getValue());
                    }
                }

                JSONObject entry = new JSONObject();
                entry.put("values", values);
                entries.put(entry);
            }

            category.put("entries", entries);
            categories.put(category);
        }

        root.put("categories", categories);
        return root.toString(2);
    }

    private static JSONObject jsonField(
            String key, String label, String type, boolean sensitive,
            boolean multipleValues, boolean multiline, String target) throws Exception {

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

    private static void putIfNotBlank(JSONObject target, String key, String value)
            throws Exception {
        if (!safe(value).trim().isEmpty()) target.put(key, value);
    }
    public static String toText(List<VaultItem> items, Options options) {
        StringBuilder out = new StringBuilder();
        out.append("KEEPRIVA EXPORT\n");
        out.append("Generated: ").append(DateFormat.getDateTimeInstance().format(new Date())).append("\n");
        out.append("WARNING: This exported document is not encrypted.\n");
        appendPolicyText(out, options);
        out.append("\n");
        for (int i = 0; i < items.size(); i++) {
            VaultItem item = items.get(i);
            out.append("============================================================\n");
            out.append(safe(item.title).isEmpty() ? "Untitled" : item.title).append("\n");
            out.append("============================================================\n");
            for (FieldValue f : fields(item, options)) out.append(f.label).append(": ").append(f.value).append("\n");
            if (i < items.size() - 1) out.append("\n");
        }
        return out.toString();
    }

    public static String toHtml(List<VaultItem> items, Options options) {
        StringBuilder out = new StringBuilder();
        out.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        out.append("<title>Keepriva Export</title><style>")
                .append("body{font-family:Arial,sans-serif;max-width:900px;margin:32px auto;padding:0 18px;color:#243447;background:#f7f9fb}")
                .append(".warning{padding:12px;border:1px solid #b06b00;background:#fff5df;border-radius:8px}.policy{padding:12px;margin-top:10px;border:1px solid #9aa4b2;background:#eef2f6;border-radius:8px}.item{background:white;margin:18px 0;padding:20px;border-radius:10px;box-shadow:0 1px 4px #0002}")
                .append("h1,h2{margin-top:0}table{border-collapse:collapse;width:100%}td{padding:8px;border-bottom:1px solid #e5e7eb;vertical-align:top}td:first-child{font-weight:bold;width:30%}pre{white-space:pre-wrap;font-family:inherit;margin:0}")
                .append("</style></head><body>");
        out.append("<h1>Keepriva Export</h1><p>Generated: ").append(html(DateFormat.getDateTimeInstance().format(new Date()))).append("</p>");
        out.append("<div class=\"warning\"><strong>Warning:</strong> This exported document is not encrypted.</div>");
        out.append("<div class=\"policy\">").append(html(policyText(options))).append("</div>");
        for (VaultItem item : items) {
            out.append("<section class=\"item\"><h2>").append(html(safe(item.title).isEmpty() ? "Untitled" : item.title)).append("</h2><table>");
            for (FieldValue f : fields(item, options)) {
                out.append("<tr><td>").append(html(f.label)).append("</td><td><pre>").append(html(f.value)).append("</pre></td></tr>");
            }
            out.append("</table></section>");
        }
        out.append("</body></html>");
        return out.toString();
    }

    public static byte[] toPdf(List<VaultItem> items, Options options) throws Exception {
        PdfDocument document = new PdfDocument();
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTextSize(18f); titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setTextSize(11f); labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(11f);
        final int width = 595, height = 842, left = 42, right = 42, top = 48, bottom = 48;
        final float maxWidth = width - left - right;
        int pageNo = 0;
        PdfDocument.Page page = null;
        Canvas canvas = null;
        float y = 0;

        for (int itemIndex = -1; itemIndex < items.size(); itemIndex++) {
            List<Line> lines = new ArrayList<>();
            if (itemIndex == -1) {
                lines.add(new Line("Keepriva Export", true, true));
                lines.add(new Line("Generated: " + DateFormat.getDateTimeInstance().format(new Date()), false, false));
                lines.add(new Line("WARNING: This exported document is not encrypted.", true, false));
                for (String wrapped : wrap(policyText(options), textPaint, maxWidth)) lines.add(new Line(wrapped, false, false));
                lines.add(new Line("", false, false));
            } else {
                VaultItem item = items.get(itemIndex);
                lines.add(new Line(safe(item.title).isEmpty() ? "Untitled" : item.title, true, true));
                for (FieldValue f : fields(item, options)) {
                    lines.add(new Line(f.label + ":", true, false));
                    for (String wrapped : wrap(f.value, textPaint, maxWidth - 16)) lines.add(new Line("  " + wrapped, false, false));
                }
                lines.add(new Line("", false, false));
            }

            for (Line line : lines) {
                Paint paint = line.heading ? titlePaint : (line.bold ? labelPaint : textPaint);
                float lineHeight = line.heading ? 27f : 17f;
                if (page == null || y + lineHeight > height - bottom) {
                    if (page != null) document.finishPage(page);
                    pageNo++;
                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(width, height, pageNo).create();
                    page = document.startPage(info);
                    canvas = page.getCanvas();
                    y = top;
                }
                canvas.drawText(line.text, left, y, paint);
                y += lineHeight;
            }
        }
        if (page != null) document.finishPage(page);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.writeTo(out);
        document.close();
        return out.toByteArray();
    }

    private static void appendPolicyText(StringBuilder out, Options options) {
        out.append(policyText(options)).append("\n");
    }

    private static String policyText(Options options) {
        if (options == null) return "Export policy: passwords and sensitive custom fields omitted.";
        String passwordState = options.includePasswords ? "INCLUDED" : "omitted";
        String customState = options.includeSensitiveCustomFields ? "INCLUDED" : "omitted";
        return "Export policy: passwords " + passwordState + "; sensitive custom fields " + customState + ".";
    }

    private static List<String> wrap(String text, Paint paint, float maxWidth) {
        List<String> lines = new ArrayList<>();
        String normalized = safe(text).replace("\r", "");
        for (String paragraph : normalized.split("\n", -1)) {
            if (paragraph.isEmpty()) { lines.add(""); continue; }
            String[] words = paragraph.split("\\s+");
            StringBuilder current = new StringBuilder();
            for (String word : words) {
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (paint.measureText(candidate) <= maxWidth) current = new StringBuilder(candidate);
                else {
                    if (current.length() > 0) lines.add(current.toString());
                    current = new StringBuilder(word);
                }
            }
            if (current.length() > 0) lines.add(current.toString());
        }
        return lines;
    }

    private static List<FieldValue> fields(VaultItem item, Options options) {
        List<FieldValue> result = new ArrayList<>();
        add(result, "Category", item.category);
        add(result, "Username / Email", item.username);
        if (options != null && options.includePasswords) add(result, "Password", item.password);
        add(result, "Phone 1", item.phone1);
        add(result, "Phone 2", item.phone2);
        add(result, "Phone 3", item.phone3);
        add(result, "Website / App", item.website);
        add(result, "Website URL", item.websiteUrl);
        if (item.customFields != null) {
            for (Map.Entry<String, String> e : item.customFields.entrySet()) {
                boolean sensitive = options != null && options.isSensitiveCustom(item.category, e.getKey());
                if (!sensitive || options.includeSensitiveCustomFields) add(result, e.getKey(), e.getValue());
            }
        }
        add(result, "Notes", item.notes);
        return result;
    }

    private static void add(List<FieldValue> list, String label, String value) {
        if (!safe(value).trim().isEmpty()) list.add(new FieldValue(label, value));
    }

    private static String html(String s) {
        return safe(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static final class FieldValue {
        final String label, value;
        FieldValue(String label, String value) { this.label = label; this.value = value; }
    }

    private static final class Line {
        final String text; final boolean bold; final boolean heading;
        Line(String text, boolean bold, boolean heading) { this.text = text; this.bold = bold; this.heading = heading; }
    }
}
