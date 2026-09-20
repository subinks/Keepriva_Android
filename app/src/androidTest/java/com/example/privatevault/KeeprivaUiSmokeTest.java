package com.example.privatevault;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Real instrumentation smoke tests for the main Keepriva UI.
 *
 * These tests intentionally use only deterministic TEST data.
 * They do not require Internet access and must never use real credentials.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class KeeprivaUiSmokeTest {

    private static final String TEST_PASSWORD = "KeeprivaTest123!";
    private ActivityScenario<MainActivity> scenario;

    @Before
    public void setUp() {
        clearAppState();
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void tearDown() {
        if (scenario != null) scenario.close();
        clearAppState();
    }

    private void clearAppState() {
        Context context = ApplicationProvider.getApplicationContext();

        SharedPreferences prefs = context.getSharedPreferences("vault_config", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();

        context.deleteDatabase("private_vault.db");
        context.deleteDatabase("private_vault");
    }

    private void createTestVault() {
        onView(withHint("Master password (12+ characters)"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withHint("Confirm master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withText("Create encrypted vault")).perform(click());
        onView(withText("Keepriva")).check(matches(isDisplayed()));
    }

    @Test
    public void freshInstall_showsSetupScreen() {
        onView(withText("Create Keepriva")).check(matches(isDisplayed()));
        onView(withHint("Master password (12+ characters)")).check(matches(isDisplayed()));
        onView(withHint("Confirm master password")).check(matches(isDisplayed()));
        onView(withText("Create encrypted vault")).check(matches(isDisplayed()));
    }

    @Test
    public void createVault_opensHomeScreen() {
        createTestVault();
        onView(withHint("Search title, username, phone, website or notes"))
                .check(matches(isDisplayed()));
        onView(withText("Import")).check(matches(isDisplayed()));
        onView(withText("Export")).check(matches(isDisplayed()));
        onView(withText("Backup")).check(matches(isDisplayed()));
    }

    @Test
    public void lock_returnsToUnlockScreen() {
        createTestVault();
        onView(withText("Lock")).perform(scrollTo(), click());
        onView(withText("Offline encrypted password manager"))
                .check(matches(isDisplayed()));
        onView(withHint("Master password")).check(matches(isDisplayed()));
        onView(withText("Unlock")).check(matches(isDisplayed()));
    }

    @Test
    public void wrongMasterPassword_doesNotUnlock() {
        createTestVault();
        onView(withText("Lock")).perform(scrollTo(), click());

        onView(withHint("Master password"))
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());
        onView(withText("Unlock")).perform(click());

        onView(withText("Unlock")).check(matches(isDisplayed()));
    }

    @Test
    public void addItem_requiresTitle() {
        createTestVault();

        onView(withContentDescription("Add item")).perform(click());

        // The Add Item dialog is open if its Save/Cancel actions are visible.
        // Do not depend on the EditText hint because Android/Espresso may expose
        // programmatically-created field hints differently across API levels.
        onView(withText("Save")).check(matches(isDisplayed()));
        onView(withText("Cancel")).check(matches(isDisplayed()));

        // Saving without a title must be rejected and the dialog must remain open.
        onView(withText("Save")).perform(click());

        onView(withText("Save")).check(matches(isDisplayed()));
        onView(withText("Cancel")).check(matches(isDisplayed()));
    }

    @Test
    public void importDialog_showsBothActions() {
        createTestVault();
        onView(withText("Import")).perform(scrollTo(), click());

        onView(withText("Download JSON import template")).check(matches(isDisplayed()));
        onView(withText("Import filled JSON template")).check(matches(isDisplayed()));
    }

    @Test
    public void backupDialog_showsBothActions() {
        createTestVault();
        onView(withText("Backup")).perform(scrollTo(), click());

        onView(withText("Create encrypted .pvault backup")).check(matches(isDisplayed()));
        onView(withText("Restore encrypted .pvault backup")).check(matches(isDisplayed()));
    }

    @Test
    public void exportWithNoItems_showsHomeAndDoesNotCrash() {
        createTestVault();
        onView(withText("Export")).perform(scrollTo(), click());

        // Empty-vault export is handled with a Toast; verify activity remains alive.
        onView(withText("Keepriva")).check(matches(isDisplayed()));
    }

    @Test
    public void preferencesDialog_isReachable() {
        createTestVault();
        onView(withText("Preferences")).perform(scrollTo(), click());

        onView(withText("Preferences")).check(matches(isDisplayed()));
        onView(withText("Enable editing category nesting depth")).check(matches(isDisplayed()));
    }

    @Test
    public void categoriesDialog_isReachable() {
        createTestVault();
        onView(withText("Categories")).perform(scrollTo(), click());

        onView(withText("Categories")).check(matches(isDisplayed()));
        onView(withText("+ New custom category / sub-category")).check(matches(isDisplayed()));
    }
}


