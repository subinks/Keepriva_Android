package com.example.privatevault;

/** Stable overflow identifiers kept independent from user-visible menu text. */
enum VaultBrowserOverflowAction {
    MANAGE_CATEGORIES(1001, "Manage categories"),
    IMPORT(1002, "Import"),
    EXPORT(1003, "Export"),
    BACKUP_RESTORE(1004, "Backup & Restore"),
    PREFERENCES(1005, "Preferences"),
    SECURITY(1006, "Security");

    private final int menuItemId;
    private final String label;

    VaultBrowserOverflowAction(int menuItemId, String label) {
        this.menuItemId = menuItemId;
        this.label = label;
    }

    int menuItemId() {
        return menuItemId;
    }

    String label() {
        return label;
    }

    static VaultBrowserOverflowAction fromMenuItemId(int menuItemId) {
        for (VaultBrowserOverflowAction action : values()) {
            if (action.menuItemId == menuItemId) return action;
        }
        return null;
    }
}
