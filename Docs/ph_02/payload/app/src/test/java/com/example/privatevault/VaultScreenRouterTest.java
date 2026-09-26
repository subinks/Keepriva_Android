package com.example.privatevault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class VaultScreenRouterTest {

    @Test
    public void newRouter_isEmpty() {
        VaultScreenRouter router = new VaultScreenRouter();

        assertNull(router.currentState());
        assertEquals(0, router.backStackSize());
        assertFalse(router.canGoBack());
    }

    @Test
    public void resetToVaultBrowser_createsRootWithoutBackHistory() {
        VaultScreenRouter router = new VaultScreenRouter();
        VaultNavigationState browser = VaultNavigationState.vaultBrowser("Work", "", 24);

        router.reset(browser);

        assertSame(browser, router.currentState());
        assertEquals(VaultScreen.VAULT_BROWSER, router.currentState().screen());
        assertEquals(0, router.backStackSize());
    }

    @Test
    public void back_restoresCompletePreviousNavigationState() {
        VaultScreenRouter router = new VaultScreenRouter();
        VaultNavigationState browser = VaultNavigationState
                .vaultBrowser("Work", "github", 180);
        VaultNavigationState details = browser
                .forScreen(VaultScreen.ITEM_DETAILS)
                .withSelectedItemId(42L);

        router.reset(browser);
        router.navigate(details);

        assertTrue(router.canGoBack());
        assertEquals(details, router.currentState());
        assertEquals(browser, router.goBack());
        assertEquals("Work", router.currentState().currentCategory());
        assertEquals("github", router.currentState().searchQuery());
        assertEquals(180, router.currentState().listScrollPosition());
        assertFalse(router.canGoBack());
    }

    @Test
    public void replaceCurrent_updatesStateWithoutAddingHistory() {
        VaultScreenRouter router = new VaultScreenRouter();
        router.reset(VaultNavigationState.vaultBrowser("All", "", 0));

        VaultNavigationState updated = router.currentState()
                .withCategory("Personal")
                .withSearchQuery("bank")
                .withListScrollPosition(90);
        router.replaceCurrent(updated);

        assertEquals(updated, router.currentState());
        assertEquals(0, router.backStackSize());
    }

    @Test
    public void clear_removesCurrentStateHistoryAndSelections() {
        VaultScreenRouter router = new VaultScreenRouter();
        VaultNavigationState browser = VaultNavigationState.vaultBrowser("Login", "admin", 15);
        router.reset(browser);
        router.navigate(browser
                .forScreen(VaultScreen.ITEM_VERSION_DETAILS)
                .withSelectedItemId(9L)
                .withSelectedHistoryVersionId(3L));

        router.clear();

        assertNull(router.currentState());
        assertEquals(0, router.backStackSize());
        assertFalse(router.canGoBack());
    }

    @Test
    public void navigationState_containsOnlyApprovedMetadataTypes() {
        Set<Class<?>> approved = new HashSet<>(Arrays.asList(
                VaultScreen.class,
                String.class,
                long.class,
                int.class));

        for (Field field : VaultNavigationState.class.getDeclaredFields()) {
            assertTrue(
                    "Unexpected navigation-state field: " + field.getName()
                            + " of type " + field.getType().getName(),
                    approved.contains(field.getType()));
        }
    }

    @Test
    public void navigationState_isNotPersistableAndroidOrJavaState() {
        assertFalse(Serializable.class.isAssignableFrom(VaultNavigationState.class));
        for (Class<?> implemented : VaultNavigationState.class.getInterfaces()) {
            assertFalse("android.os.Parcelable".equals(implemented.getName()));
        }
    }

    @Test
    public void categoryStableIds_areDeterministicAndCaseInsensitive() {
        CategoryRowModel first = new CategoryRowModel("Work", "Work", 0, 0, false);
        CategoryRowModel sameIdentity = new CategoryRowModel("work", "Renamed label", 5, 2, true);
        CategoryRowModel different = new CategoryRowModel("Personal", "Personal", 0, 0, false);

        assertEquals(first.stableId, sameIdentity.stableId);
        assertFalse(first.stableId == different.stableId);
    }

    @Test
    public void itemRowModel_exposesOnlyListPresentationFields() {
        Set<String> names = new HashSet<>();
        for (Field field : ItemRowModel.class.getDeclaredFields()) names.add(field.getName());

        assertEquals(new HashSet<>(Arrays.asList(
                "itemId", "title", "categoryLabel", "secondaryText")), names);
        assertFalse(names.contains("password"));
        assertFalse(names.contains("notes"));
        assertFalse(names.contains("customFields"));
    }
}
