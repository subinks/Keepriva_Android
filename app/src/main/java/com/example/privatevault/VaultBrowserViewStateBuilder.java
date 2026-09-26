package com.example.privatevault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds short-lived Phase 03 browser rows from activity-owned decrypted data. */
final class VaultBrowserViewStateBuilder {
    private VaultBrowserViewStateBuilder() { }

    static VaultBrowserViewState build(
            String requestedCategory,
            List<VaultItem> items,
            List<CustomCategory> categories,
            List<String> activeBuiltIns,
            CategoryHierarchyService hierarchy) {
        String currentCategory = safe(requestedCategory).trim();
        if (currentCategory.isEmpty()) currentCategory = "All";
        List<VaultItem> safeItems = items == null ? Collections.emptyList() : items;
        List<CustomCategory> safeCategories =
                categories == null ? Collections.emptyList() : categories;
        List<String> safeBuiltIns =
                activeBuiltIns == null ? Collections.emptyList() : activeBuiltIns;
        if (hierarchy == null) throw new IllegalArgumentException("hierarchy is required");
        if (!isRoot(currentCategory)
                && !categoryExists(currentCategory, safeCategories, safeBuiltIns)) {
            currentCategory = "All";
        }

        List<CategoryRowModel> subcategories = new ArrayList<>();
        if (isRoot(currentCategory)) {
            for (String builtIn : safeBuiltIns) {
                String name = safe(builtIn).trim();
                if (!name.isEmpty()) {
                    subcategories.add(categoryRow(name, safeItems, safeCategories, hierarchy));
                }
            }
            for (CustomCategory child : hierarchy.directChildren("", safeCategories)) {
                subcategories.add(categoryRow(
                        safe(child.name).trim(), safeItems, safeCategories, hierarchy));
            }
        } else {
            for (CustomCategory child : hierarchy.directChildren(currentCategory, safeCategories)) {
                subcategories.add(categoryRow(
                        safe(child.name).trim(), safeItems, safeCategories, hierarchy));
            }
        }

        List<ItemRowModel> directItems = new ArrayList<>();
        if (!isRoot(currentCategory)) {
            for (VaultItem item : safeItems) {
                if (safe(item.category).trim().equalsIgnoreCase(currentCategory)) {
                    String title = safe(item.title).trim();
                    String path = hierarchy.path(item.category, safeCategories);
                    directItems.add(new ItemRowModel(
                            item.id,
                            title.isEmpty() ? "Untitled" : title,
                            path.isEmpty() ? "Other" : path,
                            ""));
                }
            }
            directItems.sort((left, right) -> {
                int titleOrder = left.title.compareToIgnoreCase(right.title);
                return titleOrder != 0 ? titleOrder : Long.compare(left.itemId, right.itemId);
            });
        }

        VaultBrowserViewState.EmptyState emptyState = emptyState(
                currentCategory, safeItems, subcategories, directItems);
        return new VaultBrowserViewState(
                currentCategory,
                isRoot(currentCategory) ? "Keepriva" : currentCategory,
                hierarchy.browserPath(currentCategory, safeCategories),
                hierarchy.browserParent(currentCategory, safeCategories),
                subcategories,
                directItems,
                emptyState);
    }

    private static CategoryRowModel categoryRow(
            String name,
            List<VaultItem> items,
            List<CustomCategory> categories,
            CategoryHierarchyService hierarchy) {
        int count = 0;
        for (VaultItem item : items) {
            if (hierarchy.isDescendantOf(item.category, name, categories)) count++;
        }
        return new CategoryRowModel(
                name,
                name,
                count,
                hierarchy.depth(name, categories),
                false,
                VaultCategoryIconResolver.resolve(name, categories));
    }

    private static VaultBrowserViewState.EmptyState emptyState(
            String currentCategory,
            List<VaultItem> allItems,
            List<CategoryRowModel> subcategories,
            List<ItemRowModel> directItems) {
        if (isRoot(currentCategory) && allItems.isEmpty()) {
            return VaultBrowserViewState.EmptyState.EMPTY_VAULT;
        }
        if (!isRoot(currentCategory) && directItems.isEmpty()) {
            return subcategories.isEmpty()
                    ? VaultBrowserViewState.EmptyState.EMPTY_CATEGORY
                    : VaultBrowserViewState.EmptyState.NO_DIRECT_ITEMS;
        }
        return VaultBrowserViewState.EmptyState.NONE;
    }

    private static boolean isRoot(String categoryName) {
        return "All".equalsIgnoreCase(safe(categoryName).trim());
    }

    private static boolean categoryExists(
            String categoryName,
            List<CustomCategory> categories,
            List<String> activeBuiltIns) {
        for (String builtIn : activeBuiltIns) {
            if (safe(builtIn).trim().equalsIgnoreCase(categoryName)) return true;
        }
        for (CustomCategory category : categories) {
            if (safe(category.name).trim().equalsIgnoreCase(categoryName)) return true;
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
