package com.example.privatevault;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasType;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.hamcrest.Matchers.allOf;

import android.app.Activity;
import android.content.Intent;

import androidx.test.espresso.intent.Intents;
import androidx.test.espresso.intent.matcher.IntentMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class KeeprivaDataTransferTest extends KeeprivaTestBase {

    @Before
    public void initIntents() {
        Intents.init();
    }

    @After
    public void releaseIntents() {
        Intents.release();
    }

    private void stubDocumentPicker() {
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
                .respondWith(new android.app.Instrumentation.ActivityResult(
                        Activity.RESULT_CANCELED, null));
    }

    @Test
    public void importDialog_hasBothActions() {
        createTestVault();

        onView(withContentDescription("Import")).perform(scrollTo(), click());

        onView(withContentDescription("Download JSON import template")).check(matches(isDisplayed()));
        onView(withContentDescription("Import completed JSON template")).check(matches(isDisplayed()));
    }

    @Test
    public void downloadImportTemplate_launchesCreateDocument() {
        createTestVault();

        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
                .respondWith(new android.app.Instrumentation.ActivityResult(
                        Activity.RESULT_CANCELED, null));

        onView(withContentDescription("Import")).perform(scrollTo(), click());
        onView(withContentDescription("Download JSON import template")).perform(click());

        intended(allOf(
                hasAction(Intent.ACTION_CREATE_DOCUMENT),
                hasType("application/json")
        ));
    }

    @Test
    public void importFilledTemplate_launchesOpenDocument() {
        createTestVault();

        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(new android.app.Instrumentation.ActivityResult(
                        Activity.RESULT_CANCELED, null));

        onView(withContentDescription("Import")).perform(scrollTo(), click());
        onView(withContentDescription("Import completed JSON template")).perform(click());

        intended(allOf(
                hasAction(Intent.ACTION_OPEN_DOCUMENT),
                hasType("application/json")
        ));
    }

    @Test
    public void backupDialog_hasCreateAndRestore() {
        createTestVault();

        onView(withContentDescription("Backup & Restore")).perform(scrollTo(), click());

        onView(withText("Create encrypted .pvault backup")).check(matches(isDisplayed()));
        onView(withText("Restore encrypted .pvault backup")).check(matches(isDisplayed()));
    }

    @Test
    public void shortBackupPassword_keepsDialogOpen() {
        createTestVault();

        onView(withContentDescription("Backup & Restore")).perform(scrollTo(), click());
        onView(withText("Create encrypted .pvault backup")).perform(click());

        onView(withHint("Backup password (10+ characters)"))
                .perform(replaceText("short"), closeSoftKeyboard());
        onView(withHint("Confirm backup password"))
                .perform(replaceText("short"), closeSoftKeyboard());

        onView(withText("Continue")).perform(click());

        onView(withText("Continue")).check(matches(isDisplayed()));
    }

    @Test
    public void mismatchedBackupPasswords_keepDialogOpen() {
        createTestVault();

        onView(withContentDescription("Backup & Restore")).perform(scrollTo(), click());
        onView(withText("Create encrypted .pvault backup")).perform(click());

        onView(withHint("Backup password (10+ characters)"))
                .perform(replaceText("BackupPass123!"), closeSoftKeyboard());
        onView(withHint("Confirm backup password"))
                .perform(replaceText("DifferentPass123!"), closeSoftKeyboard());

        onView(withText("Continue")).perform(click());

        onView(withText("Continue")).check(matches(isDisplayed()));
    }

    @Test
    public void validBackupPassword_launchesCreateDocument() {
        createTestVault();

        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
                .respondWith(new android.app.Instrumentation.ActivityResult(
                        Activity.RESULT_CANCELED, null));

        onView(withContentDescription("Backup & Restore")).perform(scrollTo(), click());
        onView(withText("Create encrypted .pvault backup")).perform(click());

        onView(withHint("Backup password (10+ characters)"))
                .perform(replaceText("BackupPass123!"), closeSoftKeyboard());
        onView(withHint("Confirm backup password"))
                .perform(replaceText("BackupPass123!"), closeSoftKeyboard());

        /*
         * Continue belongs to the backup-password AlertDialog. The click
         * immediately dismisses that dialog and launches ACTION_CREATE_DOCUMENT.
         * On the CI emulator the Activity can therefore lose focus before
         * Espresso's default root picker finishes resolving the click.
         *
         * Pin the action to the dialog root that actually owns the button.
         */
        onView(withText("Continue"))
                .inRoot(isDialog())
                .perform(click());

        intended(allOf(
                hasAction(Intent.ACTION_CREATE_DOCUMENT),
                hasType("application/octet-stream")
        ));
    }

    @Test
    public void restoreBackup_launchesOpenDocument() {
        createTestVault();

        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(new android.app.Instrumentation.ActivityResult(
                        Activity.RESULT_CANCELED, null));

        onView(withContentDescription("Backup & Restore")).perform(scrollTo(), click());

        onView(withText("Restore encrypted .pvault backup"))
                .inRoot(isDialog())
                .perform(click());

        onView(withText("Restore encrypted backup"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        onView(withText("Choose .pvault file"))
                .inRoot(isDialog())
                .perform(click());

        intended(hasAction(Intent.ACTION_OPEN_DOCUMENT));
    }

    @Test
    public void safeEntryExport_showsAllFormatsWithoutReauth() {
        createTestVault();
        createBasicItem("Export Safe Item");

        onView(withContentDescription("Open entry Export Safe Item")).perform(scrollTo(), click());
        onView(withContentDescription("Export entry"))
                .inRoot(isDialog())
                .perform(scrollTo(), click());
        onView(withText("Continue")).perform(click());

        onView(withContentDescription("Export Keepriva JSON")).check(matches(isDisplayed()));
        onView(withText("Formatted text (.txt)")).check(matches(isDisplayed()));
        onView(withText("HTML page (.html)")).check(matches(isDisplayed()));
        onView(withText("PDF document (.pdf)")).check(matches(isDisplayed()));
    }

    @Test
    public void sensitiveEntryExport_requiresMasterPassword() {
        createTestVault();
        createLoginItem("Sensitive Export", "user@test.com", "Secret123!");

        onView(withContentDescription("Open entry Sensitive Export")).perform(scrollTo(), click());
        onView(withContentDescription("Export entry"))
                .inRoot(isDialog())
                .perform(scrollTo(), click());
        onView(withText("Include passwords")).perform(click());
        onView(withText("Continue")).perform(click());

        onView(withHint("Master password")).check(matches(isDisplayed()));
        onView(withText("Authenticate")).check(matches(isDisplayed()));
    }

    @Test
    public void wrongSensitiveExportPassword_keepsReauthOpen() {
        createTestVault();
        createLoginItem("Wrong Export Password", "user@test.com", "Secret123!");

        onView(withContentDescription("Open entry Wrong Export Password")).perform(scrollTo(), click());
        onView(withContentDescription("Export entry"))
                .inRoot(isDialog())
                .perform(scrollTo(), click());
        onView(withText("Include passwords")).perform(click());
        onView(withText("Continue")).perform(click());

        onView(withHint("Master password"))
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());
        onView(withText("Authenticate")).perform(click());

        onView(withText("Authenticate")).check(matches(isDisplayed()));
    }

    @Test
    public void correctSensitiveExportPassword_showsFormats() {
        createTestVault();
        createLoginItem("Correct Export Password", "user@test.com", "Secret123!");

        onView(withContentDescription("Open entry Correct Export Password")).perform(scrollTo(), click());
        onView(withContentDescription("Export entry"))
                .inRoot(isDialog())
                .perform(scrollTo(), click());
        onView(withText("Include passwords")).perform(click());
        onView(withText("Continue")).perform(click());

        onView(withHint("Master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());
        onView(withText("Authenticate")).perform(click());

        onView(withContentDescription("Export Keepriva JSON")).check(matches(isDisplayed()));
        onView(withText("Formatted text (.txt)")).check(matches(isDisplayed()));
        onView(withText("HTML page (.html)")).check(matches(isDisplayed()));
        onView(withText("PDF document (.pdf)")).check(matches(isDisplayed()));
    }
}
