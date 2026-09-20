package com.example.privatevault;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

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
    /**
     * Matches the Nth view that satisfies another matcher.
     */
    private static Matcher<View> withIndex(final Matcher<View> matcher, final int index) {
        return new TypeSafeMatcher<View>() {
            private int currentIndex = 0;

            @Override
            protected boolean matchesSafely(View view) {
                if (!matcher.matches(view)) return false;
                return currentIndex++ == index;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("with index: " + index + " of matcher: ");
                matcher.describeTo(description);
            }
        };
    }

    private void createBasicItem(String titleText) {
        onView(withContentDescription("Add item")).perform(click());

        onView(withIndex(allOf(isAssignableFrom(EditText.class), isDisplayed()), 0))
                .perform(replaceText(titleText), closeSoftKeyboard());

        onView(withText("Save")).perform(click());
        onView(withText(titleText)).check(matches(isDisplayed()));
    }

    private void createLoginItemWithPassword(String titleText, String itemPassword) {
        onView(withContentDescription("Add item")).perform(click());

        onView(withIndex(allOf(isAssignableFrom(EditText.class), isDisplayed()), 0))
                .perform(replaceText(titleText), closeSoftKeyboard());

        onView(withHint("Password (optional)"))
                .perform(replaceText(itemPassword), closeSoftKeyboard());

        onView(withText("Save")).perform(click());
        onView(withText(titleText)).check(matches(isDisplayed()));
    }

    private void openSecuritySettings() {
        onView(withText("Security")).perform(scrollTo(), click());

        onView(withHint("Master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withText("Continue")).perform(click());

        onView(withText("Change master password"))
                .check(matches(isDisplayed()));
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

    // ---------------------------------------------------------------------
    // Expanded functional regression coverage
    // ---------------------------------------------------------------------

    @Test
    public void weakMasterPassword_isRejected() {
        onView(withHint("Master password (12+ characters)"))
                .perform(replaceText("weak"), closeSoftKeyboard());

        onView(withHint("Confirm master password"))
                .perform(replaceText("weak"), closeSoftKeyboard());

        onView(withText("Create encrypted vault")).perform(click());
        onView(withText("Create encrypted vault")).check(matches(isDisplayed()));
    }

    @Test
    public void mismatchedMasterPasswords_areRejected() {
        onView(withHint("Master password (12+ characters)"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withHint("Confirm master password"))
                .perform(replaceText("DifferentTest123!"), closeSoftKeyboard());

        onView(withText("Create encrypted vault")).perform(click());
        onView(withText("Create encrypted vault")).check(matches(isDisplayed()));
    }

    @Test
    public void correctMasterPassword_unlocksAfterLock() {
        createTestVault();

        onView(withText("Lock")).perform(scrollTo(), click());

        onView(withHint("Master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withText("Unlock")).perform(click());

        onView(withHint("Search title, username, phone, website or notes"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void addAndSaveBasicItem_showsItemOnHome() {
        createTestVault();
        createBasicItem("Test Login A");
        onView(withText("Test Login A")).check(matches(isDisplayed()));
    }

    @Test
    public void savedItem_opensDetailsDialog() {
        createTestVault();
        createBasicItem("Details Test");

        onView(withText("Details Test")).perform(click());

        onView(withText("Export this entry")).check(matches(isDisplayed()));
        onView(withText("Edit")).check(matches(isDisplayed()));
        onView(withText("Delete")).check(matches(isDisplayed()));
        onView(withText("Close")).check(matches(isDisplayed()));
    }

    @Test
    public void search_findsSavedItem() {
        createTestVault();
        createBasicItem("Searchable Account");

        onView(withHint("Search title, username, phone, website or notes"))
                .perform(replaceText("Searchable"), closeSoftKeyboard());

        onView(withText("Searchable Account")).check(matches(isDisplayed()));
    }

    @Test
    public void deleteCancel_keepsItem() {
        createTestVault();
        createBasicItem("Delete Cancel Test");

        onView(withText("Delete Cancel Test")).perform(click());
        onView(withText("Delete")).perform(click());

        onView(withText("Delete item?")).check(matches(isDisplayed()));
        onView(withText("Cancel")).perform(click());

        onView(withText("Close")).perform(click());
        onView(withText("Delete Cancel Test")).check(matches(isDisplayed()));
    }

    @Test
    public void securitySettings_correctPassword_opensSettings() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Change master password")).check(matches(isDisplayed()));
        onView(withText("Lock when screen turns off")).check(matches(isDisplayed()));
    }

    @Test
    public void securitySettings_wrongPassword_keepsReauthDialogOpen() {
        createTestVault();

        onView(withText("Security")).perform(scrollTo(), click());

        onView(withHint("Master password"))
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());

        onView(withText("Continue")).perform(click());

        onView(withText("Continue")).check(matches(isDisplayed()));
        onView(withHint("Master password")).check(matches(isDisplayed()));
    }

    @Test
    public void autoLockDialog_showsAllChoices() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Auto-lock: 30 seconds")).perform(click());

        onView(withText("Immediately")).check(matches(isDisplayed()));
        onView(withText("30 seconds")).check(matches(isDisplayed()));
        onView(withText("1 minute")).check(matches(isDisplayed()));
        onView(withText("5 minutes")).check(matches(isDisplayed()));
    }

    @Test
    public void clipboardTimeoutDialog_showsAllChoices() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Clipboard timeout: 30s")).perform(click());

        onView(withText("15 seconds")).check(matches(isDisplayed()));
        onView(withText("30 seconds (recommended)")).check(matches(isDisplayed()));
        onView(withText("60 seconds")).check(matches(isDisplayed()));
        onView(withText("Never auto-clear")).check(matches(isDisplayed()));
    }

    @Test
    public void changeMasterPassword_wrongCurrentPassword_keepsDialogOpen() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Change master password")).perform(click());

        onView(withHint("Current master password"))
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());

        onView(withHint("New master password (12+ characters)"))
                .perform(replaceText("NewKeepriva123!"), closeSoftKeyboard());

        onView(withHint("Confirm new master password"))
                .perform(replaceText("NewKeepriva123!"), closeSoftKeyboard());

        onView(withText("Change")).perform(click());

        onView(withText("Change")).check(matches(isDisplayed()));
        onView(withHint("Current master password")).check(matches(isDisplayed()));
    }

    @Test
    public void newFolderCategory_canBeCreatedWithoutFields() {
        createTestVault();

        onView(withText("Categories")).perform(scrollTo(), click());
        onView(withText("+ New custom category / sub-category"))
                .perform(scrollTo(), click());

        onView(withHint("Category name"))
                .perform(replaceText("Folder Only"), closeSoftKeyboard());

        onView(withText("Save")).perform(click());

        onView(withHint("Search title, username, phone, website or notes"))
                .check(matches(isDisplayed()));

        onView(withText("Categories")).perform(scrollTo(), click());
        onView(withText("Folder Only  •  0 fields")).check(matches(isDisplayed()));
    }

    @Test
    public void backupPasswordTooShort_keepsDialogOpen() {
        createTestVault();

        onView(withText("Backup")).perform(scrollTo(), click());
        onView(withText("Create encrypted .pvault backup")).perform(click());

        onView(withHint("Backup password (10+ characters)"))
                .perform(replaceText("short"), closeSoftKeyboard());

        onView(withHint("Confirm backup password"))
                .perform(replaceText("short"), closeSoftKeyboard());

        onView(withText("Continue")).perform(click());

        onView(withText("Continue")).check(matches(isDisplayed()));
        onView(withHint("Backup password (10+ characters)")).check(matches(isDisplayed()));
    }

    @Test
    public void backupPasswordMismatch_keepsDialogOpen() {
        createTestVault();

        onView(withText("Backup")).perform(scrollTo(), click());
        onView(withText("Create encrypted .pvault backup")).perform(click());

        onView(withHint("Backup password (10+ characters)"))
                .perform(replaceText("BackupPass123!"), closeSoftKeyboard());

        onView(withHint("Confirm backup password"))
                .perform(replaceText("OtherBackup123!"), closeSoftKeyboard());

        onView(withText("Continue")).perform(click());

        onView(withText("Continue")).check(matches(isDisplayed()));
        onView(withHint("Confirm backup password")).check(matches(isDisplayed()));
    }

    @Test
    public void sensitiveExport_wrongMasterPassword_keepsReauthOpen() {
        createTestVault();
        createLoginItemWithPassword("Sensitive Export Item", "EntrySecret123!");

        onView(withText("Sensitive Export Item")).perform(click());
        onView(withText("Export this entry")).perform(click());

        onView(withText("Include passwords")).perform(click());
        onView(withText("Continue")).perform(click());

        onView(withHint("Master password"))
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());

        onView(withText("Authenticate")).perform(click());

        onView(withText("Authenticate")).check(matches(isDisplayed()));
        onView(withHint("Master password")).check(matches(isDisplayed()));
    }

    @Test
    public void sensitiveExport_correctMasterPassword_showsAllFormats() {
        createTestVault();
        createLoginItemWithPassword("Export Format Item", "EntrySecret123!");

        onView(withText("Export Format Item")).perform(click());
        onView(withText("Export this entry")).perform(click());

        onView(withText("Include passwords")).perform(click());
        onView(withText("Continue")).perform(click());

        onView(withHint("Master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());

        onView(withText("Authenticate")).perform(click());

        onView(withText("Formatted text (.txt)")).check(matches(isDisplayed()));
        onView(withText("HTML page (.html)")).check(matches(isDisplayed()));
        onView(withText("PDF document (.pdf)")).check(matches(isDisplayed()));
    }
}



