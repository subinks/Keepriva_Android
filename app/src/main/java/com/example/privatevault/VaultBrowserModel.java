package com.example.privatevault;

import java.util.Collections;
import java.util.List;

/** Presentation-only browser tree; it contains no passwords, notes, or custom-field values. */
final class VaultBrowserModel {
    final CategoryNode allCategories;
    final List<CategoryNode> roots;
    final boolean vaultEmpty;

    VaultBrowserModel(CategoryNode allCategories, List<CategoryNode> roots, boolean vaultEmpty) {
        this.allCategories = allCategories;
        this.roots = Collections.unmodifiableList(roots);
        this.vaultEmpty = vaultEmpty;
    }

    static final class CategoryNode {
        final String name;
        final String label;
        final String iconFamily;
        final int totalItemCount;
        final List<ItemRow> directItems;
        final List<CategoryNode> children;

        CategoryNode(String name, String label, String iconFamily, int totalItemCount,
                     List<ItemRow> directItems, List<CategoryNode> children) {
            this.name = name;
            this.label = label;
            this.iconFamily = iconFamily;
            this.totalItemCount = totalItemCount;
            this.directItems = Collections.unmodifiableList(directItems);
            this.children = Collections.unmodifiableList(children);
        }
    }

    static final class ItemRow {
        final long id;
        final String title;
        final String secondary;

        ItemRow(long id, String title, String secondary) {
            this.id = id;
            this.title = title;
            this.secondary = secondary;
        }
    }
}
