package com.example.privatevault;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns the legacy item dialogs for this session; sensitive models stay local to each dialog. */
final class ItemDialogController implements VaultController {
    interface Gateway {
        VaultItem findItem(long itemId);
        List<CustomCategory> categoryDefinitions();
        List<String> activeBuiltInCategories();
        String categoryPath(String name);
        long saveItem(VaultItem item) throws Exception;
        void deleteItem(long itemId);
        void exportItem(long itemId);
        void copyPassword(String password);
        void copySensitiveField(String value);
        void showMessage(String text);
        boolean isSessionActive();
    }

    private final Activity activity;
    private final VaultViewFactory views;
    private final DialogRegistry dialogRegistry;
    private final Gateway gateway;
    private final ItemDialogActions actions;
    private boolean closed;

    ItemDialogController(Activity activity, VaultViewFactory views, DialogRegistry dialogRegistry,
                         Gateway gateway, ItemDialogActions actions) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.views = Objects.requireNonNull(views, "views");
        this.dialogRegistry = Objects.requireNonNull(dialogRegistry, "dialogRegistry");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    void showDetails(long itemId) {
        if (!active()) return;
        VaultItem item = gateway.findItem(itemId);
        if (item != null) renderDetails(item);
    }

    void showEditor(Long itemId, String initialCategory) {
        if (!active()) return;
        VaultItem item = itemId == null ? null : gateway.findItem(itemId);
        if (itemId != null && item == null) return;
        renderEditor(item, initialCategory);
    }

    private boolean active() { return !closed && gateway.isSessionActive(); }

    @Override public void close() { closed = true; }

    private static String safe(String value) { return value == null ? "" : value; }
    private int dp(int value) { return views.dp(value); }
    private LinearLayout baseVertical(int gap) { return views.verticalContainer(gap); }
    private ScrollView wrap(View view) { return views.scroll(view); }
    private TextView subtitle(String text) { return views.subtitle(text); }
    private TextView boldLabel(String text) { return views.boldLabel(text); }
    private EditText field(String hint, String value) { return views.field(hint, value); }
    private EditText passwordField(String hint) { return views.passwordField(hint); }
    private EditText phoneField(String hint, String value) { return views.phoneField(hint, value); }
    private Button button(String label) { return views.secondaryButton(label); }
    private Button primaryButton(String label) { return views.primaryButton(label); }
    private ImageButton smallIconButton(int icon, String description, boolean danger) {
        return views.smallIconButton(icon, description, danger);
    }
    private LinearLayout.LayoutParams matchWidth() { return views.matchWidth(); }
    private AlertDialog showDialog(AlertDialog dialog) { return dialogRegistry.show(dialog); }
    private String categoryPath(String category) { return gateway.categoryPath(category); }
    private List<String> activeBuiltInCategories() { return gateway.activeBuiltInCategories(); }

    private static final class CategoryOption {
        final String name;
        final String label;
        CategoryOption(String name, String label) { this.name = name; this.label = label; }
        @Override public String toString() { return label; }
    }

    private void renderDetails(VaultItem item) {
        if (!active()) return;
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
        exportEntry.setOnClickListener(v -> gateway.exportItem(item.id));
        body.addView(exportEntry);

        if (!safe(item.password).isEmpty()) {
            TextView label = boldLabel("Password");
            body.addView(label);
            LinearLayout pwRow = new LinearLayout(activity);
            pwRow.setOrientation(LinearLayout.HORIZONTAL);
            TextView pw = new TextView(activity);
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
            copy.setOnClickListener(v -> gateway.copyPassword(item.password));
            pwRow.addView(copy);
            body.addView(pwRow);
        }
        addNonEmptyLabelValue(body, "Notes", item.notes);

        LinearLayout detailActions = new LinearLayout(activity);
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

        AlertDialog d = new AlertDialog.Builder(activity)
                .setTitle(item.title.isEmpty() ? "Vault item" : item.title)
                .setView(wrap(body))
                .create();
        editItem.setOnClickListener(v -> { d.dismiss(); showEditor(item.id, null); });
        deleteItem.setOnClickListener(v -> confirmDelete(item, d));
        closeDetails.setOnClickListener(v -> d.dismiss());
        ScreenSecurityManager.protect(d);
        showDialog(d);
    }

    private void confirmDelete(VaultItem item, AlertDialog parent) {
        if (!active()) return;
        final VaultItem deletedSnapshot = copyVaultItem(item);
        showDialog(new AlertDialog.Builder(activity)
                .setTitle("Delete item?")
                .setMessage("Delete \"" + safe(item.title) + "\" from the vault? You can undo this deletion immediately afterward.")
                .setPositiveButton("Delete", (d, w) -> {
                    if (!active()) return;
                    gateway.deleteItem(item.id);
                    parent.dismiss();
                    actions.onItemDeleted(item.id);
                    showUndoDeletedItem(deletedSnapshot);
                })
                .setNegativeButton("Cancel", null).create());
    }

    static VaultItem copyVaultItem(VaultItem source) {
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
        if (!active()) return;
        showDialog(new AlertDialog.Builder(activity)
                .setTitle("Item deleted")
                .setMessage("\"" + safe(deletedSnapshot.title) + "\" was deleted.")
                .setPositiveButton("Undo", (d, w) -> {
                    try {
                        if (!active()) return;
                        long restoredId = gateway.saveItem(deletedSnapshot);
                        actions.onItemRestored(restoredId);
                        gateway.showMessage("Item restored.");
                    } catch (Exception e) {
                        gateway.showMessage("Could not restore the deleted item.");
                    }
                })
                .setNegativeButton("Dismiss", null)
                .create());
    }

    private void renderEditor(VaultItem existing, String initialCategory) {
        if (!active()) return;
        VaultItem item = existing == null ? new VaultItem() : existing;
        LinearLayout form = baseVertical(6);
        EditText title = field("Title", item.title);
        title.setContentDescription("Item title");
        Spinner category = new Spinner(activity);
        CategoryOption[] editableCats = getEditableCategories();
        category.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, editableCats));
        int idx = 0;
        String requestedCategory = existing == null && initialCategory != null
                ? initialCategory
                : item.category;
        for (int i = 0; i < editableCats.length; i++) {
            if (editableCats[i].name.equals(requestedCategory)) idx = i;
        }
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
        int screenHeightPx = activity.getResources().getDisplayMetrics().heightPixels;
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

        LinearLayout actions = new LinearLayout(activity);
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

        AlertDialog d = new AlertDialog.Builder(activity)
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
                if (!active()) return;
                long savedId = gateway.saveItem(item);
                d.dismiss();
                ItemDialogController.this.actions.onItemSaved(savedId, item.category);
            } catch (Exception e) {
                gateway.showMessage("Could not save encrypted item.");
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
        List<CustomCategory> sorted = new ArrayList<>(gateway.categoryDefinitions());
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
        for (CustomCategory c : gateway.categoryDefinitions()) if (safe(c.name).equals(name)) return c;
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

    private void addLabelValue(LinearLayout body, String label, String value) {
        body.addView(boldLabel(label)); body.addView(subtitle(value));
    }

    private void addNonEmptyLabelValue(LinearLayout body, String label, String value) {
        if (value != null && !value.trim().isEmpty()) addLabelValue(body, label, value);
    }
    private void addSensitiveCustomField(LinearLayout body, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;

        body.addView(boldLabel(label + " (sensitive)"));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView display = new TextView(activity);
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
        copy.setOnClickListener(v -> gateway.copySensitiveField(value));
        row.addView(copy);

        body.addView(row);
    }

}
