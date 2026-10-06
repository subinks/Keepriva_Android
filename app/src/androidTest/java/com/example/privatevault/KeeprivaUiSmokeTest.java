package com.example.privatevault;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Fast top-level smoke coverage.
 *
 * Deeper coverage lives in:
 * KeeprivaAuthHomeTest
 * KeeprivaItemCrudTest
 * KeeprivaCategoryPreferencesTest
 * KeeprivaSecuritySettingsTest
 * KeeprivaDataTransferTest
 * KeeprivaLifecycleRobustnessTest
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class KeeprivaUiSmokeTest extends KeeprivaTestBase {
    @Test
    public void freshInstall_showsSetupScreen() {
        onView(withText("Create Keepriva")).check(matches(isDisplayed()));
        onView(withHint("Master password (12+ characters)"))
                .check(matches(isDisplayed()));
        onView(withHint("Confirm master password"))
                .check(matches(isDisplayed()));
        onView(withText("Create encrypted vault"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void createVault_opensHomeScreen() {
        createTestVault();

        onView(withContentDescription("Search vault")).check(matches(isDisplayed()));
        onView(withContentDescription("Lock vault")).check(matches(isDisplayed()));
        onView(withContentDescription("More vault actions")).check(matches(isDisplayed()));
        onView(withContentDescription("Add entry or subcategory"))
                .perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void lock_returnsToUnlockScreen() {
        createTestVault();

        onView(withContentDescription("Lock vault"))
                .perform(scrollTo(), performClickDirectly());

        onView(withHint("Master password")).check(matches(isDisplayed()));
        onView(withText("Unlock")).check(matches(isDisplayed()));
    }

    @Test
    public void wrongMasterPassword_doesNotUnlock() {
        createTestVault();

        onView(withContentDescription("Lock vault"))
                .perform(scrollTo(), performClickDirectly());

        onView(withHint("Master password"))
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());

        onView(withText("Unlock")).perform(click());

        waitForUnlockReady();
        onView(withText("Unlock")).check(matches(isDisplayed()));
    }

    @Test
    public void addItem_requiresTitle() {
        createTestVault();

        onView(withContentDescription("Add entry or subcategory"))
                .perform(scrollTo(), click());
        onView(withText("Entry")).perform(click());
        onView(withText("Add vault item")).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withContentDescription("Save vault item")).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withContentDescription("Cancel vault item")).inRoot(isDialog()).check(matches(isDisplayed()));

        onView(withContentDescription("Save vault item")).inRoot(isDialog()).perform(click());

        // Validation must keep the add form open.
        onView(withContentDescription("Save vault item")).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withContentDescription("Cancel vault item")).inRoot(isDialog()).check(matches(isDisplayed()));
    }

    @Test
    public void importDialog_showsBothActions() {
        createTestVault();

        openVaultAction("Import");

        onView(withContentDescription("Download JSON import template"))
                .check(matches(isDisplayed()));
        onView(withContentDescription("Import completed JSON template"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void backupDialog_showsBothActions() {
        createTestVault();

        openVaultAction("Backup & Restore");

        onView(withText("Create encrypted .pvault backup"))
                .check(matches(isDisplayed()));
        onView(withText("Restore encrypted .pvault backup"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void exportWithNoItems_doesNotCrash() {
        createTestVault();

        openVaultAction("Export");

        onView(withContentDescription("Search vault")).check(matches(isDisplayed()));
    }

    @Test
    public void preferencesDialog_isReachable() {
        createTestVault();

        openVaultAction("Preferences");

        onView(withText("Preferences")).check(matches(isDisplayed()));
        onView(withText("Enable editing category nesting depth"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void categoriesDialog_isReachable() {
        createTestVault();

        openVaultAction("Manage categories");

        onView(withText("Manage Categories")).check(matches(isDisplayed()));
        onView(withContentDescription("Add category"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
    }
}
