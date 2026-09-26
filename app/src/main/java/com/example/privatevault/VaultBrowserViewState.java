package com.example.privatevault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, non-secret presentation state for one category-first browser screen. */
public final class VaultBrowserViewState {
    public enum EmptyState {
        NONE,
        EMPTY_VAULT,
        EMPTY_CATEGORY,
        NO_DIRECT_ITEMS
    }

    private final String currentCategory;
    private final String title;
    private final String breadcrumb;
    private final String parentCategory;
    private final List<CategoryRowModel> subcategories;
    private final List<ItemRowModel> items;
    private final EmptyState emptyState;

    VaultBrowserViewState(
            String currentCategory,
            String title,
            String breadcrumb,
            String parentCategory,
            List<CategoryRowModel> subcategories,
            List<ItemRowModel> items,
            EmptyState emptyState) {
        this.currentCategory = safe(currentCategory);
        this.title = safe(title);
        this.breadcrumb = safe(breadcrumb);
        this.parentCategory = safe(parentCategory);
        this.subcategories = immutableCopy(subcategories);
        this.items = immutableCopy(items);
        this.emptyState = emptyState == null ? EmptyState.NONE : emptyState;
    }

    public String currentCategory() {
        return currentCategory;
    }

    public String title() {
        return title;
    }

    public String breadcrumb() {
        return breadcrumb;
    }

    public String parentCategory() {
        return parentCategory;
    }

    public List<CategoryRowModel> subcategories() {
        return subcategories;
    }

    public List<ItemRowModel> items() {
        return items;
    }

    public EmptyState emptyState() {
        return emptyState;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(
                values == null ? new ArrayList<>() : new ArrayList<>(values));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
