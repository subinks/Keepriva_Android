package com.example.privatevault;

/** Typed browser events. Controllers pass identifiers and non-secret labels only. */
interface VaultBrowserActions {
    void onLockRequested();
    void onCategorySelected(String categoryName);
    void onItemSelected(long itemId);
    void onAddEntryRequested(String categoryName);
    void onAddSubcategoryRequested(String parentCategoryName);
    void onManageCategoriesRequested();
    void onImportRequested();
    void onExportRequested(String categoryName);
    void onBackupRestoreRequested();
    void onPreferencesRequested();
    void onSecurityRequested();
    void onBrowserNavigationChanged(String categoryName, String query, int scrollPosition);
}
