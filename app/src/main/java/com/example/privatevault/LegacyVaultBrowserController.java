package com.example.privatevault;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Temporary behavior-preserving browser extracted before the Phase 3 redesign. */
final class LegacyVaultBrowserController implements VaultController {
    private final Activity activity;
    private final VaultViewFactory views;
    private final DataSource dataSource;
    private final VaultBrowserActions actions;
    private final Set<String> expandedCategories = new HashSet<>();
    private android.widget.EditText searchBox;
    private LinearLayout categoryTree;
    private ScrollView scrollView;
    private String selectedCategory = "All";
    private boolean closed;

    LegacyVaultBrowserController(
            Activity activity,
            VaultViewFactory views,
            DataSource dataSource,
            VaultBrowserActions actions) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.views = Objects.requireNonNull(views, "views");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    View createView() {
        ensureOpen();
        LinearLayout outer = views.verticalContainer(14);
        outer.setPadding(views.dp(14), views.dp(14), views.dp(14), views.dp(28));

        View header = VaultUiComponents.toolbar(
                activity,
                "Keepriva",
                "Private vault • Offline by design",
                R.drawable.ic_keepriva_lock,
                "Lock vault",
                v -> actions.onLockRequested());
        header.setBackground(UiStyle.rounded(activity, R.color.keepriva_primary_dark, 16));
        outer.addView(header, views.matchWidth());

        searchBox = views.field("Search title, username, phone, website or notes", "");
        searchBox.setSingleLine(true);
        LinearLayout.LayoutParams searchParams = views.matchWidth();
        searchParams.topMargin = views.dp(12);
        outer.addView(searchBox, searchParams);

        LinearLayout categoryCard = UiStyle.verticalCard(activity, 10);
        LinearLayout categoryHeader = new LinearLayout(activity);
        categoryHeader.setOrientation(LinearLayout.HORIZONTAL);
        categoryHeader.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout categoryHeaderText = new LinearLayout(activity);
        categoryHeaderText.setOrientation(LinearLayout.VERTICAL);
        categoryHeaderText.addView(UiStyle.sectionTitle(activity, "Categories"));
        categoryHeaderText.addView(UiStyle.sectionCaption(
                activity, "Browse your vault by category and subcategory."));
        categoryHeader.addView(categoryHeaderText, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button quickAdd = views.secondaryButton("+");
        quickAdd.setContentDescription("Add entry or subcategory");
        quickAdd.setTextSize(24);
        quickAdd.setMinWidth(0);
        quickAdd.setMinimumWidth(0);
        quickAdd.setMinHeight(0);
        quickAdd.setMinimumHeight(0);
        quickAdd.setTextColor(activity.getColor(android.R.color.white));
        quickAdd.setBackground(UiStyle.rounded(activity, R.color.keepriva_primary, 22));
        quickAdd.setOnClickListener(this::showQuickAddMenu);
        LinearLayout.LayoutParams quickAddParams =
                new LinearLayout.LayoutParams(views.dp(44), views.dp(44));
        quickAddParams.rightMargin = views.dp(8);
        categoryHeader.addView(quickAdd, quickAddParams);

        Button manage = views.secondaryButton("Manage");
        manage.setContentDescription("Manage categories");
        UiStyle.styleCompactButton(manage);
        manage.setOnClickListener(v -> actions.onManageCategoriesRequested());
        categoryHeader.addView(manage);
        categoryCard.addView(categoryHeader, views.matchWidth());

        categoryTree = new LinearLayout(activity);
        categoryTree.setOrientation(LinearLayout.VERTICAL);
        categoryCard.addView(categoryTree, views.matchWidth());
        LinearLayout.LayoutParams categoryParams = views.matchWidth();
        categoryParams.topMargin = views.dp(12);
        outer.addView(categoryCard, categoryParams);

        LinearLayout toolsCard = UiStyle.verticalCard(activity, 10);
        toolsCard.addView(UiStyle.sectionTitle(activity, "Vault tools"));
        toolsCard.addView(UiStyle.sectionCaption(
                activity, "Transfer, back up, configure and secure Keepriva."));
        toolsCard.addView(toolRow(R.drawable.ic_keepriva_import, "Import",
                "Bulk import credentials from Keepriva JSON",
                v -> actions.onImportRequested()), views.matchWidth());
        toolsCard.addView(toolRow(R.drawable.ic_keepriva_export, "Export",
                "Export the currently selected category",
                v -> actions.onExportRequested(selectedCategory)), views.matchWidth());
        toolsCard.addView(toolRow(R.drawable.ic_keepriva_backup, "Backup & Restore",
                "Encrypted .pvault backup and recovery",
                v -> actions.onBackupRestoreRequested()), views.matchWidth());
        toolsCard.addView(toolRow(R.drawable.ic_keepriva_settings, "Preferences",
                "Category nesting and app preferences",
                v -> actions.onPreferencesRequested()), views.matchWidth());
        toolsCard.addView(toolRow(R.drawable.ic_keepriva_security, "Security",
                "Master password, biometrics and lock settings",
                v -> actions.onSecurityRequested()), views.matchWidth());
        LinearLayout.LayoutParams toolsParams = views.matchWidth();
        toolsParams.topMargin = views.dp(16);
        outer.addView(toolsCard, toolsParams);

        searchBox.addTextChangedListener(new SimpleTextWatcher(() -> {
            rebuildTree();
            emitNavigationState();
        }));
        scrollView = views.scroll(outer);
        scrollView.setOnScrollChangeListener((view, x, y, oldX, oldY) -> emitNavigationState());
        rebuildTree();
        return scrollView;
    }

    void refresh() {
        if (!closed) rebuildTree();
    }

    void selectCategory(String categoryName, boolean expand) {
        ensureOpen();
        selectedCategory = categoryName == null || categoryName.trim().isEmpty()
                ? "All"
                : categoryName;
        if (expand && !"All".equals(selectedCategory)) {
            expandedCategories.add(selectedCategory.toLowerCase(Locale.ROOT));
        }
        actions.onCategorySelected(selectedCategory);
    }

    void clearSessionState() {
        expandedCategories.clear();
        selectedCategory = "All";
        searchBox = null;
        categoryTree = null;
        scrollView = null;
    }

    private View toolRow(int iconRes, String title, String description, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(views.dp(10), views.dp(11), views.dp(8), views.dp(11));
        row.setBackground(UiStyle.outlined(
                activity, R.color.keepriva_surface, R.color.keepriva_outline, 12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(action);
        row.setContentDescription(title);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setPadding(views.dp(8), views.dp(8), views.dp(8), views.dp(8));
        icon.setBackground(UiStyle.rounded(activity, R.color.keepriva_surface_soft, 20));
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(views.dp(42), views.dp(42));
        iconParams.rightMargin = views.dp(12);
        row.addView(icon, iconParams);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setTextColor(activity.getColor(R.color.keepriva_text_primary));
        text.addView(titleView);
        TextView descriptionView = new TextView(activity);
        descriptionView.setText(description);
        descriptionView.setTextSize(13);
        descriptionView.setTextColor(activity.getColor(R.color.keepriva_text_secondary));
        text.addView(descriptionView);
        row.addView(text, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = new TextView(activity);
        arrow.setText("›");
        arrow.setTextSize(28);
        arrow.setTextColor(activity.getColor(R.color.keepriva_text_secondary));
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(views.dp(32), views.dp(40)));
        LinearLayout.LayoutParams params = views.matchWidth();
        params.setMargins(0, views.dp(5), 0, views.dp(5));
        row.setLayoutParams(params);
        return row;
    }

    private void showQuickAddMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(activity, anchor);
        menu.getMenu().add("Entry");
        menu.getMenu().add("Sub Category");
        menu.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());
            if ("Entry".equals(title)) {
                actions.onAddEntryRequested("All".equals(selectedCategory) ? "Login" : selectedCategory);
                return true;
            }
            if ("Sub Category".equals(title)) {
                actions.onAddSubcategoryRequested("All".equals(selectedCategory) ? "" : selectedCategory);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void rebuildTree() {
        if (categoryTree == null || searchBox == null) return;
        categoryTree.removeAllViews();
        String query = searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        VaultBrowserModel model = dataSource.browserModel(query);
        if (query.isEmpty()) addCategory(categoryTree, model.allCategories, 0, true, false);
        for (VaultBrowserModel.CategoryNode root : model.roots) {
            addCategory(categoryTree, root, 0, false, !query.isEmpty());
        }

        if (query.isEmpty() && model.vaultEmpty) {
            TextView empty = views.subtitle("No entries yet. Use + to add an entry or subcategory.");
            empty.setContentDescription("Empty vault");
            empty.setPadding(views.dp(8), views.dp(16), views.dp(8), views.dp(16));
            categoryTree.addView(empty);
        } else if (!query.isEmpty() && categoryTree.getChildCount() == 0) {
            TextView empty = views.subtitle("No matching categories, subcategories or entries.");
            empty.setContentDescription("No tree search results");
            empty.setPadding(views.dp(8), views.dp(16), views.dp(8), views.dp(16));
            categoryTree.addView(empty);
        }
    }

    private void addCategory(
            LinearLayout container,
            VaultBrowserModel.CategoryNode node,
            int depth,
            boolean allCategories,
            boolean searching) {
        String key = node.name.toLowerCase(Locale.ROOT);
        boolean expanded = searching || expandedCategories.contains(key);
        LinearLayout indent = new LinearLayout(activity);
        indent.setOrientation(LinearLayout.VERTICAL);
        indent.setPadding(views.dp(Math.min(depth, VaultSecurityPreferences.HARD_MAX_CATEGORY_DEPTH) * 20), 0, 0, 0);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(views.dp(8), views.dp(10), views.dp(6), views.dp(10));
        row.setBackground(UiStyle.outlined(
                activity, R.color.keepriva_surface, R.color.keepriva_outline, 12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription((expanded ? "Close category " : "Open category ") + node.label);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconFor(node.iconFamily));
        icon.setContentDescription("Category icon " + node.iconFamily);
        icon.setPadding(views.dp(7), views.dp(7), views.dp(7), views.dp(7));
        icon.setBackground(UiStyle.rounded(activity, R.color.keepriva_surface_soft, 20));
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(views.dp(42), views.dp(42));
        iconParams.rightMargin = views.dp(12);
        row.addView(icon, iconParams);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(activity);
        name.setText(node.label);
        name.setTextSize(depth == 0 ? 16 : 15);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextColor(activity.getColor(R.color.keepriva_text_primary));
        text.addView(name);
        TextView count = new TextView(activity);
        count.setText((depth == 0 ? "Category" : "Subcategory") + " • "
                + node.totalItemCount + (node.totalItemCount == 1 ? " entry" : " entries"));
        count.setTextSize(13);
        count.setTextColor(activity.getColor(R.color.keepriva_text_secondary));
        text.addView(count);
        row.addView(text, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = new TextView(activity);
        arrow.setText(expanded ? "⌄" : "›");
        arrow.setTextSize(26);
        arrow.setTextColor(activity.getColor(R.color.keepriva_text_secondary));
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(views.dp(32), views.dp(40)));
        row.setOnClickListener(v -> {
            selectedCategory = allCategories ? "All" : node.name;
            actions.onCategorySelected(selectedCategory);
            if (expandedCategories.contains(key)) expandedCategories.remove(key);
            else expandedCategories.add(key);
            emitNavigationState();
            rebuildTree();
        });
        indent.addView(row, views.matchWidth());
        LinearLayout.LayoutParams params = views.matchWidth();
        params.setMargins(0, views.dp(4), 0, views.dp(4));
        container.addView(indent, params);
        if (!expanded) return;

        for (VaultBrowserModel.ItemRow item : node.directItems) {
            addItem(container, item, depth + 1);
        }
        for (VaultBrowserModel.CategoryNode child : node.children) {
            addCategory(container, child, depth + 1, false, searching);
        }
        if (!allCategories && node.directItems.isEmpty() && node.children.isEmpty() && !searching) {
            TextView empty = views.subtitle("No entries or subcategories.");
            empty.setPadding(views.dp((depth + 1) * 20 + 8), views.dp(6), views.dp(8), views.dp(10));
            container.addView(empty);
        }
    }

    private void addItem(LinearLayout container, VaultBrowserModel.ItemRow item, int depth) {
        LinearLayout indent = new LinearLayout(activity);
        indent.setOrientation(LinearLayout.VERTICAL);
        indent.setPadding(views.dp(Math.min(
                depth, VaultSecurityPreferences.HARD_MAX_CATEGORY_DEPTH + 1) * 20), 0, 0, 0);
        LinearLayout card = views.verticalContainer(4);
        card.setPadding(views.dp(12), views.dp(10), views.dp(12), views.dp(10));
        UiStyle.styleCard(card);
        card.setContentDescription("Open entry " + item.title);
        TextView title = new TextView(activity);
        title.setText(item.title);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(activity.getColor(R.color.keepriva_text_primary));
        card.addView(title);
        TextView secondary = new TextView(activity);
        secondary.setText(item.secondary);
        secondary.setTextSize(13);
        secondary.setTextColor(activity.getColor(R.color.keepriva_text_secondary));
        card.addView(secondary);
        card.setOnClickListener(v -> actions.onItemSelected(item.id));
        indent.addView(card, views.matchWidth());
        LinearLayout.LayoutParams params = views.matchWidth();
        params.setMargins(0, views.dp(3), 0, views.dp(3));
        container.addView(indent, params);
    }

    private int iconFor(String family) {
        switch (family) {
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

    private void emitNavigationState() {
        if (closed) return;
        actions.onBrowserNavigationChanged(
                selectedCategory,
                searchBox == null ? "" : searchBox.getText().toString(),
                scrollView == null ? 0 : scrollView.getScrollY());
    }

    @Override
    public void close() {
        closed = true;
        clearSessionState();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Legacy browser controller is closed");
    }

    interface DataSource {
        VaultBrowserModel browserModel(String query);
    }
}
