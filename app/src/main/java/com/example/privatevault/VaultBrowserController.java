package com.example.privatevault;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Objects;

/** Category-first Phase 03 browser. It retains presentation rows and identifiers only. */
final class VaultBrowserController implements VaultController {
    private final Activity activity;
    private final DataSource dataSource;
    private final VaultBrowserActions actions;
    private final CategoryRowAdapter categoryAdapter;
    private final ItemRowAdapter itemAdapter;

    private String selectedCategory = "All";
    private ScrollView scrollView;
    private TextView toolbarTitle;
    private TextView toolbarSubtitle;
    private TextView breadcrumb;
    private TextView categoryHeading;
    private TextView itemHeading;
    private FrameLayout emptyContainer;
    private PopupMenu activeQuickAddMenu;
    private PopupMenu activeOverflowMenu;
    private boolean suppressNavigationEvents;
    private int renderGeneration;
    private boolean closed;

    VaultBrowserController(Activity activity, DataSource dataSource, VaultBrowserActions actions) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.actions = Objects.requireNonNull(actions, "actions");
        categoryAdapter = new CategoryRowAdapter(row -> selectCategory(row.name, false));
        itemAdapter = new ItemRowAdapter(actions::onItemSelected);
    }

    View createView() {
        ensureOpen();
        renderGeneration++;
        View root = LayoutInflater.from(activity).inflate(R.layout.view_vault_browser, null, false);
        scrollView = root.findViewById(R.id.vault_browser_scroll);
        toolbarTitle = root.findViewById(R.id.toolbar_title);
        toolbarSubtitle = root.findViewById(R.id.toolbar_subtitle);
        ImageButton search = root.findViewById(R.id.toolbar_search);
        search.setVisibility(View.VISIBLE);
        search.setImageResource(R.drawable.ic_keepriva_search);
        search.setContentDescription("Search vault");
        search.setTooltipText("Search vault");
        search.setOnClickListener(v -> actions.onVaultSearchRequested());
        ImageButton lock = root.findViewById(R.id.toolbar_action);
        lock.setImageResource(R.drawable.ic_keepriva_lock);
        lock.setContentDescription("Lock vault");
        lock.setTooltipText("Lock vault");
        lock.setOnClickListener(v -> actions.onLockRequested());
        ImageButton overflow = root.findViewById(R.id.toolbar_overflow);
        overflow.setVisibility(View.VISIBLE);
        overflow.setImageResource(R.drawable.ic_keepriva_more);
        overflow.setContentDescription("More vault actions");
        overflow.setTooltipText("More vault actions");
        overflow.setOnClickListener(this::showOverflowMenu);

        breadcrumb = root.findViewById(R.id.browser_breadcrumb);
        categoryHeading = root.findViewById(R.id.browser_category_heading);
        itemHeading = root.findViewById(R.id.browser_item_heading);
        emptyContainer = root.findViewById(R.id.browser_empty_container);

        RecyclerView categories = root.findViewById(R.id.browser_category_list);
        categories.setLayoutManager(new LinearLayoutManager(activity));
        categories.setAdapter(categoryAdapter);
        RecyclerView items = root.findViewById(R.id.browser_item_list);
        items.setLayoutManager(new LinearLayoutManager(activity));
        items.setAdapter(itemAdapter);

        Button quickAdd = root.findViewById(R.id.browser_quick_add);
        quickAdd.setOnClickListener(this::showQuickAddMenu);
        Button manage = root.findViewById(R.id.browser_manage_categories);
        manage.setOnClickListener(v -> actions.onManageCategoriesRequested());
        scrollView.setOnScrollChangeListener((view, x, y, oldX, oldY) -> emitNavigationState());
        render();
        return root;
    }

    void refresh() {
        if (!closed) render();
    }

    void selectCategory(String categoryName, boolean ignoredLegacyExpandFlag) {
        ensureOpen();
        suppressNavigationEvents = true;
        try {
            selectedCategory = normalizeCategory(categoryName);
            if (scrollView != null) scrollView.scrollTo(0, 0);
            actions.onCategorySelected(selectedCategory);
            render();
        } finally {
            suppressNavigationEvents = false;
        }
        emitNavigationState();
    }

    boolean navigateToParentCategory() {
        ensureOpen();
        if ("All".equalsIgnoreCase(selectedCategory)) return false;
        VaultBrowserViewState state = dataSource.browserState(selectedCategory);
        selectCategory(state.parentCategory().isEmpty() ? "All" : state.parentCategory(), false);
        return true;
    }

    VaultNavigationState snapshotNavigationState() {
        ensureOpen();
        return VaultNavigationState.vaultBrowser(
                selectedCategory,
                "",
                scrollView == null ? 0 : scrollView.getScrollY());
    }

    void restoreNavigationState(VaultNavigationState state) {
        ensureOpen();
        if (state == null || state.screen() != VaultScreen.VAULT_BROWSER) {
            throw new IllegalArgumentException("A vault-browser state is required");
        }
        suppressNavigationEvents = true;
        try {
            selectedCategory = normalizeCategory(state.currentCategory());
            actions.onCategorySelected(selectedCategory);
            render();
        } finally {
            suppressNavigationEvents = false;
        }

        ScrollView target = scrollView;
        int generation = renderGeneration;
        int position = state.listScrollPosition();
        if (target == null) {
            emitNavigationState();
            return;
        }
        target.post(() -> {
            if (closed || generation != renderGeneration || target != scrollView) return;
            suppressNavigationEvents = true;
            try {
                target.scrollTo(0, position);
            } finally {
                suppressNavigationEvents = false;
            }
            emitNavigationState();
        });
    }

    void clearSessionState() {
        renderGeneration++;
        dismissPopupMenus();
        suppressNavigationEvents = false;
        selectedCategory = "All";
        categoryAdapter.submitRows(null);
        itemAdapter.submitRows(null);
        scrollView = null;
        toolbarTitle = null;
        toolbarSubtitle = null;
        breadcrumb = null;
        categoryHeading = null;
        itemHeading = null;
        emptyContainer = null;
    }

    private void render() {
        if (toolbarTitle == null) return;
        VaultBrowserViewState state = dataSource.browserState(selectedCategory);
        String normalizedCategory = state.currentCategory().isEmpty()
                ? "All"
                : state.currentCategory();
        if (!normalizedCategory.equalsIgnoreCase(selectedCategory)) {
            selectedCategory = normalizedCategory;
            actions.onCategorySelected(selectedCategory);
        }
        toolbarTitle.setText(state.title());
        toolbarSubtitle.setText("Private vault • Offline by design");
        breadcrumb.setVisibility("All".equalsIgnoreCase(selectedCategory) ? View.GONE : View.VISIBLE);
        breadcrumb.setText("‹  " + state.breadcrumb());
        breadcrumb.setContentDescription("Close category " + selectedCategory);
        breadcrumb.setOnClickListener(v -> selectCategory(
                state.parentCategory().isEmpty() ? "All" : state.parentCategory(), false));

        categoryHeading.setText("Subcategories");
        categoryHeading.setVisibility(!"All".equalsIgnoreCase(selectedCategory)
                && !state.subcategories().isEmpty() ? View.VISIBLE : View.GONE);
        itemHeading.setText("Items");
        itemHeading.setVisibility(state.items().isEmpty() ? View.GONE : View.VISIBLE);
        categoryAdapter.submitRows(state.subcategories());
        itemAdapter.submitRows(state.items());
        bindEmptyState(state);
    }

    private void bindEmptyState(VaultBrowserViewState state) {
        emptyContainer.removeAllViews();
        VaultBrowserEmptyStatePresentation presentation =
                VaultBrowserEmptyStatePresentation.from(state.emptyState(), selectedCategory);
        if (presentation == null) return;
        View empty = VaultUiComponents.emptyState(
                activity,
                presentation.title,
                presentation.message,
                false,
                "",
                null);
        empty.setContentDescription(presentation.contentDescription);
        emptyContainer.addView(empty);
    }

    private void showQuickAddMenu(View anchor) {
        dismissPopupMenus();
        PopupMenu menu = new PopupMenu(activity, anchor);
        activeQuickAddMenu = menu;
        menu.getMenu().add("Entry");
        menu.getMenu().add("Sub Category");
        menu.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());
            if ("Entry".equals(title)) {
                actions.onAddEntryRequested(
                        "All".equalsIgnoreCase(selectedCategory) ? "Login" : selectedCategory);
                return true;
            }
            if ("Sub Category".equals(title)) {
                actions.onAddSubcategoryRequested(
                        "All".equalsIgnoreCase(selectedCategory) ? "" : selectedCategory);
                return true;
            }
            return false;
        });
        menu.setOnDismissListener(dismissed -> {
            if (activeQuickAddMenu == dismissed) activeQuickAddMenu = null;
        });
        menu.show();
    }

    private void dismissQuickAddMenu() {
        PopupMenu menu = activeQuickAddMenu;
        activeQuickAddMenu = null;
        if (menu != null) menu.dismiss();
    }

    private void showOverflowMenu(View anchor) {
        dismissPopupMenus();
        PopupMenu menu = new PopupMenu(activity, anchor);
        activeOverflowMenu = menu;
        for (VaultBrowserOverflowAction action : VaultBrowserOverflowAction.values()) {
            menu.getMenu().add(Menu.NONE, action.menuItemId(), Menu.NONE, action.label());
        }
        menu.setOnMenuItemClickListener(item ->
                handleOverflowAction(VaultBrowserOverflowAction.fromMenuItemId(item.getItemId())));
        menu.setOnDismissListener(dismissed -> {
            if (activeOverflowMenu == dismissed) activeOverflowMenu = null;
        });
        menu.show();
    }

    private boolean handleOverflowAction(VaultBrowserOverflowAction action) {
        if (action == null) return false;
        switch (action) {
            case MANAGE_CATEGORIES:
                actions.onManageCategoriesRequested();
                return true;
            case IMPORT:
                actions.onImportRequested();
                return true;
            case EXPORT:
                actions.onExportRequested(selectedCategory);
                return true;
            case BACKUP_RESTORE:
                actions.onBackupRestoreRequested();
                return true;
            case PREFERENCES:
                actions.onPreferencesRequested();
                return true;
            case SECURITY:
                actions.onSecurityRequested();
                return true;
            default:
                return false;
        }
    }

    private void dismissOverflowMenu() {
        PopupMenu menu = activeOverflowMenu;
        activeOverflowMenu = null;
        if (menu != null) menu.dismiss();
    }

    private void dismissPopupMenus() {
        dismissQuickAddMenu();
        dismissOverflowMenu();
    }

    private void emitNavigationState() {
        if (closed || suppressNavigationEvents) return;
        actions.onBrowserNavigationChanged(
                selectedCategory,
                "",
                scrollView == null ? 0 : scrollView.getScrollY());
    }

    @Override
    public void close() {
        closed = true;
        clearSessionState();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Vault browser controller is closed");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeCategory(String value) {
        String normalized = safe(value).trim();
        return normalized.isEmpty() ? "All" : normalized;
    }

    interface DataSource {
        VaultBrowserViewState browserState(String categoryName);
    }
}
