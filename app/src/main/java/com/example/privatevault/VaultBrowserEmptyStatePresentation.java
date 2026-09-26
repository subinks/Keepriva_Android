package com.example.privatevault;

/** Pure presentation mapping for the Phase 03 browser empty states. */
final class VaultBrowserEmptyStatePresentation {
    final String title;
    final String message;
    final String contentDescription;

    private VaultBrowserEmptyStatePresentation(
            String title, String message, String contentDescription) {
        this.title = title;
        this.message = message;
        this.contentDescription = contentDescription;
    }

    static VaultBrowserEmptyStatePresentation from(
            VaultBrowserViewState.EmptyState state, String categoryName) {
        String category = categoryName == null ? "" : categoryName.trim();
        if (state == VaultBrowserViewState.EmptyState.EMPTY_VAULT) {
            return new VaultBrowserEmptyStatePresentation(
                    "Your vault is empty",
                    "Use + to add your first entry or create a category.",
                    "Empty vault");
        }
        if (state == VaultBrowserViewState.EmptyState.EMPTY_CATEGORY) {
            return new VaultBrowserEmptyStatePresentation(
                    "Empty category",
                    "There are no entries or subcategories in " + category + ".",
                    "Empty category " + category);
        }
        if (state == VaultBrowserViewState.EmptyState.NO_DIRECT_ITEMS) {
            return new VaultBrowserEmptyStatePresentation(
                    "No direct entries",
                    "This category contains subcategories but no entries of its own.",
                    "No direct items in " + category);
        }
        return null;
    }
}
