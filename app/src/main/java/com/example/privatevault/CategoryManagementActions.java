package com.example.privatevault;

/** Category-management completion events without decrypted item collections. */
interface CategoryManagementActions {
    void onCategoriesChanged();
    void onCategoryManagementClosed();
}
