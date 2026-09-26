package com.example.privatevault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class VaultBrowserEmptyStatePresentationTest {
    @Test
    public void emptyVault_hasStableAccessibilityContract() {
        VaultBrowserEmptyStatePresentation value = VaultBrowserEmptyStatePresentation.from(
                VaultBrowserViewState.EmptyState.EMPTY_VAULT, "All");
        assertEquals("Your vault is empty", value.title);
        assertEquals("Empty vault", value.contentDescription);
    }

    @Test
    public void emptyCategory_namesCurrentCategory() {
        VaultBrowserEmptyStatePresentation value = VaultBrowserEmptyStatePresentation.from(
                VaultBrowserViewState.EmptyState.EMPTY_CATEGORY, "Login");
        assertEquals("Empty category", value.title);
        assertEquals("Empty category Login", value.contentDescription);
    }

    @Test
    public void noDirectItems_isDistinctFromEmptyCategory() {
        VaultBrowserEmptyStatePresentation value = VaultBrowserEmptyStatePresentation.from(
                VaultBrowserViewState.EmptyState.NO_DIRECT_ITEMS, "Work");
        assertEquals("No direct entries", value.title);
        assertEquals("No direct items in Work", value.contentDescription);
    }

    @Test
    public void noEmptyState_hasNoPresentation() {
        assertNull(VaultBrowserEmptyStatePresentation.from(
                VaultBrowserViewState.EmptyState.NONE, "Login"));
    }
}
