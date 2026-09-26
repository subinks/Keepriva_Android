package com.example.privatevault;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Temporary behavior-preserving owner of category-management dialogs.
 *
 * <p>The controller never owns a session key or decrypted item collection. Database work is
 * performed through the narrow gateway, and successful mutations are reported as typed events.
 */
final class CategoryManagementController implements VaultController {
    private final Activity activity;
    private final VaultViewFactory views;
    private final DialogRegistry dialogs;
    private final CategoryHierarchyService hierarchy;
    private final Gateway gateway;
    private final CategoryManagementActions actions;
    private boolean closed;

    CategoryManagementController(
            Activity activity,
            VaultViewFactory views,
            DialogRegistry dialogs,
            CategoryHierarchyService hierarchy,
            Gateway gateway,
            CategoryManagementActions actions) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.views = Objects.requireNonNull(views, "views");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    void showManager() {
        if (closed) return;
        List<CustomCategory> categories = gateway.categoryDefinitions();
        LinearLayout root = views.verticalContainer(4);
        root.setPadding(views.dp(10), views.dp(8), views.dp(10), views.dp(8));

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = UiStyle.sectionTitle(activity, "Manage Categories");
        top.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ImageButton close = views.smallIconButton(
                R.drawable.ic_keepriva_close, "Close category manager", false);
        close.setTooltipText("Close category manager");
        top.addView(close, new LinearLayout.LayoutParams(views.dp(36), views.dp(36)));
        root.addView(top, views.matchWidth());

        LinearLayout hierarchyHeader = new LinearLayout(activity);
        hierarchyHeader.setOrientation(LinearLayout.HORIZONTAL);
        hierarchyHeader.setGravity(Gravity.CENTER_VERTICAL);
        hierarchyHeader.setPadding(0, views.dp(4), 0, views.dp(4));
        ImageButton add = views.smallIconButton(
                R.drawable.ic_keepriva_add, "Add category", false);
        add.setTooltipText("Add category or subcategory");
        hierarchyHeader.addView(add, new LinearLayout.LayoutParams(views.dp(36), views.dp(36)));
        TextView heading = views.boldLabel("Category Hierarchy");
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        headingParams.leftMargin = views.dp(8);
        hierarchyHeader.addView(heading, headingParams);
        root.addView(hierarchyHeader, views.matchWidth());

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (String builtIn : gateway.activeBuiltInCategories()) {
            addBuiltInRow(rows, builtIn, categories);
        }
        List<CustomCategory> sorted = new ArrayList<>(categories);
        sorted.sort((left, right) -> hierarchy.path(left.name, categories)
                .compareToIgnoreCase(hierarchy.path(right.name, categories)));
        for (CustomCategory category : sorted) addCustomRow(rows, category, categories);
        if (rows.getChildCount() == 0) {
            TextView empty = views.subtitle("No categories available. Use + to create one.");
            empty.setContentDescription("Empty category hierarchy");
            rows.addView(empty);
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        AlertDialog dialog = new AlertDialog.Builder(activity).setView(root).create();
        root.setTag(dialog);
        close.setOnClickListener(v -> {
            dialog.dismiss();
            actions.onCategoryManagementClosed();
        });
        add.setOnClickListener(v -> {
            dialog.dismiss();
            showEditor(null, null);
        });
        ScreenSecurityManager.protect(dialog);
        dialog.setOnShowListener(ignored -> {
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.88f));
            }
        });
        dialogs.show(dialog);
    }

    void showEditor(Long categoryId, String initialParentName) {
        if (closed) return;
        List<CustomCategory> categories = gateway.categoryDefinitions();
        CustomCategory existing = findById(categoryId, categories);
        CustomCategory model = existing == null ? new CustomCategory() : copyCategory(existing);
        LinearLayout form = views.verticalContainer(6);
        EditText name = views.field("Category name", model.name);

        Spinner parent = new Spinner(activity);
        Option[] parentOptions = parentOptionsFor(existing, categories);
        parent.setAdapter(new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_dropdown_item, parentOptions));
        int parentIndex = 0;
        String requestedParent = existing == null && initialParentName != null
                ? initialParentName
                : safe(model.parentName);
        for (int index = 0; index < parentOptions.length; index++) {
            if (parentOptions[index].name.equals(requestedParent)) {
                parentIndex = index;
                break;
            }
        }
        parent.setSelection(parentIndex);

        EditText fields = views.field(
                "Field names - one per line (optional for folder categories)",
                String.join("\n", model.fields));
        fields.setSingleLine(false);
        fields.setMinLines(7);
        fields.setGravity(Gravity.TOP);
        EditText sensitiveFields = views.field(
                "Sensitive field names - one per line (optional)",
                String.join("\n", model.sensitiveFields));
        sensitiveFields.setSingleLine(false);
        sensitiveFields.setMinLines(4);
        sensitiveFields.setGravity(Gravity.TOP);
        form.addView(name);
        form.addView(views.boldLabel("Parent category / folder"));
        form.addView(parent);
        form.addView(views.subtitle("Choose where this category belongs. A subcategory appears directly below its parent. Cycles and moves beyond the configured maximum depth are blocked."));
        form.addView(views.subtitle("Example fields: Account Number, Recovery Email, Security Question, Membership ID"));
        form.addView(fields);
        form.addView(views.subtitle("Sensitive fields are omitted from normal TXT/HTML/PDF exports unless you explicitly include them and re-authenticate. Names must match the field list above."));
        form.addView(sensitiveFields);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(existing == null ? "New category" : "Edit / move category")
                .setView(views.scroll(form))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> saveEditor(
                        dialog, existing, model, categories, name, parent,
                        parentOptions, fields, sensitiveFields)));
        ScreenSecurityManager.protect(dialog);
        dialogs.show(dialog);
    }

    private void saveEditor(
            AlertDialog dialog,
            CustomCategory existing,
            CustomCategory model,
            List<CustomCategory> categories,
            EditText name,
            Spinner parent,
            Option[] parentOptions,
            EditText fields,
            EditText sensitiveFields) {
        String categoryName = name.getText().toString().trim();
        String newParent = selectedOptionName(parent, parentOptions);
        if (categoryName.isEmpty()) {
            name.setError("Category name is required");
            return;
        }
        if (gateway.isBuiltInCategory(categoryName)) {
            name.setError("This is a built-in category name");
            return;
        }
        for (CustomCategory category : categories) {
            if (category.id != model.id && safe(category.name).equalsIgnoreCase(categoryName)) {
                name.setError("A category with this name already exists");
                return;
            }
        }
        if (existing != null && !newParent.isEmpty()
                && hierarchy.isDescendantOf(newParent, existing.name, categories)) {
            gateway.showMessage("A category cannot be moved under itself or one of its descendants.");
            return;
        }
        int proposedDepth = newParent.isEmpty() ? 1 : hierarchy.depth(newParent, categories) + 1;
        int subtree = existing == null ? 1 : hierarchy.subtreeRelativeDepth(existing.name, categories);
        if (proposedDepth + subtree - 1 > gateway.maxCategoryDepth()) {
            gateway.showMessage("This move would exceed the configured maximum category depth of "
                    + gateway.maxCategoryDepth() + ".");
            return;
        }

        List<String> parsed = parseUniqueLines(fields.getText().toString());
        List<String> parsedSensitive = new ArrayList<>();
        for (String field : parseUniqueLines(sensitiveFields.getText().toString())) {
            if (parsed.contains(field)) parsedSensitive.add(field);
        }
        String oldName = safe(model.name);
        model.name = categoryName;
        model.parentName = newParent;
        model.fields = parsed;
        model.sensitiveFields = parsedSensitive;
        try {
            gateway.saveCategory(model, oldName);
            dialog.dismiss();
            actions.onCategoriesChanged();
            gateway.showMessage("Category saved.");
        } catch (Exception error) {
            gateway.showMessage("Could not save category.");
        }
    }

    private void addBuiltInRow(
            LinearLayout body, String name, List<CustomCategory> categories) {
        LinearLayout row = compactRow(name, 1);
        addRowIcon(row, name, categories);
        row.addView(rowLabel(name), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ImageButton delete = views.smallIconButton(
                R.drawable.ic_keepriva_delete, "Delete category " + name, true);
        delete.setTooltipText("Delete " + name);
        delete.setOnClickListener(v -> dismissDialogThen(
                findShowingDialogForView(v), () -> deleteCategory(name, null)));
        row.addView(delete, new LinearLayout.LayoutParams(views.dp(36), views.dp(36)));
        addRow(body, row);
    }

    private void addCustomRow(
            LinearLayout body, CustomCategory category, List<CustomCategory> categories) {
        LinearLayout row = compactRow(category.name, hierarchy.depth(category.name, categories));
        addRowIcon(row, category.name, categories);
        TextView label = rowLabel(category.name);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageButton edit = views.smallIconButton(
                R.drawable.ic_keepriva_edit, "Edit category " + category.name, false);
        edit.setTooltipText("Edit " + category.name);
        edit.setOnClickListener(v -> {
            AlertDialog parent = findShowingDialogForView(v);
            if (parent != null) parent.dismiss();
            showEditor(category.id, null);
        });
        row.addView(edit, new LinearLayout.LayoutParams(views.dp(36), views.dp(36)));

        ImageButton delete = views.smallIconButton(
                R.drawable.ic_keepriva_delete, "Delete category " + category.name, true);
        delete.setTooltipText("Delete " + category.name);
        delete.setOnClickListener(v -> dismissDialogThen(
                findShowingDialogForView(v), () -> deleteCategory(category.name, category.id)));
        LinearLayout.LayoutParams deleteParams =
                new LinearLayout.LayoutParams(views.dp(36), views.dp(36));
        deleteParams.leftMargin = views.dp(3);
        row.addView(delete, deleteParams);
        addRow(body, row);
    }

    private void deleteCategory(String categoryName, Long customCategoryId) {
        if (closed) return;
        List<CustomCategory> categories = gateway.categoryDefinitions();
        if (customCategoryId == null
                && gateway.isBuiltInCategory(categoryName)
                && gateway.activeBuiltInCategories().size() == 1
                && categories.isEmpty()) {
            gateway.showMessage("Create another category before deleting the final available category.");
            return;
        }

        Set<String> subtreeNames = categorySubtreeNames(categoryName, categories);
        int itemCount = gateway.itemCountInSubtree(subtreeNames);
        int descendantCount = Math.max(0, subtreeNames.size() - 1);
        if (itemCount == 0 && descendantCount == 0) {
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Delete category?")
                    .setMessage("Delete \"" + hierarchy.path(categoryName, categories) + "\"?")
                    .setPositiveButton("Delete category", null)
                    .setNegativeButton("Cancel", null)
                    .create();
            dialog.setOnShowListener(ignored -> {
                Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                confirm.setContentDescription("Confirm delete category " + categoryName);
                confirm.setTextColor(activity.getColor(R.color.keepriva_danger));
                confirm.setOnClickListener(v -> dismissDialogThen(
                        dialog,
                        () -> performSubtreeDelete(categoryName, customCategoryId, subtreeNames)));
            });
            ScreenSecurityManager.protect(dialog);
            dialogs.show(dialog);
            return;
        }

        String summary = "\"" + hierarchy.path(categoryName, categories) + "\" contains "
                + itemCount + (itemCount == 1 ? " item" : " items") + " and "
                + descendantCount
                + (descendantCount == 1 ? " subcategory" : " subcategories")
                + " in its complete subtree.";
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Delete category")
                .setMessage(summary + "\n\nChoose the safe move flow, or explicitly delete the entire subtree.")
                .setPositiveButton("Move contents", null)
                .setNeutralButton("Delete all contents", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button move = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            move.setEnabled(customCategoryId != null);
            move.setOnClickListener(v -> {
                if (customCategoryId == null) {
                    gateway.showMessage("Built-in category contents must be deleted together or moved manually first.");
                    return;
                }
                dialog.dismiss();
                showMoveDialog(customCategoryId, categoryName,
                        gateway.directItemCount(categoryName), directChildCount(categoryName, categories));
            });
            Button destructive = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            destructive.setTextColor(activity.getColor(R.color.keepriva_danger));
            destructive.setContentDescription("Delete category and all contents");
            destructive.setOnClickListener(v -> dismissDialogThen(
                    dialog,
                    () -> confirmForceDelete(
                            categoryName, customCategoryId, subtreeNames,
                            itemCount, descendantCount)));
        });
        ScreenSecurityManager.protect(dialog);
        dialogs.show(dialog);
    }

    private void confirmForceDelete(
            String categoryName,
            Long customCategoryId,
            Set<String> subtreeNames,
            int itemCount,
            int descendantCount) {
        AlertDialog confirm = new AlertDialog.Builder(activity)
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
            delete.setTextColor(activity.getColor(R.color.keepriva_danger));
            delete.setContentDescription("Confirm permanent category deletion");
            delete.setOnClickListener(v -> dismissDialogThen(
                    confirm,
                    () -> performSubtreeDelete(categoryName, customCategoryId, subtreeNames)));
            confirm.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setContentDescription("Cancel permanent category deletion");
        });
        ScreenSecurityManager.protect(confirm);
        dialogs.show(confirm);
    }

    private void performSubtreeDelete(
            String categoryName, Long customCategoryId, Set<String> subtreeNames) {
        try {
            gateway.deleteCategorySubtree(categoryName, customCategoryId, subtreeNames);
            actions.onCategoriesChanged();
            gateway.showMessage("Category subtree deleted.");
        } catch (Exception error) {
            actions.onCategoriesChanged();
            gateway.showMessage("Could not delete category. No partial deletion was committed.");
        }
    }

    private void showMoveDialog(
            long sourceCategoryId, String sourceCategoryName, int entryCount, int childCount) {
        List<CustomCategory> categories = gateway.categoryDefinitions();
        List<Option> destinations = new ArrayList<>();
        for (String builtIn : gateway.activeBuiltInCategories()) {
            if (!hierarchy.isDescendantOf(builtIn, sourceCategoryName, categories)) {
                destinations.add(new Option(builtIn, builtIn));
            }
        }
        for (CustomCategory custom : categories) {
            if (custom.id == sourceCategoryId) continue;
            if (hierarchy.isDescendantOf(custom.name, sourceCategoryName, categories)) continue;
            int deepestDirectChildSubtree = 0;
            for (CustomCategory child : categories) {
                if (safe(child.parentName).equals(sourceCategoryName)) {
                    deepestDirectChildSubtree = Math.max(
                            deepestDirectChildSubtree,
                            hierarchy.subtreeRelativeDepth(child.name, categories));
                }
            }
            int destinationDepth = hierarchy.depth(custom.name, categories);
            if (childCount > 0
                    && destinationDepth + deepestDirectChildSubtree > gateway.maxCategoryDepth()) {
                continue;
            }
            destinations.add(new Option(
                    custom.name, hierarchy.path(custom.name, categories)));
        }
        destinations.sort((left, right) -> left.label.compareToIgnoreCase(right.label));
        if (destinations.isEmpty()) {
            gateway.showMessage("No safe destination is available within the configured nesting depth.");
            return;
        }

        Option[] choices = destinations.toArray(new Option[0]);
        String[] labels = new String[choices.length];
        for (int index = 0; index < choices.length; index++) labels[index] = choices[index].label;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Choose destination")
                .setItems(labels, (ignored, which) -> confirmMoveAndDelete(
                        sourceCategoryId, sourceCategoryName, choices[which].name,
                        entryCount, childCount, categories))
                .setNegativeButton("Cancel", null)
                .create();
        ScreenSecurityManager.protect(dialog);
        dialogs.show(dialog);
    }

    private void confirmMoveAndDelete(
            long sourceCategoryId,
            String sourceCategoryName,
            String destinationCategory,
            int entryCount,
            int childCount,
            List<CustomCategory> categories) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Confirm move and delete")
                .setMessage("Move " + entryCount
                        + (entryCount == 1 ? " direct item" : " direct items")
                        + " and re-parent " + childCount
                        + (childCount == 1 ? " direct sub-category" : " direct sub-categories")
                        + " from \"" + hierarchy.path(sourceCategoryName, categories)
                        + "\" to \"" + hierarchy.path(destinationCategory, categories)
                        + "\", then delete only the selected category?")
                .setPositiveButton("Move & delete", (ignored, which) -> {
                    try {
                        gateway.moveContentsAndDeleteCategory(
                                sourceCategoryId, sourceCategoryName, destinationCategory);
                        actions.onCategoriesChanged();
                        gateway.showMessage("Contents moved and category deleted.");
                    } catch (Exception error) {
                        actions.onCategoriesChanged();
                        gateway.showMessage("Could not move category contents. No partial deletion was committed.");
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        ScreenSecurityManager.protect(dialog);
        dialogs.show(dialog);
    }

    private Option[] parentOptionsFor(
            CustomCategory existing, List<CustomCategory> categories) {
        List<Option> options = new ArrayList<>();
        options.add(new Option("", "Top level"));
        int maxDepth = gateway.maxCategoryDepth();
        for (String builtIn : gateway.activeBuiltInCategories()) {
            if (1 < maxDepth) options.add(new Option(builtIn, builtIn));
        }
        List<CustomCategory> sorted = new ArrayList<>(categories);
        sorted.sort((left, right) -> hierarchy.path(left.name, categories)
                .compareToIgnoreCase(hierarchy.path(right.name, categories)));
        for (CustomCategory candidate : sorted) {
            if (existing != null) {
                if (candidate.id == existing.id) continue;
                if (hierarchy.isDescendantOf(candidate.name, existing.name, categories)) continue;
                if (!hierarchy.moveFitsDepth(
                        existing.name, candidate.name, maxDepth, categories)) continue;
            } else if (hierarchy.depth(candidate.name, categories) + 1 > maxDepth) {
                continue;
            }
            options.add(new Option(candidate.name, hierarchy.path(candidate.name, categories)));
        }
        return options.toArray(new Option[0]);
    }

    private LinearLayout compactRow(String name, int depth) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(
                views.dp(6 + Math.max(0, depth - 1) * 14),
                views.dp(3), views.dp(4), views.dp(3));
        row.setMinimumHeight(views.dp(46));
        row.setBackground(UiStyle.outlined(
                activity, R.color.keepriva_surface, R.color.keepriva_outline, 10));
        row.setContentDescription("Category row " + name);
        return row;
    }

    private void addRowIcon(
            LinearLayout row, String name, List<CustomCategory> categories) {
        String family = VaultBrowserModelBuilder.iconFamily(name, categories);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconFor(family));
        icon.setContentDescription("Category icon " + family);
        icon.setPadding(views.dp(5), views.dp(5), views.dp(5), views.dp(5));
        icon.setBackground(UiStyle.rounded(
                activity, R.color.keepriva_surface_soft, 16));
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(views.dp(32), views.dp(32));
        params.rightMargin = views.dp(8);
        row.addView(icon, params);
    }

    private TextView rowLabel(String name) {
        TextView label = new TextView(activity);
        label.setText(name);
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setSingleLine(true);
        label.setTextColor(activity.getColor(R.color.keepriva_text_primary));
        return label;
    }

    private void addRow(LinearLayout body, LinearLayout row) {
        LinearLayout.LayoutParams params = views.matchWidth();
        params.bottomMargin = views.dp(3);
        body.addView(row, params);
    }

    private int iconFor(String family) {
        switch (family) {
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

    private void dismissDialogThen(AlertDialog dialog, Runnable continuation) {
        if (dialog == null) {
            continuation.run();
            return;
        }
        dialog.setOnDismissListener(ignored -> continuation.run());
        dialog.dismiss();
    }

    private AlertDialog findShowingDialogForView(View view) {
        View current = view;
        while (current != null) {
            Object tag = current.getTag();
            if (tag instanceof AlertDialog && ((AlertDialog) tag).isShowing()) {
                return (AlertDialog) tag;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    static List<String> parseUniqueLines(String text) {
        List<String> parsed = new ArrayList<>();
        for (String line : safe(text).split("\\r?\\n")) {
            String field = line.trim();
            if (!field.isEmpty() && !parsed.contains(field)) parsed.add(field);
        }
        return parsed;
    }

    static Set<String> categorySubtreeNames(
            String rootName, List<CustomCategory> categories) {
        Set<String> names = new HashSet<>();
        names.add(safe(rootName).toLowerCase(Locale.ROOT));
        boolean changed;
        do {
            changed = false;
            for (CustomCategory category : categories) {
                String parent = safe(category.parentName).toLowerCase(Locale.ROOT);
                String child = safe(category.name).toLowerCase(Locale.ROOT);
                if (names.contains(parent) && names.add(child)) changed = true;
            }
        } while (changed);
        return names;
    }

    private static int directChildCount(
            String parentName, List<CustomCategory> categories) {
        int count = 0;
        for (CustomCategory category : categories) {
            if (safe(category.parentName).equals(parentName)) count++;
        }
        return count;
    }

    private static String selectedOptionName(Spinner spinner, Option[] options) {
        int position = spinner.getSelectedItemPosition();
        return position >= 0 && position < options.length ? options[position].name : "";
    }

    private static CustomCategory findById(
            Long categoryId, List<CustomCategory> categories) {
        if (categoryId == null) return null;
        for (CustomCategory category : categories) {
            if (category.id == categoryId) return category;
        }
        return null;
    }

    static CustomCategory copyCategory(CustomCategory source) {
        CustomCategory copy = new CustomCategory();
        copy.id = source.id;
        copy.name = safe(source.name);
        copy.parentName = safe(source.parentName);
        copy.fields = new ArrayList<>(source.fields == null
                ? Collections.emptyList() : source.fields);
        copy.sensitiveFields = new ArrayList<>(source.sensitiveFields == null
                ? Collections.emptyList() : source.sensitiveFields);
        copy.createdAt = source.createdAt;
        copy.updatedAt = source.updatedAt;
        return copy;
    }

    @Override
    public void close() {
        closed = true;
    }

    interface Gateway {
        List<CustomCategory> categoryDefinitions();
        List<String> activeBuiltInCategories();
        int maxCategoryDepth();
        boolean isBuiltInCategory(String name);
        int itemCountInSubtree(Set<String> categoryNames);
        int directItemCount(String categoryName);
        void saveCategory(CustomCategory category, String oldName) throws Exception;
        void deleteCategorySubtree(
                String categoryName, Long customCategoryId, Set<String> subtreeNames) throws Exception;
        void moveContentsAndDeleteCategory(
                long sourceCategoryId, String sourceCategoryName, String destinationCategory)
                throws Exception;
        void showMessage(String message);
    }

    private static final class Option {
        final String name;
        final String label;

        Option(String name, String label) {
            this.name = name;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
