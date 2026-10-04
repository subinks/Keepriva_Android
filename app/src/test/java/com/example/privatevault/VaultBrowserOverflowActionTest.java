package com.example.privatevault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class VaultBrowserOverflowActionTest {
    @Test
    public void everyAction_hasAUniqueStableMenuIdAndRoundTrips() {
        Set<Integer> ids = new HashSet<>();

        for (VaultBrowserOverflowAction action : VaultBrowserOverflowAction.values()) {
            assertTrue(action.menuItemId() > 0);
            assertTrue(ids.add(action.menuItemId()));
            assertEquals(action,
                    VaultBrowserOverflowAction.fromMenuItemId(action.menuItemId()));
        }
    }

    @Test
    public void requiredWorkflowLabels_arePresent() {
        assertEquals("Manage categories", VaultBrowserOverflowAction.MANAGE_CATEGORIES.label());
        assertEquals("Import", VaultBrowserOverflowAction.IMPORT.label());
        assertEquals("Export", VaultBrowserOverflowAction.EXPORT.label());
        assertEquals("Backup & Restore", VaultBrowserOverflowAction.BACKUP_RESTORE.label());
        assertEquals("Preferences", VaultBrowserOverflowAction.PREFERENCES.label());
        assertEquals("Security", VaultBrowserOverflowAction.SECURITY.label());
    }

    @Test
    public void unknownMenuId_isRejected() {
        assertNull(VaultBrowserOverflowAction.fromMenuItemId(Integer.MIN_VALUE));
    }
}
