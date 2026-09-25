package com.example.privatevault;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class KeeprivaSecuritySettingsTest extends KeeprivaTestBase {

    // Phase 1 security-dialog hardening: every modal interaction names its root,
    // and every test dismisses its final dialog before ActivityScenario teardown.

    @Test
    public void securityRequiresReauthentication() {
        createTestVault();

        onView(withContentDescription("Security")).perform(scrollTo(), click());

        waitForDialogHint("Master password");
        onView(withHint("Master password"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
        onView(withText("Continue"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        closeCurrentDialog("Cancel");
    }

    @Test
    public void wrongSecurityPassword_keepsDialogOpen() {
        createTestVault();

        onView(withContentDescription("Security")).perform(scrollTo(), click());
        waitForDialogHint("Master password");
        onView(withHint("Master password"))
                .inRoot(isDialog())
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());
        onView(withText("Continue"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogText("Continue");
        closeCurrentDialog("Cancel");
    }

    @Test
    public void correctSecurityPassword_opensSettings() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Change master password"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        closeCurrentDialog("Done");
    }

    @Test
    public void securityStatusShowsDefaults() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Lock when screen turns off"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
        onView(withText("Auto-lock: 30 seconds"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
        onView(withText("Clipboard timeout: 30s"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));

        closeCurrentDialog("Done");
    }

    @Test
    public void autoLockDialog_listsAllOptions() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Auto-lock: 30 seconds"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogText("Immediately");
        assertDialogTextDisplayed("Immediately");
        assertDialogTextDisplayed("30 seconds");
        assertDialogTextDisplayed("1 minute");
        assertDialogTextDisplayed("5 minutes");

        closeCurrentDialog("Cancel");
    }

    @Test
    public void autoLockCanBeChangedToImmediate() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Auto-lock: 30 seconds"))
                .inRoot(isDialog())
                .perform(click());
        waitForDialogText("Immediately");
        onView(withText("Immediately"))
                .inRoot(isDialog())
                .perform(click());
        onView(withText("Save"))
                .inRoot(isDialog())
                .perform(click());

        waitForActivityWindowFocus();
        assertHomeDisplayed();
    }

    @Test
    public void clipboardDialog_listsAllOptions() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Clipboard timeout: 30s"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogText("15 seconds");
        assertDialogTextDisplayed("15 seconds");
        assertDialogTextDisplayed("30 seconds (recommended)");
        assertDialogTextDisplayed("60 seconds");
        assertDialogTextDisplayed("Never auto-clear");

        closeCurrentDialog("Cancel");
    }

    @Test
    public void clipboardCanBeChangedTo15Seconds() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Clipboard timeout: 30s"))
                .inRoot(isDialog())
                .perform(click());
        waitForDialogText("15 seconds");
        onView(withText("15 seconds"))
                .inRoot(isDialog())
                .perform(click());
        onView(withText("Save"))
                .inRoot(isDialog())
                .perform(click());

        waitForActivityWindowFocus();
        assertHomeDisplayed();
    }

    @Test
    public void changePasswordDialog_opens() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Change master password"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogHint("Current master password");
        assertDialogHintDisplayed("Current master password");
        assertDialogHintDisplayed("New master password (12+ characters)");
        assertDialogHintDisplayed("Confirm new master password");

        closeCurrentDialog("Cancel");
    }

    @Test
    public void wrongCurrentPassword_doesNotChangePassword() {
        createTestVault();
        openSecuritySettings();

        openChangePasswordDialog();

        onView(withHint("Current master password"))
                .inRoot(isDialog())
                .perform(replaceText("WrongPassword123!"), closeSoftKeyboard());
        onView(withHint("New master password (12+ characters)"))
                .inRoot(isDialog())
                .perform(replaceText("NewKeepriva123!"), closeSoftKeyboard());
        onView(withHint("Confirm new master password"))
                .inRoot(isDialog())
                .perform(replaceText("NewKeepriva123!"), closeSoftKeyboard());

        onView(withText("Change"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogText("Change");
        closeCurrentDialog("Cancel");
    }

    @Test
    public void mismatchedNewPasswords_areRejected() {
        createTestVault();
        openSecuritySettings();

        openChangePasswordDialog();

        onView(withHint("Current master password"))
                .inRoot(isDialog())
                .perform(replaceText(TEST_PASSWORD), closeSoftKeyboard());
        onView(withHint("New master password (12+ characters)"))
                .inRoot(isDialog())
                .perform(replaceText("NewKeepriva123!"), closeSoftKeyboard());
        onView(withHint("Confirm new master password"))
                .inRoot(isDialog())
                .perform(replaceText("DifferentNew123!"), closeSoftKeyboard());

        onView(withText("Change"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogText("Change");
        closeCurrentDialog("Cancel");
    }

    @Test
    public void biometricSettings_isReachable() {
        createTestVault();
        openSecuritySettings();

        onView(withText("Biometric unlock: Disabled"))
                .inRoot(isDialog())
                .perform(click());

        waitForDialogText("Enable biometric unlock?");
        assertDialogTextDisplayed("Enable biometric unlock?");

        closeCurrentDialog("Cancel");
    }

    private void openChangePasswordDialog() {
        onView(withText("Change master password"))
                .inRoot(isDialog())
                .perform(click());
        waitForDialogHint("Current master password");
    }

    private void assertDialogTextDisplayed(String text) {
        onView(withText(text))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
    }

    private void assertDialogHintDisplayed(String hint) {
        onView(withHint(hint))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
    }

    private void closeCurrentDialog(String buttonText) {
        onView(withText(buttonText))
                .inRoot(isDialog())
                .perform(click());
        waitForActivityWindowFocus();
    }

    private void assertHomeDisplayed() {
        onView(withHint("Search title, username, phone, website or notes"))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }
}
