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
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
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

        onView(withText("Import")).perform(scrollTo(), click());

        onView(withText("Step 1 — Save JSON import template")).check(matches(isDisplayed()));
        onView(withText("Step 2 — Import completed JSON template")).check(matches(isDisplayed()));
    }

    @Test
    public void downloadImportTemplate_launchesCreateDocument() {
        createTestVault();

        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
                .respondWith(new android.app.Instrumentation.ActivityResult(
                        Activity.RESULT_CANCELED, null));

        onView(withText("Import")).perform(scrollTo(), click());
        onView(withText("Step 1 — Save JSON import template")).perform(click());

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

        onView(withText("Import")).perform(scrollTo(), click());
        onView(withText("Step 2 — Import completed JSON template")).perform(click());

        intended(allOf(
                hasAction(Intent.ACTION_OPEN_DOCUMENT),
                hasType("application/json")
        ));
    }

    @Test
    public void backupDialog_hasCreateAndRestore() {
        createTestVault();

        onView(withText("Backup")).perform(scrollTo(), click());

        onView(withText("Create encrypted .pvault backup")).check(matches(isDisplayed()));
        onView(withText("Restore encrypted .pvault backup")).check(matches(isDisplayed()));
    }

    @Test
    public void shortBackupPassword_keepsDialogOpen() {
        createTestVault();

        onView(withText("Backup")).perform(scrollTo(), click());
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

        onView(withText("Backup")).perform(scrollTo(), click());
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

        onView(withText("Backup")).perform(scrollTo(), click());
        onView(withText("Create encrypted .pvault backup")).perform(click());

        onView(withHint("Backup password (10+ characters)"))
                .perform(replaceText("BackupPass123!"), closeSoftKeyboard());
        onView(withHint("Confirm backup password"))
                .perform(replaceText("BackupPass123!"), closeSoftKeyboard());

        onView(withText("Continue")).perform(click());

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

        onView(withText("Backup")).perform(scrollTo(), click());
        onView(withText("Restore encrypted .pvault backup")).perform(click());
        onView(withText("Choose .pvault file")).perform(click());

        intended(hasAction(Intent.ACTION_OPEN_DOCUMENT));
    }

    @Test
    public void safeEntryExport_showsAllFormatsWithoutReauth() {
        createTestVault();
        createBasicItem("Export Safe Item");

        onView(withText("Export Safe Item")).perform(click());
        onView(withText("Export this entry")).perform(click());
        onView(withText("Continue")).perform(click());

        onView(withText("Keepriva JSON (.json) — re-importable")).check(matches(isDisplayed()));
        onView(withText("Formatted text (.txt)")).check(matches(isDisplayed()));
        onView(withText("HTML page (.html)")).check(matches(isDisplayed()));
        onView(withText("PDF document (.pdf)")).check(matches(isDisplayed()));
    }

    @Test
    public void sensitiveEntryExport_requiresMasterPassword() {
        createTestVault();
        createLoginItem("Sensitive Export", "user@test.com", "Secret123!");

        onView(withText("Sensitive Export")).perform(click());
        onView(withText("Export this entry")).perform(click());
        onView(withText("Include passwords")).perform(click());
        onView(withText("Continue")).perform(click());

        onView(withHint("Master password")).check(matches(isDisplayed()));
        onView(withText("Authenticate")).check(matches(isDisplayed()));
    }

    @Test
    public void wrongSensitiveExportPassword_keepsReauthOpen() {
        createTestVault();
        createLoginItem("Wrong Export Password", "user@test.com", "Secret123!");

        onView(withText("Wrong Export Password")).perform(click());
        onView(withText("Export this entry")).perform(click());
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

        onView(withText("Correct Export Password")).perform(click());
        onView(withText("Export this entry")).perform(click());
        onView(withText("Include passwords")).perform(click());
        onView(withText("Continue")).perform(click());

        onView(withHint("Master password"))
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());
        onView(withText("Authenticate")).perform(click());

        onView(withText("Keepriva JSON (.json) — re-importable")).check(matches(isDisplayed()));
        onView(withText("Formatted text (.txt)")).check(matches(isDisplayed()));
        onView(withText("HTML page (.html)")).check(matches(isDisplayed()));
        onView(withText("PDF document (.pdf)")).check(matches(isDisplayed()));
    }
}
